package cd.ptrel.dshpostapi.compose

import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import cd.ptrel.dshpostapi.CompatApi
import cd.ptrel.dshpostapi.net.StreamEvent
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 把 DSH 的 /chat/stream（SSE）转成 [StreamEvent] 流（v0.9.0）。
 *
 * ## 为什么用 callbackFlow 而不是自己管线程
 *
 * SSE 是「服务端持续吐、随时可能断」的长连接。用 callbackFlow 的好处是
 * **取消语义天然正确**：UI 离开页面 → collect 被取消 → awaitClose 里关闭连接。
 * 手写 Thread + 标志位在「用户切走时连接还挂着」这类场景会漏掉。
 *
 * ## 服务端事件契约（dsh-postapi-bridge v1.9.x）
 *
 *     start      { type:"start", sessionId }
 *     text       { type:"text", text }
 *     reasoning  { type:"reasoning", text }
 *     tool       { type:"tool", name, args }
 *     done       { type:"done", reason:"complete"|"cancelled", sessionId }
 *     error      { type:"error", message }
 *
 * 另有心跳注释行 `: ping`（每 15 秒）与开头的 `: connected`，
 * 它们是**注释**不是事件，必须跳过 —— 当成 JSON 解析会直接爆炸。
 */
class DshStreamClient(
    private val client: OkHttpClient,
) {

    private val sseClient: OkHttpClient = client.newBuilder()
        // SSE 是长连接，读超时必须放宽或置零：
        // 模型思考几十秒不发一个字节是正常的，读超时 10s 会把它当成断线。
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /**
     * 观察一个会话（v0.13.0，对应 GET /chat/watch）。
     *
     * 与 [stream] 的区别：**只读**。它不提交 prompt、不推进轮次，
     * 只把服务端见到的事件转成帧推回来。用于「网页端在跑，App 同步显示」。
     *
     * 返回原始 [WatchFrame] 而不是复用 [StreamEvent]：两者的帧语义不同
     * （watch 有 text/reasoning/turn-start/questions，stream 有 tool/done/error），
     * 强行合并会让两边的分支都变得判不准。
     */
    fun watch(request: Request): Flow<WatchFrame> = callbackFlow {
        val call = sseClient.newCall(request)
        try {
            val response = call.execute()
            android.util.Log.i("WatchDiag", "watch response code=" + response.code + " url=" + request.url)
            if (!response.isSuccessful) {
                // v0.15.7：把状态码与响应体打进日志。
                // 早先只给 UI 一句「HTTP xxx」，排查时看不到服务端说了什么。
                val bodyText = try { response.body?.string().orEmpty().take(300) } catch (e: Exception) { "<read failed>" }
                android.util.Log.w("WatchDiag", "watch failed code=" + response.code + " body=" + bodyText)
                trySend(WatchFrame.Failed(if (response.code == 401) "登录已失效，请重新登录" else "HTTP " + response.code))
                close()
                return@callbackFlow
            }
            val source = response.body?.source()
            if (source == null) {
                trySend(WatchFrame.Failed("服务端没有返回内容"))
                close()
                return@callbackFlow
            }
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (line.isEmpty()) continue
                if (line.startsWith(":")) continue      // 心跳 / connected
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty()) continue
                parseWatch(payload)?.let { trySend(it) }
            }
            close()
        } catch (e: IOException) {
            android.util.Log.w("WatchDiag", "watch IOException: " + e.javaClass.simpleName + " " + e.message)
            // 主动取消（页面退出/切会话）不是故障，不报错。
            if (!call.isCanceled()) trySend(WatchFrame.Failed("网络中断：" + (e.message ?: "未知")))
            close()
        } catch (e: Exception) {
            // v0.15.7：这个分支**必须**打日志。
            // 早先只给 UI 一句「读取失败」，排查时看不到异常类型与栈 ——
            // 实测就是这里吞掉了真正的错误（2ms 内失败，连 response code 都没打印）。
            android.util.Log.w(
                "WatchDiag",
                "watch EXCEPTION: " + e.javaClass.name + " : " + e.message + " | " + e.stackTraceToString(),
            )
            trySend(WatchFrame.Failed("读取失败：" + (e.message ?: e.javaClass.simpleName)))
            close()
        }
        awaitClose {
            // 关闭时取消底层 call，否则 OkHttp 的连接会挂着不释放。
            try { call.cancel() } catch (_: Exception) {}
        }
    }
        // ⚠️ v0.15.7：**必须** flowOn(IO)。
        //
        // callbackFlow 的 block 跑在 collect 的那个线程上，而 startWatching()
        // 用的是 viewModelScope（Dispatchers.Main）—— 于是 call.execute()
        // 直接触发 NetworkOnMainThreadException，连接从未建立。
        //
        // 实测：每次 watch 都在 2ms 内失败，服务端连一个请求都没收到，
        // 表现为「进了会话页但收不到外部流式内容」。
        //
        // stream() 一直有这行（它从没出过这个问题），watch() 是后加的、漏了。
        .buffer(capacity = 64, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
        .flowOn(Dispatchers.IO)

    /** 把一帧 JSON 解析成 [WatchFrame]；不认识的类型返回 null（向前兼容）。 */
    private fun parseWatch(payload: String): WatchFrame? {
        return try {
            val o = org.json.JSONObject(payload)
            when (o.optString("type")) {
                "snapshot" -> WatchFrame.Snapshot
                "text" -> o.optString("text").takeIf { it.isNotEmpty() }?.let { WatchFrame.Text(it) }
                "reasoning" -> o.optString("text").takeIf { it.isNotEmpty() }?.let { WatchFrame.Reasoning(it) }
                "turn-start" -> WatchFrame.TurnStart(o.optInt("turn", -1))
                "turn-end" -> WatchFrame.TurnEnd(o.optInt("turn", -1))
                "done" -> WatchFrame.Done(o.optString("reason", "complete"))
                "questions" -> parseQuestions(o)
                "question-ready" -> WatchFrame.QuestionReady
                "error" -> WatchFrame.Failed(o.optString("message", "观察流出错"))
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseQuestions(o: org.json.JSONObject): WatchFrame.Questions? {
        val callId = o.optString("callId")
        val arr = o.optJSONArray("questions") ?: return null
        if (callId.isEmpty() || arr.length() == 0) return null
        val list = ArrayList<CompatApi.PendingQuestion.Question>()
        for (i in 0 until arr.length()) {
            val q = arr.optJSONObject(i) ?: continue
            val opts = ArrayList<CompatApi.PendingQuestion.Option>()
            q.optJSONArray("options")?.let { oa ->
                for (j in 0 until oa.length()) {
                    oa.optJSONObject(j)?.let { opt ->
                        opts.add(CompatApi.PendingQuestion.Option(opt.optString("label"), opt.optString("description").takeIf { it.isNotEmpty() }))
                    }
                }
            }
            list.add(
                CompatApi.PendingQuestion.Question(
                    id = q.optString("id"),
                    question = q.optString("question"),
                    detail = q.optString("detail").takeIf { it.isNotEmpty() },
                    header = q.optString("header").takeIf { it.isNotEmpty() },
                    multiSelect = q.optBoolean("multiSelect", false),
                    options = opts,
                ),
            )
        }
        return WatchFrame.Questions(CompatApi.PendingQuestion(callId, list))
    }

    /**
     * 发起一轮对话。
     *
     * @param request 已带好 Cookie / UA 的请求（由 CompatApi 构造，避免在这里重复拼头部）
     */
    fun stream(request: Request): Flow<StreamEvent> = callbackFlow {
        val call = sseClient.newCall(request)

        try {
            val response = call.execute()
            if (!response.isSuccessful) {
                // 401 单独给文案：它是「凭据失效」而不是「网络问题」，
                // 用户按提示重新登录即可，不该让他干等重试。
                val msg = if (response.code == 401) "登录已失效，请重新登录" else "请求失败（HTTP " + response.code + "）"
                trySend(StreamEvent.Error(msg, response.code))
                close()
                return@callbackFlow
            }
            val source = response.body?.source()
            if (source == null) {
                trySend(StreamEvent.Error("服务端没有返回内容"))
                close()
                return@callbackFlow
            }

            // 逐行读。SSE 的帧以空行分隔，但每个 event 的 data 只有一行，
            // 所以按行处理是安全的（多行 data 需要拼接，本契约不用）。
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (line.isEmpty()) continue          // 帧分隔
                if (line.startsWith(":")) continue     // 注释/心跳
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty()) continue
                val event = parse(payload) ?: continue
                trySend(event)
                if (event is StreamEvent.Done || event is StreamEvent.Stopped || event is StreamEvent.Error) break
            }
            close()
        } catch (e: IOException) {
            // 主动取消时不报错：那是用户点了「停止」，不是故障。
            if (!call.isCanceled()) {
                trySend(StreamEvent.Error("网络中断：" + (e.message ?: "未知")))
            }
            close()
        } catch (e: Exception) {
            Log.w(TAG, "stream failed: " + e.javaClass.simpleName)
            trySend(StreamEvent.Error("解析失败：" + (e.message ?: "")))
            close()
        }
        // ⚠️ 这里**不能**放 finally { call.cancel() }。
        //   20261116 实测：加了它之后流看起来卡住（按钮一直显示「停止」）——
        //   因为 cancel() 会在读取循环正常结束后立刻执行，而此时
        //   callbackFlow 里还有未投递的事件（trySend 到缓冲后、collect 之前）。
        //   cancel 会把尚未被消费的数据一起丢掉，Done 事件因此丢失。
        //
        //   资源释放由 awaitClose 负责：collect 结束（正常结束或 UI 取消）时触发，
        //   那才是真正的「没人要了，可以断」的时机。

        // 缓冲策略：默认容量 64，流式 token 来得快时容易满。
        // 满了 trySend 会**静默失败**（返回 false），表现为「丢字」。
        // DROP_OLDEST 在极端情况下丢的是旧帧，比整段丢失可接受。
        awaitClose { call.cancel() }
    }.buffer(capacity = 64, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
        .flowOn(Dispatchers.IO)

    /**
     * 把一帧 JSON 转成 [StreamEvent]。
     *
     * 返回 null 表示「这一帧不需要呈现给用户」（例如 start 之外的无用字段）。
     * 解析失败也返回 null 而不是抛异常：SSE 里夹一帧脏数据不该中断整轮对话。
     */
    private fun parse(payload: String): StreamEvent? = try {
        val o = JSONObject(payload)
        when (o.optString("type")) {
            // DSH 的 start 帧带 sessionId，SimpleChat 的 StreamEvent 没有对应类型。
            // 客户端需要它（新会话首轮要记下来复用），所以借用 Title：
            // Title 在 SimpleChat 里是「服务端下发的会话标题」，本项目不用该功能，
            // 复用它传 sessionId 不会与任何既有逻辑冲突，UI 侧忽略即可。
            "start" -> o.optString("sessionId").takeIf { it.isNotEmpty() }?.let { StreamEvent.Title(it) }
            "text" -> o.optString("text").takeIf { it.isNotEmpty() }?.let { StreamEvent.Content(it) }
            "reasoning" -> o.optString("text").takeIf { it.isNotEmpty() }?.let { StreamEvent.Reasoning(it) }
            // v0.14.0：执行过程帧（服务端从 tool/call、tool/result、step/* 转发）。
            // 早先这里把 "tool" 直接返回 null —— 注释还写着「要显示工具过程，
            // 得在 StreamEvent 里加一个 Tool 类型并让 UI 处理」。现在补上了。
            "tool-call" -> o.optString("name").takeIf { it.isNotEmpty() }?.let {
                StreamEvent.ToolCall(
                    callId = o.optString("callId"),
                    name = it,
                    args = o.optString("args"),
                )
            }
            "tool-result" -> StreamEvent.ToolResult(
                callId = o.optString("callId"),
                ok = o.optBoolean("ok", true),
                text = o.optString("text"),
            )
            "step" -> StreamEvent.Step(starting = o.optString("kind") == "start")
            "tool" -> null   // 旧帧名，服务端已不再发送；保留以免老版本兼容问题
            "done" -> {
                val cancelled = o.optString("reason") == "cancelled"
                if (cancelled) StreamEvent.Stopped("cancelled") else StreamEvent.Done
            }
            "error" -> StreamEvent.Error(o.optString("message", "未知错误"))
            else -> null
        }
    } catch (e: Exception) {
        Log.w(TAG, "bad frame skipped")
        null
    }

    private companion object {
        const val TAG = "DshStreamClient"
    }
}

/**
 * 观察流（/chat/watch）的一帧。
 *
 * 与 [StreamEvent] 分开定义的理由见 [DshStreamClient.watch] 的注释：
 * 两套帧的语义不同，合并会让两边的判据都变模糊。
 */
sealed interface WatchFrame {
    /** 连接建立，服务端给了进入游标。此时可以拉一次历史补齐。 */
    data object Snapshot : WatchFrame

    /**
     * 某一轮开始。
     *
     * ⚠️ v0.17.4：带上 [turn] 编号。
     *
     * 早先是无参 object —— 服务端明明发了 `turn`，客户端丢掉了。
     * 后果：**多端同时运行时无法区分「这一轮是不是我自己发起的」**。
     * 本端发消息时另一端的内容会被当成回声丢掉（见 ChatScreenViewModel 的注释）。
     */
    data class TurnStart(val turn: Int) : WatchFrame

    /** 正文增量。 */
    data class Text(val text: String) : WatchFrame

    /** 思考增量。 */
    data class Reasoning(val text: String) : WatchFrame

    /** 某一轮结束。同样带 [turn]，用于确认「结束的是哪一轮」。 */
    data class TurnEnd(val turn: Int) : WatchFrame

    /** 整个 turn 结束（[reason] 是宿主的结束原因）。 */
    data class Done(val reason: String) : WatchFrame

    /** 有人在等回答。 */
    data class Questions(val pending: CompatApi.PendingQuestion) : WatchFrame

    /** 可以提交答案了（服务端确认问题已进入可答状态）。 */
    data object QuestionReady : WatchFrame

    /** 观察流本身失败（网络 / 鉴权）。 */
    data class Failed(val message: String) : WatchFrame
}
