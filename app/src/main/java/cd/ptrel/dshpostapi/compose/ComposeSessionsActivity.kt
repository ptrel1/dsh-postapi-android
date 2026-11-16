package cd.ptrel.dshpostapi.compose

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cd.ptrel.dshpostapi.AccountStore
import cd.ptrel.dshpostapi.AppPrefs
import cd.ptrel.dshpostapi.CompatApi
import cd.ptrel.dshpostapi.LoginActivity
import cd.ptrel.dshpostapi.R
import cd.ptrel.dshpostapi.Updates
import cd.ptrel.dshpostapi.WebViewActivity
import cd.ptrel.dshpostapi.data.Res
import cd.ptrel.dshpostapi.ui.common.AppConfirmDialog
import cd.ptrel.dshpostapi.ui.theme.SimpleChatTheme
import kotlinx.coroutines.delay

/**
 * 兼容模式的会话列表（v0.11.0，Compose 重写）。
 *
 * ## 这一版做了什么、没做什么
 *
 * 把界面从 XML + RecyclerView 换成 Compose（视觉与聊天页统一），
 * **业务逻辑原样保留**，且都留在 Activity 这一层：
 *   · 刷新 / 新建会话 / 点进会话 —— 交给 SessionsViewModel 与 Composable
 *   · 切换使用方式 / 检查更新 / 退出登录 / 凭据失效跳登录页 —— 仍在这里
 *
 * 为什么导航与账号逻辑不搬进 ViewModel：
 *   它们要 startActivity、要弹确认框、要碰 AccountStore ——
 *   全是 Activity 上下文的事。搬进 ViewModel 只是把 Activity 引用藏起来，
 *   换不来任何可测性（这些动作本来就无法脱离真实环境验证）。
 *
 * ## 旧版去哪了
 *
 * 旧的 XML 版 CompatSessionsActivity 仍保留在仓库里（manifest 里也仍在），
 * 它是这块功能在真机验证前的回退点。
 */
class ComposeSessionsActivity : ComponentActivity() {

    /** 列表的重新加载入口（Composable 里的 vm 拿不到时由它兜底）。 */
    private var reload: (() -> Unit)? = null

    /** 是否已经历过第一次 onResume —— 挡掉紧随 onCreate 的重复加载。 */
    private var hasResumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val api = CompatApi.fromStore(this)
        if (api == null) {
            // 凭据不可用（没有 / 已过期）：先给一个看得见的提示，再清栈回登录页。
            Toast.makeText(this, R.string.compat_not_logged_in, Toast.LENGTH_LONG).show()
            redirectToLogin()
            return
        }

        setContent {
            // 与聊天页同一个主题：默认 ThemeMode.SYSTEM = 跟随系统深色。
            SimpleChatTheme {
                val vm: SessionsViewModel = viewModel(
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            return SessionsViewModel(api) as T
                        }
                    },
                )
                val state by vm.state.collectAsStateWithLifecycle()
                val reloginTick by vm.reloginTick.collectAsStateWithLifecycle()

                reload = vm::load

                // 凭据失效 → 延迟跳登录页。
                // 延迟 1.2s 让用户**看见**那行提示，否则界面一闪就换页，
                // 用户不知道发生了什么（与旧版 redirectToLogin 的延迟一致）。
                LaunchedEffect(reloginTick) {
                    if (reloginTick <= 0L) return@LaunchedEffect
                    delay(1200L)
                    redirectToLogin()
                }

                var logoutConfirm by remember { mutableStateOf(false) }

                SessionsScreen(
                    state = state,
                    onRefresh = vm::load,
                    onRetry = vm::retry,
                    onOpenSession = { row -> openChat(row.sessionId) },
                    onNewSession = { openChat(null) },
                    onSwitchMode = ::switchToFullMode,
                    onCheckUpdate = { Updates.checkManually(this@ComposeSessionsActivity) },
                    onLogout = { logoutConfirm = true },
                    // safeDrawing 而不是 imePadding：这一页没有输入框，
                    // 但状态栏 / 手势条必须让开，否则顶栏压在状态栏下面。
                    modifier = Modifier.padding(WindowInsets.safeDrawing.asPaddingValues()),
                )

                if (logoutConfirm) {
                    AppConfirmDialog(
                        title = Res.get(R.string.compat_menu_logout),
                        message = Res.get(R.string.compat_menu_logout_confirm),
                        confirmText = Res.get(R.string.compat_menu_logout),
                        destructive = true,
                        onDismiss = { logoutConfirm = false },
                        onConfirm = {
                            logoutConfirm = false
                            confirmLogout()
                        },
                    )
                }
            }
        }
    }

    /**
     * 从聊天页返回时刷新一次：刚聊过的会话应该出现在最上面。
     *
     * 用 hasResumed 挡掉紧随 onCreate 的第一次 onResume：
     * ViewModel 在 init 里已经拉过一次，不挡就是每次进页面打两次请求。
     */
    override fun onResume() {
        super.onResume()
        if (hasResumed) reload?.invoke()
        hasResumed = true
    }

    // ==========================================================================
    // 导航与账号（与旧 XML 版逐字对齐，只是换了调用位置）
    // ==========================================================================

    /** 进聊天页。sessionId 为 null = 新会话。 */
    private fun openChat(sessionId: String?) {
        startActivity(
            Intent(this, ComposeChatActivity::class.java).apply {
                if (sessionId != null) putExtra(ComposeChatActivity.EXTRA_SESSION_ID, sessionId)
            },
        )
    }

    /**
     * 切回完整模式。
     *
     * 与 WebViewActivity.relaunchIntoMode 做同一件事：记住模式偏好，
     * 然后进 WebViewActivity 并带 resume 标志 —— 不带它会被
     * onCreate 的 `if (!resumeMode && cookieName.isEmpty()) finish()` 挡回去。
     */
    private fun switchToFullMode() {
        AppPrefs.setUiMode(this, AppPrefs.UI_MODE_FULL)
        val account = AccountStore.load(this)
        val host = account?.host.orEmpty()
        if (host.isEmpty()) {
            // 凭据丢了：与其带着空地址启动再被 finish 掉，不如回登录页。
            redirectToLogin()
            return
        }
        startActivity(
            Intent(this, WebViewActivity::class.java).apply {
                putExtra(WebViewActivity.EXTRA_LAUNCH_URL, host)
                putExtra(WebViewActivity.EXTRA_RESUME_MODE, true)
                putExtra(WebViewActivity.EXTRA_HOST, host)
            },
        )
        finish()
    }

    /** 退出登录：清凭据 → 回登录页。 */
    private fun confirmLogout() {
        AccountStore.clearAll(this)
        AppPrefs.setLastSessionId(this, null)
        redirectToLogin()
    }

    /**
     * 回登录页重新认证。
     *
     * 目标用 LoginActivity 而不是 ModeSelect 之类的分发器：
     * 后者会再次读 UI 模式并可能又绕回本页，形成循环。
     * 清栈（NEW_TASK + CLEAR_TASK）：重新登录后不该还能按返回键回到失效的界面。
     */
    private fun redirectToLogin() {
        startActivity(
            Intent(this, LoginActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            },
        )
        finish()
    }
}
