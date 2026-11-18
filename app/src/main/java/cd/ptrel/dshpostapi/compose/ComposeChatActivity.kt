package cd.ptrel.dshpostapi.compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import cd.ptrel.dshpostapi.ui.theme.SimpleChatTheme
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import cd.ptrel.dshpostapi.CompatApi

/**
 * Compose 聊天页宿主（v0.9.0）。
 *
 * ## 为什么用 ComponentActivity 而不是 AppCompatActivity
 *
 * Compose 只需要 ComponentActivity（setContent 来自 activity-compose）。
 * AppCompatActivity 是给 View 体系 + AppCompat 主题用的；
 * 混用会让 Material3 主题与 AppCompat 主题争夺同一个窗口。
 *
 * ## 凭据从哪来
 *
 * 与旧页面一致：每次从 CompatApi.fromStore() 现读，不缓存字段。
 * 拿不到（未登录 / 已过期）时直接 finish —— 入口方（会话列表）负责跳登录页，
 * 不该由这里决定导航。
 */
class ComposeChatActivity : ComponentActivity() {

    /**
     * 当前 ViewModel 引用（v0.10.1）。
     *
     * 为什么需要提到字段：onResume 里要触发 reload()，而它是 Compose 树里
     * 通过 viewModel() 拿到的局部变量。提到字段是拿到它的最小改动 ——
     * 另一条路是把 onResume 的逻辑也搬进 Compose（用 LifecycleEventObserver），
     * 但那要多一个组合项，且与 Activity 生命周期混在一起更难读。
     */
    private var chatViewModel: ChatScreenViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val api = CompatApi.fromStore(this)
        if (api == null) {
            // 凭据不可用：交给上层处理（会话列表会跳登录页）。
            finish()
            return
        }

        // v0.9.1：没指定会话时，回退到「上次活跃会话」（ISSUE-01 P1）。
        //
        // 为什么要有这个回退：从桌面图标直接打开 App（不是从会话列表进来）时，
        // intent 里没有 sessionId。早先这下会当成新会话 ⇒ 每次开 App 都新建一条
        // 游离会话（实测 20261116 一天产生 7 条）。
        // 用户对「打开 App」的预期是接着上次聊，不是又开一段。
        // 标题由会话列表传入（v0.10.3）：它有现成的值，这一页不该再请求一次。
        val sessionTitle = intent.getStringExtra(EXTRA_SESSION_TITLE).orEmpty()
        // 新建会话时选好的工作区（v0.11.0）。有值就发给服务端，它按白名单校验。
        val workspaceCwd = intent.getStringExtra(EXTRA_CWD).orEmpty()
        val explicitNew = intent.getBooleanExtra(EXTRA_NEW_SESSION, false)
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
            // 明确新建时**不**回退到上次会话（见 EXTRA_NEW_SESSION 的说明）。
            ?: if (explicitNew) null else cd.ptrel.dshpostapi.AppPrefs.getLastSessionId(this)
        val streamClient = DshStreamClient(okHttpOf(api))

