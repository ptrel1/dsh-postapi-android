package cd.ptrel.dshpostapi

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager

/**
 * 启动 / 登录的**决策层**：读存储 → 判断能否跳过登录页 → 必要时自动重登 → 落存储。
 *
 * ==================== 为什么单独一个文件 ====================
 * 这段逻辑有 7 条互相冲突的边界（见下表），塞进 LoginActivity 会让生命周期方法
 * （onCreate / onResume）变成一大坨 if。更糟的是：Activity 会因转屏/返回而重建，
 * 而"是否已经在自动重登"的判定必须跨重建保持一致 —— 放在 object 里才可控。
 *
 * ==================== 边界决策表（每条都对应 README 的一行）====================
 *
 * | 自动重登的返回 | 清密码? | 清 cookie? | 下一步 |
 * | :------------- | :------ | :--------- | :----- |
 * | 200            | 否      | 覆盖为新值 | 进 WebView（存新 cookie） |
 * | 401            | **是**  | 是         | 登录页（用户名预填、密码空） |
 * | 403            | **是**  | 是         | 登录页（+ 说明账号可能被禁用） |
 * | 429 限流       | 否      | 否         | 登录页 + 「稍后再试」提示（可重试，不清凭据） |
 * | 5xx / 404      | 否      | 否         | 登录页 + 提示（服务端问题，清了也没用） |
 * | 网络错误/超时  | 否      | 否         | 登录页 + 「重试」提示（可能只是断网） |
 * | 存储失效(Keystore) | 已清 | 已清     | 登录页（空） | * | 同进程内已退出登录 | 否 | 否       | 登录页（host/username/密码**预填**，见下） |
 *
 * ==================== v0.4.0：退出登录不再清账号（问题 ③）====================
 * 用户原话：「退出登录之后地址用户名密码应该数据保存在本地不需要重复输入才对」。
 * 所以 [AccountStore.clearSession] 只清 cookie，保留 host / username / password；
 * 因此密码也必须回到登录页的输入框里，否则"不用重复输入"只做到一半。
 * 本类的启动决策用 [Decision.ShowLoginPage.prefillPassword] 区分两种语义：
 *   · 冷启动（cookie 过期）→ 自动重登；
 *   · 从 WebView 退出登录后重新进入 → **只预填，不自动登录**。
 *
 * **核心原则**：只有「服务端明确否定了这组凭据」（401/403）才清密码。
 * 任何"不确定"（网络、限流、服务端故障）都保留 —— 否则一次地铁隧道里的启动
 * 就能把用户记了很久的密码抹掉，下次还得重输，正好违背这个功能的初衷。
 *
 * ==================== 线程模型 ====================
 *   - 存储读写（Keystore 解密 / 首启生成密钥）**必须在后台线程**：首次可 200~500ms。
 *   - [ApiClient.login] 的回调已在主线程，且保证只回调一次。
 *   - 因此本类用 [io] 单线程执行器串行化所有存储任务，回调经 [mainHandler] 回主线程。
 *     **串行**很重要：两次并发的 commit 会让存储与内存缓存短暂不一致。
 */
object LoginFlow {

