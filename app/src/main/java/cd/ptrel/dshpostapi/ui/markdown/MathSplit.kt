package cd.ptrel.dshpostapi.ui.markdown

/**
 * 正文里的一段内容：普通 Markdown，或一段数学公式。
 *
 * 切分是**纯函数**（[splitMath]），有单测（`MathSplitTest`）——
 * 公式切错的表现是"整段正文少了一截"或者"代码块里突然冒出个公式"，
 * 从界面上几乎不可能定位。
 */
sealed interface ContentSegment {

    data class Text(val text: String) : ContentSegment

    /** [display] = true 表示独占一行的行间公式（`$$…$$` / `\[…\]`）。 */
    data class Formula(val tex: String, val display: Boolean) : ContentSegment
}

/**
 * 把一段 Markdown 切成「文本 / 公式」交替的片段。
 *
 * ### 支持的定界符
 * | 写法 | 形态 |
 * |---|---|
 * | `$$…$$` | 行间（独占一行、居中） |
 * | `\[…\]` | 行间 |
 * | `$…$` | 行内 |
 * | `\(…\)` | 行内 |
 *
 * ### 三条刻意的规则
 * 1. **代码里的 `$` 不算公式**：围栏代码块与行内代码整段跳过 —— 否则一段 shell
 *    脚本里的 `$PATH` 会把后面整篇正文吞掉。
 * 2. **定界符内侧不能是空白**：这条把 `$5 和 $10` 这类金额挡在外面
 *    （开引号后面紧跟数字、闭引号前面是空格 → 不成对）。
 * 3. **没闭合就按普通文本**：流式期间半截公式会先以原文显示，闭合后才变成公式。
 *    宁可晚一拍，也不要在屏幕上闪一个渲染失败的红框。
 */
internal fun splitMath(content: String): List<ContentSegment> {
    if (content.isEmpty()) return emptyList()
    // 没有任何定界符：快路径，原样返回（绝大多数消息走到这里）
    if (!content.contains('$') && !content.contains("\\[") && !content.contains("\\(")) {
        return listOf(ContentSegment.Text(content))
    }

    val out = mutableListOf<ContentSegment>()
    val buffer = StringBuilder()
    var i = 0

    fun flushText() {
        if (buffer.isNotEmpty()) {
            out += ContentSegment.Text(buffer.toString())
            buffer.clear()
        }
    }

    while (i < content.length) {
        // ── 围栏代码块：整块跳过，里面的 $ 不算公式 ──
        if (content.startsWith("```", i) || content.startsWith("~~~", i)) {
            val marker = content.substring(i, i + 3)
            val end = content.indexOf(marker, i + 3)
            val stop = if (end < 0) content.length else end + 3
            buffer.append(content, i, stop)
            i = stop
            continue
        }

        // ── 行内代码：同样跳过 ──
        if (content[i] == '`') {
            val end = content.indexOf('`', i + 1)
            val stop = if (end < 0) i + 1 else end + 1
            buffer.append(content, i, stop)
            i = stop
            continue
        }

        // ── 行间公式 ──
        if (content.startsWith("$$", i)) {
            val end = content.indexOf("$$", i + 2)
            if (end > i + 2) {
                val tex = content.substring(i + 2, end)
                if (tex.isNotBlank()) {
                    flushText()
                    out += ContentSegment.Formula(tex.trim(), display = true)
                    i = end + 2
                    continue
                }
            }
        }
        if (content.startsWith("\\[", i)) {
            val end = content.indexOf("\\]", i + 2)
            if (end > i + 2) {
                val tex = content.substring(i + 2, end)
                if (tex.isNotBlank()) {
                    flushText()
                    out += ContentSegment.Formula(tex.trim(), display = true)
                    i = end + 2
                    continue
                }
            }
        }

        // ── 行内公式 ──
        if (content.startsWith("\\(", i)) {
            val end = content.indexOf("\\)", i + 2)
            if (end > i + 2) {
                val tex = content.substring(i + 2, end)
                if (tex.isNotBlank()) {
                    flushText()
                    out += ContentSegment.Formula(tex.trim(), display = false)
                    i = end + 2
                    continue
                }
            }
        }
        if (content[i] == '$') {
            val close = inlineClose(content, i)
            if (close > 0) {
                flushText()
                out += ContentSegment.Formula(
                    content.substring(i + 1, close).trim(),
                    display = false,
                )
                i = close + 1
                continue
            }
        }

        buffer.append(content[i])
        i++
    }
    flushText()
    return out
}

/**
 * 找行内公式的闭合 `$`，找不到返回 -1。
 *
 * 规则：开引号后面不能是空白（排除 "$ 5"），闭引号前面不能是空白（排除 "$5 和 $10"），
 * 中间不能有换行（行内公式不跨行），`\$` 是**转义**的美元号、不当作闭合。
 */
private fun inlineClose(content: String, open: Int): Int {
    var i = open + 1
    if (i >= content.length) return -1
    if (content[i].isWhitespace()) return -1
    if (content[i] == '$') return -1 // "$$" 是行间公式，交给上面处理
    while (i < content.length) {
        val c = content[i]
        if (c == '\n') return -1
        if (c == '\\') {
            // 跳过被转义的那个字符（`\$` / `\\` / `\{`）
            i += 2
            continue
        }
        if (c == '$') {
            if (content[i - 1].isWhitespace()) return -1
            return i
        }
        i++
    }
    return -1
}
