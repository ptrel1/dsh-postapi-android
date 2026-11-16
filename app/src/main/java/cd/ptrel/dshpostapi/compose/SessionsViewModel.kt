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
        startAutoRefresh()
    }

    /**
     * 自动刷新（v0.10.2）。
     *
     * 为什么需要：早先只在进页面和 onResume 时拉一次。用户看着列表，
     * 另一个会话在后台跑完了 / 有新消息了，列表**完全不会变** ——
     * 必须退出重进才看得到。读屏用户尤其明显：他不知道有东西变了。
     *
     * 间隔改成 15 秒（v0.10.3）：
     *   · 实测服务端 0.12~0.46s / 46KB per call（334 个会话）；
     *   · 5 秒那份开销对「看别的会话跑完」这个需求**过于敏感** ——
     *     用户感知不到 5 秒与 15 秒的差别，但请求量差 3 倍；
     *   · 多设备同时开着时是线性叠加，间隔越短越容易把机器拖住。
     * 真需要即时感的话，正确做法是长连接推送，而不是缩短轮询周期。
     *
     * 用 viewModelScope：ViewModel 销毁（离开页面）时自动取消，不用手写清理。
     */
    private fun startAutoRefresh() {
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(15_000)
                // 静默刷新：不置 loading（那会让界面闪一下加载圈），
                // 不动 error（偶发失败不该把已有列表打成错误态）。
                load(silent = true)
            }
        }
    }

    /**
     * 拉取会话列表。
     *
     * ⚠️ 必须 withContext(IO)：CompatApi 全是阻塞的 OkHttp execute()，
     * viewModelScope 默认在 Main 上跑，直接调会抛 NetworkOnMainThreadException
     * （聊天页踩过同一个坑，见 ChatScreenViewModel.openSession）。
     */
    fun load(silent: Boolean = false) {
        // silent 不置 loading：轮询时让界面闪一下加载圈是最刺眼的那种抖动。
        if (!silent) _state.update { it.copy(loading = it.sessions.isEmpty()) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { api.listSessions() }
            when (result) {
                is CompatApi.Result.Ok -> _state.update {
                    // v0.10.3：内容没变就**不派发新值**。
                    //
                    // 5 秒轮询每次都会拿到 334 条全新对象（JSON 反序列化出来的），
                    // 直接 copy(sessions = new) 会让 StateFlow 派发新引用 ⇒
                    // LazyColumn 认为所有 item 都变了 ⇒ 全量重组。
                    // 实测低端机上是明显掉帧，而绝大部分轮询的结果和上一次一模一样。
                    //
                    // 判据用「条数 + 每条的 id/标题/时间/运行态」的等价性：
                    // 这些都是会变且影响显示的字段；cwd 不会变，不必比。
                    val incoming = result.value
                    val same = it.sessions.size == incoming.size &&
                        it.sessions.indices.all { i ->
                            val a = it.sessions[i]
                            val b = incoming[i]
                            a.sessionId == b.sessionId &&
                                a.title == b.title &&
                                a.updatedAt == b.updatedAt &&
                                a.running == b.running
                        }
                    if (same) it.copy(loading = false, error = null)
                    else it.copy(sessions = incoming, loading = false, error = null)
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
