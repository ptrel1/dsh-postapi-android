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
class CompatApi(
    private val hostBaseUrl: String,
    private val cookieName: String,
    private val cookieValue: String,
    /** 下载落盘目录（通常是 context.cacheDir）。 */
    private val cacheDirCompat: java.io.File,
) {

    companion object {
        private const val TAG = "CompatApi"

        /**
         * 所有请求统一的 User-Agent（v0.8.1）。
         *
         * ⚠️ 这不是可选的礼貌头，而是**会话存活的必要条件**：
         *
         * 插件把会话绑定在 UA 设备类别上（lib/index.js）：登录时 ApiClient 上报的是
         * 「Mozilla/5.0 (Linux; Android...) Mobile Safari...」，类别 = mobile。
         * 而 CompatApi 早先**不带 UA**，OkHttp 默认发 "okhttp/4.12.0"，
         * uaDeviceClass() 判成 other —— 永远不等于 mobile。
         *
         * 后果：兼容模式每个请求都被判 mismatch，mismatchCount 累计到 3
         * 插件就**吊销整个会话**，用户看到的就是「账号失效」。
         * 而且完整模式与兼容模式来回切几次就必现（两边 UA 不同）。
         *
         * 修法：与 ApiClient 保持同一个字面量。两边必须一致 ——
         * 改这里就要同步改 ApiClient.kt 与 UpdateClient.mobileUserAgent()。
         */
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 DSH-PostAPI-Android"
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
            return CompatApi(account.host, account.cookieName, account.cookieValue, context.cacheDir)
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

    /**
     * 一次历史读取的结果。
     *
     * 为什么把交付物放在**顶层**而不是只挂在消息上：
     *   宿主的分页窗口只包含「最近 N 条消息」那一段事件，而交付物事件的 seq
     *   可能远在窗口之外（实测某会话：消息 seq 18540~18965，交付物 seq 1781~18214，零重叠）。
     *   所以插件 v1.9.2 起单独返回 deliverablesByTurn —— App 按 turn 挂到消息上，
     *   消息不在当前页就先存着，翻到再显示。
     */
    data class HistoryPage(
        val messages: List<Message>,
        /** turn -> 该轮产出的交付物。key 是 Number.toString()。 */
        val deliverablesByTurn: Map<Int, List<Deliverable>>,
        /** 服务端是否还有更早的消息（v0.8.0 上翻加载用）。 */
        val hasMore: Boolean = false,
    )

    /** 交付物：挂在某条助手消息下。 */
    data class Deliverable(
        val name: String,
        val description: String,
        /** 下载坐标：/file-ticket 需要 sessionId + seq + index。 */
        val seq: Int,
        val index: Int,
    )

    /** 历史消息里的一条。 */
    data class Message(
        val seq: Int,
        val role: String,       // "user" / "assistant" / "other"
        val text: String,
        val time: Long,
        /** 该轮产出的交付物（插件 v1.9.1 起提供）。空 = 没有。 */
        val deliverables: List<Deliverable> = emptyList(),
        /**
         * 这条助手消息属于第几轮（插件 v1.9.3 起下发）。
         * 用户消息通常没有 turn（宿主只在 assistant/message 上带）。
         */
        val turn: Int = -1,
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
            .header("User-Agent", MOBILE_UA)
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

    fun history(sessionId: String, beforeSeq: Int? = null, limit: Int = 50): Result<HistoryPage> {
        val sb = StringBuilder(hostBaseUrl.trimEnd('/') + API_PREFIX + "/chat/history")
        sb.append("?sessionId=").append(java.net.URLEncoder.encode(sessionId, "UTF-8"))
        sb.append("&limit=").append(limit)
        if (beforeSeq != null) sb.append("&beforeSeq=").append(beforeSeq)

        val req = Request.Builder()
            .url(sb.toString())
            .header("Cookie", cookieHeader())
            .header("User-Agent", MOBILE_UA)
            .get()
            .build()
        return exec(req) { body ->
            val arr = body.optJSONArray("items") ?: org.json.JSONArray()
            val out = ArrayList<Message>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val delivArr = o.optJSONArray("deliverables")
                val delivs = ArrayList<Deliverable>()
                if (delivArr != null) {
                    for (j in 0 until delivArr.length()) {
                        val d = delivArr.optJSONObject(j) ?: continue
                        delivs.add(
                            Deliverable(
                                name = d.optString("name", ""),
                                description = d.optString("description", ""),
                                seq = d.optInt("seq", 0),
                                index = d.optInt("index", 0),
                            ),
                        )
                    }
                }
                out.add(
                    Message(
                        seq = o.optInt("seq", 0),
                        role = o.optString("role", "other"),
                        text = o.optString("text", ""),
                        time = o.optLong("time", 0L),
                        deliverables = delivs,
                        turn = o.optInt("turn", -1),
                    ),
                )
            }

            // 顶层 deliverablesByTurn：按 turn 收集，不受分页窗口限制（插件 v1.9.2）。
            // 用 String key 读再转 Int —— org.json 的 keys() 只给 String。
            val byTurn = HashMap<Int, List<Deliverable>>()
            val topObj = body.optJSONObject("deliverablesByTurn")
            if (topObj != null) {
                val it = topObj.keys()
                while (it.hasNext()) {
                    val keyStr = it.next()
                    val turn = keyStr.toIntOrNull() ?: continue
                    val list = topObj.optJSONArray(keyStr) ?: continue
                    val dl = ArrayList<Deliverable>(list.length())
                    for (j in 0 until list.length()) {
                        val d = list.optJSONObject(j) ?: continue
                        dl.add(
                            Deliverable(
                                name = d.optString("name", ""),
                                description = d.optString("description", ""),
                                seq = d.optInt("seq", 0),
                                index = d.optInt("index", 0),
                            ),
                        )
                    }
                    byTurn[turn] = dl
                }
            }
            HistoryPage(out, byTurn, body.optBoolean("hasMore", false))
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
            .header("User-Agent", MOBILE_UA)
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
            .header("User-Agent", MOBILE_UA)
            .header("Accept", "text/event-stream")
            .post(json.toString().toRequestBody(JSON_MEDIA))
            .build()
    }

    // ==========================================================================

    // ==========================================================================
    // v0.7.0：交付物下载
    // ==========================================================================

    /**
     * 下载一个交付物到 cache 目录。
     *
     * 两步：
     *   ① POST /file-ticket { sessionId, seq, index } → { url, filename }
     *   ② GET 该 url（插件签发的临时票据地址，48h 内可触发）
     *
     * 为什么分两步而不是直接给最终地址：
     *   清单里的直链（dl 票）也会过期，而票据接口能拿到**文件名与大小**，
     *   失败时还能给出确切原因（文件被删 / 越界 / 过期），诊断成本更低。
     *
     * @return 成功时返回落盘后的 File。
     */
    fun downloadDeliverable(sessionId: String, d: Deliverable): Result<java.io.File> {
        // ① 换票据
        val payload = JSONObject()
            .put("sessionId", sessionId)
            .put("seq", d.seq)
            .put("index", d.index)
            .toString()
        val ticketReq = Request.Builder()
            .url(hostBaseUrl.trimEnd('/') + API_PREFIX + "/file-ticket")
            .header("Cookie", cookieHeader())
            .header("User-Agent", MOBILE_UA)
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()

        val ticket = try {
            client.newCall(ticketReq).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return Result.Err(httpReason(resp.code), resp.code)
                }
                JSONObject(text)
            }
        } catch (e: java.io.IOException) {
            return Result.Err("网络错误：" + (e.message ?: e.javaClass.simpleName))
        } catch (e: org.json.JSONException) {
            return Result.Err("票据返回格式无法解析")
        }

        val path = ticket.optString("url", "")
        if (path.isEmpty()) return Result.Err("服务端没有返回下载地址")
        val filename = ticket.optString("filename", d.name).ifEmpty { d.name }

        // ② 下载
        val fileUrl = if (path.startsWith("http")) path else hostBaseUrl.trimEnd('/') + path
        val getReq = Request.Builder()
            .url(fileUrl)
            .header("Cookie", cookieHeader())
            .header("User-Agent", MOBILE_UA)
            .get()
            .build()

        return try {
            client.newCall(getReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return Result.Err(httpReason(resp.code), resp.code)
                }
                val body = resp.body ?: return Result.Err("服务端没有返回文件内容")
                // 文件名消毒：模型给的名字可能含路径分隔符或 ..，直接拼路径会写到目录外。
                val safe = filename.replace('/', '_').replace('\\', '_').let {
                    if (it == "." || it == ".." || it.isBlank()) "download.bin" else it
                }
                val dir = java.io.File(cacheDirCompat, "deliverables").apply { mkdirs() }
                val out = java.io.File(dir, safe)
                body.byteStream().use { input ->
                    out.outputStream().use { os -> input.copyTo(os) }
                }
                Result.Ok(out)
            }
        } catch (e: java.io.IOException) {
            Result.Err("下载中断：" + (e.message ?: e.javaClass.simpleName))
        }
    }

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
