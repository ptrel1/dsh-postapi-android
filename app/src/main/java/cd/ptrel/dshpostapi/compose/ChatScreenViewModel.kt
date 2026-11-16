package cd.ptrel.dshpostapi.compose

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cd.ptrel.dshpostapi.CompatApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 界面上的一条消息（UI 模型，与网络 DTO 分开）。 */
data class UiMessage(
    /** 稳定 id：Compose 的 LazyColumn 靠它做 key，没有稳定 key 会整列表重组。 */
    val id: String,
    val role: Role,
    val text: String = "",
    val reasoning: String = "",
    val status: Status = Status.Done,
    /** 正在用的工具名（有值且 status=Streaming 时显示「正在使用 X」）。 */
    val tool: String? = null,
) {
    enum class Role { User, Assistant }
    enum class Status { Streaming, Done, Stopped, Error }
}

/**
 * Compose 聊天页的状态与调度（v0.9.0）。
 *
 * ## 与旧 ChatViewModel 的分工
 *
 * 旧的那个（XML 版）用 Handler + ListAdapter 手动同步；这个用 StateFlow +
 * 不可变快照，UI 只负责渲染。两者并存直到旧页面下线。
 *
 * ## 为什么消息列表是不可变 List
 *
 * Compose 的重组判定靠**引用相等**。可变列表原地 add 时引用没变，
 * Compose 认为「没变」就不重组 —— 表现为界面不更新。
 * 每次更新都 copy 出新列表，是最省心的正确做法。
 */
class ChatScreenViewModel(
    private val api: CompatApi,
    private val streamClient: DshStreamClient,
) : ViewModel() {

    data class UiState(
        val messages: List<UiMessage> = emptyList(),
        val busy: Boolean = false,
        val error: String? = null,
        val sessionId: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var streamJob: Job? = null
    private var seq = 0L

    /** 生成本地 id。用自增序号而不是 UUID：可读、可测、排序天然稳定。 */
    private fun nextId(prefix: String): String = prefix + "-" + (seq++)

    /**
     * 发一条消息。
     *
     * 本地回显先行：用户点发送后立刻看到自己的话，否则会以为没发出去。
     * 这是聊天类应用的通用预期，不是可选的优化。
     */
    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.busy) return

        val userMsg = UiMessage(nextId("u"), UiMessage.Role.User, trimmed)
        val replyId = nextId("a")
        val placeholder = UiMessage(replyId, UiMessage.Role.Assistant, status = UiMessage.Status.Streaming)

        _state.update {
            it.copy(
                messages = it.messages + userMsg + placeholder,
                busy = true,
                error = null,
            )
        }

        val request = api.streamRequest(trimmed, _state.value.sessionId)
        streamJob = viewModelScope.launch {
            streamClient.stream(request).collect { event ->
                when (event) {
                    is StreamEvent.Started -> _state.update { it.copy(sessionId = event.sessionId) }
                    is StreamEvent.Content -> updateReply(replyId) { it.copy(text = it.text + event.text) }
                    is StreamEvent.Reasoning -> updateReply(replyId) { it.copy(reasoning = it.reasoning + event.text) }
                    is StreamEvent.Tool -> updateReply(replyId) {
                        // 工具名可能为空（服务端某些帧只给 id），空时保留上一个，
                        // 否则界面会闪一下「正在使用 」这种断句。
                        it.copy(tool = event.name.ifEmpty { it.tool ?: "" })
                    }
                    is StreamEvent.Done -> {
                        val status = if (event.cancelled) UiMessage.Status.Stopped else UiMessage.Status.Done
                        updateReply(replyId) { it.copy(status = status, tool = null) }
                        _state.update { it.copy(busy = false) }
                    }
                    is StreamEvent.Error -> {
                        updateReply(replyId) { it.copy(status = UiMessage.Status.Error, tool = null) }
                        _state.update { it.copy(busy = false, error = event.message) }
                    }
                }
            }
        }
    }

    /** 中断当前生成。取消 collect 会触发 callbackFlow 的 awaitClose，连接随之关闭。 */
    fun stop() {
        streamJob?.cancel()
        streamJob = null
        _state.update { st ->
            st.copy(
                busy = false,
                // 把最后一条仍在 Streaming 的回复标成已停止 ——
                // 否则那个「正在生成」的动画会一直转下去。
                messages = st.messages.map {
                    if (it.status == UiMessage.Status.Streaming) it.copy(status = UiMessage.Status.Stopped, tool = null) else it
                },
            )
        }
    }

    /** 读某个会话的历史（进入会话时调用）。 */
    fun openSession(sessionId: String) {
        _state.update { it.copy(sessionId = sessionId, messages = emptyList(), error = null) }
        // ⚠️ 必须 withContext(IO)：CompatApi.history() 内部是 OkHttp 的同步 execute()。
        //    viewModelScope 默认跑 Dispatchers.Main，直接调会抛 NetworkOnMainThreadException
        //    （20261116 实测崩溃点就在这里，栈顶是 CompatApi.exec）。
        viewModelScope.launch {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                api.history(sessionId)
            }
            when (result) {
                is CompatApi.Result.Ok -> {
                    val list = result.value.messages.map { m ->
                        UiMessage(
                            id = nextId(if (m.role == "user") "u" else "a"),
                            role = if (m.role == "user") UiMessage.Role.User else UiMessage.Role.Assistant,
                            text = m.text,
                        )
                    }
                    _state.update { it.copy(messages = list) }
                }
                is CompatApi.Result.Err -> {
                    Log.w("ChatScreenViewModel", "history failed: " + result.reason)
                    _state.update { it.copy(error = result.reason) }
                }
            }
        }
    }

    /** 就地更新某条回复（copy 出新列表，保证 Compose 能看出变化）。 */
    private fun updateReply(id: String, transform: (UiMessage) -> UiMessage) {
        _state.update { st ->
            st.copy(messages = st.messages.map { if (it.id == id) transform(it) else it })
        }
    }
}
