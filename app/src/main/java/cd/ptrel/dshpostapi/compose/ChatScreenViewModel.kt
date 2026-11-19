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
    internal val api: CompatApi,
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

        /**
         * 诊断计数（v0.19.1）。只在诊断模式开启时被界面读取。
         *
         * 排查「流式重复 / 漏字 / 多端不同步」时，这几个数字是唯一判据：
         *   · 收到多少帧、丢弃多少帧 —— 判断去重是否生效
         *   · watch 是否连着 —— 判断是不是断了
         */
        val diagTextFrames: Int = 0,
        val diagDroppedFrames: Int = 0,
        val diagWatchConnected: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var streamJob: Job? = null
    /** 观察流（/chat/watch）的作业；换会话或开始发送时取消。 */
    private var watchJob: Job? = null

    /**
     * 当前「外部轮」的消息 id 与序号（v0.15.8）。
     *
     * 每次收到 TurnStart 就换一个新 id —— 多轮不能复用同一条消息，
     * 否则第二轮会追加到第一轮上面（见 [appendExternal] 的注释）。
     */
    /**
     * 观察流连续失败多少次后放弃（v0.15.9）。
     *
     * 取 8：按 2/4/8/16/30/30/30/30 秒退避，总共约 2.5 分钟。
     * 够覆盖「服务端重启」（通常几十秒）这类可恢复故障，
     * 又不至于在永久故障时无限重试。
     */
    private val MAX_WATCH_RETRY = 8

    private var externalTurnSeq = 0
    private var currentExternalId: String = "external-0"

    /**
     * /chat/watch 当前跟随的轮次编号（v0.17.4）。
     *
     * 服务端在 turn-start / turn-end 里带了 turn，客户端早先丢掉了它 ——
     * 于是无法区分「这条 text 属于哪一轮」。
     */
    private var watchedTurn: Int = 0

    /**
     * 本端通过 /chat/stream 发起的那一轮的 turn 编号（v0.17.4）。
     *
     * -1 = 未知。服务端在 start 帧里给 turn 后写入。
     * /chat/watch 收到 turn == selfTurn 的 text 是本端回声，丢弃；
     * 其它一律当成别端内容追加。
     */
    private var selfTurn: Int = -1

    /**
     * 本设备的客户端标识（v0.17.5）。
     *
     * 进程内固定即可 —— 它的用途是「区分同一会话的不同发起方」，
     * 不是身份认证。重启后换个值也无妨（那时旧连接早断了）。
     */
    internal val clientId: String = java.util.UUID.randomUUID().toString().take(8)
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
        android.util.Log.i("WatchDiag", "startWatching called, sessionId=" + _state.value.sessionId)
        watchJob?.cancel()
        val sid = _state.value.sessionId ?: run {
            android.util.Log.w("WatchDiag", "startWatching: sessionId is null, abort")
            return
        }
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
                android.util.Log.i("WatchDiag", "connecting watch attempt=" + attempt + " sid=" + current)
                try {
                    streamClient.watch(api.watchRequest(current, clientId)).collect { frame ->
                        android.util.Log.i("WatchDiag", "frame: " + frame.javaClass.simpleName)
                        when (frame) {
                            is WatchFrame.Snapshot -> {
                                // 连上即补拉：断线期间的内容靠这次补齐。
                                attempt = 0
                                // v0.19.4：诊断标志 —— 收到 snapshot 就说明连接真的建立了。
                                //（服务端连上后第一件事就是发 snapshot，这是可靠信号）
                                _state.update { it.copy(diagWatchConnected = true) }
                                DiagLog.add("watch", "connected")
                                reload()
                            }
                            is WatchFrame.Text -> {
                                // v0.15.8：一有正文输出就说明提问已被处理，顺手关弹窗
                                // （不是所有轮次都会发 turn-end）。
                                if (_state.value.pendingQuestion != null) {
                                    _state.update { it.copy(pendingQuestion = null) }
                                }
                                // v0.17.4：按 **turn 编号** 判断是不是别端的内容。
                                //
                                // 早先看 `busy` —— 但 busy 只表示「本端忙不忙」，
                                // 不是「这条内容是谁的」。本端在跑时，另一端的正常流式内容
                                // 也被一起丢掉了，表现为「两台 App 同时发消息，流式接收不同步」。
                                //
                                // 现在：turn == 0 表示服务端没给编号（老版本服务端），保守地当成别端内容；
                                // 否则只要不是本端正在跟随的那一轮，就正常追加。
                                // v0.17.5：按**发起方身份**判断，不再看 busy 或 turn。
                                //
                                //   clientId == 本端  → 这是我自己那轮的回声，丢弃（正文已由 stream 写入）
                                //   clientId 是别人  → 别端的内容，正常追加
                                //   clientId 为空    → 服务端不知道发起方（网页端直连），也追加
                                //
                                // 早先看 busy：本端在跑时把别端内容也丢了，
                                // 表现为「两台 App 同时发消息，流式接收不同步」（用户反馈）。
                                // v0.19.0：诊断日志 —— 重复渲染问题需要看清每一帧的归属。
                                android.util.Log.i(
                                    "WatchDiag",
                                    "text frame: clientId=" + frame.clientId +
                                        " self=" + clientId +
                                        " watchedTurn=" + watchedTurn +
                                        " curExt=" + currentExternalId +
                                        " drop=" + (frame.clientId == clientId) + " busy=" + _state.value.busy,
                                )
                                // v0.19.0：兜底 —— clientId 为空时按 busy 判断。
                                //
                                // ## 为什么需要这层兜底
                                //
                                // 服务端只在 /chat/stream 入口记下发起方（sessionOriginClient），
                                // 而工具调用、子代理等路径产生的文本没有这个上下文，
                                // 推送时 clientId 是空串。
                                //
                                // 空串不等于本端，于是被当成「别端内容」追加 → 同一段
                                // 内容在 replyId 与 external-N 里各出现一次 → 界面重复。
                                //
                                // 兜底规则：clientId 为空 **且本端正在跑** 时直接丢弃。
                                // 代价是「本端跑着时别端用不记 clientId 的路径发来的内容」会丢，
                                // 但那种情况本来就极少，且下次 reload 会补上。
                                // v0.22.2：区分「明确是本端」与「服务端不知道是谁」。
                                //
                                // 日志实证（2026-11-19，997 帧）：服务端在工具调用 / 子代理
                                // 路径上推的文本 clientId 是空串，而旧兜底把它当成回声全丢弃 ——
                                // 结果是 watch 通道全程空转，多端同步形同虚设。
                                //
                                // 正确区分：
                                //   clientId == 本端   → **确定**是本端回声，丢弃（否则重复）
                                //   clientId 为空      → 服务端不知道谁发起，可能是任意一端
                                //                        → 接收，但要按内容去重
                                //   clientId == 别端    → 正常接收
                                val isOwnEcho = frame.clientId == clientId
                                val unknownOrigin = frame.clientId.isEmpty()
                                // 空来源的帧：如果这段文本已经在某个消息的尾部出现过，
                                // 说明是同一条内容的第二次推送（stream 通道先写过一遍），跳过。
                                val duplicated = unknownOrigin && tailAlreadyContains(frame.text)
                                val dropAsEcho = isOwnEcho || duplicated
                                // v0.19.5：记一条诊断日志（**不含正文**，只有归属判定）。
                                //
                                // 这行是排查「重复 / 漏字 / 不同步」的关键数据：
                                // 知道每一帧被归到「本端回声」还是「别端内容」，
                                // 才能判断去重到底生效没有。
                                DiagLog.add(
                                    "text",
                                    "clientId=" + frame.clientId +
                                        " self=" + clientId +
                                        " drop=" + dropAsEcho +
                                        " busy=" + _state.value.busy +
                                        " len=" + frame.text.length,
                                )
                                // v0.19.1：诊断计数 —— 排查同步问题时这是唯一判据。
                                _state.update {
                                    it.copy(
                                        diagTextFrames = it.diagTextFrames + 1,
                                        diagDroppedFrames = it.diagDroppedFrames + if (dropAsEcho) 1 else 0,
                                    )
                                }
                                if (!dropAsEcho) {
                                    appendExternal(frame.text)
                                }
                            }
                            // v0.15.2：把**外部**轮的起止也映射到 busy ——
                            // 网页端在跑时 App 也该显示「深度求索中」，否则两端行为一致不了。
                            // 注意不置 busy=true 会覆盖本端发送：本端发送时 watch 是被暂停的
                            //（startWatching 里 `if (busy) { delay; continue }`），所以不会打架。
                            is WatchFrame.TurnStart -> {
                                // v0.15.8：每轮开始换新消息 id —— 多轮不能拼在同一条上。
                                externalTurnSeq++
                                currentExternalId = "external-" + externalTurnSeq
                                if (!_state.value.busy) {
                                    _state.update {
                                        it.copy(busy = true, busyStartedAt = android.os.SystemClock.elapsedRealtime())
                                    }
                                }
                            }
                            is WatchFrame.TurnEnd -> {
                                // v0.15.8：先把这条外部消息收尾，再清 busy ——
                                // 否则它一直停在 Streaming，下一轮的 Text 会追加上去。
                                finishExternal()
                                // v0.15.8：本轮结束说明提问已被回答（或取消）—— 关掉弹窗。
                                //
                                // 服务端只在**发现**待答问题时推 questions 帧，从不通知「已关闭」；
                                // 而「本轮继续/结束」本身就是问题已解决的最可靠信号：
                                // ask_user_question 是阻塞式的，answered 之后 agent 才会往下走。
                                //
                                // 不关的后果：用户在网页端答完，App 的弹窗永远悬在那儿。
                                _state.update { st ->
                                    if (st.pendingQuestion == null) st
                                    else st.copy(pendingQuestion = null)
                                }
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
                } catch (e: Exception) {
                    // 观察流异常不影响主功能，静默重连。
                    android.util.Log.w("WatchDiag", "watch error: " + e.javaClass.simpleName + " " + e.message)
                }
                if (!isActive) break
                // v0.19.4：走到这里说明上一轮 collect 结束（连不上或掉线）。
                _state.update { it.copy(diagWatchConnected = false) }
                DiagLog.add("watch", "disconnected, attempt=" + attempt)
                attempt++
                // v0.15.9：指数退避 + 上限，超限后停止重连。
                //
                // 早先是 `if (attempt < 3) 2s else 10s` —— 服务端**永久不可用**
                // （端点被移除、反代配置错了）时会每 10 秒重试一次，无限重试。
                // 那既无意义（不会自己好起来）又费电，还会在服务端日志里刷屏。
                //
                // 现在：2s → 4s → 8s → 16s → 30s（封顶），连续失败 MAX_WATCH_RETRY 次后停止。
                // 用户切走再回来（openSession 重新调用）会重置计数 —— 那是明确的"重试意图"。
                if (attempt > MAX_WATCH_RETRY) {
                    android.util.Log.w("WatchDiag", "give up watching after " + attempt + " attempts")
                    break
                }
                val backoffMs = (2_000L shl (attempt - 1).coerceAtMost(5)).coerceAtMost(30_000L)
                kotlinx.coroutines.delay(backoffMs)
            }
        }
    }

    private fun frameText(f: WatchFrame.Text): String = f.text

    /**
     * 追加到「外部流式消息」；没有就创建一条。
     *
     * ## 为什么 id 不再用固定的 "__external__"（v0.15.8 修）
     *
     * 固定 id 时，外部**连续跑两轮**会出问题：
     *   第一轮创建了 id=__external__ 的占位，一直停在 Streaming；
     *   第二轮继续往**同一条**追加，两轮的正文被拼成一段，
     *   而且那条消息永远不会变成 Done。
     *
     * 现在每轮用新 id（由 [externalTurnSeq] 递增），
     * 并在 TurnEnd 时把当前那条收尾。
     */
    /**
     * 这段文本是否已经出现在「正在流式的消息」尾部（v0.22.2）。
     *
     * ## 为什么需要内容去重
     *
     * 服务端在工具调用 / 子代理路径推的文本没有 clientId，分不清是哪一端发起的。
     * 先前靠 `clientId.isEmpty() && busy` 一律丢弃 —— 日志实证 997 帧全丢，
     * 代价是别端内容也全丢，多端同步形同虚设。
     *
     * 改为看内容：本端发送时 /chat/stream 会先写入同样的文本，
     * watch 再推一次时，尾部的 64 字符里就能找到 —— 说明是重复。
     *
     * ## 为什么取 64 字符
     *
     * 流式 chunk 通常几个字符到几十个（日志实测 405 帧只有 1 字符）。
     * 64 足以覆盖一帧，又不会因为标点重复而误判。
     */
    private fun tailAlreadyContains(text: String): Boolean {
        if (text.isEmpty()) return true
        val streaming = _state.value.messages.lastOrNull {
            it.status == NetMessage.Status.Streaming
        } ?: return false
        return streaming.text.takeLast(64).contains(text)
    }

    private fun appendExternal(text: String) {
        _state.update { st ->
            val list = st.messages
            val last = list.lastOrNull()
            if (last != null && last.id == currentExternalId && last.status == NetMessage.Status.Streaming) {
                st.copy(messages = list.dropLast(1) + last.copy(text = last.text + text))
            } else if (text.isNotEmpty()) {
                st.copy(
                    messages = list + NetMessage(
                        currentExternalId,
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

    /**
     * 把当前这条外部流式消息收尾（TurnEnd 时调用）。
     *
     * 不收尾的后果：它一直是 Streaming 状态，
     * 后续轮次的 Text 会被追加到它上面（见 [appendExternal] 的判据），
     * 表现为多轮内容混成一条。
     */
    private fun finishExternal() {
        _state.update { st ->
            st.copy(
                messages = st.messages.map {
                    if (it.id == currentExternalId && it.status == NetMessage.Status.Streaming) {
                        it.copy(status = NetMessage.Status.Done)
                    } else {
                        it
                    }
                },
            )
        }
    }

    fun send(text: String, images: List<CompatApi.PendingImage> = emptyList()) {
        val trimmed = text.trim()
        // v0.11.0：允许「只发图片不打字」——与宿主 hasPromptContent 的判据一致。
        if ((trimmed.isEmpty() && images.isEmpty()) || _state.value.busy) return
        // v0.16.4：即便守卫放行，也确保没有旧 job 在跑。
        // 正常路径上 busy=false 时旧 job 已结束；这里是防御 ——
        // 万一有残留（比如 Error 分支清了 busy 但协程还在收尾），
        // 覆盖 streamJob 会让它变成孤儿，永远不被 cancel。
        streamJob?.cancel()
        streamJob = null

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
                        // v0.17.5：告诉服务端「这一轮是谁发起的」，
                        // 它在 /chat/watch 推送时原样标注，客户端据此丢弃自己的回声。
                        clientId = clientId,
                    )
        streamJob = viewModelScope.launch {
            try {
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
                    // ⚠️ v0.16.4：这三个分支**不再**清 busy。
                    //
                    // 早先在收到 Done 帧时就 `busy = false`，但那个协程还在 collect ——
                    // 用户此时发新消息会通过 send() 的 busy 守卫，于是：
                    //   · 新 stream 被创建，streamJob 被覆盖 → 旧 job 再也不会被 cancel
                    //   · 旧 job 继续往它的 replyId 写残余帧，与新的交叉写同一份 messages
                    // 表现就是「中途发送导致前一条流被截断」（用户反馈）。
                    //
                    // 现在只标记这条回复的状态，busy 由 job 的 finally 统一清 ——
                    // 「一轮真正结束」= 协程结束，不是「收到结束帧」。
                    is StreamEvent.Done -> {
                        updateReply(replyId) { it.copy(status = NetMessage.Status.Done, tool = null) }
                    }
                    is StreamEvent.Stopped -> {
                        updateReply(replyId) { it.copy(status = NetMessage.Status.Stopped, tool = null) }
                    }
                    is StreamEvent.Error -> {
                        updateReply(replyId) { it.copy(status = NetMessage.Status.Error, tool = null) }
                        _state.update { it.copy(error = event.message) }
                    }
                    else -> Unit   // Usage / 块级事件：本项目暂不消费
                }
            }
            } finally {
                // v0.16.4：**一轮真正结束**在这里才发生（协程结束），不是收到 Done 帧。
                //
                // 正常结束、抛异常、被 cancel 都会走到这里 —— busy 一定要清，
                // 否则界面永远停在「深度求索中」。
                _state.update { it.copy(busy = false, busyStartedAt = 0L) }
                // 把仍在 Streaming 的回复收尾（异常/取消路径下不会有 Done 帧）
                _state.update { st ->
                    st.copy(
                        messages = st.messages.map {
                            if (it.id == replyId && it.status == NetMessage.Status.Streaming) {
                                it.copy(status = NetMessage.Status.Error, tool = null)
                            } else {
                                it
                            }
                        },
                    )
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
        // v0.15.7：**先建 watch，再拉历史**。
        //
        // 早先 startWatching() 挂在 history 请求之后 —— 只要 history 失败/超时，
        // 观察流就永远不建立，表现为「进了会话页但收不到外部流式内容」。
        // 而「订阅变化」和「加载历史」是两件独立的事，不该互相依赖。
        //
        // 顺序也有讲究：先连上再拉历史，能保证「拉历史期间的增量」不会漏
        // （snapshot 帧会再触发一次 reload 补齐）。
        startWatching()
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
            // v0.15.7：观察流已在函数开头启动（见那里的注释）。
            // 这里不再调用 —— 重复调用会 cancel 掉刚建立的连接。
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
