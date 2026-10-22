package cd.ptrel.dshpostapi

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import cd.ptrel.dshpostapi.databinding.ActivityWebviewBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * WebView 容器：种入插件会话 cookie，然后用一次导航加载 launchUrl。
 *
 * ============================ 本文件的两个不可改约束 ============================
 * 1. launchUrl 必须【一次导航】直接加载。
 *    官方门禁（browser-auth.ts:244）只认 GET / 且恰好一个 token 参数，
 *    校验通过才 303 + Set-Cookie 铸出官方签名 cookie。
 *    ⇒ 不能"先 loadUrl(host) 再改地址"、不能拆成两步、不能在中间插别的跳转。
 *
 * 2. cookie 必须在 loadUrl 之前种好，并且要 flush()。
 *    插件会话 cookie 是 WebView 内所有 /api/dsh/v1/ 请求（含第二版的下载接口）
 *    的鉴权依据；CookieManager 的写入是异步落盘的，不 flush 时本次进程内的
 *    后续请求有时拿不到（尤其是紧接着的导航）。
 * =============================================================================
 *
 * cookie 为什么不落 SharedPreferences：
 *   官方 cookie 是 HttpOnly + SameSite=Strict（browser-auth.ts:122），本来就取不出来；
 *   插件 cookie 虽可取，但它是可冒用的凭据，存盘等于长期留存一份可被读取的凭证。
 *   MVP 的语义就是"一次登录 = 一次会话"，重启 App 需重新登录（见 README「已知限制」）。
 */
class WebViewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LAUNCH_URL = "cd.ptrel.dshpostapi.EXTRA_LAUNCH_URL"
        const val EXTRA_COOKIE_NAME = "cd.ptrel.dshpostapi.EXTRA_COOKIE_NAME"
        const val EXTRA_COOKIE_VALUE = "cd.ptrel.dshpostapi.EXTRA_COOKIE_VALUE"
        const val EXTRA_COOKIE_MAX_AGE = "cd.ptrel.dshpostapi.EXTRA_COOKIE_MAX_AGE"

        /** 登录时用的服务基址。只用于「退出登录」后的预填，本身不是机密。 */
        const val EXTRA_HOST = "cd.ptrel.dshpostapi.EXTRA_HOST"

        /**
         * resume 模式（v0.3.0）。
         *
         * true 表示本次进入**不是**一次新登录：cookie 已经在 CookieManager 里
         * （冷启动命中未过期 cookie），或已由 LoginFlow 种好（自动重登成功）。
         * 此时本页**不再种 cookie**，避免把 Max-Age 重置成另一个值。
         *
         * 为什么用显式开关而不是"cookie 三件套是否为空"来推断：
         *   空 cookie 在本 App 里是一个合法的异常状态（存储被清但残留 intent），
         *   靠字段为空来分支会让两种情况混在一起，难以排查。
         */
        const val EXTRA_RESUME_MODE = "cd.ptrel.dshpostapi.EXTRA_RESUME_MODE"
    }

    private lateinit var binding: ActivityWebviewBinding
    private var launchUrl: String = ""
    private var lastExitBackAt: Long = 0L

    /**
     * 登录时用的服务基址（不含尾斜杠）。本身不是机密。
     *
     * 用途：Intent 没带 EXTRA_HOST 时的兜底（initHostBaseUrl），以及错误文案里定位服务器。
     * ⚠️ 不要往里塞任何凭据。
     */
    private var hostBaseUrl: String = ""

    /**
     * 本次进入是否复用已有 cookie（见 [EXTRA_RESUME_MODE]）。
     *
     * 只影响两处：不再种 cookie；错误页在 401/403 时仍允许「重试」。
     */
    private var resumeMode: Boolean = false

    /**
     * 是否已经在本页做过一次「加载成功后检查更新」。
     *
     * 为什么要这个标志：onPageFinished 在单页应用里会随路由变化反复触发，
     * 不设标志就是每换一个会话都查一次更新 —— 既是无谓流量，也可能在用户
     * 正在打字时弹窗。用户要求的是「WebView 首次加载成功后检查一次」。
     */
    private var updateCheckedOnPageFinished = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ===== 系统栏留白（targetSdk 35 强制 edge-to-edge 的必要处理）=====
        // 详见 WindowInsetsHelper 的文件注释。要点：
        //   · padding 加在 rootFrame 上 ⇒ 状态栏区域显示 rootFrame 的背景色
        //     （= colorBackground），网页从状态栏下方开始，不会"透出网页"；
        //   · loadingView / errorView 是 rootFrame 的子 View，会一并被 padding 约束，
        //     因此它们也不会盖到状态栏区域（这是要求 3）。
        //   · systemBars or ime 一起取 ⇒ 键盘弹出时底部留白同步长高，
        //     WebView 内的输入框不会被键盘挡住（配合 manifest 的 adjustResize）。
        WindowInsetsHelper.applySystemBarPadding(binding.rootFrame)
        // 图标明暗跟随主题：浅色背景配深色图标，深色背景配浅色图标。
        // 不设置会出现「白图标画在白底上」看不见。
        WindowInsetsHelper.applySystemBarIconAppearance(this, isLightBackground())

        val intent = intent
        launchUrl = intent.getStringExtra(EXTRA_LAUNCH_URL).orEmpty()
        val cookieName = intent.getStringExtra(EXTRA_COOKIE_NAME).orEmpty()
        val cookieValue = intent.getStringExtra(EXTRA_COOKIE_VALUE).orEmpty()
        val cookieMaxAge = intent.getIntExtra(EXTRA_COOKIE_MAX_AGE, 86400)
        resumeMode = intent.getBooleanExtra(EXTRA_RESUME_MODE, false)
        hostBaseUrl = intent.getStringExtra(EXTRA_HOST).orEmpty()

        if (launchUrl.isEmpty()) {
            // 入口参数不全只可能是被外部应用直接拉起（本 Activity 未导出，
            // 正常路径不会发生）。直接退回登录页，不要留一个白屏。
            finish()
            return
        }
        if (hostBaseUrl.isEmpty()) hostBaseUrl = deriveHostBaseUrl(launchUrl)

        // 非 resume 模式必须有完整 cookie 三件套；resume 模式靠 CookieManager 里已有的。
        if (!resumeMode && (cookieName.isEmpty() || cookieValue.isEmpty())) {
            finish()
            return
        }

        configureWebView()
        if (!resumeMode) {
            seedCookie(launchUrl, cookieName, cookieValue, cookieMaxAge)
        }
        setupBack()
        binding.retryButton.setOnClickListener { reload() }
        binding.reloginButton.setOnClickListener { backToLogin() }
        // ===== 退出登录（v0.3.0）=====
        // 入口位置的理由见 [confirmLogout] 的注释。
        binding.logoutFab.setOnClickListener { confirmLogout() }

        // 关键：一次导航直接到 launchUrl。
        binding.webView.loadUrl(launchUrl)
    }

    /**
     * WebView 设置。
     *
     * 逐项理由：
     *   - JavaScriptEnabled：官方前端是 React SPA，没有 JS 就是空白页；
     *   - DomStorageEnabled：会话/草稿用 localStorage，关掉会反复丢状态；
     *   - DatabaseEnabled：旧版数据存储路径，部分 WebView 版本仍依赖；
     *   - useWideViewPort + loadWithOverviewMode：官方前端有 viewport meta，
     *     不开这两项会按 980px 虚拟宽度渲染，手机上字小到看不清；
     *   - MixedContentMode = COMPATIBILITY_MODE：https 页面里若引用 http 资源
     *     （例如内网联调时的图片），默认会被拦成空白。这里放宽的是"混合内容"，
     *     不影响主文档必须 https 的要求（release 包里 usesCleartextTraffic=false）。
     *   - 关掉 file:// 访问：本 App 不需要读本地文件，留着只是扩大攻击面。
     */
    private fun configureWebView() {
        val settings: WebSettings = binding.webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.mediaPlaybackRequiresUserGesture = true
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        // 混合内容：https 页面里引用 http 资源时默认被拦成空白。
        // 这里放宽为 COMPATIBILITY_MODE（按内容类型决定，图片/媒体放行）。
        // 注意它只影响"页面内子资源"，不改变"主文档必须 https"这一条 ——
        // release 包的 usesCleartextTraffic=false 才是主文档级的门禁。
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        // user agent 不覆盖：用 WebView 默认 UA（含 Android + Mobile），
        // 与 ApiClient 登录时用的 UA 同类（见 ApiClient 里的指纹说明）。
        CookieManager.getInstance().setAcceptCookie(true)
        // 官方前端会把静态资源挂在同源路径下，没有第三方域；
        // 但登录/重定向链路上个别浏览器实现会按第三方处理，打开更稳。
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webView, true)

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // 导航开始即切到加载态，但**不清 error 层**里的内容——
                // 用户点"重试"时错误详情应当继续可见直到真的加载成功。
                binding.loadingView.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.loadingView.visibility = View.GONE
                binding.errorView.visibility = View.GONE
                // ===== 检查更新（v0.2.0）=====
                // 只在首次加载成功后查一次。此时插件会话 cookie 一定已随 launchUrl 的
                // 一次导航生效（否则页面本身就是 401 错误页，不会走到这里）。
                // 具体实现是异步 + 5s 超时 + 失败静默，见 Updates.checkQuietly。
                if (!updateCheckedOnPageFinished) {
                    updateCheckedOnPageFinished = true
                    Updates.checkQuietly(this@WebViewActivity)
                }
            }

            /**
             * 主文档级失败（导航本身没成功）。
             *
             * 为什么要区分 onReceivedHttpError 与 onReceivedError：
             *   前者是"服务器回了 HTML，但状态码是错的"（401/404/500），
             *   后者是"根本没拿到响应"（断网 / DNS / 连接被拒）。
             *   两者给用户的下一步动作完全不同，所以分开处理。
             */
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                // 只处理主文档的失败；子资源（图片/字体）失败不应该整页报错。
                if (request == null || !request.isForMainFrame) return
                val code = error?.errorCode ?: 0
                val desc = error?.description?.toString().orEmpty()
                // 这里是"网络层错误码"（WebViewClient.ERROR_* 常量），不是 HTTP 状态码，
                // 文案里必须区分开 —— 否则排查时会去找服务端日志，而实际是客户端网络问题。
                showError("net error code=$code " + desc)
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?,
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request == null || !request.isForMainFrame) return
                val status = errorResponse?.statusCode ?: 0
                if (status >= 500 || status == 401 || status == 403) {
                    showError("HTTP $status " + request.url.toString())
                }
                // 4xx 的其它情况（如 404 的 favicon）不弹错误页，避免误报。
            }

            /**
             * 站外链接交给系统浏览器。
             *
             * 为什么必须做：DSH 聊天里会出现外部链接（文档、issue）。
             * 若在同一个 WebView 里跳出去，用户就回不到会话了；而我们的登录取消
             * 也无法接管一个别人的站点。
             */
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val target = request?.url ?: return false
                return shouldOverrideUrlLoadingInternal(target)
            }

            /**
             * 旧签名重载：部分 WebView 内部路径仍会调用 String 版。
             * 只实现新签名时，这些路径会走默认行为（在当前 WebView 内加载），
             * 于是站外链接会"偶发"跳走 —— 属于典型的难查不一致。这里显式补齐。
             */
            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url.isNullOrEmpty()) return false
                val target = Uri.parse(url)
                return shouldOverrideUrlLoadingInternal(target)
            }
        }
    }

    /** 新/旧两个入口共用的判断逻辑，避免两处判据漂移。 */
    private fun shouldOverrideUrlLoadingInternal(target: Uri): Boolean {
        if (isSameHostInApp(target)) return false
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, target))
            true
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(this, target.toString(), Toast.LENGTH_LONG).show()
            true
        }
    }

    /**
     * 是否与应用自身的 origin 同源（scheme + host + 端口归一化后相同）。
     *
     * 为什么不能直接比较 Uri.port：
     *   Uri.parse("https://h") 的 port 是 -1（未写端口），
     *   而页面里生成的绝对链接可能写成 https://h:443/...，port=443。
     *   直接比 -1 == 443 会判成"站外"，把本该留在 App 内的链接踢到浏览器。
     *   这里把 -1 按 scheme 的默认端口归一化。
     */
    private fun isSameHostInApp(target: Uri): Boolean {
        val base = Uri.parse(launchUrl)
        if (target.scheme != base.scheme) return false
        if (target.host != base.host) return false
        return effectivePort(target) == effectivePort(base)
    }

    /** [Uri.port] 为 -1（URL 未写端口）时按 scheme 的默认端口取值。 */
    private fun effectivePort(uri: Uri): Int {
        if (uri.port != -1) return uri.port
        return when (uri.scheme) {
            "https" -> 443
            "http" -> 80
            else -> -1
        }
    }

    /**
     * 从 launchUrl 推导服务基址（scheme + authority）。
     *
     * 只在 Intent 没带 EXTRA_HOST 时使用。launchUrl 是 "https://host/?token=..." 形式，
     * Uri.parse 之后取 scheme + authority 即可 —— **不带** path 与 query，
     * 避免把一次性 token 拼进字符串里（那是凭据，不该在别的字段里再出现一份）。
     */
    private fun deriveHostBaseUrl(url: String): String {
        return try {
            val uri = Uri.parse(url)
            val scheme = uri.scheme ?: return ""
            val authority = uri.authority ?: return ""
            scheme + "://" + authority
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 种入插件会话 cookie。
     *
     * URL 只用 scheme://authority（不带路径）：CookieManager 以 URL 决定 domain/path 归属，
     * 传 launchUrl 也未尝不可，但它带 ?token= 查询串，语义上不该参与 cookie 归属判断。
     *
     * domain 不显式写：交给 CookieManager 从 URL 推导，避免手写 domain 时
     * 漏掉前导点（.example.com 与 example.com 在 cookie 语义里不同）。
     *
     * flush() 的理由：CookieManager 的写入是异步的，不 flush 时紧随其后的
     * loadUrl 有概率读不到这枚 cookie，于是插件接口 401。这是本文件最容易被忽略的一行。
     */
    private fun seedCookie(launchUrl: String, name: String, value: String, maxAgeSeconds: Int) {
        val base = Uri.parse(launchUrl)
        val origin = base.scheme + "://" + base.authority
        val cookie = name + "=" + value + "; Path=/; Max-Age=" + maxAgeSeconds + "; SameSite=Lax"
        CookieManager.getInstance().setCookie(origin, cookie)
        CookieManager.getInstance().flush()
    }

    /**
     * 返回键语义：
     *   - 网页有历史 → 后退（用户预期如此，尤其在 SPA 里点了会话再按返回）；
     *   - 已在首页 → 连按两次退出，避免"一次误触直接退出登录"。
     *
     * 用 OnBackPressedCallback 而不是重写 onBackPressed()：
     *   后者在目标 SDK 33+ 已不推荐，且与 predictive back 手势不兼容。
     */
    private fun setupBack() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                    return
                }
                val now = System.currentTimeMillis()
                if (now - lastExitBackAt < 2000L) {
                    finish()
                } else {
                    lastExitBackAt = now
                    Toast.makeText(
                        this@WebViewActivity,
                        getString(R.string.webview_exit_hint),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        })
    }

    /** 重试：先回到加载态，再重新导航到 launchUrl。
     *
     * 为什么 reload 而不是 loadUrl(launchUrl)：
     *   页面已经因为 launchUrl 的 token 兑换过 cookie，token 是一次性的；
     *   reload 复用当前文档地址（已重定向到 /），不会二次消耗 token。
     *   只有在"完全没导航成功"（currentUrl 为空）时才回退到 loadUrl。
     */
    private fun reload() {
        binding.errorView.visibility = View.GONE
        binding.loadingView.visibility = View.VISIBLE
        val current = binding.webView.url
        if (current.isNullOrEmpty()) {
            binding.webView.loadUrl(launchUrl)
        } else {
            binding.webView.reload()
        }
    }

    /**
     * 展示错误页。
     *
     * 只显示错误层、**不动 WebView**：销毁重建会丢掉页面内状态，
     * 而且用户点重试时我们希望尽可能复用同一个 WebView。
     *
     * 为什么把 401/403 归到"需要重新登录"：
     *   launchUrl 里的 token 只能兑换一次官方 cookie。若这次导航没能落上 cookie
     *   （例如中途被拦截/断网），之后 reload 拿到的仍是带 token 的地址，
     *   但那个 token 已经消费掉了 —— 表现就是一直 401。
     *   此时重试没有意义，必须回登录页重新取一张 launchUrl。
     */
    private fun showError(detail: String) {
        binding.loadingView.visibility = View.GONE
        binding.errorDetail.text = detail
        binding.errorView.visibility = View.VISIBLE

        val needsRelogin = detail.contains("HTTP 401") || detail.contains("HTTP 403")

        // ===== v0.3.0：会话已失效 ⇒ 作废本地缓存的那枚 cookie =====
        // 如果不作废，下次启动会命中"cookie 未过期 → 直接进 WebView"的分支，
        // 又看到同一个 401 错误页 —— 用户会以为 App 坏了，而且每次都白跑一轮。
        // 在这里清是安全的：能走到这条路径说明服务端刚刚用它回了 401/403。
        if (needsRelogin) {
            val appContext = applicationContext
            Thread({ AccountStore.clearCookie(appContext) }, "dsh-clear-cookie").apply {
                isDaemon = true
            }.start()
        }

        // 401/403 时「重试」隐藏：launchUrl 里的 token 只能兑换一次，
        // 重试拿到的还是已作废的 token，没有意义。
        // 但 **resume 模式**是个例外 —— 那时地址是 host 根路径、没有 token，
        // 重试确实可能失败（网络抖动），所以保留「重试」。
        val canRetry = !needsRelogin || resumeMode
        binding.retryButton.visibility = if (canRetry) View.VISIBLE else View.GONE

        // 「返回登录」的语义随场景变化，必须换文案：
        //   · 新登录（刚拿到 launchUrl）：401 说明 token 已消费 ⇒ "返回登录"是重新取一张；
        //   · resume 模式（用存储的 cookie 直接进）：401 说明 cookie 被服务端吊销 ⇒
        //     用户真正需要的是"用保存的账号密码重新登录一次" —— 而 [LoginActivity]
        //     从 WebView 返回后**不会**自动重登（它只在冷启动时自动重登）。
        //
        // ⚠️ 已知取舍（诚实记录，见 README「需真机确认」）：
        //    目前点它会回到登录页（已预填 host + username，密码框为空），
        //    用户需要重新输入密码。要真正做到"点一下自动重登"，需要
        //    ① 作废存储的 cookie，② 回到 LoginActivity 并强制走 LoginFlow 的自动重登分支。
        //    本版没有做，因为它会引入"用户主动退出到登录页"与"自动重登"两个入口的歧义。
        binding.reloginButton.text = if (needsRelogin && resumeMode) {
            getString(R.string.action_back_to_login_needs_password)
        } else {
            getString(R.string.action_back_to_login)
        }
        binding.reloginButton.visibility = View.VISIBLE
        binding.logoutButton.visibility = View.VISIBLE
    }

    /**
     * 「返回登录」：会话出问题时的出口。
     *
     * 与「退出登录」的区别（这是**两件不同的事**，不要合并）：
     *   返回登录 = 我要**重新登一次**（同一个账号）。保留存储里的账号密码，
     *              登录页会预填 host + username，密码留空；
     *   退出登录 = 这台手机**别留我的记录**。清空全部存储 + CookieManager。
     *
     * 为什么保留两套：WebView 401 错误页上的用户诉求是"重新登录"，
     * 他要的是回到可用状态，不是把我记住的账号抹掉。
     * 只有明确说"退出"的人才需要清除。
     */
    private fun backToLogin() {
        finish()
    }

    /**
     * 「退出登录」确认弹窗。
     *
     * ===== 入口位置 & 理由（用户让我判断）=====
     * 放**两处**，因为两种诉求出现的时机不同：
     *
     * ① 页面右上角的悬浮小按钮（常驻）：会话**正常**时用户想换账号 / 借手机给别人。
     *    这时页面是 WebView 内容，没有任何原生 UI 可以挂 —— 只能悬浮。
     *    为什么是"小圆点按钮"而不是"右上角菜单"：
     *      · 官方前端自己在右上角可能有按钮，做一个展开式菜单会与之重叠，
     *        点哪一层都可能被 WebView 抢走事件；
     *      · 菜单需要额外的展开状态、点击外部关闭、返回键拦截 —— 为**一个**动作做一整套
     *        菜单是过度设计；真的需要第二个动作（如"刷新"）再加也不迟。
     *    为什么放右侧而不是左上：左上"返回箭头"位置在 SPA 里容易被误认为浏览器后退。
     *
     * ② 错误页「返回登录」按钮旁边：会话**已失效**时（401/403/断网）最需要退出。
     *    此时错误页盖住了 WebView，悬浮按钮也被盖住，所以错误页上必须有。
     *    用 TextButton（次要样式）而不是两个等宽的主按钮 —— 避免用户误点。
     *
     * 两处都走本方法（同一个确认弹窗），保证行为一致。
     */
    private fun confirmLogout() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.logout_title)
            .setMessage(R.string.logout_message)
            .setNegativeButton(R.string.logout_action_cancel, null)
            .setPositiveButton(R.string.logout_action_confirm) { _, _ -> performLogout() }
            .show()
    }

    /**
     * 真正执行退出登录。
     *
     * 三件事**都必须做**，少一件就会留下"以为退出了其实没退"的状态：
     *
     *   ① 清空加密存储（账号 + 密码 + cookie）：下次启动不会自动重登；
     *   ② 清 CookieManager：否则官方签名 cookie（HttpOnly，我们存不下来也删不掉
     *      除 CookieManager 之外的任何地方）还在，下次进 WebView 仍是已登录状态 ——
     *      这是"退出登录后换台手机/换个人还能看到会话"的根因；
     *   ③ 清 WebView 的本地存储：官方 SPA 用 localStorage 存草稿/设置，
     *      留着等于把上一账号的使用痕迹留给下一个人。
     *
     * ⚠️ 顺序很重要：先清 CookieManager 再销毁 WebView。
     *    反过来的话，WebView 销毁过程中的某些访问可能又把内存里的 cookie 写回磁盘。
     * ⚠️ removeAllCookies 是**异步**的（回调里才是真的清完）。这里不等回调：
     *    本页马上 finish，没有后续导航会用到那枚 cookie；即使异步落盘有延迟，
     *    下次 WebView 加载时才会读 cookie，那时早已清完。
     *    但 flush() 必须调 —— 它把"清空"这个动作同步挤进磁盘队列。
     */
    private fun performLogout() {
        // ① 加密存储（在后台线程：AES + commit；主线程不等它）
        val appContext = applicationContext
        Thread({
            AccountStore.clearAll(appContext)
        }, "dsh-logout-store").apply { isDaemon = true }.start()

        // ② CookieManager
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.flush()

        // ③ WebView 的页面级状态
        //    没有 WebView.clearStorage() 这个 API —— 那是我第一版的错误记忆。
        //    真正对应的三个调用：
        //      · WebStorage（localStorage / IndexedDB / WebSQL）：WebStorage.getInstance().deleteAllData()
        //        ⚠️ 它是**全应用**级的，会连同其它 WebStorage 数据一起清 —— 本 App 只有一个 WebView，
        //        这正是"退出登录要把上一账号的痕迹清干净"想要的效果。
        //      · 表单自动填充：clearFormData()（用户可能存过输入过的搜索词）
        //      · 前进后退历史：clearHistory()，避免"退出后按返回还能回到上一账号的页面"
        WebStorage.getInstance().deleteAllData()
        binding.webView.clearFormData()
        binding.webView.clearHistory()

        // ④ 更新流程的界面状态（下载中 → 取消；否则回调会打到已销毁的界面）
        Updates.onActivityDestroyed()

        Toast.makeText(this, R.string.logout_done, Toast.LENGTH_SHORT).show()

        // 回登录页：startActivity 一个新的 LoginActivity 再 finish 自己。
        // 不用 CLEAR_TASK：那会把系统最近任务里的记录也清掉，行为上像"杀进程"，
        // 用户从最近任务切回时会看到一个全新的 App（失焦感）。
        // 这里用 NEW_TASK|CLEAR_TOP 语义上不合适（LoginActivity 可能已在栈里），
        // 最稳的是普通 startActivity + finish —— 栈里最多多一个登录页，
        // 退出登录是低频操作，不值得为此设计栈管理。
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    /**
     * 当前是否为浅色背景（决定系统栏图标用深色还是浅色）。
     *
     * 判断依据用 `uiMode` 而不是读取某个颜色值：
     *   本项目的浅/深色由 `values/` 与 `values-night/` 两套资源决定，
     *   而 uiMode 的 NIGHT 位正是资源系统选择 values-night 的依据。
     *   两者同源，所以不会出现"资源是深色、图标按浅色画"的错配。
     */
    private fun isLightBackground(): Boolean {
        val nightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return nightMode != android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    override fun onPause() {
        super.onPause()
        // 让页面进入后台态，暂停计时器类动画（省电）。onResume 里恢复。
        binding.webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
    }

    override fun onDestroy() {
        // 取消在途的更新下载：否则回调会在 Activity 销毁后尝试操作对话框。
        // 只取消下载，不动 UpdateSession（会话上下文属于整个登录周期）。
        Updates.onActivityDestroyed()
        // WebView 持有 Activity 的 Context，不主动销毁会泄漏整个 Activity
        // （表现：退出后内存不释放，反复进出后 OOM）。
        // 只在真正要结束时销毁：配置变更走 destroy → 重建会丢页面状态，
        // 而本 Activity 已在 manifest 里声明 configChanges 接管常见配置变化，
        // 因此 isFinishing 的判断就够用了。
        if (isFinishing) {
            binding.webView.stopLoading()
            binding.webView.loadUrl("about:blank")
            binding.webView.clearHistory()
            binding.webView.removeAllViews()
            binding.webView.destroy()
        }
        super.onDestroy()
    }
}