        // v0.20.0：设置弹层里的「背景图」。
        val pickBackground = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.GetContent(),
        ) { uri ->
            if (uri == null) return@registerForActivityResult
            java.util.concurrent.Executors.newSingleThreadExecutor().execute {
                val saved = saveBackgroundImage(applicationContext, uri)
                runOnUiThread {
                    if (saved != null) {
                        // 重建当前页让新背景生效（BackgroudPath 在 setContent 里读取）。
                        recreate()
                    } else {
                        android.widget.Toast.makeText(
                            this@ComposeChatActivity,
                            "背景图保存失败",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }

        setContent {
            // getValue/setValue 来自 runtime 的委托扩展，必须显式 import（或在同一包内可见）
            val logoutState = androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(false)
            }
            var showLogoutConfirm = logoutState.value
            val setShowLogoutConfirm: (Boolean) -> Unit = { logoutState.value = it }
            // v0.10.1：改用移植来的 SimpleChatTheme（默认 ThemeMode.SYSTEM = 跟随系统）。
            // 早先是裸 MaterialTheme{} —— 它不带 colorScheme，等于永远用浅色默认值，
            // 因此系统开深色时界面仍是白底。这是个很容易漏的点：
            // MaterialTheme() 无参调用是**合法**的，不报错，只是不跟随系统。
            SimpleChatTheme {
                // 用 viewModel() 拿实例：它随 Activity 的 ViewModelStore 走，
                // 转屏/进程重建时状态不丢（旧 XML 版是自己 new 的，转屏就丢消息）。
                val vm: ChatScreenViewModel = viewModel(
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            return ChatScreenViewModel(api, streamClient, applicationContext, workspaceCwd) as T
                        }
                    },
                )
                chatViewModel = vm
                androidx.compose.runtime.LaunchedEffect(sessionId) {
                    if (!sessionId.isNullOrEmpty()) vm.openSession(sessionId)
                }
                // 会把 ChatScreen 里的 AppBackground 整个盖住（实测：
                // 会话列表有背景、聊天页没有，就是这个原因）。
                // v0.20.0：退出登录确认。与列表页同一套文案与危险色。
                if (showLogoutConfirm) {
                    cd.ptrel.dshpostapi.ui.common.AppConfirmDialog(
                        title = cd.ptrel.dshpostapi.data.Res.get(cd.ptrel.dshpostapi.R.string.compat_menu_logout),
                        message = cd.ptrel.dshpostapi.data.Res.get(cd.ptrel.dshpostapi.R.string.compat_menu_logout_confirm),
                        confirmText = cd.ptrel.dshpostapi.data.Res.get(cd.ptrel.dshpostapi.R.string.compat_menu_logout),
                        destructive = true,
                        onDismiss = { setShowLogoutConfirm(false) },
                        onConfirm = {
                            setShowLogoutConfirm(false)
                            // 清 cookie 但保留账号密码（与列表页行为一致）
                            cd.ptrel.dshpostapi.AccountStore.clearSession(this@ComposeChatActivity)
                            finish()
                            startActivity(
                                android.content.Intent(
                                    this@ComposeChatActivity,
                                    cd.ptrel.dshpostapi.LoginActivity::class.java,
                                ).putExtra("auto_login", false),
                            )
                        },
                    )
                }

                Scaffold(containerColor = androidx.compose.ui.graphics.Color.Transparent) { padding ->
                    ChatScreen(
                        viewModel = vm,
                        title = sessionTitle,
                        // 与会话列表共享同一个背景设置（v0.12.0）。
                        backgroundPath = cd.ptrel.dshpostapi.AppPrefs.getBackgroundPath(this@ComposeChatActivity),
                        onBack = { finish() },

                        // v0.20.0：设置弹层里的外观/账号入口。
                        // 之前这些只在会话列表的 ⋮ 菜单里，聊天时想改背景得先返回首页。
                        onPickBackground = { pickBackground.launch("image/*") },
                        onCheckUpdate = { cd.ptrel.dshpostapi.Updates.checkManually(this@ComposeChatActivity) },
                        onSwitchMode = {
                            // 与列表页一致：切到全量模式后需要重新走一遍入口
                            finish()
                            startActivity(
                                android.content.Intent(
                                    this@ComposeChatActivity,
                                    ComposeSessionsActivity::class.java,
                                ),
                            )
                        },
                        onLogout = { setShowLogoutConfirm(true) },

                        // v0.13.0：外部提问的回答提交。
                        onAnswerQuestion = { callId, answers -> vm.answerQuestion(callId, answers) },
                        modifier = Modifier.padding(padding).fillMaxSize(),
                    )
                }
            }
        }
    }

    /**
     * 新建会话（v0.10.3）：清掉 sessionId 后重建本 Activity。
     *
     * 为什么不 finish() 再 start 一个新的：那会闪一帧白屏，且返回栈里
     * 会短暂出现两个聊天页。recreate 是同一次转场，视觉上更接近
     * 「原地清空，开始新对话」。
     *
     * 为什么用 intent 而不是 ViewModel 的 reset()：Activity 重建后
     * ViewModelStore 会保留旧实例（那是转屏复用的设计），
     * 显式换 intent + recreate 才能保证拿到干净的 ViewModel。
     */
    private fun recreateWithNewSession() {
        intent.removeExtra(EXTRA_SESSION_ID)
        intent.removeExtra(EXTRA_SESSION_TITLE)
        // 让 ViewModel 跟着这次重建一起销毁：不设这个 flag 的话，
        // Activity 重建会复用同一个 ViewModelStore，旧消息还在。
        intent.putExtra("recreate_for_new_session", true)
        recreate()
    }

    /**
     * 回到前台时刷新一次历史（v0.10.1）。
     *
     * 为什么需要：早先只在 openSession 时加载一次。用户在别的设备或网页端
     * 聊过之后回到 App，看不到新内容 —— 必须退出聊天页再进才刷新，
     * 体感是「消息不更新」。
     *
     * 不做节流：history 读的是本地会话日志（不是网络请求），很轻；
     * onResume 也不会高频触发。ViewModel 内部的 reload 还会比对内容，
     * 没变化就不重绘。
     */
    override fun onResume() {
        super.onResume()
        chatViewModel?.reload()
    }

    /**
     * 给 [DshStreamClient] 准备一个 OkHttpClient。
     *
     * 为什么不复用 CompatApi 内部那个：
     *   CompatApi 不暴露 client（也不该暴露 —— 它管着 UA 与超时策略），
     *   而且 SSE 需要 readTimeout=0，那会污染普通请求
     *   （读历史时永远不超时 = UI 卡死）。
     *
     * 这里新建一个**默认配置**的就够：
     *   Cookie / UA / Accept 全部已经在 [CompatApi.streamRequest] 构造的 Request 里，
     *   不依赖 client 层再补任何东西。DshStreamClient 自己会 newBuilder 出
     *   带 readTimeout(0) 的副本，所以这里不必预先调超时。
     */
    private fun okHttpOf(@Suppress("UNUSED_PARAMETER") api: CompatApi): okhttp3.OkHttpClient =
        okhttp3.OkHttpClient.Builder().build()

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_SESSION_TITLE = "session_title"
        const val EXTRA_CWD = "cwd"
        /**
         * 明确表示「这是一个新会话」（v0.11.0）。
         *
         * 为什么需要单独的标记：sessionId 为空有两种含义 ——
         *   ① 从桌面图标启动 → 应该回退到上次会话（用户预期是「接着聊」）；
         *   ② 用户主动点了「新建会话」→ 必须**开新的**，不能回退。
         * 只看 sessionId 无法区分，实测导致「选完工作区却进了旧会话」。
         */
        const val EXTRA_NEW_SESSION = "new_session"
    }
}
