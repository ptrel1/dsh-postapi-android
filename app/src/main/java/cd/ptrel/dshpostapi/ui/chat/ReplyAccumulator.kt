package cd.ptrel.dshpostapi.ui.chat

import cd.ptrel.dshpostapi.net.Citation
import cd.ptrel.dshpostapi.net.ReplyBlock
import cd.ptrel.dshpostapi.net.StreamEvent

/**
 * 把块级流式事件**归并**成一条可落库、可渲染的回复。
 *
 * 官方 demo 的 `applyEvent` 是同一件事的 TS 版；这里额外做两件本项目需要的事：
 * 1. 思考块的起止时刻在本地记（服务端不给），用来算「已思考（用时 N 秒）」；
 * 2. 提供 [settle]，把"结束原因 = 安全拒绝"的情况按协议收尾。
 *
 * ### 为什么不用 data class + 纯函数
 * 事件里的 `index` 是**本次响应**的块序号，与落库后的下标无关；
 * 只有"边收边记"才能把增量对回正确的块。为此保留一个 [indexOf] 映射，
 * 它本身就是可变状态 —— 与其假装成纯函数（每次递归复制一份 Map），不如直说是累加器。
 *
 * 单测覆盖：块增量的归并、引用去重、思考计时、安全拒绝收尾（`ReplyAccumulatorTest`）。
 */
class ReplyAccumulator {

    private val indexOf = mutableMapOf<Int, Int>()

    /** 续写开局时已经存在多少块 —— [settle] 绝不能碰它们。 */
    private var seededCount = 0

    var blocks: List<ReplyBlock> = emptyList()
        private set

    /** 服务端生成的会话标题（仅首轮请求了 `title.enabled` 时才有）。 */
    var title: String? = null
        private set

    /** 结束原因。续写能力与安全拒绝的收尾都看它。 */
    var stopReason: String? = null
        private set

    /** 正文（所有 text 块按序拼接）。 */
    val answer: String
        get() = blocks.filterIsInstance<ReplyBlock.Text>().joinToString("") { it.text }

    /** 思维链（所有 thinking 块按序拼接）。 */
    val thinking: String
        get() = blocks.filterIsInstance<ReplyBlock.Thinking>().joinToString("") { it.thinking }

    /**
     * 续写：以已有回复开局，新事件往**后面**接。
     *
     * 只种内容、不种 `indexOf` —— 续写是**新的一次响应**，它的块序号从 0 重新开始，
     * 与旧的块下标毫无关系。种进去才不会有"第 0 块被新内容覆盖"这种事。
     */
    fun seed(existing: List<ReplyBlock>) {
        blocks = existing
        seededCount = existing.size
        indexOf.clear()
        stopReason = null
    }

    fun consume(event: StreamEvent, now: Long) {
        when (event) {
            is StreamEvent.BlockStart -> {
                indexOf.remove(event.index)
                val block = event.block.withStart(now)
                indexOf[event.index] = blocks.size
                blocks = blocks + block
            }

            is StreamEvent.TextDelta -> update(event.index) { block ->
                if (block is ReplyBlock.Text) block.copy(text = block.text + event.text) else block
            }

            is StreamEvent.ThinkingDelta -> update(event.index) { block ->
                if (block is ReplyBlock.Thinking) {
                    block.copy(thinking = block.thinking + event.text)
                } else {
                    block
                }
            }

            is StreamEvent.SignatureDelta -> update(event.index) { block ->
                if (block is ReplyBlock.Thinking) {
                    block.copy(signature = (block.signature.orEmpty() + event.signature))
                } else {
                    block
                }
            }

            is StreamEvent.CitationDelta -> update(event.index) { block ->
                if (block is ReplyBlock.Text) block.copy(citations = block.citations.plusUnique(event.citation))
                else block
            }

            is StreamEvent.BlockStop -> {
                update(event.index) { block ->
                    if (block is ReplyBlock.Thinking && block.startedAt != null &&
                        block.endedAt == null
                    ) {
                        block.copy(endedAt = now)
                    } else {
                        block
                    }
                }
                indexOf.remove(event.index)
            }

            is StreamEvent.Title -> title = event.text

            is StreamEvent.Stopped -> stopReason = event.reason

            // OpenAI 通道的增量事件不走这个累加器；流量到了这里说明通道配错，忽略即可
            is StreamEvent.Content, is StreamEvent.Reasoning, is StreamEvent.Usage,
            StreamEvent.Done, is StreamEvent.Error -> Unit
        }
    }

    /**
     * 收尾：把所有未结束的思考块按 [now] 收口。
     *
     * ### 安全拒绝（`refusal`）**不丢任何已经收到的内容**
     *
     * 早先这里按 Anthropic 文档的字面意思"这一轮生成的东西必须丢弃"，
     * 在 `stop_reason = refusal` 时删掉正文与思考。那是**想错了**：
     *
     * - 我们是**第三方客户端**，不是内容裁量的执行者。服务端说不写了，
     *   它已经停了 —— 客户端再去把屏幕上已经出现的字擦掉，等于替服务端做销毁；
     * - 这在本项目里是**数据丢失**：和"用户点了停止生成，正文却消失"同性质，
     *   而且用户完全无从预料 —— 他看到字一个个写出来，然后整段不见了。
     *
     * 保留内容不影响任何判定：`stopReason` 仍然记着 `refusal`
     * （`ContinuePolicy` 靠它禁掉"继续生成"），只是不再删字。
     */
    fun settle(now: Long) {
        blocks = blocks.map { block ->
            if (block is ReplyBlock.Thinking && block.startedAt != null && block.endedAt == null) {
                block.copy(endedAt = now)
            } else {
                block
            }
        }
        indexOf.clear()
    }

    /** 思考总用时（毫秒）。没有思考块时为 null。 */
    fun thinkingMillis(now: Long): Long? {
        val spans = blocks.filterIsInstance<ReplyBlock.Thinking>().mapNotNull { block ->
            val start = block.startedAt ?: return@mapNotNull null
            (block.endedAt ?: now) - start
        }
        return spans.takeIf { it.isNotEmpty() }?.sum()
    }

    private inline fun update(index: Int, transform: (ReplyBlock) -> ReplyBlock) {
        val at = indexOf[index] ?: return
        val block = blocks.getOrNull(at) ?: return
        val next = transform(block)
        if (next !== block) {
            blocks = blocks.toMutableList().also { it[at] = next }
        }
    }

    /** 思考块从开始计时（服务端不给时刻，只能本地记）。 */
    private fun ReplyBlock.withStart(now: Long): ReplyBlock =
        if (this is ReplyBlock.Thinking && startedAt == null) copy(startedAt = now) else this

    companion object {
        /** 安全拒绝的结束原因字面值。 */
        const val Refusal = "refusal"

        /** 输出被上限截断 —— 可以续写。 */
        const val MaxTokens = "max_tokens"
    }
}

/** 同一块内引用按 url 去重（协议：同一块内引用按 url 只记一次）。 */
private fun List<Citation>.plusUnique(citation: Citation): List<Citation> =
    if (any { it.url == citation.url }) this else this + citation
