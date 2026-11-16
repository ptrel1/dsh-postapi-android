package cd.ptrel.dshpostapi.ui.markdown

/**
 * 把「裸的单个换行」补成空行 —— 即当作**段落边界**。
 *
 * ### 为什么需要它
 *
 * markdown 语法里单个换行是"软换行"，渲染时会被合并成一个空格。
 * 而**中文创作里单换行常常就是分段**，模型也经常这么输出。
 * 不处理的话，几个自然段会糊成一整块 —— 长文完全没法读。
 *
 * ### 为什么不是"单换行一律当分段"
 *
 * 实测模型（DeepSeek）是**懂 markdown** 的，它自己在用两套写法：
 *
 * ```
 * 第一段。<LF><LF>                     ← 空行 = 段落分隔
 * 句子 A。  <LF>                        ← 行尾两个空格 = 段内换行（markdown 硬换行）
 * 句子 B。<LF><LF>
 * ```
 *
 * 所以规则要看**行尾**：行尾带着硬换行标记的，是模型**故意**的段内换行，
 * 动了就把人家好好的一段拆成两段；行尾干干净净的单个换行，
 * 才是"这里该分段但 markdown 会吃掉"。
 *
 * ### 七类地方不能动
 *
 * 1. 围栏代码块内部（把代码拆了）
 * 2. 表格行（表头与分隔行必须紧挨着，插空行表格就散了）
 * 3. 本来就是空行边界（不需要重复补）
 * 4. 行尾有硬换行标记（见上）
 * 5. 缩进续行（本行或下一行以空白开头 —— 列表的续行、缩进代码块）
 * 6. **行尾是句中标点**（`，、；：` 等）—— 句子还没结束，下一行是同一段的续行。
 *    不加这一条，一个被换行截断的长句会被拆成两段，段落比原文碎。
 *    但下一行开了新块（围栏 / 表格 / 列表 / 标题 / 引用）时仍要分。
 * 7. **相邻的引用行**（`>`）—— 它们是同一个引用块；拆开后每段各带一条竖线，
 *    GFM 提示块（`> [!NOTE]` + 正文）的标题与正文更会各成一块。
 *
 * 抽成纯函数是为了能单测：五条规则漏一条，表现都是"某一类内容渲染错乱"，
 * 而错乱的种类太多，光靠肉眼看长文根本对不出来。
 */
internal fun normalizeSoftBreaks(markdown: String): String {
    val lines = markdown.lines()
    val out = ArrayList<String>(lines.size * 2)

    /** 当前开着的围栏记号；null = 不在代码块里。 */
    var fenceMarker: String? = null

    for ((index, line) in lines.withIndex()) {
        val marker = fenceMarkerOf(line.trimStart())

        if (marker != null) {
            when {
                fenceMarker == null -> fenceMarker = marker      // 开
                marker == fenceMarker -> fenceMarker = null      // 合
                // 记号对不上（``` 里写的 ~~~）：当代码内容，状态不动
            }
        }

        out += line

        // 开围栏那一行、以及围栏内部的每一行：都不插空行
        if (fenceMarker != null) continue

        val next = lines.getOrNull(index + 1) ?: continue

        val keep = line.isBlank() ||                                   // 3
            next.isBlank() ||                                          // 3
            endsWithHardBreak(line) ||                                 // 4
            endsWithContinuation(line) && !startsNewBlock(next) ||      // 6
            isTableRow(line) ||                                        // 2
            isQuoteLine(line) && isQuoteLine(next) ||                  // 7
            line.firstOrNull()?.isWhitespace() == true ||              // 5
            next.firstOrNull()?.isWhitespace() == true                 // 5
        if (keep) continue

        out += ""
    }
    return out.joinToString("\n")
}

/** 围栏记号：``` 或 ~~~。与 `HtmlNormalize.kt` 共用。 */
internal fun fenceMarkerOf(trimmed: String): String? = when {
    trimmed.startsWith("```") -> "```"
    trimmed.startsWith("~~~") -> "~~~"
    else -> null
}

/** markdown 的两种硬换行写法：行尾两个及以上的空格，或行尾一个反斜杠。 */
private fun endsWithHardBreak(line: String): Boolean =
    line.endsWith("  ") || line.endsWith("\\")

/**
 * 句中收尾：行尾是这些标点，说明句子**还没结束**，下一行是同一段的续行。
 *
 * 不看"整行有没有标点"，只看**最后一个非空白字符** ——
 * 中文写作里一行一短句，长句被换行截断时行尾就是逗号/顿号。
 */
private fun endsWithContinuation(line: String): Boolean {
    val last = line.lastOrNull { !it.isWhitespace() } ?: return false
    return last in CONTINUATION_PUNCTUATION
}

/** 句中标点（含成对的开引号/开括号）：出现在行尾即视为续行。 */
private const val CONTINUATION_PUNCTUATION = "，、；：,;:（(「『“‘《〈【〔"

/**
 * 下一行开了一个**块级元素**。
 *
 * 续行判断只在"下一行还是普通正文"时成立：`看代码：` 后面跟围栏、
 * `表格如下：` 后面跟表格行，都是**必须分块**的场合 —— 不分块的话
 * 代码块与表格根本不会被解析出来。
 */
private fun startsNewBlock(line: String): Boolean {
    val t = line.trimStart()
    return t.startsWith("```") || t.startsWith("~~~") ||
        t.startsWith("|") || t.startsWith("#") || t.startsWith(">") ||
        t.startsWith("---") || t.startsWith("***") ||
        LIST_MARKER.matches(t)
}

private val LIST_MARKER = Regex("""(?:[-*+]|\d+[.)])\s.*""")

/**
 * 表格行。
 *
 * 只看**本行**：相邻两个表格行之间不能插空行（表头与分隔行必须紧挨着），
 * 但正文与表格之间**要**插 —— GFM 的表格不能打断段落，
 * 紧贴着正文的表格根本不会被解析成表格。
 */
private fun isTableRow(line: String): Boolean = line.trimStart().startsWith("|")

/**
 * 引用行。
 *
 * 连续的引用行是**同一个引用块**：中间插空行会被拆成多个各自带竖线的引用。
 * GFM 提示块（`> [!NOTE]` + 正文）更是必须在一起 —— 拆开后标题与正文各成一块。
 */
private fun isQuoteLine(line: String): Boolean = line.trimStart().startsWith(">")
