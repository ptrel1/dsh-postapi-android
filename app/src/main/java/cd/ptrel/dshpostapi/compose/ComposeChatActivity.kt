package cd.ptrel.dshpostapi.compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val api = CompatApi.fromStore(this)
        if (api == null) {
            // 凭据不可用：交给上层处理（会话列表会跳登录页）。
            finish()
            return
        }

        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        val streamClient = DshStreamClient(okHttpOf(api))

        setContent {
            MaterialTheme {
                // 用 viewModel() 拿实例：它随 Activity 的 ViewModelStore 走，
                // 转屏/进程重建时状态不丢（旧 XML 版是自己 new 的，转屏就丢消息）。
                val vm: ChatScreenViewModel = viewModel(
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            return ChatScreenViewModel(api, streamClient) as T
                        }
                    },
                )
                androidx.compose.runtime.LaunchedEffect(sessionId) {
                    if (!sessionId.isNullOrEmpty()) vm.openSession(sessionId)
                }
                Scaffold { padding ->
                    ChatScreen(vm, Modifier.padding(padding).fillMaxSize())
                }
            }
        }
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
    }
}
