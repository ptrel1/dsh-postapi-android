package cd.ptrel.dshpostapi.net

/** 流式事件。UI 层只依赖这个抽象，不接触 SSE 细节。 */
sealed interface StreamEvent {

    /** 正文增量。 */
    data class Content(val text: String) : StreamEvent

    /** 思维链增量（DeepSeek 的 `reasoning_content`）。 */
    data class Reasoning(val text: String) : StreamEvent

    /** 用量统计，通常在流末尾到达。 */
    data class Usage(
        val promptTokens: Int,
        val completionTokens: Int,
        val cacheHitTokens: Int?,
    ) : StreamEvent

    /** 正常结束（收到 `data: [DONE]` 或 finish_reason）。 */
    data object Done : StreamEvent

    /** 失败。`code` 为 HTTP 状态码，网络异常时为 null。 */
    data class Error(
        val message: String,
        val code: Int? = null,
        val retryable: Boolean = false,
    ) : StreamEvent

    // ── 以下是 Anthropic 兼容通道的**块级**事件 ─────────────────────────
    //
    // OpenAI 兼容通道只吐 Content / Reasoning 两种增量，回复没有"结构"；
    // 扩展模式（联网搜索、读链接、引用）必须保留块的结构，否则界面无从
    // 还原「先想了什么、搜了什么、看了哪几个网页、正文在哪」。
    //
    // 两类事件并存，消费方按需取用：只认 Content 的长文路径行为完全不变。

    /** 一个内容块开始。服务端工具块是原子的 —— 开始时内容就已经完整。 */
    data class BlockStart(val index: Int, val block: ReplyBlock) : StreamEvent

    /** 正文增量，[index] 是**本次响应**里的块序号（不是落库后的下标）。 */
    data class TextDelta(val index: Int, val text: String) : StreamEvent

    /** 思考增量。 */
    data class ThinkingDelta(val index: Int, val text: String) : StreamEvent

    /** 思考签名增量；续写时随思考块原样回传。 */
    data class SignatureDelta(val index: Int, val signature: String) : StreamEvent

    /** 正文引用角标增量（`[citation:N]` 的来源）。 */
    data class CitationDelta(val index: Int, val citation: Citation) : StreamEvent

    /** 内容块结束。 */
    data class BlockStop(val index: Int) : StreamEvent

    /** 服务端生成的会话标题（仅首轮且请求了 `title.enabled` 时下发）。 */
    data class Title(val text: String) : StreamEvent

    /** 结束原因：`end_turn` / `max_tokens` / `tool_use` / `refusal`。 */
    data class Stopped(val reason: String?) : StreamEvent
}
