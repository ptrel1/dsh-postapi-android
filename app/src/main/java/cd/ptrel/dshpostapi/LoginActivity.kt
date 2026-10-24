package cd.ptrel.dshpostapi

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cd.ptrel.dshpostapi.databinding.ActivityLoginBinding

/**
 * 原生登录页：账号密码 -> POST /api/dsh/v1/mobile-login -> 把结果交给 WebViewActivity。
 *
 * 为什么登录页是原生的、而不是直接用官方 /login 网页：
 *   官方登录页是"提交表单 + 302 跳转"，WebView 里做完之后仍然缺官方签名 cookie
 *   的铸造入口（那个入口只有 GET /?token= 一个）。mobile-login 正是为此加的
 *   机器可编程入口：一次调用同时拿到 launchUrl（可铸官方 cookie）与插件会话 cookie。
 *
 * ==================== v0.3.0：账号本地记忆 ====================
 * 本页现在是**两个入口**：
 *   ① 冷启动：先问 [LoginFlow.decideOnStart]，可能直接跳过本页进 WebView；
 *   ② 从 WebView「返回登录/退出登录」回来：预填上次的 host + username + password
 *      （v0.4.0 问题 ③：密码也填，但**不**自动登录 —— 见 [EXTRA_AUTO_LOGIN]）
 *
 * 决策逻辑全在 [LoginFlow]（那里有完整的边界表），本页只负责**呈现**。
 *
 * 凭据处理（安全边界，改代码前先读）：
 *   - 密码在本页只存在于两个地方：输入框（用户输入）与 [AutoLogin] 的局部变量；
 *   - **不写日志**（本文件所有 Log 都只记异常类名/状态，绝不记输入值）；
 *   - **不进 Intent extras**：跳 WebViewActivity 只传 launchUrl 与 cookie ——
 *     等等，cookie 也进 Intent 了（见 openWebView）。这是 v0.2.0 的既有设计，
 *     v0.3.0 保留：Intent 只在同一 App 的进程内传递（WebViewActivity 未导出，
 *     exported=false），且不入最近任务快照。密码则**完全不出本页**。
 *   - 登录成功后清空输入框里的密码（onResume 也会清，双保险）。
 */
class LoginActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LoginActivity"

        /**
         * 「进来之后要不要用保存的账号自动登录」（v0.4.0，问题 ③）。
         *
         * 默认 true（冷启动的正常行为）。WebViewActivity 的「退出登录」显式传 false：
         * 用户刚点了退出，登录页只负责把地址/用户名/密码**预填**好，绝不自动登回去。
         *
         * 为什么用 Intent 参数而不是"同进程"判断：退出登录是 startActivity 一个新实例，
         * 新旧实例并存，任何进程级标志都难以区分"用户主动退出"与"被系统重建"。
         */
        const val EXTRA_AUTO_LOGIN = "cd.ptrel.dshpostapi.EXTRA_AUTO_LOGIN"

        /** 交给 [ModeSelectActivity] 的服务端根地址（选项页不导航，只把它带到下一个界面）。 */
        const val EXTRA_PENDING_HOST = "cd.ptrel.dshpostapi.EXTRA_PENDING_HOST"
    }

    private lateinit var binding: ActivityLoginBinding

    /** 是否已按 [LoginFlow.Decision] 完成过预填。避免 onResume 反复覆盖用户正在输入的内容。 */
    private var prefillDone = false

    /**
     * 本次进来是否允许用保存的账号自动登录（见 [EXTRA_AUTO_LOGIN]）。
     *
     * false = 从 WebView「退出登录」回来：只预填，不发任何登录请求。
     */
    private var allowAutoLogin = true

    /**
     * 自动重登用到的凭据。
     *
     * 为什么不用字段存密码：见 onDestroy —— 本页退出时把它置空，
     * 减少"密码在内存里长驻"的窗口。存的是 LoginFlow 从加密库读出来的那一份。
     */
    private var pendingAutoLoginHost: ApiClient.HostSpec? = null
    private var pendingAutoLoginUsername: String = ""
    private var pendingAutoLoginPassword: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ===== 系统栏留白（targetSdk 35 强制 edge-to-edge 的必要处理）=====
        WindowInsetsHelper.applySystemBarPadding(binding.loginScroll)
        WindowInsetsHelper.applySystemBarIconAppearance(this, isLightBackground())

        binding.loginButton.setOnClickListener { submit() }
        binding.passwordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }
        // 「重新登录」：自动重登失败后的重试入口（网络 / 429 场景）。
        binding.retryAutoLoginButton.setOnClickListener { retryAutoLogin() }
        // ===== 「清除已保存的账号」（v0.4.0，问题 ③）=====
        // 退出登录只清会话（保留账号供预填），"彻底清掉这台手机上的记录"必须另有入口，
        // 否则用户想换人用/卖手机时没有可用的动作。这里放一个**次要链接**：
        // 低频、不可逆、且会让用户重敲三样东西，不该做成与「登录」同等重量的按钮。
        binding.clearAccountLink.setOnClickListener { confirmClearAccount() }
        // v0.5.2：使用方式切换（登录页内嵌，不跳页）。
        setupModeRow()

        // 地址被用户改动 ⇒ 视为换服务器，清掉旧账号的密码与 cookie。
        // 只监听 hostInput，且只在"已有账号且新地址与旧地址不同"时动手（见 onHostEdited）。
        binding.hostInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (suppressHostWatcher) return
                onHostEdited(s?.toString().orEmpty())
            }
        })

        // ===== 冷启动决策（只做一次）=====
        // 为什么用 LoginFlow 的进程级标志而不是 savedInstanceState：
        //   本 Activity 被「退出登录」重新拉起（startActivity(new Intent(this, LoginActivity))）
        //   时，savedInstanceState 是 null —— 那会被误判成"冷启动"，
        //   于是刚退出登录就立刻自动重登回去，用户会觉得"退不出去"。
        //   进程级标志在两种情况下的语义都对：
        //     · 冷启动：第一次进 → 做决策（含自动重登）；
        //     · 退出登录后重进：同一进程，标志已是 true → 只预填，不自动重登。
        //   两个 Activity 实例并存（栈里保留旧实例）时也共享这个标志，不会打架。
        // v0.4.0（问题 ③）：退出登录会带 EXTRA_AUTO_LOGIN=false —— 那就把密码填好，
        // 但**不**自动登录。冷启动（默认 true）行为不变。
        allowAutoLogin = intent?.getBooleanExtra(EXTRA_AUTO_LOGIN, true) ?: true
        if (!LoginFlow.isStartDecisionDone()) {
            startDecision()
        } else {
            prefillFromStore()
        }
    }

    // ==========================================================================
    // 启动决策
    // ==========================================================================

    private fun startDecision() {
        // ⚠️ 先把"启动决策已开始"这个标志置上，**在**异步读取之前。
        // 理由：decideOnStart 是异步的（IO 线程读存储）。如果标志在回调里才置，
        // 用户在 200~500ms 的窗口内转屏 → 新实例 onCreate 又跑一遍 → 两次决策、
        // 两次自动重登（服务端会按 IP 计两次失败）。开始前置上就堵住了这个窗口。
        LoginFlow.markStartDecisionDone()
        // LoginFlow 内部切线程（存储读取要几百毫秒）。
        LoginFlow.decideOnStart(this) { decision ->
            if (isFinishing || isDestroyed) return@decideOnStart
            when (decision) {
                is LoginFlow.Decision.EnterWebView -> {
                    // cookie 仍有效：跳过登录页，直接进 WebView。
                    enterWebViewWithoutLogin(decision.launchUrl)
                }
                is LoginFlow.Decision.ShowLoginPage -> {
                    applyPrefill(decision.host, decision.username)
                    if (decision.password.isNotEmpty() && (decision.prefillPassword || !allowAutoLogin)) {
                        // v0.4.0（问题 ③）：用户刚「退出登录」，只是希望不用重敲 ——
                        // 填好但不发请求。"退出登录"必须真的退出，不能被自动重登撤销。
                        binding.passwordInput.setText(decision.password)
                        Log.i(TAG, "prefilled saved password, not auto-logging in")
                    } else if (decision.password.isNotEmpty()) {
                        // 有密码且要求自动重登 ⇒ 发起自动重登（用户看不到登录页，除非失败）。
                        pendingAutoLoginUsername = decision.username
                        pendingAutoLoginPassword = decision.password
                        pendingAutoLoginHost = ApiClient.parseHost(decision.host)
                        if (pendingAutoLoginHost != null) {
                            performAutoLogin()
                        } else {
                            // host 坏到连解析都失败：当作没账号，停在登录页让用户改。
                            clearPendingCredentials()
                        }
                    }
                }
                is LoginFlow.Decision.AutoLoginFailed -> Unit // decideOnStart 不会返回它
            }
        }
    }

    /**
     * 非冷启动（例如「退出登录」后重新进入本页）：只预填，不做任何网络请求。
     *
     * v0.4.0（问题 ③）：退出登录改成 [AccountStore.clearSession] 之后，存储里的
     * host / username / **password** 都还在（clearSession 只清 cookie）。
     * 所以这里要把三个框都填好 —— 用户要求「退出登录之后地址用户名密码
     * 应该数据保存在本地不需要重复输入」。密码只填**不发请求**：
     * 自动登录由 [allowAutoLogin] 单独控制。
     *
     * 若存储里什么都没有（例如 Keystore 失效导致整库被清），回退到
     * [LoginDefaults] 里"上次用过的地址"（那个库刻意不被清除）。
     */
    private fun prefillFromStore() {
        val account = LoginFlow.peek(this)
        if (account != null) {
            applyPrefill(account.host, account.username)
            // 密码也预填（用户点「登录」即可）；不在这里发请求 —— 本方法只做预填。
            if (account.password.isNotEmpty()) {
                binding.passwordInput.setText(account.password)
            }
        } else {
            val last = LoginDefaults.lastHost(this)
            if (last != null) applyPrefill(last, "")
        }
    }

    /** 用上次的 host / username 预填；密码永远留空（除非走自动重登）。 */
    private fun applyPrefill(host: String, username: String) {
        if (prefillDone) return
        prefillDone = true
        // 直接 setText 会触发 hostInput 的 TextWatcher → 被误判成"用户改了服务器"。
        // 用 suppressHostWatcher 抑制这一次。
        suppressHostWatcher = true
        try {
            binding.hostInput.setText(host)
            binding.usernameInput.setText(username)
            binding.passwordInput.setText("")
        } finally {
            suppressHostWatcher = false
        }
    }

    // ==========================================================================
    // v0.5.0：使用模式分发
    // ==========================================================================

    /**
     * 按已保存的使用模式进对应界面；**从没选过则先让用户选**。
     *
     * @param launchUrl 一次性登录 token 的导航地址；空串表示 resume（复用 CookieManager 里的 cookie）。
     * @param hostBaseUrl 服务端根地址，兼容模式不用但完整模式需要。
     *
     * 为什么模式判定放在 LoginActivity 而不是各目标界面自己判：
     *   目标界面（WebViewActivity）是被动容器，它不该知道"还有另一种模式"这回事；
     *   分发是登录页的职责，且这里已经有全部上下文（launchUrl / host / cookie）。
     */
    private fun routeToConfiguredMode(launchUrl: String, hostBaseUrl: String, finishSelf: Boolean = true) {
        // v0.5.2：不再跳独立的模式选择页。
        // 原因（设计心理学）：用户已到登录页，目标是"进去"；再插一页是在目标前加障碍
        // （目标梯度效应），且登录后跳新页面时用户注意力在"登录成功了吗"，
        // 根本看不见新页面的内容（变更盲视）。
        // 改成登录页上一行常驻开关（见 activity_login.xml 的 modeRow），原地切换、状态可见。
        //
        // 默认 = 完整模式：老用户行为完全不变（他们本来就是进 WebView）。
        when (AppPrefs.getUiMode(this)) {
            AppPrefs.UI_MODE_COMPAT -> enterConfiguredMode(CompatActivity::class.java, launchUrl, hostBaseUrl)
            else -> enterConfiguredMode(WebViewActivity::class.java, launchUrl, hostBaseUrl)
        }
        // 登录成功这条路径**不** finish：用户从 WebView 返回时应当能回到登录页重试
        // （v0.3.0 起的既有行为，见调用处注释）。
        // 冷启动 resume / 自动重登这两条路径要 finish —— 登录页已经没有意义了。
        if (finishSelf) finish()
    }

    /**
     * 进某个具体界面（两种模式共用）。
     *
     * launchUrl 为空时用 EXTRA_RESUME_MODE（复用 CookieManager 已有 cookie），
     * 否则把 launchUrl 交出去让目标界面做那一次导航（官方 BrowserAuth 只在那一次请求上铸 cookie）。
     */
    /**
     * 进某个具体界面（两种模式共用）。
     *
     * ⚠️ **必须同时设 EXTRA_RESUME_MODE = true**，即使带了 launchUrl。
     *   原因：WebViewActivity.onCreate 里有一条护栏 ——
     *     if (!resumeMode && (cookieName.isEmpty() || cookieValue.isEmpty())) { finish(); return }
     *   它防的是「非 resume 却没有 cookie 三件套」这种参数不全的调用。
     *   而 v0.5.0 起 cookie 已经由调用方先种进 CookieManager（见 openWebView），
     *   Intent 里不再传三件套 ⇒ 不设 resumeMode 就会被这条护栏判定为参数不全、立刻 finish。
     *   （v0.5.1~0.5.4 实测就是这个 bug：完整模式登录后页面一闪就退回登录页；
     *    而从兼容模式切完整模式却能进 —— 因为那条路径设了 resumeMode。）
     *
     * launchUrl 仍要传：官方 BrowserAuth 只在「GET /?token=」那一次请求上铸官方 cookie，
     * resume 语义只表示"不用再种插件 cookie"，不是"不用导航"。
     */
    private fun enterConfiguredMode(target: Class<*>, launchUrl: String, hostBaseUrl: String? = null) {
        val intent = Intent(this, target)
        intent.putExtra(WebViewActivity.EXTRA_RESUME_MODE, true)
        if (launchUrl.isNotEmpty()) {
            intent.putExtra(WebViewActivity.EXTRA_LAUNCH_URL, launchUrl)
        }
        if (!hostBaseUrl.isNullOrEmpty()) {
            intent.putExtra(WebViewActivity.EXTRA_HOST, hostBaseUrl)
        }
        startActivity(intent)
    }

    // ==========================================================================
    // v0.5.2：登录页内嵌的模式切换
    // ==========================================================================

    /**
     * 把「使用方式」那一行接到点击事件上，并回填当前值。
     *
     * 为什么放在登录页而不是另开一页 —— 见 activity_login.xml 里 modeRow 的注释
     * （目标梯度效应 + 变更盲视 + 状态必须可见）。
     *
     * 为什么是 Dialog 而不是下拉/Spinner：
     *   两个选项各自有一句「它意味着什么」的说明，Spinner 一行放不下；
     *   而且 Spinner 的选中态不好表达"当前值"。Dialog 能容纳解释、且取消零成本。
     */
    private fun setupModeRow() {
        binding.modeRow.setOnClickListener { showModeDialog() }
        refreshModeRow()
    }

    /** 回填模式行右侧的当前值。 */
    private fun refreshModeRow() {
        val compat = AppPrefs.getUiMode(this) == AppPrefs.UI_MODE_COMPAT
        binding.modeValue.text = getString(
            if (compat) R.string.mode_compat_title else R.string.mode_full_title,
        )
    }

    /**
     * 弹「选择使用方式」。
     *
     * 用单选 + 确定（而不是点一下立即生效）：
     *   两个选项的代价不对称 —— 兼容模式缺功能。让用户看清说明再确认，
     *   比"误点了立刻切走"更稳。取消 = 什么都没发生，零成本。
     */
    /**
     * 弹「选择使用方式」。
     *
     * 用单选 + 确定（而不是点一下立即生效）：
     *   两个选项的代价不对称 —— 兼容模式缺功能。让用户看清说明再确认，
     *   比"误点了立刻切走"更稳。取消 = 什么都没发生，零成本。
     *
     * ⚠️ 不要把说明放进 setMessage()：
     *   AlertDialog 的 setMessage 和 setSingleChoiceItems **共用同一个内容槽**，
     *   先设 message 会把后面的 ListView 盖掉 —— 表现是"只有一行说明，选项不见了"（实测踩过）。
     *   正确做法是把说明并进每个选项的标题文字里，多行也照样显示。
     */
    private fun showModeDialog() {
        val modes = arrayOf(AppPrefs.UI_MODE_FULL, AppPrefs.UI_MODE_COMPAT)
        val titles = arrayOf(
            getString(R.string.mode_full_title) + "\n" + getString(R.string.mode_full_desc),
            getString(R.string.mode_compat_title) + "\n" + getString(R.string.mode_compat_desc),
        )
        val current = AppPrefs.getUiMode(this) ?: AppPrefs.UI_MODE_FULL
        var picked = if (current == AppPrefs.UI_MODE_COMPAT) 1 else 0

        // 自定义带两行文字的列表项：系统 simple_list_item_single_choice 是单行的，
        // 第二行会被截断或挤在一起，读不清楚。
        val adapter = object : android.widget.ArrayAdapter<String>(this, 0, titles) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val v = convertView ?: layoutInflater.inflate(
                    android.R.layout.simple_list_item_single_choice, parent, false,
                )
                (v as android.widget.CheckedTextView).text = getItem(position)
                v.textSize = 14f
                v.setLineSpacing(0f, 1.25f)
                return v
            }
        }

        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.mode_dialog_title)
            .setSingleChoiceItems(adapter, picked) { _, which -> picked = which }
            .setPositiveButton(R.string.mode_dialog_confirm) { _, _ ->
                if (!AppPrefs.setUiMode(this, modes[picked])) {
                    android.util.Log.w("LoginActivity", "persist ui mode failed")
                }
                // 只刷新登录页那一行显示；模式在登录时才真正生效，不需要重启界面。
                refreshModeRow()
            }
            .setNegativeButton(R.string.mode_dialog_cancel, null)
            .show()
    }

    /**
     * 的「cookie 仍有效」路径：不经过登录，直接进 WebView。
     *
     * 此时没有新的 launchUrl（一次性 token 早已消费），所以：
     *   - 加载地址 = host 根路径；
     *   - **不需要种 cookie**：官方签名 cookie 与插件 cookie 都在 CookieManager 里
     *     （WebView 自己的 cookie 库跨进程持久化），导航时自动带上。
     *
     * 若这枚 cookie 其实已被服务端吊销，WebView 会显示 401 错误页 —— 那里有
     * 「重新登录」入口，会回到本页并强制走自动重登（见 WebViewActivity）。
     */
    private fun enterWebViewWithoutLogin(url: String) {
        // 检查更新需要 cookie：此时 cookie 在 CookieManager 里而不是登录响应的
        // 内存上下文里，UpdateSession 从 AccountStore 读（那里存着登录时那份），
        // 所以这里不需要额外做什么。若存储里没有 cookie（例如上一版残留），
        // Updates.checkQuietly 会静默返回，不会误报。
        // v0.5.0：必须走模式分发 —— 硬编码 WebViewActivity 会让选了兼容模式的用户
        // 每次冷启动都被踢回完整模式（cookie 有效路径是最高频的入口）。
        // hostBaseUrl 传空：WebViewActivity 有 AccountStore 兜底（initHostBaseUrl）。
        routeToConfiguredMode(url, "")
    }

    // ==========================================================================
    // 自动重登
    // ==========================================================================

    private fun performAutoLogin() {
        val host = pendingAutoLoginHost ?: return
        if (pendingAutoLoginPassword.isEmpty()) return

        setBusy(true)
        binding.autoLoginHint.visibility = View.VISIBLE
        binding.autoLoginHint.text = getString(R.string.auto_login_in_progress)
        binding.retryAutoLoginButton.visibility = View.GONE

        LoginFlow.performAutoLogin(
            context = this,
            host = host,
            username = pendingAutoLoginUsername,
            password = pendingAutoLoginPassword,
            onSuccess = { launchUrl ->
                if (isFinishing || isDestroyed) return@performAutoLogin
                Log.i(TAG, "auto-login ok")
                clearPendingCredentials()
                binding.autoLoginHint.visibility = View.GONE
                setBusy(false)
                // 自动重登成功：存 cookie 由 LoginFlow 完成（它拿到的是响应体）。
                // 这里只负责跳转。
                openWebViewWithLaunchUrl(launchUrl, host.baseUrl)
            },
            onFailure = { message, detail, cleared, retryable ->
                if (isFinishing || isDestroyed) return@performAutoLogin
                // ⚠️ 不记录 detail 到日志：detail 可能含响应体片段（服务端 403 的 body）。
                // 它只展示在错误区，供用户排查。
                Log.w(TAG, "auto-login failed: cleared=" + cleared + " retryable=" + retryable)
                setBusy(false)
                pendingAutoLoginPassword = ""
                binding.autoLoginHint.visibility = View.GONE
                showError(message, detail)
                if (cleared) {
                    // 凭据已被清除：密码框必须清空，否则用户会以为"密码还在，只是没登上去"。
                    binding.passwordInput.setText("")
                    binding.usernameInput.setText(pendingAutoLoginUsername.ifEmpty {
                        binding.usernameInput.text?.toString().orEmpty()
                    })
                }
                binding.retryAutoLoginButton.visibility = if (retryable) View.VISIBLE else View.GONE
                if (retryable) {
                    binding.loginButton.text = getString(R.string.action_login)
                }
            },
        )
    }

    /** 「重新登录」按钮：只有 retryable 时可见。重新读一次存储里的密码后再试。 */
    private fun retryAutoLogin() {
        if (pendingAutoLoginPassword.isNotEmpty()) {
            // 内存里还有（网络/限流失败路径保留），直接用。
            binding.errorText.visibility = View.GONE
            performAutoLogin()
            return
        }
        // 内存里没了（Activity 重建过 / 上次被清）：从加密存储重新取一次。
        binding.errorText.visibility = View.GONE
        binding.autoLoginHint.visibility = View.VISIBLE
        binding.autoLoginHint.text = getString(R.string.auto_login_in_progress)
        binding.retryAutoLoginButton.visibility = View.GONE
        setBusy(true)
        LoginFlow.decideOnStart(this) { decision ->
            if (isFinishing || isDestroyed) return@decideOnStart
            setBusy(false)
            binding.autoLoginHint.visibility = View.GONE
            if (decision is LoginFlow.Decision.ShowLoginPage && decision.password.isNotEmpty()) {
                pendingAutoLoginUsername = decision.username
                pendingAutoLoginPassword = decision.password
                pendingAutoLoginHost = ApiClient.parseHost(decision.host)
                performAutoLogin()
            } else {
                // 密码已经不在了（上一次失败是 401/403）⇒ 只能手动登录。
                showError(getString(R.string.auto_login_retry_unavailable))
                binding.retryAutoLoginButton.visibility = View.GONE
            }
        }
    }

    /**
     * 自动重登成功后的跳转。
     *
     * 与 [openWebView]（手动登录路径）的分工：
     *   · 手动登录：result 就在手上（含 cookie）→ cookie 走 Intent 交给 WebViewActivity 种；
     *   · 自动重登：cookie 由 LoginFlow 在 onSuccess 之前**已经种进 CookieManager 并 flush**
     *     （见 LoginFlow.seedSessionCookie），所以这里用 resume 模式，
     *     WebViewActivity 不会再种一次（避免 Max-Age 被重置）。
     *
     * 这样做的好处：cookie 值不需要再从存储读出来、再穿过 Intent ——
     * 凭据在进程里流动的路径越短越好。
     */
    private fun openWebViewWithLaunchUrl(launchUrl: String, hostBaseUrl: String) {
        Updates.checkAfterLogin(this)
        enterConfiguredMode(WebViewActivity::class.java, launchUrl, hostBaseUrl)
        finish()
    }

    /**
     * 清掉内存副本。
     *
     * 为什么这么在意：密码在 JVM 堆里是不可擦除的（String 不可变、GC 会搬移），
     * 我们能做的是**尽快丢掉引用**，缩短它可被堆转储/调试器读到的窗口。
     */
    private fun clearPendingCredentials() {
        pendingAutoLoginHost = null
        pendingAutoLoginUsername = ""
        pendingAutoLoginPassword = ""
    }

    // ==========================================================================
    // 换服务器 ⇒ 清空旧凭据
    // ==========================================================================

    /**
     * host 输入框被改动时的处理。
     *
     * ===== 为什么必须清凭据 =====
     * 用户把地址从 A 改成 B，若不清凭据，下一步会是：
     *   ① 自动重登仍拿「A 的密码」去试「B」——失败，然后按 401 清掉密码；
     *   ② 更糟：若 B 上恰好有同名账号且密码相同，会把 A 的密码写给 B 的账号，
     *      并且**用 A 的身份登进了 B**（或者在 B 上留下 A 的凭据）。
     * 两种结果都不可接受，所以改地址 = 换服务器 = 清空账号记忆。
     *
     * ===== 为什么不是"每敲一个字符就清" =====
     * 那样用户输入 https://... 的过程中会不断清库（AES 解密 + commit 反复执行），
     * 既慢又把日志刷满。这里做的是**防抖 + 归一化比较**：
     *   - 输入还解析不出合法地址（正在打字）→ 不动；
     *   - 归一化后与存储的 host 相同 → 不动（例如补了个尾斜杠、加了 https://）；
     *   - 归一化后不同 → 清。
     *
     * 已知取舍：用户清空输入框再重敲同一个地址，中间那一刻不会清（解析返回 null）。
     * 结果等价 —— 地址没变，不清正是对的。
     */
    private fun onHostEdited(raw: String) {
        val spec = ApiClient.parseHost(raw) ?: return
        if (hostClearedFor != null && hostClearedFor == spec.baseUrl) return
        val saved = LoginFlow.peek(this) ?: return
        if (saved.host == spec.baseUrl) return

        // 换服务器：清空全部旧凭据（含密码与 cookie）。
        // 为什么连 username 一起清：那是 A 服务器的账号名，留在 B 的输入框里会误导用户。
        Log.i(TAG, "server changed, clearing stored credentials")
        AccountStore.clearAll(this)
        LoginDefaults.rememberHost(this, spec.baseUrl)
        hostClearedFor = spec.baseUrl
        suppressHostWatcher = true
        try {
            binding.usernameInput.setText("")
            binding.passwordInput.setText("")
        } finally {
            suppressHostWatcher = false
        }
        pendingAutoLoginUsername = ""
        pendingAutoLoginPassword = ""
        binding.retryAutoLoginButton.visibility = View.GONE
        binding.errorText.visibility = View.GONE
    }

    /** 防止 TextWatcher 自触发（setText 也会触发它）。 */
    private var suppressHostWatcher = false

    /** 已经因为"换服务器"清过的那一个 host，避免重复清。 */
    private var hostClearedFor: String? = null

    // ==========================================================================
    // 手动登录
    // ==========================================================================

    /** 是否已经完成过一次 onResume：用来区分"首次进入"与"从别处返回"。 */
    private var resumedOnce = false

    /**
     * 从 WebView 返回时的处理。
     *
     * 与 v0.2.0 的差别：现在存储里有账号，所以返回登录页时必须
     * **预填 host + username、清空密码**（用户明确要求"密码留空"）。
     */
    override fun onResume() {
        super.onResume()
        if (resumedOnce) {
            // 从 WebView 回来：密码框清掉（手机上最常见的事故是"人离开屏幕后密码还在框里"）。
            binding.passwordInput.text?.clear()
            setBusy(false)
            // 重填 host / username（WebView 里可能刚退出登录：v0.4.0 起 clearSession
            // 保留 host / username，所以这里能正常预填；若整库失效则回退到
            // LoginDefaults 里"上次用过的地址"——那个库刻意不被清除，
            // 否则用户每次退出都要重新回忆域名）。
            val account = LoginFlow.peek(this)
            if (account != null) {
                applyPrefillIfEmpty(account.host, account.username)
            } else {
                val last = LoginDefaults.lastHost(this)
                if (last != null) applyPrefillIfEmpty(last, "")
            }
            // 自动重登的失败提示已经过时了（用户可能已在别处登录成功）：
            // 只清掉提示文字，不动用户正在输入的密码。
            binding.autoLoginHint.visibility = View.GONE
        }
        resumedOnce = true
    }

    /**
     * 只填"用户没动过的框"。
     *
     * 为什么不能无条件覆盖：用户可能正在改地址（从 WebView 回来后又想换服务器），
     * 覆盖回去会让人抓狂。判据是"框里现在是空的"。
     */
    private fun applyPrefillIfEmpty(host: String, username: String) {
        suppressHostWatcher = true
        try {
            if (binding.hostInput.text.isNullOrEmpty()) binding.hostInput.setText(host)
            if (binding.usernameInput.text.isNullOrEmpty()) binding.usernameInput.setText(username)
        } finally {
            suppressHostWatcher = false
        }
    }

    /**
     * 「清除已保存的账号」确认（v0.4.0）。
     *
     * 为什么必须二次确认：它清的是 host + username + password + cookie，
     * 而且**没有撤销**。与「退出登录」的区别要在文案里说清（见 strings.xml），
     * 否则用户会以为两个按钮等价，误点后重敲三样东西。
     */
    private fun confirmClearAccount() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_account_title)
            .setMessage(R.string.clear_account_message)
            .setNegativeButton(R.string.logout_action_cancel, null)
            .setPositiveButton(R.string.clear_account_action) { _, _ -> performClearAccount() }
            .show()
    }

    /**
     * 真正清除账号：加密存储整份清（含 Keystore 之外的字段）+ 输入框清空 + 内存副本清空。
     *
     * 在后台线程写：AES + commit 是阻塞 IO，而这个动作对用户是"点一下就没"的，
     * 不该让他看到主线程卡顿。清完之后把输入框恢复成"首次安装"的样子。
     */
    private fun performClearAccount() {
        // 内存里可能还留着自动重登用的那份（用户点了清除，必须立刻作废）。
        clearPendingCredentials()
        val appContext = applicationContext
        Thread({
            AccountStore.clearAll(appContext)
        }, "dsh-clear-account").apply { isDaemon = true }.start()

        suppressHostWatcher = true
        try {
            binding.hostInput.setText(getString(R.string.default_host))
            binding.usernameInput.setText("")
            binding.passwordInput.setText("")
        } finally {
            suppressHostWatcher = false
        }
        binding.errorText.visibility = View.GONE
        binding.autoLoginHint.visibility = View.GONE
        binding.retryAutoLoginButton.visibility = View.GONE
        Toast.makeText(this, R.string.clear_account_done, Toast.LENGTH_SHORT).show()
        Log.i(TAG, "saved account cleared")
    }

    /** 校验输入并发起登录。所有提前 return 的分支都在这里给出可见反馈，不留"点了没反应"。 */
    private fun submit() {
        if (busy) return
        binding.errorText.visibility = View.GONE
        binding.retryAutoLoginButton.visibility = View.GONE
        clearPendingCredentials()

        val host = ApiClient.parseHost(binding.hostInput.text?.toString().orEmpty())
        if (host == null) {
            showError(getString(R.string.error_host_invalid))
            return
        }
        val username = binding.usernameInput.text?.toString()?.trim().orEmpty()
        if (username.isEmpty()) {
            showError(getString(R.string.error_username_empty))
            return
        }
        val password = binding.passwordInput.text?.toString().orEmpty()
        if (password.isEmpty()) {
            showError(getString(R.string.error_password_empty))
            return
        }

        // ===== 换服务器保护（第二道闸）=====
        // host 输入框的 TextWatcher 已经处理了绝大多数情况，但有两种它兜不住：
        //   ① 用户粘贴一个地址后立刻点登录（editText 的 afterTextChanged 还没跑到比较）；
        //   ② Activity 重建后 hostClearedFor 清空，而存储里还是旧服务器的凭据。
        // 这里在发请求前再比一次：地址不同 ⇒ 先清旧凭据，再登。
        val saved = LoginFlow.peek(this)
        if (saved != null && saved.host != host.baseUrl) {
            Log.i(TAG, "server changed at submit, clearing stored credentials")
            AccountStore.clearAll(this)
        }

        setBusy(true)
        ApiClient.login(host, username, password) { result, error ->
            // ApiClient 保证回调在主线程，这里可以直接操作 View。
            // 必须防"回调晚于 Activity 销毁"：用户点登录后立刻按返回（或旋转屏幕），
            // 请求仍在飞行中；此时操作 binding 会抛 IllegalStateException/NPE。
            if (isFinishing || isDestroyed) return@login
            setBusy(false)
            if (result != null) {
                // ===== 首次登录成功：存 host/username/password/cookie =====
                // 写入在后台线程（commit 是阻塞 IO）；写入结果不阻塞跳转 ——
                // "记不住"最多让用户下次重登，不该拦住本次使用。
                persistAfterManualLogin(host.baseUrl, username, password, result)
                openWebView(result, host.baseUrl)
            } else if (error != null) {
                showError(messageFor(error), error.debugText())
            }
        }
    }

    /**
     * 手动登录成功 → 落存储。
     *
     * 为什么在这里、而不是在 LoginFlow 里做：
     *   密码只有本方法栈里有（用户刚输入的）；LoginFlow 的 auto-login 路径
     *   是"从存储读出来的密码再写回去"，它有自己的落盘逻辑。
     *   两条路径共用 [AccountStore.saveAfterLogin]，保证只有一份写入实现。
     */
    private fun persistAfterManualLogin(
        hostBaseUrl: String,
        username: String,
        password: String,
        result: LoginResult,
    ) {
        val appContext = applicationContext
        Thread({
            try {
                AccountStore.saveAfterLogin(
                    context = appContext,
                    host = hostBaseUrl,
                    username = result.username.ifEmpty { username },
                    password = password,
                    cookieName = result.cookieName,
                    cookieValue = result.cookieValue,
                    cookieMaxAgeSeconds = result.cookieMaxAgeSeconds,
                )
                LoginDefaults.rememberHost(appContext, hostBaseUrl)
            } catch (e: Exception) {
                Log.w(TAG, "persist after login failed (" + e.javaClass.simpleName + ")")
            }
        }, "dsh-persist-login").apply { isDaemon = true }.start()
    }

    /**
     * 把登录结果交给 WebView 容器（手动登录路径）。
     *
     * cookie 通过 Intent 传，但**只在同一进程的可信组件之间**
     * （WebViewActivity exported=false，外部应用无法拉起）。
     */
    private fun openWebView(result: LoginResult, hostBaseUrl: String) {
        // ===== 检查更新 =====
        // 时机：登录成功之后、跳 WebView 之前。它是**唯一保证有 cookie 的位置**。
        // v0.4.4：改调 checkAfterLogin —— 它会先把本进程的自动检查名额清空再查。
        // 原因见 Updates.checkAfterLogin 的注释（冷启动 401 会顺手清 cookie，
        // 可能与这次写入交错，只有"登录成功这一刻"能确认 cookie 真的有效）。
        // v0.3.0 起会话上下文由 Updates 从 AccountStore 现读（上面刚异步写入，
        // 多数情况下已就绪；若还没写完，本次检查会静默跳过 —— 由 WebView
        // 首次 onPageFinished 的那次补上）。
        Updates.checkAfterLogin(this)

        // ==================== v0.5.0：先决定进哪个模式 ====================
        // 三种可能：
        //   ① 从没选过（getUiMode = null）→ 进模式选择页。**这是唯一会看到选择页的路径**，
        //      且只在前两处（resume / 自动重登）与这里之一触发一次。
        //   ② 选过兼容模式 → 进 CompatActivity（原生界面）
        //   ③ 选过完整模式 → 进 WebViewActivity（与旧行为完全一致）
        //
        // ⚠️ 登录响应里的 cookie 三件套**必须在这里种进 CookieManager**，不能留给目标界面：
        //    WebViewActivity 收到三件套会自己种；但如果进的是 CompatActivity（原生界面），
        //    它不碰 CookieManager，cookie 就丢了 —— 下次启动判定为"未登录"，
        //    用户会莫名其妙被踢回登录页。所以先种、再分发。
        // 把 cookie 种进 CookieManager（origin 只到 authority，不显式写 domain，必须 flush）。
        // ⚠️ 为什么必须在这里种、不能留给目标界面：
        //    完整模式（WebViewActivity）收到三件套会自己种，但兼容模式（CompatActivity）
        //    根本不碰 CookieManager —— cookie 丢了下次启动就判定"未登录"，用户被莫名踢回登录页。
        //    先种、再分发，两种模式都不会丢。
        try {
            val origin = android.net.Uri.parse(result.launchUrl)
            val authority = origin.scheme + "://" + origin.authority
            val cookie = result.cookieName + "=" + result.cookieValue +
                "; Path=/; Max-Age=" + result.cookieMaxAgeSeconds + "; SameSite=Lax"
            android.webkit.CookieManager.getInstance().setCookie(authority, cookie)
            android.webkit.CookieManager.getInstance().flush()
        } catch (e: Exception) {
            // 种失败不阻断：launchUrl 那次导航本身会铸出官方 cookie；
            // 插件 cookie 缺失表现为"页面能开、插件功能 401"，用户可点重新登录。
            android.util.Log.w("LoginActivity", "seed cookie failed (" + e.javaClass.simpleName + ")")
        }
        routeToConfiguredMode(result.launchUrl, hostBaseUrl, finishSelf = false)
        // 不在登录页 finish()：用户从 WebView 返回时应当能回到这里重试。
        // v0.3.0 保留此行为 —— 「退出登录」在 WebView 里，回来后本页会重填账号。
    }

    // ==========================================================================
    // UI 辅助
    // ==========================================================================

    private fun isLightBackground(): Boolean {
        val nightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return nightMode != android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun messageFor(error: LoginError): String = when (error) {
        is LoginError.Network -> getString(R.string.error_network)
        is LoginError.Timeout -> getString(R.string.error_timeout)
        is LoginError.InvalidCredentials -> getString(R.string.error_401)
        is LoginError.TooManyAttempts -> getString(R.string.error_429)
        is LoginError.ServiceUnavailable -> getString(R.string.error_503)
        is LoginError.EndpointNotFound -> getString(R.string.error_404)
        is LoginError.BadResponse -> getString(R.string.error_bad_response)
        is LoginError.InvalidInput -> getString(R.string.error_host_invalid)
        is LoginError.Http -> getString(R.string.error_server, error.code)
    }

    /** 错误区 = 一句人话 + 一行原始描述。原始描述是排查的唯一线索，不要删。 */
    private fun showError(message: String, detail: String? = null) {
        val text = if (detail.isNullOrEmpty()) message else message + "\n" + detail
        binding.errorText.text = text
        binding.errorText.visibility = View.VISIBLE
    }

    private var busy = false

    /** 统一"忙碌"态：按钮禁用 + 文案切换 + 转圈。避免连点产生多次登录（服务端按 IP 计数，会很快触发 429）。 */
    private fun setBusy(b: Boolean) {
        busy = b
        binding.loginButton.isEnabled = !b
        binding.loginButton.text = getString(
            if (b) R.string.action_login_in_progress else R.string.action_login,
        )
        binding.loginProgress.visibility = if (b) View.VISIBLE else View.GONE
        binding.hostInput.isEnabled = !b
        binding.usernameInput.isEnabled = !b
        binding.passwordInput.isEnabled = !b
    }

    override fun onDestroy() {
        clearPendingCredentials()
        super.onDestroy()
    }
}
