package cd.ptrel.dshpostapi.ui.markdown

/**
 * 把整篇 Markdown 切成「可独立渲染的块」。
 *
 * ### 为什么需要它
 *
 * 流式渲染的瓶颈不在"多久提交一次"，而在**每次提交都重解析整篇**。
 * `Markdown(content = 全文)` 只要有一个字符变了，整篇的 AST 就要重来一遍 ——
 * 一篇五千字的回复，流式期间要整篇解析几十次，每次都伴随着全篇重新排版。
 * 掉帧和"抽一下"都是从这里来的。
 *
 * 切成块之后：**已完成的块文本不再变化**，Compose 按 `key` 跳过它们的重组，
 * 解析器也就不会重解析；只有最后一块随 token 变。解析量从 O(全文) 降到 O(尾块)。
 *
 * 这本质上是**近似**"元素级渲染"：理想做法是按 Markdown 元素类型逐个渲染
 * （标题/段落/代码/表格各一个 composable）。我们改不了第三方库的解析器，
 * 但可以改**喂给它的粒度** —— 一块一次 `Markdown()`，效果同源。
 *
 * ### 与 [normalizeSoftBreaks] 的分工
 *
 * 先 normalize（把裸换行补成空行），再按**空行**切块。两者规则必须一致：
 * normalize 认为"不能插空行"的地方（围栏、表格、缩进续行），
 * 切块时也必须不切，否则同一段内容会被两种规则撕成不同的形状。
 */

/**
 * 一个可独立渲染的 Markdown 块。
 *
 * - [start]：在（已 normalize 的）原文中的起始下标。同时用作稳定 key ——
 *   追加内容时前面各块的 start 不变，Compose 就能跳过它们的重组与重解析。
 * - [text]：原文**子串**（合并同族块时也是原文连续区间），不增不删。
 *
 * 「原文子串、不增不删」这条很要紧：块间距交给外层 Column 给，
 * 不靠 markdown 的块内 padding，所以块文本必须原样。
 */
internal data class MarkdownBlock(
    val start: Int,
    val text: String,
) {
    /** 稳定 key：起始下标在追加时不变。 */
    val key: String get() = "mdblk@$start"
}

/**
 * 切块规则：
 *
 * 1. **空行**是块边界 —— 前提是**不在围栏代码块内部**。
 * 2. 围栏（``` / ~~~）内部**绝不切**：未闭合的围栏会让"剩余全部"成为一个块，
 *    这正是流式中未闭合代码块该有的行为（半截代码不会被拆成正文）。
 * 3. 相邻的**同族块**（列表↔列表、表格行↔表格行）会合并回一个块，
 *    避免把 `- a\n\n- b` 这种「松列表」拆成两个各自从 1 开始的列表。
 *
 * 复杂度 O(n)，n = 字符数。数千字在几十微秒级，扛得住每秒十几次的提交。
 */
internal fun splitMarkdownBlocks(source: String): List<MarkdownBlock> {
    if (source.isEmpty()) return emptyList()

    val lines = source.split('\n')
    // 每行的起始 offset，用于把「行区间」映射回「字符区间」。
    val lineStart = IntArray(lines.size)
    run {
        var acc = 0
        for (i in lines.indices) {
            lineStart[i] = acc
            acc += lines[i].length + 1
        }
    }

    /** 把行区间 [fromLine, toLineExclusive) 映射回原文子串；全空白则丢弃。 */
    fun substringOf(fromLine: Int, toLineExclusive: Int): String? {
        if (toLineExclusive <= fromLine) return null
        val start = lineStart[fromLine]
        val end =
            if (toLineExclusive >= lines.size) source.length
            else lineStart[toLineExclusive] - 1 // 去掉行尾的 '\n'
        val text = source.substring(start, end)
        return text.ifBlank { null }
    }

    val blocks = ArrayList<MarkdownBlock>()
    var fence: String? = null
    var blockStartLine = 0

    for (i in lines.indices) {
        val trimmed = lines[i].trimStart()
        val marker = fenceMarkerOf(trimmed)
        if (marker != null) {
            when {
                fence == null -> fence = marker
                marker == fence -> fence = null
                // 记号对不上（``` 里写 ~~~）：当代码内容，状态不动
            }
        }
        if (fence != null) continue          // 围栏内部不切
        if (lines[i].isBlank()) {
            substringOf(blockStartLine, i)?.let { blocks += MarkdownBlock(lineStart[blockStartLine], it) }
            blockStartLine = i + 1
        }
    }
    substringOf(blockStartLine, lines.size)?.let { blocks += MarkdownBlock(lineStart[blockStartLine], it) }

    return mergeSameFamily(blocks, source)
}

/**
 * 合并相邻同族块（松列表 / 表格）。
 *
 * 合并后的文本取**原文连续区间** `source[prev.start, cur.end)`，因此仍然不增不删。
 * 中间的空白行会留在块文本里，渲染出来就是"松列表"的样子 —— 与切块前一致。
 */
private fun mergeSameFamily(blocks: List<MarkdownBlock>, source: String): List<MarkdownBlock> {
    if (blocks.size < 2) return blocks
    val out = ArrayList<MarkdownBlock>(blocks.size)
    for (b in blocks) {
        val prev = out.lastOrNull()
        if (prev != null && sameFamily(prev.text, b.text)) {
            val end = (b.start + b.text.length).coerceAtMost(source.length)
            out[out.size - 1] = MarkdownBlock(prev.start, source.substring(prev.start, end))
        } else {
            out += b
        }
    }
    return out
}

private fun sameFamily(a: String, b: String): Boolean {
    val fa = firstMeaningfulLine(a)
    val fb = firstMeaningfulLine(b)
    return (isListMarker(fa) && isListMarker(fb)) || (isTableRow(fa) && isTableRow(fb))
}

private fun firstMeaningfulLine(text: String): String =
    text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trimStart()

private fun isListMarker(trimmedLine: String): Boolean {
    if (trimmedLine.isEmpty()) return false
    // 无序：- * +
    if (trimmedLine.length >= 2 && trimmedLine[0] in "-*+" && trimmedLine[1] == ' ') return true
    // 有序：1. 12) 等
    val dot = trimmedLine.indexOfFirst { it == '.' || it == ')' }
    return dot in 1..9 && trimmedLine.take(dot).all { it.isDigit() }
}

private fun isTableRow(trimmedLine: String): Boolean = trimmedLine.startsWith("|")
