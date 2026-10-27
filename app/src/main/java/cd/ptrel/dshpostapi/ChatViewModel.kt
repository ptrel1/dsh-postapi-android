package cd.ptrel.dshpostapi

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

/**
 * 兼容模式聊天页的状态与调度。
 *
 * ==================== 为什么不用 AndroidX ViewModel ====================
 * 项目现有代码刻意不引 androidx.lifecycle（依赖最小化，见 build.gradle.kts 的注释）。
 * 转屏重建时本类的状态会丢 —— 代价是"转屏后回到会话列表"，
 * 而换回的是少一个依赖 + 与既有代码风格一致。
 * 如果将来要保状态，把这个类改成 androidx ViewModel 即可，接口不用动。
 *
 * ==================== 线程模型 ====================
 * 所有网络与解析在 [io] 单线程池；回调统一切回主线程（[main]）。
 * 单线程而不是多线程：聊天页同一时刻只会有一个请求在飞（流式期间禁止再发），
 * 多线程只会引入竞态。
 *
 * ==================== 流式增量怎么拼 ====================
 * SSE 的 text 帧是**增量片段**（"你是" / " AI" / "助手"），必须累加而不是替换。
 * 累加目标 = 列表里最后一条 assistant 消息；若没有就新建一条。
 */
/** 流式占位消息的 seq。用 -2 与「本地回显的用户消息」(-1) 区分，避免判等时混淆。 */
private const val STREAMING_SEQ = -2

class ChatViewModel(
    private val api: CompatApi,
    private val listener: Listener,
) {

    interface Listener {
        /** 消息列表整体变了（重排/新增）。 */
        fun onMessages(messages: List<CompatApi.Message>)

        /** 流式中的那条消息文本变了（只更新最后一条，避免整表重绘）。 */
        fun onStreamingChanged(text: String)

        /** 一轮对话结束（正常或出错）。 */
        fun onTurnFinished(error: String?)

        /** 忙闲状态变了（决定发送按钮是否可点、是否显示"停止"）。 */
        fun onBusyChanged(busy: Boolean)
    }


    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "compat-io").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    private val messages = ArrayList<CompatApi.Message>()
    private var currentSessionId: String? = null
    private var streaming = StringBuilder()
    private var busy = false
    private var cancelled = false

    fun sessionId(): String? = currentSessionId

    fun isBusy(): Boolean = busy

    // ==========================================================================
    // 打开一个会话
    // ==========================================================================

    /**
     * 切到某个会话并拉第一页历史。
     *
     * @param sessionId null 表示"新会话"—— 不拉历史，等第一条消息发出后由服务端分配 id。
     */
    fun openSession(sessionId: String?) {
        currentSessionId = sessionId
        messages.clear()
        streaming = StringBuilder()
        emitMessages()

        if (sessionId == null) return

        io.execute {
            val result = api.history(sessionId, beforeSeq = null, limit = 50)
            main.post {
                when (result) {
                    is CompatApi.Result.Ok -> {
                        // 服务端按时间正序返回；这里直接替换，不追加。
                        messages.clear()
                        messages.addAll(result.value.messages)

                        // v1.9.3：用消息自带的 turn 直接对齐 ——
                        // 每条 assistant/message 都带 data.turn，插件现在把它下发了。
                        // 早先版本靠「第 N 条助手消息 ≈ 第 N 个 turn」猜，会挂错（一轮可能多条助手消息）。
                        val byTurn = result.value.deliverablesByTurn
                        if (byTurn.isNotEmpty()) {
                            for (i in messages.indices) {
                                val m = messages[i]
                                if (m.role != "assistant" || m.turn < 0) continue
                                val files = byTurn[m.turn] ?: continue
                                messages[i] = m.copy(deliverables = files)
                            }
                        }
                        emitMessages()
                    }
                    is CompatApi.Result.Err -> {
                        // 历史读失败不阻断发消息 —— 用户仍然可以说话，只是看不到上下文。
                        Log.w("ChatViewModel", "history failed: " + result.reason)
                        listener.onTurnFinished(result.reason)
                    }
                }
            }
        }
    }

    // ==========================================================================
    // 发消息（SSE）
    // ==========================================================================

    fun send(text: String) {
        if (busy) return
        if (text.isBlank()) return

        // 立刻把用户消息放进列表 —— 否则用户点发送后有一段时间看不到自己说的话，
        // 会以为没发出去（这是聊天类应用的通用预期：本地回显先行）。
        messages.add(CompatApi.Message(seq = -1, role = "user", text = text, time = System.currentTimeMillis()))
        emitMessages()

        busy = true
        cancelled = false
        streaming = StringBuilder()
        listener.onBusyChanged(true)

        io.execute {
            StreamingClient(api).run(
                prompt = text,
                sessionId = currentSessionId,
                onStart = { sid ->
                    // 新会话第一次发送后服务端才给 id，记下来供后续轮次复用。
                    if (currentSessionId == null) currentSessionId = sid
                },
                onText = { delta ->
                    streaming.append(delta)
                    val snapshot = streaming.toString()
                    main.post {
                        // 流式内容作为**列表里的一条真实消息**来更新。
                        // 为什么不能在 adapter 里合成：ListAdapter 的内部计数与 getItem 必须一致，
                        // 覆写 getItemCount 会抛 IndexOutOfBoundsException（已在 v0.6.1 踩过）。
                        // 这里维护一条 seq=-2 的占位消息，每次增量只改它的 text 再 submitList。
                        val idx = messages.indexOfLast { it.seq == STREAMING_SEQ }
                        val updated = CompatApi.Message(
                            seq = STREAMING_SEQ,
                            role = "assistant",
                            text = snapshot,
                            time = if (idx >= 0) messages[idx].time else System.currentTimeMillis(),
                        )
                        if (idx >= 0) messages[idx] = updated else messages.add(updated)
                        listener.onMessages(ArrayList(messages))
                    }
                },
                onDone = {
                    finishTurn(null)
                },
                onError = { reason ->
                    finishTurn(reason)
                },
                isCancelled = { cancelled },
            )
        }
    }

    /** 中断当前生成。 */
    fun cancel() {
        val sid = currentSessionId ?: return
        cancelled = true
        io.execute { api.cancel(sid) }
    }

    /**
     * 一轮结束：把流式内容固化成一条正常消息。
     *
     * 为什么放主线程：要改 [messages] 并通知 UI，两件事都必须在主线程。
     */
    private fun finishTurn(error: String?) {
        main.post {
            val text = streaming.toString()
            val idx = messages.indexOfLast { it.seq == STREAMING_SEQ }
            if (text.isEmpty()) {
                // 一个字都没收到（例如立刻出错）：把占位行删掉，不要留一个空气泡。
                if (idx >= 0) messages.removeAt(idx)
            } else if (idx >= 0) {
                // 占位转正：seq 改成 -1（本地消息），内容就是最终文本。
                // 保留同一个对象位置，避免列表跳一下。
                messages[idx] = CompatApi.Message(
                    seq = -1, role = "assistant", text = text, time = messages[idx].time,
                )
            }
            streaming = StringBuilder()
            busy = false
            listener.onBusyChanged(false)
            emitMessages()
            listener.onTurnFinished(error)
        }
    }

    private fun emitMessages() {
        // 传副本：监听方可能在渲染过程中改列表（例如 LazyColumn 复用），传引用会 ConcurrentModification。
        listener.onMessages(ArrayList(messages))
    }

    /** Activity 销毁时调用：停线程池，避免泄漏。 */
    fun dispose() {
        cancelled = true
        io.shutdownNow()
    }
}
