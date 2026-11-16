package cd.ptrel.dshpostapi.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cd.ptrel.dshpostapi.CompatApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 会话列表页的状态与调度（v0.11.0）。
 *
 * ## 与旧 XML 版（CompatSessionsActivity + ListAdapter）的分工
 *
 * 旧的那套自己开了单线程池 + runOnUiThread 手动同步 UI。
 * 这里用 StateFlow + 不可变快照，UI 只负责渲染 —— 与聊天页
 * [ChatScreenViewModel] 是同一套路子，两页行为一致。
 *
 * ## 凭据失效为什么用「事件」而不是「状态」
 *
 * 跳登录页是一次性动作，不能挂在 UiState 里当普通字段：
 * 旋转屏幕 / 重组时 Composable 会重新读一遍状态，
 * 「状态里还是未登录」会让它再跳一次（旧 XML 版就是靠 redirecting 标志位挡的）。
 * 用 StateFlow<Long?> 承载「第几次要求跳转」，UI 侧 LaunchedEffect 只对
 * 新的值反应一次，天然幂等。
 */
class SessionsViewModel(
    private val api: CompatApi,
) : ViewModel() {

    data class UiState(
        val sessions: List<CompatApi.SessionRow> = emptyList(),
        /** 首屏是否还没回来 —— 决定显示加载圈还是空态。 */
        val loading: Boolean = true,
        /** 错误提示（顶部一行红字），null = 无错误。 */
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * 凭据失效、需要回登录页的次数（每次 +1）。
     *
     * null = 从未要求跳转。UI 侧用一个 LaunchedEffect 观察它，
     * 只对「值变化」反应，所以重组不会重复跳。
     */
    private val _reloginTick = MutableStateFlow(0L)
    val reloginTick: StateFlow<Long> = _reloginTick.asStateFlow()

    init {
        // 进页面就拉一次：与旧版 onCreate 里 load() 对齐。
        load()
    }

    /**
     * 拉取会话列表。
     *
     * ⚠️ 必须 withContext(IO)：CompatApi 全是阻塞的 OkHttp execute()，
     * viewModelScope 默认在 Main 上跑，直接调会抛 NetworkOnMainThreadException
     * （聊天页踩过同一个坑，见 ChatScreenViewModel.openSession）。
     */
    fun load() {
        _state.update { it.copy(loading = it.sessions.isEmpty()) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { api.listSessions() }
            when (result) {
                is CompatApi.Result.Ok -> _state.update {
                    it.copy(
                        sessions = result.value,
                        loading = false,
                        error = null,
                    )
                }
                is CompatApi.Result.Err -> {
                    _state.update { it.copy(loading = false, error = result.reason) }
                    // 401/403 是「凭据失效」，不是普通网络错误：
                    // 用户在这一页没有任何可点的补救入口（没地址栏、没下拉刷新），
                    // 必须把他送回登录页，否则等于卡死。
                    if (result.code == 401 || result.code == 403) {
                        _reloginTick.update { it + 1 }
                    }
                }
            }
        }
    }

    /**
     * 用户在列表页手动重试（点错误行）。
     *
     * 与 [load] 相同，单独留一个方法名是为了 UI 侧语义清楚：
     * 「重试」按钮点下去应该清掉错误，而不是让它一直挂着。
     */
    fun retry() {
        _state.update { it.copy(error = null) }
        load()
    }
}
