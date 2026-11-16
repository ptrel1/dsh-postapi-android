package cd.ptrel.dshpostapi.ui.chat

/**
 * 「继续生成」（续写）的**准入规则** —— 与官方 Chat Demo 的 `canContinueReply` 等价。
 *
 * 纯函数，有单测（`ContinuePolicyTest`）。判错的两种代价都很明显：
 * 放行不该续的回复 → 服务端拒绝或接出一段风马牛不相及的内容；
 * 拦下该续的 → 用户明明看到它被截断了，却没有按钮可点。
 *
 * ### 为什么只认扩展通道
 * 续写的协议形态是「末尾再带一条 assistant」：Anthropic 兼容协议天然支持，
 * OpenAI 兼容协议需要走 `/beta` 端点并给最后一条 assistant 打 `prefix: true`，
 * 而那个端点不在本项目配置的 Base URL 上。与其把"半能用"的按钮摆出来，
 * 不如只在真能用的通道上出现（`timeline` 是否为空就是通道标志）。
 *
 * ### 四条否决
 * 1. 不是**活跃路径的最后一条** —— 续写接在末尾，中间那条没地方接
 * 2. 正在流式 / 不在可见终态 —— 并发写同一条会打架
 * 3. 没有时间线（OpenAI 通道或老数据）
 * 4. 用过服务端工具 —— 官方明确这类回复不能续写（工具块要一起回传才对得上）
 *
 * 刻意**不看 `stop_reason`**：那是服务端"为什么停"的记录，不是客户端该不该放行的判据。
 * 早先按 `refusal` 否决过，等于客户端替服务端执行内容裁量 —— 已经不做了。
 */
internal fun canContinueReply(message: UiMessage, isLastOnPath: Boolean): Boolean {
    if (!isLastOnPath) return false
    if (message.role != MessageRole.ASSISTANT) return false
    if (message.streaming) return false
    if (message.timeline.isEmpty()) return false
    if (message.timeline.any { it is cd.ptrel.dshpostapi.net.ReplyBlock.ToolUse }) return false
    if (message.timeline.any { it is cd.ptrel.dshpostapi.net.ReplyBlock.SearchResult }) return false
    if (message.timeline.any { it is cd.ptrel.dshpostapi.net.ReplyBlock.FetchResult }) return false
    return when (message.status) {
        // 用户点过「停止」：正想接着写
        MessageStatus.STOPPED -> true
        // 输出被上限截断：没写完，但也没人主动停
        MessageStatus.DONE -> message.stopReason == ReplyMaxTokens
        else -> false
    }
}


/** 输出被上限截断的结束原因字面值。 */
internal const val ReplyMaxTokens = "max_tokens"
