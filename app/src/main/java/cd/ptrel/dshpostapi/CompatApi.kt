package cd.ptrel.dshpostapi

import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 兼容模式（原生界面）用的 HTTP 客户端。
 *
 * ==================== 与 ApiClient 的分工 ====================
 * ApiClient 只负责「登录」这一件事（换 launchUrl + cookie），
 * 本类负责登录之后的一切读写：列会话、读历史、发消息（SSE）、中断。
 *
 * 为什么不合并进 ApiClient：
 *   ApiClient 的每个方法都围绕「登录结果」建模（HostSpec / LoginResult / LoginError），
 *   而本类面对的是「已登录之后的业务数据」。混在一起会让 ApiClient 变成什么都做的上帝对象，
 *   且登录路径（安全敏感）与业务路径的错误处理策略完全不同
 *   （登录要区分 401/403/429 决定清不清密码；业务只要「失败了、重试」）。
 *
 * ==================== 会话凭据从哪来 ====================
 * 每次都从 [AccountStore] 现读（不缓存到字段）：
 *   · cookie 会被服务端吊销、被「退出登录」清理；
 *   · 插件会话 cookie 的 Max-Age 是 24h，长时间挂后台后可能已失效。
 *   现读的代价是一次 SharedPreferences 读（命中内存缓存，纳秒级），远小于一次网络往返。
 *
 * ==================== ⚠️ 绝不要把 cookie 值写进日志 ====================
 * 本类所有 Log 只打方法名与状态码，不打请求头。
 */
class CompatApi(private val hostBaseUrl: String, private val cookieName: String, private val cookieValue: String) {

    companion object {
        private const val TAG = "CompatApi"
        private const val API_PREFIX = "/api/dsh/v1"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaTypeOrNull()

        /**
         * 从存储里造一个实例；没有可用凭据时返回 null。
         *
         * 调用方拿到 null 就应提示「未登录」并回登录页 —— 不要在这里自己跳转，
         * 客户端不该替 UI 决定导航。
         */
        fun fromStore(context: android.content.Context): CompatApi? {
            val account = AccountStore.load(context) ?: return null
            if (account.cookieValue.isEmpty()) return null
            return CompatApi(account.host, account.cookieName, account.cookieValue)
        }
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // 读超时给足：SSE 在模型思考时可能几十秒没有新数据，但我们有心跳（15s 一次）。
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** 会话列表里的一行。 */
    data class SessionRow(
        val sessionId: String,
        val title: String,
        val updatedAt: Long,
    )

    /** 历史消息里的一条。 */
    data class Message(
        val seq: Int,
        val role: String,       // "user" / "assistant" / "other"
        val text: String,
        val time: Long,
    )

    /** 统一的结果类型：成功带值、失败带可展示的原因。 */
    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        data class Err(val reason: String, val code: Int = 0) : Result<Nothing>()
    }


    // ==========================================================================
    // GET /sessions
    // ==========================================================================

    fun listSessions(): Result<List<SessionRow>> {
        val req = Request.Builder()
            .url(hostBaseUrl.trimEnd('/') + API_PREFIX + "/sessions")
            .header("Cookie", cookieHeader())
            .get()
            .build()
        return exec(req) { body ->
            val arr = body.optJSONArray("items") ?: org.json.JSONArray()
            val out = ArrayList<SessionRow>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    SessionRow(
                        sessionId = o.optString("sessionId", ""),
                        title = o.optString("title", "").ifEmpty {
                            // 宿主可能不给标题（新会话还没生成），给个占位而不是空行，
                            // 否则列表里会出现一堆看不出区别的空白项。
                            o.optString("sessionId", "").take(12)
                        },
                        updatedAt = o.optLong("updatedAt", 0L),
                    ),
                )
            }
            out
        }
    }

    // ==========================================================================
    // GET /chat/history
    // ==========================================================================

    fun history(sessionId: String, beforeSeq: Int? = null, limit: Int = 50): Result<List<Message>> {
        val sb = StringBuilder(hostBaseUrl.trimEnd('/') + API_PREFIX + "/chat/history")
        sb.append("?sessionId=").append(java.net.URLEncoder.encode(sessionId, "UTF-8"))
        sb.append("&limit=").append(limit)
        if (beforeSeq != null) sb.append("&beforeSeq=").append(beforeSeq)

        val req = Request.Builder()
            .url(sb.toString())
            .header("Cookie", cookieHeader())
            .get()
            .build()
        return exec(req) { body ->
            val arr = body.optJSONArray("items") ?: org.json.JSONArray()
            val out = ArrayList<Message>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    Message(
                        seq = o.optInt("seq", 0),
                        role = o.optString("role", "other"),
                        text = o.optString("text", ""),
                        time = o.optLong("time", 0L),
                    ),
                )
            }
            out
        }
    }

    // ==========================================================================
    // POST /chat/cancel
    // ==========================================================================

    fun cancel(sessionId: String): Result<Boolean> {
        val payload = JSONObject().put("sessionId", sessionId).toString()
        val req = Request.Builder()
            .url(hostBaseUrl.trimEnd('/') + API_PREFIX + "/chat/cancel")
            .header("Cookie", cookieHeader())
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        return exec(req) { body -> body.optBoolean("ok", false) }
    }

    // ==========================================================================
    // SSE：POST /chat/stream（由 StreamingClient 消费，这里只负责造请求）
    // ==========================================================================

    fun newStreamingClient(): OkHttpClient = client

    fun streamRequest(prompt: String, sessionId: String?): Request {
        val json = JSONObject().put("prompt", prompt)
        if (!sessionId.isNullOrEmpty()) json.put("sessionId", sessionId)
        return Request.Builder()
            .url(hostBaseUrl.trimEnd('/') + API_PREFIX + "/chat/stream")
            .header("Cookie", cookieHeader())
            .header("Accept", "text/event-stream")
            .post(json.toString().toRequestBody(JSON_MEDIA))
            .build()
    }

    // ==========================================================================

    private fun cookieHeader(): String = "$cookieName=$cookieValue"

    /**
     * 同步执行并在 IO 线程外解析 —— **调用方负责放到后台线程**（本类所有方法都是阻塞的）。
     *
     * 为什么不做成异步回调：
     *   兼容模式的调用点都在 ViewModel 里，用协程或线程池统一调度更清楚；
     *   再套一层回调会让错误传播路径多一层，反而难写。
     */
    private fun <T> exec(req: Request, parse: (JSONObject) -> T): Result<T> {
        return try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "http " + resp.code + " for " + req.url.encodedPath)
                    return Result.Err(httpReason(resp.code), resp.code)
                }
                Result.Ok(parse(JSONObject(text)))
            }
        } catch (e: java.io.IOException) {
            Log.w(TAG, "io error: " + e.javaClass.simpleName)
            Result.Err("网络错误：" + (e.message ?: e.javaClass.simpleName))
        } catch (e: org.json.JSONException) {
            Log.w(TAG, "bad json: " + e.message)
            Result.Err("服务端返回格式无法解析")
        }
    }

    private fun httpReason(code: Int): String = when (code) {
        401, 403 -> "登录已失效，请重新登录"
        404 -> "接口不存在（插件版本可能过旧，需 >= 1.9.0）"
        429 -> "操作太频繁，请稍后再试"
        in 500..599 -> "服务端错误（" + code + "）"
        else -> "请求失败（" + code + "）"
    }
}
