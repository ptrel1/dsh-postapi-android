package cd.ptrel.dshpostapi

import android.util.Log
import okhttp3.Response
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 消费插件的 SSE 端点（`POST /api/dsh/v1/chat/stream`）。
 *
 * ==================== 协议（与插件 v1.8.0 对齐）====================
 * ```
 * : connected                       <- 首帧注释行，确认连接建立
 * data: {"type":"start","sessionId":"..."}
 * data: {"type":"text","text":"增量"}        <- **增量片段，必须累加**
 * data: {"type":"reasoning","text":"..."}   <- 思考过程（本版忽略）
 * data: {"type":"tool","name":"...","args":"..."}  <- 工具调用（本版只记录）
 * data: {"type":"done","reason":"completed"}
 * data: {"type":"error","message":"..."}
 * : ping                            <- 每 15s 心跳
 * ```
 *
 * ==================== SSE 解析的两个坑 ====================
 * 1. **事件以「空行」分隔，不是以换行分隔**。一个事件可以是多行 data:，
 *    要用空行判断「这一条读完了」。逐行当事件处理会把一条 JSON 拆散。
 * 2. **注释行以 : 开头**（: connected / : ping），必须跳过，否则 JSON 解析必然失败。
 *
 * ==================== 为什么自己读流而不用 okhttp-sse ====================
 * okhttp-sse 是独立依赖。本项目坚持依赖最小化（见 build.gradle.kts 注释），
 * 而这段解析只有几十行，且协议是我们自己定的 —— 不值得为一个依赖引入版本兼容负担。
 */
class StreamingClient(private val api: CompatApi) {

    private companion object {
        const val TAG = "Streaming"
    }

    private enum class Event { CONTINUE, DONE, ERROR }

    /**
     * 跑一轮对话。**阻塞调用，必须在后台线程执行。**
     *
     * @param isCancelled 每收到一帧检查一次；返回 true 就主动断开。
     *   为什么用轮询而不是直接 close ResponseBody：从另一个线程 close 正在读的 body
     *   会抛 IOException，把正常取消变成一条错误提示。
     */
    fun run(
        prompt: String,
        sessionId: String?,
        onStart: (String) -> Unit,
        onText: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        var response: Response? = null
        try {
            response = api.newStreamingClient().newCall(api.streamRequest(prompt, sessionId)).execute()

            if (!response.isSuccessful) {
                val code = response.code
                onError(
                    when (code) {
                        401, 403 -> "登录已失效，请重新登录"
                        404 -> "接口不存在（插件版本可能过旧，需 >= 1.9.0）"
                        in 500..599 -> "服务端错误（" + code + "）"
                        else -> "请求失败（" + code + "）"
                    },
                )
                return
            }

            val body = response.body
            if (body == null) {
                onError("服务端没有返回内容")
                return
            }

            BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { reader ->
                val dataBuffer = StringBuilder()
                var sawDone = false
                var sawError = false

                while (true) {
                    if (isCancelled()) return
                    val line = reader.readLine() ?: break

                    if (line.isEmpty()) {
                        if (dataBuffer.isNotEmpty()) {
                            val ev = handleEvent(dataBuffer.toString(), onStart, onText, onError)
                            dataBuffer.setLength(0)
                            if (ev == Event.DONE) { sawDone = true; break }
                            if (ev == Event.ERROR) { sawError = true; break }
                        }
                        continue
                    }
                    if (line.startsWith(":")) continue
                    if (line.startsWith("data:")) {
                        dataBuffer.append(line.removePrefix("data:").trim())
                    }
                }

                if (!isCancelled() && !sawError) {
                    // 无论是否收到 done 帧都调 onDone：
                    // 连接被服务端正常关闭却没发 done（如插件进程重启）时，
                    // 把已收到的内容固化下来比丢掉更好。
                    onDone()
                }
            }
        } catch (e: java.io.IOException) {
            if (!isCancelled()) {
                Log.w(TAG, "stream io: " + e.javaClass.simpleName)
                onError("网络中断：" + (e.message ?: e.javaClass.simpleName))
            }
        } catch (e: Exception) {
            Log.w(TAG, "stream error: " + e.javaClass.simpleName)
            if (!isCancelled()) onError("解析失败：" + (e.message ?: e.javaClass.simpleName))
        } finally {
            try { response?.close() } catch (e: Exception) { }
        }
    }

    private fun handleEvent(
        json: String,
        onStart: (String) -> Unit,
        onText: (String) -> Unit,
        onError: (String) -> Unit,
    ): Event {
        if (json.isEmpty()) return Event.CONTINUE
        val obj = try {
            org.json.JSONObject(json)
        } catch (e: org.json.JSONException) {
            Log.w(TAG, "bad frame: " + json.take(80))
            return Event.CONTINUE
        }
        return when (obj.optString("type")) {
            "start" -> { onStart(obj.optString("sessionId", "")); Event.CONTINUE }
            "text" -> {
                val t = obj.optString("text", "")
                if (t.isNotEmpty()) onText(t)
                Event.CONTINUE
            }
            "reasoning" -> Event.CONTINUE
            "tool" -> Event.CONTINUE
            "done" -> Event.DONE
            "error" -> { onError(obj.optString("message", "未知错误")); Event.ERROR }
            else -> Event.CONTINUE
        }
    }
}