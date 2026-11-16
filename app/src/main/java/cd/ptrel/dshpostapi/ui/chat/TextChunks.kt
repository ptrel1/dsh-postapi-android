package cd.ptrel.dshpostapi.ui.chat

/**
 * 把长文本切成若干块，每块单独渲染成一个 `Text`。
 *
 * ### 为什么要切
 *
 * 单个 `Text` 的**布局高度有上限**（实测约 5 屏，见 §31）。超出的部分
 * 只是被**画出来**，命中测试那里没有节点 —— 表现是"气泡上半能点、
 * 下半点不动"，长按同样没反应。
 *
 * 而 **AI 回复从来没有这个问题**：Markdown 渲染器本来就把它拆成很多个
 * 短 `Text`（一段一个）。同样的字数，拆开就没事 —— 所以这不是
 * "内容太长"的必然代价，是渲染方式的问题。
 *
 * ### 切法
 *
 * **保证每个字符都留在原地**：切出来的块都是原文的子串，不增不删。
 * 不然在块之间渲染时会凭空多出/少掉空行。
 *
 * - 优先在**换行处**断开（往回找最近的一个 `\n`）
 * - 一行本身就超过上限时**硬切** —— 用户可能粘进一整个几十万字、
 *   中间没有任何换行的段落
 *
 * 块之间**不加间距**（渲染时套一个无间距的 `Column`），否则会凭空
 * 多出一堆空行。
 */
internal fun textChunks(text: String, maxChars: Int = TEXT_CHUNK_MAX_CHARS): List<String> {
    if (text.isEmpty()) return emptyList()
    if (text.length <= maxChars) return listOf(text)

    val out = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = minOf(start + maxChars, text.length)
        if (end < text.length) {
            // 尽量在换行处断开：往回找最近的一个 '\n'（找不到就硬切）
            val newline = text.lastIndexOf('\n', end - 1)
            if (newline > start) end = newline + 1
        }
        out += text.substring(start, end)
        start = end
    }
    return out
}

/**
 * 单块的字数上限。
 *
 * 实测单个 `Text` 大约到 **5 屏**（≈1.2 万像素、约 270 行）才开始点不动，
 * 这里取 2000 字 —— 离那个上限有**一个数量级**的余量，同时块数不会多到
 * 影响排版性能（20 万字 = 100 块，仍是可接受的数量级）。
 */
internal const val TEXT_CHUNK_MAX_CHARS = 2000
