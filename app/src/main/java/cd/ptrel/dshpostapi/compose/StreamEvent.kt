package cd.ptrel.dshpostapi.compose

/**
 * 流式事件抽象（v0.9.0）。
 *
 * ## 为什么要有这一层
 *
 * UI 只依赖本接口，不接触 SSE 细节。换后端（DSH 插件 API ↔ 别的实现）时
 * 只需新增一个 Flow<StreamEvent> 实现，UI 层零改动。
 *
 * 这个分层参考了 SimpleChat（MIT）的 net/StreamEvent.kt ——
 * 它证明了这个抽象足够撑起完整聊天界面（Markdown、思考区、工具时间线都在其上）。
 * 我们的实现与它的**字段不完全一样**：DSH 服务端没有 usage 统计、
 * 但有 tool 事件（工具调用过程），所以多了 [Tool]。
 */
sealed interface StreamEvent {

    /** 正文增量。 */
    data class Content(val text: String) : StreamEvent

    /** 思维链增量（DSH 的 llm/stream 里 reasoning 段）。 */
    data class Reasoning(val text: String) : StreamEvent

    /**
     * 服务端分配/确认了会话 id。
     *
     * 新会话第一次发消息时服务端才会给出 id，客户端要记下来供后续轮次复用 ——
     * 否则每一轮都会被当成新会话，历史就断成一段一段。
     */
    data class Started(val sessionId: String) : StreamEvent

    /**
     * 工具调用（v0.9.0 新增，SimpleChat 没有这个概念）。
     *
     * DSH 的 /chat/stream 会在模型调用工具时发 `type:"tool"` 帧，带工具名与参数增量。
     * UI 把它显示成一行「正在使用 X」，让用户知道模型不是卡住了 ——
     * 长任务里没有这个反馈，用户会以为死了。
     *
     * @param name 工具名（可能为空 —— 服务端在某些帧里只给 id）
     * @param argsDelta 参数增量（可能为空）
     */
    data class Tool(val name: String, val argsDelta: String) : StreamEvent

    /** 本轮正常/被取消结束。 */
    data class Done(val cancelled: Boolean) : StreamEvent

    /** 失败。[code] 为 HTTP 状态码，网络异常时为 null。 */
    data class Error(val message: String, val code: Int? = null) : StreamEvent
}
