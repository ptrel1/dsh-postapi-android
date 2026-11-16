package cd.ptrel.dshpostapi.compose

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

                // ---- 自定义背景图（v0.12.0）----
                // 用 mutableStateOf 而不是直接读 AppPrefs：选择后要立刻重组出新背景，
                // 重启 App 才生效的话用户会以为没成功。
                var backgroundPath by remember { mutableStateOf(AppPrefs.getBackgroundPath(this@ComposeSessionsActivity)) }
                val scope = rememberCoroutineScope()
                val pickBackgroundImage = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.GetContent(),
                ) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) { saveBackground(uri) }
                        if (saved != null) {
                            backgroundPath = saved
                            Toast.makeText(
                                this@ComposeSessionsActivity,
                                getString(R.string.compat_background_set),
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            Toast.makeText(
                                this@ComposeSessionsActivity,
                                getString(R.string.compat_background_failed),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }

                var logoutConfirm by remember { mutableStateOf(false) }

                SessionsScreen(
                    state = state,
                    onRefresh = vm::load,
                    onRetry = vm::retry,
                    onOpenSession = { row -> openChat(row.sessionId, row.title) },
                    // v0.11.0：新建会话先选工作区（DSH 的会话必须绑定 cwd）。
                    // 早先直接 openChat(null)，服务端对非回环请求一律用 /tmp，
                    // 于是新会话没有项目上下文 —— 工具全在 /tmp 下跑，用户看不到自己的代码。
                    onNewSession = { pickWorkspaceThenChat() },
                    onSwitchMode = ::switchToFullMode,
                    onCheckUpdate = { Updates.checkManually(this@ComposeSessionsActivity) },
                    onLogout = { logoutConfirm = true },
                    backgroundPath = backgroundPath,
                    onPickBackground = { pickBackgroundImage.launch("image/*") },
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
    /**
     * 新建会话第一步：选工作区（v0.11.0）。
     *
     * 为什么必须选：DSH 的 cwd 决定 agent 在哪里执行命令、能读哪些文件。
     * 不选就等于在一个空目录里干活 —— 用户看不到自己的代码，工具也找不到项目。
     *
     * 为什么用 AlertDialog 而不是单独一个 Activity：
     *   它只是一次性选择，没有后续交互，开新页面反而多一层返回栈。
     * 列表项 = 工作区名 + 完整路径 + 会话数，与列表页的分组标题同构，
     * 用户看两处是同一套概念。
     */
    private fun pickWorkspaceThenChat() {
        // 拉列表要网络，不能在主线程 —— 与 SessionsViewModel.load 同一个坑。
        val api = CompatApi.fromStore(this) ?: run {
            Toast.makeText(this, R.string.compat_not_logged_in, Toast.LENGTH_LONG).show()
            redirectToLogin()
            return
        }
        val progress = android.app.ProgressDialog(this).apply {
            setMessage(getString(R.string.compat_workspace_loading))
            setCancelable(true)
            show()
        }
        Thread {
            val result = api.listWorkspaces()
            runOnUiThread {
                progress.dismiss()
                when (result) {
                    is CompatApi.Result.Ok -> {
                        val list = result.value
                        if (list.isEmpty()) {
                            // 没有工作区 = 从没建过会话。提示而不是给一个空对话框。
                            Toast.makeText(this, R.string.compat_workspace_empty, Toast.LENGTH_LONG).show()
                            return@runOnUiThread
                        }
                        val labels = list.map { w -> w.name + "  (" + w.sessions + ")\n" + w.path }.toTypedArray()
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle(R.string.compat_workspace_pick)
                            .setItems(labels) { _, which ->
                                openChat(null, null, list[which].path)
                            }
                            .setNegativeButton(R.string.compat_menu_cancel, null)
                            .show()
                    }
                    is CompatApi.Result.Err -> {
                        Toast.makeText(this, result.reason, Toast.LENGTH_LONG).show()
                        if (result.code == 401 || result.code == 403) redirectToLogin()
                    }
                }
            }
        }.start()
    }

    private fun openChat(sessionId: String?, title: String? = null, cwd: String? = null) {
        startActivity(
            Intent(this, ComposeChatActivity::class.java).apply {
                if (sessionId != null) putExtra(ComposeChatActivity.EXTRA_SESSION_ID, sessionId)
                // v0.11.0：新建会话带上选好的工作区；服务端会用白名单校验它。
                if (!cwd.isNullOrBlank()) putExtra(ComposeChatActivity.EXTRA_CWD, cwd)
                // v0.11.0：**明确新建**。没有这个标记时，聊天页会把
                // 「sessionId 为空」理解成「从桌面图标启动」并回退到上次会话 ——
                // 实测点了工作区却进了旧会话。
                if (sessionId == null) putExtra(ComposeChatActivity.EXTRA_NEW_SESSION, true)
                // 标题一起带过去（v0.10.4）：列表页已经有它了，
                // 聊天页再请求一次既慢又可能拿到不一致的值（列表快照 vs 服务端最新）。
                // 空标题不必传 —— 聊天页会对空串显示占位文案。
                if (!title.isNullOrBlank()) putExtra(ComposeChatActivity.EXTRA_SESSION_TITLE, title)
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
    /**
     * 把用户选的图片复制进 app 私有目录并记下路径（v0.12.0）。
     *
     * 为什么复制而不是记 Uri：
     *   ContentResolver 给的临时读权限只在当前进程/任务存活期内有效，
     *   重启 App 后 Uri 就打不开了 —— 而背景图是长期设置。
     *   复制一份到自己目录，权限问题彻底消失。
     *
     * 为什么**不**缩放原图：
     *   用户选的图可能 4000×3000，背景要铺满整屏。
     *   但 Compose 的 Image + ContentScale.Crop 会按控件尺寸解码采样，
     *   内存不会按原图大小爆掉；而缩放会损失画质且要处理 EXIF 旋转。
     *   实测一张 3MB 的手机照片作为背景正常。
     *
     * @returns 保存后的绝对路径；失败返回 null（调用方提示用户换一张）。
     */
    private fun saveBackground(uri: android.net.Uri): String? {
        return try {
            val dir = java.io.File(filesDir, "background")
            if (!dir.exists() && !dir.mkdirs()) return null
            // ⚠️ 用**带时间戳的新文件名**，而不是覆盖固定的 bg.img（v0.13.1 修复）。
            //
            // 覆盖同一路径会踩两个坑：
            //   ① Compose 的 remember(path) 只认字符串，path 不变就不重新解码
            //      → 用户换图后界面还是上一张（这是用户实际反馈的 bug）
            //   ② 就算改成看 mtime，同尺寸图片在同一秒内替换时 mtime 可能不变
            //      → 仍然不刷新
            //
            // 换成新文件名后 path 本身就变了，两个坑一起消失。
            // 旧文件在写入成功后删掉，私有目录里始终只有一张图。
            val target = java.io.File(dir, "bg-" + System.currentTimeMillis() + ".img")
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            // 写成功了才清理旧图：万一新图写失败，旧的还能用。
            try {
                val previous = AppPrefs.getBackgroundPath(this)
                if (!previous.isNullOrBlank() && previous != target.absolutePath) {
                    val old = java.io.File(previous)
                    // 只删自己目录下的文件，避免误删用户别处的东西
                    if (old.parentFile?.absolutePath == dir.absolutePath) old.delete()
                }
            } catch (_: Exception) {}
            // 写进 Prefs 供下次启动直接读；本进程内由 Compose state 接管。
            AppPrefs.setBackgroundPath(this, target.absolutePath)
            target.absolutePath
        } catch (e: Exception) {
            android.util.Log.w("ComposeSessions", "saveBackground failed: " + e.javaClass.simpleName)
            null
        }
    }

    private fun redirectToLogin() {
        startActivity(
            Intent(this, LoginActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            },
        )
        finish()
    }
}
