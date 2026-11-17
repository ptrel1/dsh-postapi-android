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

        /** 宿主接受的图片格式（dsh-api-remotes/lib/client.js:1085）。 */
        private val ALLOWED_IMAGE_MIME = setOf("image/png", "image/jpeg", "image/webp", "image/gif")

        /** 单张图片上限，与插件侧保持一致。 */
        private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
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
        // v0.9.1：UA 统一由 UserAgent 提供（ISSUE-20261116-01 P2）。
        // 早先这里、ApiClient、UpdateClient 各写一份字面量，三份都不同 ⇒
        // 每次登录与每次请求的指纹哈希都在变。改为单一来源。
        private val MOBILE_UA: String get() = UserAgent.VALUE
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

            // ⚠️ v0.8.2：必须在这里判过期，不能只判非空。
            //
            // 插件会话默认 24 小时（DEFAULT_MAX_AGE_MS），过期后服务端直接当没有会话，
            // 返回 401。而 AccountStore.load() **不做**过期校验（它只管解密与字段完整性），
            // 所以 5 天后打开 App 时：cookieValue 非空 → 造出实例 → 带着死 cookie 发请求
            // → 401 → 界面提示「未登录或凭据已失效」。
            //
            // 用户看到的是「明明刚登录过怎么又未登录」，实际是过期的 cookie 被当成可用的用。
            // 返回 null 让调用方走「需要重新登录」的正常路径，比发一个必然失败的请求更诚实。
            //
            // 注意用 hasFreshCookie 而不是自己比 cookieExp：
            // 它同时覆盖 cookieName 为空、cookieExp 为 0（服务端没给 maxAge）两种情况。
            if (!account.hasFreshCookie()) {
                android.util.Log.i(TAG, "fromStore: 本地 cookie 已过期，需重新登录")
                return null
            }

            return CompatApi(account.host, account.cookieName, account.cookieValue, context.cacheDir)
        }
    
        /**
         * 把相册/相机返回的 Uri 读成 [PendingImage]（v0.11.0）。
         *
         * 三件事都在这里做，调用方不必关心：
         *   ① **MIME 以 ContentResolver 为准**，不信文件扩展名 —— 宿主会校验字节，
         *      扩展名和真实格式不符时整条消息会被拒；
         *   ② **白名单过滤**：只收 png/jpeg/webp/gif，其余返回 null（UI 层提示不支持）；
         *   ③ **大小上限 4MB**：与插件侧一致。超过就直接返回 null，
         *      不要先编码再上传 —— base64 会让内存占用翻 1.33 倍，大图容易 OOM。
         *
         * ⚠️ 必须在 IO 线程调用：两次流式读取（拿大小 + 读字节）。
         */
        fun readImage(context: android.content.Context, uri: android.net.Uri): PendingImage? {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri) ?: return null
            if (mime !in ALLOWED_IMAGE_MIME) return null
            // 先问大小，避免把一张 40MB 的原图整个读进内存
            val size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            if (size in 1..(MAX_IMAGE_BYTES + 1)) {
                // ok：已知且在限额内
            } else if (size > MAX_IMAGE_BYTES) {
                return null
            }
            // 流式读取（size 未知时也只能这样）
            val bytes = resolver.openInputStream(uri)?.use { input ->
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(chunk)
                    if (n <= 0) break
                    total += n
                    if (total > MAX_IMAGE_BYTES) return null   // 边读边判，超了立刻放弃
                    buf.write(chunk, 0, n)
                }
                buf.toByteArray()
            } ?: return null
            val name = (uri.lastPathSegment ?: "image").substringAfterLast('/').take(120)
            return PendingImage(
                mediaType = mime,
                base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP),
                name = name,
            )
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
        /**
         * 该会话是否有一轮正在生成（v0.10.2）。
         *
         * 服务端 /sessions 从 v1.9.x 起就返回 running: s.running === true，
         * 但客户端一直没解析 —— 于是「哪个会话在后台跑」这条信息被白白丢掉。
         * 有了它，列表可以给正在运行的会话画一个状态点，用户不必点进去才知道。
         */
        val running: Boolean = false,
        /**
         * 会话的工作目录（v0.10.2）。
         *
         * 服务端从 v1.9.x 起就返回 cwd，客户端一直没解析 —— 与 running 同一种遗漏。
         * DSH 的会话按工作区（项目目录）聚合才有意义：用户开一个会话是为了
         * 在某个项目里干活，把 30 个会话平铺成一列，找「上次在 dsh1-plugins 里
         * 干到哪了」只能靠标题猜。
         *
         * 空串 = 宿主没给（老会话或非 workdir 场景）。这类会话归到「其他」。
         */
        val cwd: String = "",
    )

    /**
     * 按工作区分组后的一个区（v0.10.2）。
     *
     * 排序规则（两层）：
     *   组内：会话按 updatedAt 倒序 —— 同一项目里最近聊的排前面；
     *   组间：按组内**最新**会话的 updatedAt 倒序 —— 最近在动的项目浮上来。
     * 为什么不按组名排：用户找的是「我刚才在哪个项目里干活」，不是字典序。
     */
    data class WorkspaceGroup(
        /** 组名：cwd 的最后一段（如 dsh1-plugins）；空 cwd 时为空串，UI 显示「其他」。 */
        val name: String,
        /** 完整路径，作为副标题显示 —— 同名工作区（不同父目录）靠它区分。 */
        val path: String,
        /** 组内会话，按 updatedAt 倒序。 */
        val sessions: List<SessionRow>,
    ) {
        /** 组内最新一条的时间，用于组间排序。 */
        val latestAt: Long get() = sessions.firstOrNull()?.updatedAt ?: 0L
    }



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
        /** turn -> 该轮产出的交付物。key 是 turn 的十进制字符串（JSON 对象的键只能是字符串）。 */
        val deliverablesByTurn: Map<Int, List<Deliverable>>,
        /** 服务端是否还有更早的消息（v0.8.0 上翻加载用）。 */
        val hasMore: Boolean = false,
        /**
         * 当前会话的模式（插件 v1.13.5 起下发），如 `standard` / `ptc`。
         *
         * 来源是宿主的 agentPreset 会话投影 —— 用户切过模式后 header 不变，
         * 只有投影会跟着 agent-preset/selected 事件走。
         * null = 读不到（不显示，不猜）。
         */
        val agentPreset: String? = null,
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
        /**
         * 执行过程（插件 v1.14.0 起下发）：这条助手消息所属那一轮调了哪些工具。
         *
         * 与实时流式的 [StreamEvent.ToolCall]/[StreamEvent.ToolResult] 是同一份数据，
         * 只是一个来自历史、一个来自 SSE —— 界面渲染时不该区分来源。
         */
        val execSteps: List<ExecStep> = emptyList(),
    ) {
        /** 一次工具调用 + 结果（[result] 为 null 表示当时没拿到结果）。 */
        data class ExecStep(
            val callId: String,
            val name: String,
            val args: String,
            val result: ExecResult?,
        )
        data class ExecResult(val ok: Boolean, val text: String)
    }

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
            // TEMP DIAG：确认服务端到底返回了多少条
            Log.i("CompatApi", "sessions: items=" + arr.length())
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
                        running = o.optBoolean("running", false),
                        cwd = o.optString("cwd", ""),
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
                // v0.14.0：执行过程（插件 v1.14.0 起 history 带 execSteps）。
                val stepsArr = o.optJSONArray("execSteps")
                val steps = ArrayList<Message.ExecStep>()
                if (stepsArr != null) {
                    for (k in 0 until stepsArr.length()) {
                        val st = stepsArr.optJSONObject(k) ?: continue
                        val resObj = st.optJSONObject("result")
                        steps.add(
                            Message.ExecStep(
                                callId = st.optString("callId"),
                                name = st.optString("name"),
                                args = st.optString("args"),
                                result = resObj?.let {
                                    Message.ExecResult(it.optBoolean("ok", true), it.optString("text"))
                                },
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
                        execSteps = steps,
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
            HistoryPage(
                messages = out,
                deliverablesByTurn = byTurn,
                hasMore = body.optBoolean("hasMore", false),
                agentPreset = body.optString("agentPreset").takeIf { it.isNotEmpty() && it != "null" },
            )
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

    /**
     * 待发送的图片（v0.11.0）。
     *
     * 协议来自宿主 dsh-api-remotes/lib/client.js:1083 —— prompt 的 content 接受
     *   { type:'image', mediaType, data:'<canonical base64>', name? }
     * 宿主会**校验声明的 mediaType 与解码后的字节是否一致**，所以 mediaType 不能瞎猜，
     * 必须来自系统返回的 MIME（下面 fromUri 用 contentResolver.getType）。
     */
    data class PendingImage(
        val mediaType: String,
        val base64: String,
        val name: String,
    )



    /**
     * 一个可选的工作区（v0.11.0）。
     *
     * DSH 的会话**必须绑定 cwd** —— 它决定 agent 在哪里执行命令、能读哪些文件。
     * 早先客户端的「新建会话」不带 cwd，服务端对非回环请求一律用 /tmp，
     * 于是新会话没有项目上下文（工具全在 /tmp 下跑）。
     */
    data class Workspace(
        val path: String,
        val name: String,
        val sessions: Int,
        val lastActiveAt: Long,
    )

    /**
     * 拉取允许选择的工作区列表。
     *
     * 服务端只返回「**已有会话用过**」的 cwd —— 这天然就是用户自己的目录。
     * 不支持任意路径：cwd 决定 agent 的工作目录，传 /etc 就等于把任意目录交给模型。
     */
    fun listWorkspaces(): Result<List<Workspace>> {
        val req = Request.Builder()
            .url(hostBaseUrl.trimEnd('/') + API_PREFIX + "/workspaces")
            .header("Cookie", cookieHeader())
            .header("User-Agent", MOBILE_UA)
            .get()
            .build()
        return exec(req) { body ->
            val arr = body.optJSONArray("items") ?: org.json.JSONArray()
            val out = ArrayList<Workspace>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val path = o.optString("path", "")
                if (path.isEmpty()) continue
                out.add(
                    Workspace(
                        path = path,
                        name = o.optString("name", "").ifEmpty { path.substringAfterLast('/') },
                        sessions = o.optInt("sessions", 0),
                        lastActiveAt = o.optLong("lastActiveAt", 0L),
                    ),
                )
            }
            out
        }
    }

    /**
     * 观察一个会话（v0.13.0，对应服务端 GET /chat/watch）。
     *
     * ## 解决什么问题
     *
     * 用户在**网页端**发消息时，App 开着聊天页完全感知不到 ——
     * 早先只有 onResume 才拉一次历史，页面存活期间外部变化一律看不见。
     *
     * ## 为什么不是 /chat/stream
     *
     * 那个是「提交 + 观察」一体的：它推进一轮对话。而外部已经在跑的轮次没法接管，
     * 硬接会让两条流写同一份 state。/chat/watch 是**纯观察**，无副作用。
     *
     * ## 帧格式（服务端 v1.13.x）
     *
     *   {type:'snapshot', cursor, hasMore}         进入时的游标
     *   {type:'event', kind, seq, data}            会话事件（用户/助手消息、标题）
     *   {type:'turn-start', turn, step}            一轮开始
     *   {type:'text', text}                        正文增量
     *   {type:'reasoning', text}                   思考增量
     *   {type:'turn-end', turn}                    一轮结束
     *   {type:'done', reason}                      整个 turn 结束
     *   {type:'questions', callId, questions[]}    有人在等回答
     *   {type:'question-ready', callId}            可以提交答案了
     *   {type:'error', message}                    观察流本身出错
     *
     * @return SSE 请求；调用方负责拿到连接并逐行读取 data: 行。
     */
    fun watchRequest(sessionId: String, clientId: String = ""): Request {
        // v0.17.5：带上 clientId。服务端在推送每个增量时标注发起方，
        // 客户端据此丢弃**自己那轮**的回声 —— 而不是靠 busy 推断
        //（那会在两端同时发消息时把对方的内容也丢掉）。
        val q = "sessionId=" + java.net.URLEncoder.encode(sessionId, "UTF-8") +
            "&clientId=" + java.net.URLEncoder.encode(clientId, "UTF-8")
        return Request.Builder()
            .url(hostBaseUrl.trimEnd('/') + API_PREFIX + "/chat/watch?" + q)
            .header("Cookie", cookieHeader())
            .header("User-Agent", MOBILE_UA)
            .header("Accept", "text/event-stream")
            .get()
            .build()
    }

    /** 回答一个待答问题（对应服务端 POST /chat/answer）。 */
    fun answerRequest(sessionId: String, callId: String, answers: List<AnswerItem>): Request {
        val arr = org.json.JSONArray()
        answers.forEach { a ->
            arr.put(
                JSONObject()
                    .put("id", a.id)
                    .put("selected", org.json.JSONArray(a.selected))
                    .apply { if (!a.custom.isNullOrBlank()) put("custom", a.custom) },
            )
        }
        val body = JSONObject()
            .put("sessionId", sessionId)
            .put("callId", callId)
            .put("answers", arr)
        return Request.Builder()
            .url(hostBaseUrl.trimEnd('/') + API_PREFIX + "/chat/answer")
            .header("Cookie", cookieHeader())
            .header("User-Agent", MOBILE_UA)
            .post(okhttp3.RequestBody.create(JSON_MEDIA, body.toString()))
            .build()
    }

    /**
     * 提交答案（v0.13.0）。
     *
     * 注意 409 的语义：服务端用它表示「这个问题已经不在可答状态」
     * （另一端先答了、或者服务端重启把它丢了）。这**不是**故障，
     * 调用方应当关闭回答面板并给用户一句提示，而不是让他重试。
     */
    fun execAnswer(req: Request): Result<Unit> = exec(req) { _ -> Unit }

    /** 一条答案：[selected] 是选中的选项标签，[custom] 是「其他」自由文本。 */
    data class AnswerItem(val id: String, val selected: List<String>, val custom: String? = null)

    /** 服务端推来的待答问题。 */
    data class PendingQuestion(
        val callId: String,
        val questions: List<Question>,
    ) {
        /** 单个问题。[options] 为空表示开放式输入（没有可选项）。 */
        data class Question(
            val id: String,
            val question: String,
            val detail: String?,
            val header: String?,
            val multiSelect: Boolean,
            val options: List<Option>,
        )
        data class Option(val label: String, val description: String?)
    }

    fun streamRequest(
        prompt: String,
        sessionId: String?,
        images: List<PendingImage> = emptyList(),
        cwd: String? = null,
        /**
         * 本设备的客户端标识（v0.17.5）。
         *
         * 服务端记住「这个会话当前由谁发起」，并在 /chat/watch 推送时标注。
         * 客户端据此丢弃自己那轮的回声 —— 早先靠 busy 推断，
         * 会在两端同时发消息时把对方的内容也丢掉。
         */
        clientId: String = "",
    ): Request {
        val json = JSONObject().put("prompt", prompt)
        if (clientId.isNotEmpty()) json.put("clientId", clientId)
        if (!sessionId.isNullOrEmpty()) json.put("sessionId", sessionId)
        // 新会话的工作区（v0.11.0）。服务端按「已有会话用过的 cwd」白名单校验，
        // 不在白名单内返回 403 —— 不信任客户端传的任意路径。
        if (!cwd.isNullOrBlank()) json.put("cwd", cwd)
        if (images.isNotEmpty()) {
            val arr = org.json.JSONArray()
            images.forEach { img ->
                arr.put(
                    JSONObject()
                        .put("mediaType", img.mediaType)
                        .put("data", img.base64)
                        .put("name", img.name),
                )
            }
            json.put("images", arr)
        }
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

/**
 * 把扁平的会话列表按 cwd 聚成工作区。
 *
 * ⚠️ 为什么是**顶层**函数而不是放在 CompatApi.Companion 里：
 *   Kotlin **不允许从外部导入定义在对象内部的扩展函数**
 *   （import ...Companion.groupByWorkspace 会报 Unresolved reference）。
 *   UI 层要用它，就必须是顶层可见的。
 *
 * 纯函数、无副作用 —— 可以被单元测试直接覆盖。
 * 排序错了是最难靠肉眼发现的那类 bug，能测就一定要测。
 */
/**
 * 把扁平的会话列表按 cwd 聚成工作区。
 *
 * 纯函数、无副作用 —— 这样它可以被单元测试直接覆盖，
 * 不必起模拟器验证排序（排序错了是最难靠肉眼发现的那类 bug）。
 */
fun List<CompatApi.SessionRow>.groupByWorkspace(): List<CompatApi.WorkspaceGroup> {
    return this
        .groupBy { it.cwd }
        .map { (cwd, list) ->
            val sorted = list.sortedByDescending { it.updatedAt }
            CompatApi.WorkspaceGroup(
                // 取最后一段作组名；以 / 结尾时先去掉尾斜杠，否则会得到空名。
                name = cwd.trimEnd('/').substringAfterLast('/'),
                path = cwd,
                sessions = sorted,
            )
        }
        .sortedByDescending { it.latestAt }
}
