package cd.ptrel.dshpostapi.compose

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cd.ptrel.dshpostapi.CompatApi
import cd.ptrel.dshpostapi.net.StreamEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
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
    /**
     * 执行过程（v0.14.0）：模型调了哪些工具、每步结果如何。
     *
     * 与 [text] 分开存：正文是**对话内容**，过程是**元信息**。
     * 混在一起会污染复制/导出，也会让正文的增量拼接逻辑变复杂。
     */
    val steps: List<ExecStep> = emptyList(),
) {
    /** 一次工具调用 + 它的结果。结果未到时 [result] 为 null（显示为进行中）。 */
    data class ExecStep(
        val callId: String,
        val name: String,
        /** 原始参数（截断）。UI 只取摘要展示，不解析 JSON。 */
        val args: String,
        /** null = 还在跑；非 null = 已返回。 */
        val result: Result? = null,
    ) {
        data class Result(val ok: Boolean, val text: String)
    }
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
        /**
         * 这一轮开始的时刻（SystemClock.elapsedRealtime，v0.15.0）。
         *
         * 用 elapsedRealtime 而不是 System.currentTimeMillis：后者会被用户改时间/
         * NTP 校时影响，重则算出负数用时。0 = 没在跑。
         */
        val busyStartedAt: Long = 0L,
        val error: String? = null,
        val sessionId: String? = null,
        /**
         * 外部（网页端）发起的、正在等待回答的提问（v0.13.0）。
         *
         * 非 null 时 UI 弹出回答面板。用户在 App 里提交后走 /chat/answer。
         */
        val pendingQuestion: CompatApi.PendingQuestion? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var streamJob: Job? = null
    /** 观察流（/chat/watch）的作业；换会话或开始发送时取消。 */
    private var watchJob: Job? = null
    private var seq = 0L

    /** 生成本地 id。用自增序号而不是 UUID：可读、可测、排序天然稳定。 */
    private fun nextId(prefix: String): String = prefix + "-" + (seq++)

    /**
     * 发一条消息。
     *
     * 本地回显先行：用户点发送后立刻看到自己的话，否则会以为没发出去。
     * 这是聊天类应用的通用预期，不是可选的优化。
     */
    /**
     * 观察外部（网页端）对当前会话的改动（v0.13.0）。
     *
     * ## 为什么需要
     *
     * 用户可能在网页端和 App 同时看一个会话。早先 App 只在 onResume 拉一次历史，
     * 页面存活期间完全感知不到外部变化 —— 网页那边已经出完了，App 里还是旧内容。
     *
     * ## 什么时候不订阅
     *
     * **本端正在流式时不订阅**（[STATE.busy]）：两条流会往同一份 messages 写，
     * 结果既不是本端也不是外部的版本。本端流式结束后再重新订阅。
     *
     * ## 断线重连
     *
     * 观察流断掉（网络切换、服务端重启）后等 2 秒重连，只要页面还在、会话没换。
     * 重连成功会收到 snapshot 帧，此时补拉一次历史 —— 断线期间的内容靠它补齐。
     */
    private fun startWatching() {
        watchJob?.cancel()
        val sid = _state.value.sessionId ?: return
        // v0.15.5：**不要**因为 busy 就不连。
        //
        // 早先这里有一句 `if (busy) return` —— 它挡的恰恰是最常见的情况：
        // 用户从会话列表点进一个**正在运行**的会话时，busy 一开始是 false 能连上，
        // 收到 TurnStart 后 busy 变 true，此后任何重连都会被它挡住，
        // 表现为「只加载了历史，之后不再接收流式内容」。
        //
        // 「本端发送时混进外部帧」该由帧处理判断，不该靠「不建立连接」来回避。
        watchJob = viewModelScope.launch {
            var attempt = 0
            while (true) {
                val current = _state.value.sessionId ?: break
                // 会话换了 / 本端开始发消息 → 退出观察
                if (current != sid) break
                // v0.15.5：同样去掉 busy 空转 —— 外部轮跑着的时候更要连着。
                try {
                    streamClient.watch(api.watchRequest(current)).collect { frame ->
                        when (frame) {
                            is WatchFrame.Snapshot -> {
                                // 连上即补拉：断线期间的内容靠这次补齐。
                                attempt = 0
                                reload()
                            }
                            is WatchFrame.Text -> {
                                // v0.15.5：本端正在发送时，这条 Text 是本端自己那轮的回声 ——
                                // 已经由 /chat/stream 追加进正文了，这里再加一遍会重复。
                                //
                                // 早先靠「busy 时不建立 watch 连接」回避这个问题，
                                // 代价是外部轮永远连不上（见 startWatching 注释）。
                                // 现在改成「连着但丢帧」——精确得多。
                                if (!_state.value.busy) appendExternal(frame.text)
                            }
                            // v0.15.2：把**外部**轮的起止也映射到 busy ——
                            // 网页端在跑时 App 也该显示「深度求索中」，否则两端行为一致不了。
                            // 注意不置 busy=true 会覆盖本端发送：本端发送时 watch 是被暂停的
                            //（startWatching 里 `if (busy) { delay; continue }`），所以不会打架。
                            is WatchFrame.TurnStart -> {
                                if (!_state.value.busy) {
                                    _state.update {
                                        it.copy(busy = true, busyStartedAt = android.os.SystemClock.elapsedRealtime())
                                    }
                                }
                            }
                            is WatchFrame.TurnEnd -> {
                                // 只清掉「外部轮」的 busy：本端那条流的 busy 由 send() 自己收尾。
                                if (_state.value.busy) {
                                    _state.update { it.copy(busy = false, busyStartedAt = 0L) }
                                }
                            }
                            is WatchFrame.Done -> reload()
                            is WatchFrame.Questions -> {
                                _state.update { it.copy(pendingQuestion = frame.pending) }
                            }
                            is WatchFrame.Failed -> { /* 下面统一重连 */ }
                            else -> Unit
                        }
                    }
                } catch (_: Exception) {
                    // 观察流异常不影响主功能，静默重连。
                }
                if (!isActive) break
                attempt++
                kotlinx.coroutines.delay(if (attempt < 3) 2_000L else 10_000L)
            }
        }
    }

    private fun frameText(f: WatchFrame.Text): String = f.text

    /** 追加到「外部流式消息」；没有就创建一条。 */
    private fun appendExternal(text: String) {
        _state.update { st ->
            val list = st.messages
            val last = list.lastOrNull()
            if (last != null && last.id == "__external__" && last.status == NetMessage.Status.Streaming) {
                st.copy(messages = list.dropLast(1) + last.copy(text = last.text + text))
            } else if (text.isNotEmpty()) {
                st.copy(
                    messages = list + NetMessage(
                        "__external__",
                        NetMessage.Role.Assistant,
                        text,
                        status = NetMessage.Status.Streaming,
                    ),
                )
            } else {
                st
            }
        }
    }

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
                busyStartedAt = android.os.SystemClock.elapsedRealtime(),
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
                    // v0.14.0：执行过程。工具调用先建一条「进行中」，
                    // 结果到达时按 callId 配回去 —— 这样界面上能看到「正在跑」再变成完成态。
                    is StreamEvent.ToolCall -> updateReply(replyId) { msg ->
                        msg.copy(
                            tool = event.name,
                            steps = msg.steps + NetMessage.ExecStep(
                                callId = event.callId,
                                name = event.name,
                                args = event.args,
                            ),
                        )
                    }
                    is StreamEvent.ToolResult -> updateReply(replyId) { msg ->
                        msg.copy(
                            tool = null,
                            steps = msg.steps.map { st ->
                                if (st.callId == event.callId) {
                                    st.copy(result = NetMessage.ExecStep.Result(event.ok, event.text))
                                } else {
                                    st
                                }
                            },
                        )
                    }
                    // step 帧只用来说明「换了一步」，界面不单独展示（工具条目已足够）。
                    is StreamEvent.Step -> Unit
                    is StreamEvent.Done -> {
                        updateReply(replyId) { it.copy(status = NetMessage.Status.Done, tool = null) }
                        _state.update { it.copy(busy = false, busyStartedAt = 0L) }
                    }
                    is StreamEvent.Stopped -> {
                        updateReply(replyId) { it.copy(status = NetMessage.Status.Stopped, tool = null) }
                        _state.update { it.copy(busy = false, busyStartedAt = 0L) }
                    }
                    is StreamEvent.Error -> {
                        updateReply(replyId) { it.copy(status = NetMessage.Status.Error, tool = null) }
                        _state.update { it.copy(busy = false, busyStartedAt = 0L, error = event.message) }
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
                busyStartedAt = 0L,
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
                            // v0.14.0：历史里也带执行过程 —— 用户重开会话后
                            // 仍能看到当时调了哪些工具，与实时流式的呈现一致。
                            steps = m.execSteps.map { st ->
                                NetMessage.ExecStep(
                                    callId = st.callId,
                                    name = st.name,
                                    args = st.args,
                                    result = st.result?.let {
                                        NetMessage.ExecStep.Result(it.ok, it.text)
                                    },
                                )
                            },
                        )
                    }
                    _state.update { it.copy(messages = list) }
                }
                is CompatApi.Result.Err -> {
                    Log.w("ChatScreenViewModel", "history failed: " + result.reason)
                    _state.update { it.copy(error = result.reason) }
                }
            }
            // v0.13.0：历史加载完成后开始观察外部改动。
            // 放在这里而不是函数开头：观察流的 snapshot 帧会触发一次 reload，
            // 若此时首次历史还没加载完，两次加载会互相覆盖。
            startWatching()
        }
    }

    /**
     * 提交对外部提问的回答（v0.13.0）。
     *
     * 回答成功后立刻清掉 [UiState.pendingQuestion] 让面板关闭；
     * 服务端返回 409 表示「另一客户端已经答过了」—— 同样关面板，
     * 但要给用户一句提示，否则他会以为自己的选择生效了。
     */
    fun answerQuestion(callId: String, answers: List<CompatApi.AnswerItem>) {
        val sid = _state.value.sessionId ?: return
        viewModelScope.launch {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                api.execAnswer(api.answerRequest(sid, callId, answers))
            }
            _state.update { it.copy(pendingQuestion = null) }
            if (result is CompatApi.Result.Err) {
                // 不阻塞界面：错误用 error 字段展示（页面已有的错误条）。
                _state.update { it.copy(error = result.reason) }
            }
            // 回答后服务端会继续这一轮，观察流会推后续内容，无需手动 reload。
        }
    }

    /** 就地更新某条回复（copy 出新列表，保证 Compose 能看出变化）。 */
    private fun updateReply(id: String, transform: (NetMessage) -> NetMessage) {
        _state.update { st ->
            st.copy(messages = st.messages.map { if (it.id == id) transform(it) else it })
        }
    }
}
