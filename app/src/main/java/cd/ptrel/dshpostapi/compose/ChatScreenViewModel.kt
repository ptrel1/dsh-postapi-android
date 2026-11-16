package cd.ptrel.dshpostapi.compose

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cd.ptrel.dshpostapi.CompatApi
import cd.ptrel.dshpostapi.net.StreamEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * CompatApi.Message → NetMessage。
 *
 * openSession 与 reload 都要做同一件事，抽出来避免两处逻辑漂移
 * （一处改了字段另一处忘了，表现为「重进有内容、刷新没内容」这类诡异现象）。
 */
private fun CompatApi.Message.toDisplay(): NetMessage = NetMessage(
    id = "h-" + seq + "-" + role,
    role = if (role == "user") NetMessage.Role.User else NetMessage.Role.Assistant,
    text = text,
    status = NetMessage.Status.Done,
)

/** 界面上的一条消息（UI 模型，与网络 DTO 分开）。 */
data class NetMessage(
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
    /**
     * 用于持久化「上次活跃会话」（ISSUE-01 P1）。
     *
     * 用 applicationContext 而不是 Activity：ViewModel 可能比 Activity 活得久，
     * 持有 Activity 引用会泄漏。
     */
    private val appContext: android.content.Context,
    /**
     * 新建会话要用的工作区路径（v0.11.0）。
     *
     * DSH 的会话必须绑定 cwd —— 它决定 agent 在哪里执行命令、能读哪些文件。
     * 空串 = 沿用服务端默认（本机 /tmp），只应在「没走工作区选择」的旧入口出现。
     * 服务端会按白名单校验，非法值返回 403。
     */
    private val workspaceCwd: String = "",
) : ViewModel() {

    data class UiState(
        val messages: List<NetMessage> = emptyList(),
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
    fun send(text: String, images: List<CompatApi.PendingImage> = emptyList()) {
        val trimmed = text.trim()
        // v0.11.0：允许「只发图片不打字」——与宿主 hasPromptContent 的判据一致。
        if ((trimmed.isEmpty() && images.isEmpty()) || _state.value.busy) return

        val userMsg = NetMessage(nextId("u"), NetMessage.Role.User, trimmed)
        val replyId = nextId("a")
        val placeholder = NetMessage(replyId, NetMessage.Role.Assistant, status = NetMessage.Status.Streaming)

        _state.update {
            it.copy(
                messages = it.messages + userMsg + placeholder,
                busy = true,
                error = null,
            )
        }

        val request = api.streamRequest(
                        prompt = trimmed,
                        sessionId = _state.value.sessionId,
                        images = images,
                        // 只在**新会话**时带 cwd：已有会话的 cwd 由它自己决定，
                        // 再传一次要么被忽略、要么在服务端引发「改工作区」的歧义。
                        cwd = if (_state.value.sessionId == null) workspaceCwd else null,
                    )
        streamJob = viewModelScope.launch {
            streamClient.stream(request).collect { event ->
                when (event) {
                    // ⚠️ 这里用 Title 承载 DSH 的 start 帧（见 DshStreamClient 的注释）：
                    //    SimpleChat 的 StreamEvent 没有 Started 类型，而我们需要 sessionId。
                    is StreamEvent.Title -> {
                        val sid = event.text
                        // 服务端分配了会话 id → 立刻持久化。
                        // 不能等「这一轮结束」才存：用户可能在流式期间就退出，
                        // 那时再存就没机会了（进程随时可能被回收）。
                        cd.ptrel.dshpostapi.AppPrefs.setLastSessionId(appContext, sid)
                        _state.update { it.copy(sessionId = sid) }
                    }
                    is StreamEvent.Content -> updateReply(replyId) { it.copy(text = it.text + event.text) }
                    is StreamEvent.Reasoning -> updateReply(replyId) { it.copy(reasoning = it.reasoning + event.text) }
                    is StreamEvent.Done -> {
                        updateReply(replyId) { it.copy(status = NetMessage.Status.Done, tool = null) }
                        _state.update { it.copy(busy = false) }
                    }
                    is StreamEvent.Stopped -> {
                        updateReply(replyId) { it.copy(status = NetMessage.Status.Stopped, tool = null) }
                        _state.update { it.copy(busy = false) }
                    }
                    is StreamEvent.Error -> {
                        updateReply(replyId) { it.copy(status = NetMessage.Status.Error, tool = null) }
                        _state.update { it.copy(busy = false, error = event.message) }
                    }
                    else -> Unit   // Usage / 块级事件：本项目暂不消费
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
                    if (it.status == NetMessage.Status.Streaming) it.copy(status = NetMessage.Status.Stopped, tool = null) else it
                },
            )
        }
    }

    /** 读某个会话的历史（进入会话时调用）。 */
    /**
     * 重新拉取当前会话的历史（v0.10.1）。
     *
     * 用途：从后台切回来、或从别的设备/网页聊过之后，用户期望看到最新内容。
     * 早先只在 openSession 时加载一次 —— 停在聊天页期间服务端的更新永远看不到，
     * 必须退出重进才刷新，用户体感是「消息不更新」。
     *
     * 与 openSession 的差别：**不清空现有消息**。清空会让界面闪白一帧，
     * 而且流式中的内容会被抹掉。这里只把服务端的新结果替换进去。
     *
     * 流式中不刷新：那会和正在进行的 SSE 增量打架（同一轮内容被两处写）。
     */
    fun reload() {
        val sid = _state.value.sessionId ?: return
        if (_state.value.busy) return
        viewModelScope.launch {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                api.history(sid)
            }
            if (result is CompatApi.Result.Ok) {
                _state.update { st ->
                    // 只有条数变多或最后一条内容变了才替换 —— 避免无意义的重绘。
                    val incoming = result.value.messages
                    if (incoming.size == st.messages.size &&
                        incoming.lastOrNull()?.text == st.messages.lastOrNull()?.text
                    ) {
                        st
                    } else {
                        st.copy(messages = incoming.map { it.toDisplay() })
                    }
                }
            }
        }
    }

    fun openSession(sessionId: String) {
        // 进入会话即记为「上次活跃」—— 用户下次打开 App 应该回到这里。
        cd.ptrel.dshpostapi.AppPrefs.setLastSessionId(appContext, sessionId)
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
                    // TEMP DIAG：确认 App 实际收到了多少条、内容是什么
                    Log.i("ChatDiag", "openSession sid=" + sessionId +
                        " messages=" + result.value.messages.size +
                        " hasMore=" + result.value.hasMore)
                    result.value.messages.take(3).forEachIndexed { i, m ->
                        Log.i("ChatDiag", "  [$i] seq=" + m.seq + " role=" + m.role +
                            " text=" + m.text.take(40).replace("\n", "\u21b5"))
                    }
                    val list = result.value.messages.map { m ->
                        NetMessage(
                            id = nextId(if (m.role == "user") "u" else "a"),
                            role = if (m.role == "user") NetMessage.Role.User else NetMessage.Role.Assistant,
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
    private fun updateReply(id: String, transform: (NetMessage) -> NetMessage) {
        _state.update { st ->
            st.copy(messages = st.messages.map { if (it.id == id) transform(it) else it })
        }
    }
}
