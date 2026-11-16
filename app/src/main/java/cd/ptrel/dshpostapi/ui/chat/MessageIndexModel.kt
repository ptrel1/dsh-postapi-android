package cd.ptrel.dshpostapi.ui.chat

import cd.ptrel.dshpostapi.R
import cd.ptrel.dshpostapi.data.Res

/**
 * 消息索引（§34）的一条。
 *
 * [index] 是这条在 `vm.messages`（当前分支路径）里的下标 —— 跳转直接拿它用，
 * 不用再按 id 查一遍。
 */
data class MessageIndexEntry(
    val id: String,
    val index: Int,
    val preview: String,
)

/**
 * 预览占位的四句话。
 *
 * **做成 lambda 是为了让下面的纯函数不碰 Android Resources**：默认实现只在
 * 真被调用时才走 [Res]（随界面语言），单测则把中文直接塞进来 —— 两边都不牺牲。
 */
class MessageIndexLabels(
    val empty: () -> String = { Res.get(R.string.msg_index_empty) },
    val attachments: (Int) -> String = { count -> Res.get(R.string.msg_index_attachments, count) },
    val image: () -> String = { Res.get(R.string.msg_index_image) },
    val file: (String) -> String = { name -> Res.get(R.string.msg_index_file, name) },
)

/**
 * 建索引：**只列用户消息**（§34.3）。
 *
 * 用户说的话是"章节标记"，AI 回复是"展开"；长会话里想找的是"我当初是怎么说的"。
 * 顺带把预览文本**在这里算完** —— 索引是快照，别在组合里现算（每次重组跑一遍
 * `take(N)` 是白烧）。
 */
internal fun buildMessageIndexEntries(
    messages: List<UiMessage>,
    labels: MessageIndexLabels = MessageIndexLabels(),
): List<MessageIndexEntry> =
    messages.mapIndexedNotNull { index, message ->
        if (message.role != MessageRole.USER) return@mapIndexedNotNull null
        MessageIndexEntry(
            id = message.id,
            index = index,
            preview = messageIndexPreview(message, labels),
        )
    }

/**
 * 索引行的预览文本（§34.3）。**只管内容，不管截断** —— 一行收尾交给 UI 的
 * `maxLines = 1` + 省略号。
 *
 * 有正文一律优先正文；纯附件、没有正文的用户消息按附件占位 —— 这种消息
 * `content` 是空的，不能在索引里留一行空白（占位文案见 [MessageIndexLabels]）：
 *
 * | 情形 | 显示 |
 * |---|---|
 * | 一张图片 | `（图片）` |
 * | 多个附件 | `（附件 3）` |
 * | 单个文件 | `（文件·设定.md）` |
 */
internal fun messageIndexPreview(
    message: UiMessage,
    labels: MessageIndexLabels = MessageIndexLabels(),
): String {
    val text = message.content.trim()
    if (text.isNotEmpty()) return text

    val attachments = message.attachments
    return when {
        attachments.isEmpty() -> labels.empty()
        attachments.size > 1 -> labels.attachments(attachments.size)
        attachments.first().isImage -> labels.image()
        else -> labels.file(attachments.first().name)
    }
}