    private const val TAG = "LoginFlow"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "dsh-loginflw-io").apply { isDaemon = true }
    }

    /**
     * 自动重登是否正在进行。
     *
     * 为什么必须是 object 级状态：LoginActivity 在自动重登期间可能被重建
     * （用户转屏、从最近任务切回导致配置变化），新实例的 onCreate 会再跑一遍
     * restoreOrAutoLogin()。没有这个闸门就会**并发发两次登录请求** ——
     * 服务端按 IP 计失败次数，两次 401 就等于把限流次数烧掉一倍（5 次锁 10 分钟）。
     */
    @Volatile
    private var autoLoginInFlight = false

    /** 启动决策的结果，交给 LoginActivity 呈现。 */
    sealed class Decision {
        /** 有未过期的 cookie：直接进 WebView，不经过登录页。 */
        data class EnterWebView(val launchUrl: String) : Decision()

        /**
         * 停在登录页。[host] / [username] 用于预填。
         *
         * [password] 有两种语义，由 [prefillPassword] 区分（v0.4.0）：
         *   · [prefillPassword] = false：**存储里有密码，界面层应当立刻发起自动重登**
         *     （冷启动的正常路径）；
         *   · [prefillPassword] = true：**只把密码填进输入框，不要自动登录**
         *     （用户刚点了「退出登录」，或从 WebView 返回本页）。
         */
        data class ShowLoginPage(
            val host: String,
            val username: String,
            val password: String,
            /**
             * true = [password] 只用于**预填输入框**，界面层不得自动发起登录。
             *
             * 为什么必须有这个开关：`password.isNotEmpty()` 一个判据无法区分
             * "冷启动自动重登"与"用户刚退出登录、希望我帮他填好"—— 两者都需要密码，
             * 但一个要发请求、一个绝对不能发。
             */
            val prefillPassword: Boolean = false,
        ) : Decision()

        /**
         * 自动重登失败，停在登录页并给出原因。
         *
         * [clearedCredentials] = 是否已经把密码清掉了。仅 401/403 为 true。
         * [retryable] = 是否值得让用户再点一次"重试"（网络/限流/服务端故障为 true）。
         */
        data class AutoLoginFailed(
            val host: String,
            val username: String,
            val message: String,
            val detail: String,
            val clearedCredentials: Boolean,
            val retryable: Boolean,
        ) : Decision()
    }

    /**
     * 启动决策入口。**必须**在后台线程之外被调用（内部自己切线程），
     * 回调一定在主线程执行一次。
     */
    fun decideOnStart(
        context: Context,
        ignoreAutoLogin: Boolean = false,
        onDecision: (Decision) -> Unit,
    ) {
        io.execute {
            val decision = runCatching { computeStartDecision(context, ignoreAutoLogin) }
                .getOrElse { e ->
                    // 存储层已被 AccountStore 全兜住，走到这里只可能是 OOM 之类的意外。
                    // 兜成"显示空登录页"：用户还能用 App，只是不记忆。
                    Log.w(TAG, "start decision failed (" + e.javaClass.simpleName + ")")
                    Decision.ShowLoginPage(defaultHost(context), "", "", prefillPassword = false)
                }
            mainHandler.post { onDecision(decision) }
        }
    }

    /** 真正的决策（后台线程）。 */
    private fun computeStartDecision(context: Context, ignoreAutoLogin: Boolean): Decision {
        val account = AccountStore.load(context) ?: run {
            val fallback = LoginDefaults.lastHost(context) ?: defaultHost(context)
            return Decision.ShowLoginPage(fallback, "", "", prefillPassword = false)
        }

        // ---- 分支 1：cookie 还在有效期内 → 直接进 WebView ----
        // 注意判据里**不要求有密码**：cookie 未过期时根本不需要重登，
        // 即使密码已被清（例如上次 401 之后又手动登录过、但存储写入失败），
        // 也不影响"直接用 cookie 进 WebView"。
        if (account.hasFreshCookie()) {
            val launch = buildResumeUrl(account.host)
            if (launch != null) {
                return Decision.EnterWebView(launch)
            }
            // host 解析不出来（存储被写坏 / 旧版本残留格式）：降级到"清 cookie 走自动重登"。
            // 不清密码 —— 密码本身没问题，坏的只是 host。
            Log.w(TAG, "stored host unparsable, falling back to auto-login")
            AccountStore.clearCookie(context)
        }

        // ---- 分支 2：没有密码 → 登录页（预填 host/username）----
        if (account.password.isEmpty()) {
            return Decision.ShowLoginPage(account.host, account.username, "", prefillPassword = false)
        }

        // ---- 分支 3：有密码 ----
        // [ignoreAutoLogin] = true（同进程内退出登录后重新进入 / 从 WebView 返回本页）：
        // **不**自动重登，但把密码**预填**给界面层。
        // 为什么：用户刚点了「返回登录」/「退出登录」，此时自动把他登回去是反直觉的，
        // 而且会重复烧服务端的失败计数。
        // v0.4.0（问题 ③）：预填密码是用户明确的要求 ——「退出登录之后地址用户名密码
        // 应该数据保存在本地不需要重复输入才对」。用户点一下「登录」即可。
        if (ignoreAutoLogin) {
            return Decision.ShowLoginPage(
                account.host,
                account.username,
                account.password,
                prefillPassword = true,
            )
        }
        // 注意：这里**不发网络请求**（本函数跑在 IO 线程，但发请求需要 Activity
        // 判断是否已销毁，并且失败时要更新 UI）。只把"可以自动重登"告诉界面层。
        return Decision.ShowLoginPage(account.host, account.username, account.password, prefillPassword = false)
    }

    /**
     * 自动重登（后台存储 + 前台网络）。
     *
     * @param onSuccess 主线程回调：已把新 cookie 落盘，参数是可直接加载的 launchUrl。
     * @param onFailure 主线程回调：已按边界表处理完存储（可能已清密码）。
     */
    fun performAutoLogin(
        context: Context,
        host: ApiClient.HostSpec,
        username: String,
        password: String,
        onSuccess: (launchUrl: String) -> Unit,
        onFailure: (message: String, detail: String, cleared: Boolean, retryable: Boolean) -> Unit,
    ) {
        if (autoLoginInFlight) {
            Log.i(TAG, "auto-login already in flight, ignoring duplicate request")
            return
        }
        autoLoginInFlight = true
        val appContext = context.applicationContext

        ApiClient.login(host, username, password) { result, error ->
            // ApiClient 保证：主线程、恰好一次。
            if (result != null) {
                // 落盘在后台线程（commit 是阻塞 IO），落完再通知界面进 WebView。
                // 好处：进 WebView 的那一刻，存储已经是最新状态；
                // 坏处：多等几毫秒（可接受，登录本身已经花了 100ms+）。
                io.execute {
                    AccountStore.saveAfterLogin(
                        context = appContext,
                        host = host.baseUrl,
                        username = result.username.ifEmpty { username },
                        password = password,
                        cookieName = result.cookieName,
                        cookieValue = result.cookieValue,
                        cookieMaxAgeSeconds = result.cookieMaxAgeSeconds,
                    )
                    LoginDefaults.rememberHost(appContext, host.baseUrl)
                    autoLoginInFlight = false
                    mainHandler.post {
                        // 种 cookie 必须在**主线程 + loadUrl 之前**完成，并且要 flush()。
                        // 为什么放在这里而不是 LoginActivity：这里是唯一同时持有
                        // cookieValue 与「即将 jump 的时机」的位置；放在 UI 层就要再传
                        // 一次 cookie 值（多一份凭据在进程里流动）。
                        seedSessionCookie(result.launchUrl, result.cookieName, result.cookieValue, result.cookieMaxAgeSeconds)
                        onSuccess(result.launchUrl)
                    }
                }
                return@login
            }

            // 先在网络回调线程上复位。后面每个分支都可能再把它置一次（幂等），
            // 这样即使某个分支里抛了异常，下一次启动也不会永远被"已在进行中"挡住
            // —— 那种锁死是极难排查的（表现：永远不自动重登，也没有任何日志）。
            autoLoginInFlight = false
            val failure = error ?: LoginError.Network("unknown")
            when (failure) {
                // ===== 唯一两种「服务端明确否定凭据」的情形：必须清密码 =====
                // 不清的后果：每次启动都重试一次，永远失败，且用户看不出发生了什么
                // （以为是网络问题），实际是把限流次数一次次烧掉，最后连手动登录都被锁。
                LoginError.InvalidCredentials -> {
                    io.execute {
                        AccountStore.clearCredentials(appContext)
                        mainHandler.post {
                            onFailure(
                                context.getString(R.string.auto_login_credentials_rejected),
                                failure.debugText(),
                                true,
                                false,
                            )
                        }
                    }
                }
                is LoginError.Http -> {
                    if (failure.code == 403) {
                        io.execute {
                            AccountStore.clearCredentials(appContext)
                            mainHandler.post {
                                onFailure(
                                    context.getString(R.string.auto_login_credentials_rejected),
                                    failure.debugText(),
                                    true,
                                    false,
                                )
                            }
                        }
                    } else {
                        // 其它 4xx/5xx（404 / 500 / 502 / …）：服务端或路由问题。
                        // **不清密码** —— 用户什么都没改错。
                        // 但作废本地 cookie：它已经证明不能用了，留着只会让下次启动
                        // 又走"cookie 有效 → 进 WebView → 401 错误页"这一圈。
                        io.execute {
                            AccountStore.clearCookie(appContext)
                            mainHandler.post {
                                onFailure(
                                    context.getString(R.string.auto_login_server_error),
                                    failure.debugText(),
                                    false,
                                    true,
                                )
                            }
                        }
                    }
                }
                // ===== 429：按 IP 限流。等一会儿是唯一正确的动作，绝不能清凭据 =====
                LoginError.TooManyAttempts -> io.execute {
                    AccountStore.clearCookie(appContext)
                    mainHandler.post {
                        onFailure(
                            context.getString(R.string.auto_login_rate_limited),
                            failure.debugText(),
                            false,
                            true,
                        )
                    }
                }
                // ===== 网络 / 超时：**不清密码**，也不清 cookie =====
                // cookie 留着：很可能只是这一秒连不上，下次启动网络好了就能直接进 WebView，
                // 连自动重登都不用做。这是"断网时启动"最省钱的处理。
                is LoginError.Network, is LoginError.Timeout -> {
                    mainHandler.post {
                        onFailure(
                            context.getString(R.string.auto_login_network),
                            failure.debugText(),
                            false,
                            true,
                        )
                    }
                }
                // ===== 503 / 404 / 响应结构不符：服务端问题，人力无法解决但值得重试 =====
                LoginError.ServiceUnavailable, LoginError.EndpointNotFound, is LoginError.BadResponse -> io.execute {
                    AccountStore.clearCookie(appContext)
                    mainHandler.post {
                        onFailure(
                            context.getString(R.string.auto_login_server_error),
                            failure.debugText(),
                            false,
                            true,
                        )
                    }
                }
                is LoginError.InvalidInput -> {
                    // 400：服务端认为 host/scheme 非法。地址被改坏了，
                    // 清 host 之外的凭据没有意义；让用户自己改地址更靠谱。
                    mainHandler.post {
                        onFailure(
                            context.getString(R.string.auto_login_host_invalid),
                            failure.debugText(),
                            false,
                            false,
                        )
                    }
                }
            }
        }
    }

    /**
     * 把插件会话 cookie 种进 WebView 的 CookieManager。
     *
     * 与 WebViewActivity.seedCookie 是同一套参数（origin 只到 authority、
     * 不显式写 domain、必须 flush）。**为什么这里还要有一份**：
     * 自动重登成功后由本类直接跳 WebView（resume 模式，不经过
     * WebViewActivity 的种 cookie 分支），所以种 cookie 的责任移到了这里。
     * 两处都保持"origin = scheme://authority"的口径，避免一处带路径一处不带
     * 造成的 domain/path 归属差异（那种 bug 表现为"有时登录有效、有时 401"）。
     *
     * ⚠️ 绝不能把 cookie 值写进日志。
     */
    private fun seedSessionCookie(launchUrl: String, name: String, value: String, maxAgeSeconds: Int) {
        try {
            val base = android.net.Uri.parse(launchUrl)
            val origin = base.scheme + "://" + base.authority
            val cookie = name + "=" + value + "; Path=/; Max-Age=" + maxAgeSeconds + "; SameSite=Lax"
            android.webkit.CookieManager.getInstance().setCookie(origin, cookie)
            android.webkit.CookieManager.getInstance().flush()
        } catch (e: Exception) {
            // 种 cookie 失败不阻断跳转：launchUrl 的一次导航本身会铸出官方 cookie，
            // 插件 cookie 缺失的表现是"页面能开、插件功能 401"，用户可点重新登录。
            Log.w(TAG, "seed cookie failed (" + e.javaClass.simpleName + ")")
        }
    }

    /**
     * 用已保存的 host 构造"恢复会话"的地址。
     *
     * ⚠️ **这不是 launchUrl**。cookie 仍在有效期内时，我们不需要新的启动令牌
     * （官方 token 只能兑换一次，旧的早就用掉了），直接访问根路径即可：
     * WebView 会带着 CookieManager 里已有的官方签名 cookie（它在 WebView 自己的
     * cookie 库里，跨进程存活）完成鉴权。
     *
     * 为什么不用 launchUrl 复原：launchUrl 里含一次性 token，存盘 = 存一份
     * 已作废的凭据，而且 token 失效后用户会看到"token 无效"页面而不是首页。
     *
     * @return null 表示 host 无法解析（存储被写坏）。
     */
    private fun buildResumeUrl(host: String): String? {
        val spec = ApiClient.parseHost(host) ?: return null
        return spec.baseUrl + "/"
    }

    /**
     * **主线程安全**的"看看存储里有什么"。
     *
     * 仅供 UI 层做**非关键**的预填（host / username / 是否有密码）。
     * 与 [decideOnStart] 的分工：
     *   · 启动决策必须走 [decideOnStart]（它保证在后台线程完成，且含"能否跳过登录页"的判定）；
     *   · 转屏 / 从 WebView 返回时的"重填一下输入框"优先级很低，
     *     但**绝不能**在主线程上触发 Keystore 初始化（首次 200~500ms，会卡顿甚至 ANR）。
     *
     * 为什么这是安全的：AccountStore.load 命中内存缓存后是纯内存读取（纳秒级）；
     * 只有"本进程第一次读"才会碰磁盘/Keystore —— 而那种情况由 DshApp 的后台预热
     * 与 decideOnStart 覆盖。极端情况下（预热还没完）这里会返回 null，
     * 表现是"输入框没预填"，用户重敲一遍地址即可，不会崩也不会卡死。
     */
    fun peek(context: Context): AccountStore.StoredAccount? = AccountStore.load(context)

    /**
     * 默认服务地址：资源里的 default_host（用户指定的占位地址）。
     *
     * 第一次安装时**只有**这一个用途：预填输入框。README 已写明要改成自己的 DSH 地址。
     */
    private fun defaultHost(context: Context): String = context.getString(R.string.default_host)

    /**
     * 「本次启动进过一次 WebView 了吗」——
     * 用来避免 LoginActivity 在 onResume 时重复触发决策。
     */
    @Volatile
    private var startDecisionDone = false

    fun markStartDecisionDone() {
        startDecisionDone = true
    }

    fun isStartDecisionDone(): Boolean = startDecisionDone

    /** 进程内重置（仅测试/排查用；正常流程不会调）。 */
    fun resetStartDecision() {
        startDecisionDone = false
        autoLoginInFlight = false
    }
}
