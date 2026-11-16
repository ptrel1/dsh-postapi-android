package cd.ptrel.dshpostapi.ui.chat

import cd.ptrel.dshpostapi.data.Attachment
import cd.ptrel.dshpostapi.data.TokenEstimate

/*
 * 移植自 SimpleChat 的 ui/chat/RequestContent.kt。
 *
 * 原文件里还有 userContent() / ImageDetail —— 它们依赖 net/MessageContent 与
 * net/ImageInput，本项目尚未移植，且当前没有任何调用点，故整段不带过来。
 * 这里只留 MessageList 的 token 账需要的两部分。
 *
 * 将来要接真实请求体时，把原文件的 userContent() 连同上边两个 net 类型一起补回来。
 */

/**
 * 附件折算的 token 估算，喂给上下文占用显示。
 *
 * - 文本附件：按正文一样的规则估
 * - 图片：按官方给的**上限 1024/张** 记。实际通常低于这个数，
 *   但占用显示宁可高估 —— 它要提示的是"快满了"，低估会让人措手不及。
 */
fun attachmentTokenEstimate(attachments: List<Attachment>): Int {
    if (attachments.isEmpty()) return 0
    return attachments.sumOf { attachment ->
        if (attachment.isImage) ImageTokenUpperBound
        else TokenEstimate.of(attachment.data)
    }
}

/** 官方 Vision 指南给出的单图 token 上限。 */
const val ImageTokenUpperBound = 1024
