package cd.ptrel.dshpostapi.compose

import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
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
            // 工具帧：SimpleChat 的 StreamEvent 没有通用 tool 类型
            // （它的工具信息走 Anthropic 块级事件）。DSH 的 tool 帧暂时丢弃 ——
            // 用户看不到「正在使用 X」，但不影响对话本身。
            // 要显示工具过程，得在 StreamEvent 里加一个 Tool 类型并让 UI 处理。
            "tool" -> null
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
