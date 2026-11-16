package cd.ptrel.dshpostapi.ui.markdown

import androidx.compose.foundation.horizontalScroll
import cd.ptrel.dshpostapi.ui.chat.preferHorizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import com.mikepenz.markdown.compose.elements.material.MarkdownBasicText
import cd.ptrel.dshpostapi.ui.theme.LocalChatColors
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.CodeHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes

/**
 * 代码块的正文渲染 —— 语法高亮按**官方那套**做增量。
 *
 * ### 官方怎么做
 *
 * 它的高亮器返回**已稳定高亮的前缀长度**，只对后缀重算
 * （反编译报告 §2.4：`MarkdownCodeBlockContent.kt:95` 的
 * `code.substring(jq4Var.b.length())`）。流式时每帧只有尾部在动，
 * 于是每帧的活只剩"最后那几行"。
 *
 * ### 这里怎么做
 *
 * snipme 的高亮器**自己就带这个机制** —— `Highlights` 持有上一份
 * `CodeSnapshot`，`getHighlights()` 走 `CodeAnalyzer.analyze(code, language, snapshot)`，
 * 内部用 `CodeComparator` 对差异做增量。所以只要**复用同一个实例**
 * （而不是每次 `Builder().build()` 一个新的），增量就自动成立 —— 见
 * [IncrementalHighlight]。
 *
 * 另外两处是顺带修的，都对着同一份报告：
 *
 * 1. **收起态只渲染看得见的行。**
 *    库的 `heightIn(max = …)` 是**裁切**不是跳过：几千行的代码块为了显示十几行，
 *    照样把整篇文字排一遍。收起时只喂前 [CollapsedRenderLines] 行 ——
 *    排版量从 O(全部) 降到 O(可见)。这是代码块最大的一笔开销。
 * 2. **不做"先纯文本再高亮"的两段渲染。**
 *    库的 `produceState(initialValue = AnnotatedString(text = code))` 是异步高亮，
 *    结果没回来之前先显示纯文本。这里在组合期同步算（增量之后本来就只剩尾部），
 *    同一份内容连结果对象都是同一个，省掉一次全篇重排。
 *
 * ⚠️ 视觉结构与库的 `MarkdownHighlightedCode` **逐行一致**（同一个
 * [MarkdownCodeBackground] + 同一个 [MarkdownBasicText] + 同一套 locals +
 * 同一个 `codeBlockPadding`），换掉的只是"高亮结果从哪来"。
 *
 * @param code 已由库解析并 `replaceIndent()` 过的围栏正文。
 * @param style 库传下来的代码样式（`typography.code`）。
 * @param truncated 收起态：只渲染前若干行。
 */
@Composable
internal fun CodeFenceText(
    code: String,
    language: String?,
    style: TextStyle,
    truncated: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMarkdownColors.current
    val cornerSize = LocalMarkdownDimens.current.codeBackgroundCornerSize
    val blockPadding = LocalMarkdownPadding.current.codeBlock

    // 收起态只留看得见的部分；截断点落在行边界上，不会切出半行
    val shown = remember(code, truncated) {
        if (truncated) code.truncatedToLines(CollapsedRenderLines) else code
    }

    /*
     * 增量高亮。缓存对象活在 `remember` 里、只在主线程（组合期）改 ——
     * 与组合同线程，不需要任何同步。
     */
    val dark = isSystemInDarkTheme()
    val highlighter = remember { IncrementalHighlight() }
    val highlighted = remember(shown, language, dark) { highlighter.of(shown, language, dark) }

    /*
     * 代码的**自然宽度**（最长一行）—— 横滚的可滚距离全靠它。
     *
     * `Text` 报给布局的宽度是"约束宽度"，不是文字实际宽度，于是
     * `horizontalScroll` 算出来的滚距恒为 0、横滚形同虚设（实测长行被切在右边界，
     * 划它零位移）。`width(IntrinsicSize.Max)` 与 `requiredWidth(IntrinsicSize.Max)`
     * 都试过，**同样无效** —— 固有宽度这条路走不通，所以自己量。
     *
     * 取各行右边界的最大值，而不是 `TextLayoutResult.size.width` —— 后者同样是约束宽度。
     * `softWrap = false` 保证一行源码就是一行排版，不会中途折行干扰测量。
     */
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val codeWidth = remember(shown, style) {
        val measured = textMeasurer.measure(
            text = AnnotatedString(shown),
            style = style,
            /*
             * ⚠️ 约束不能给到 `Int.MAX_VALUE` 附近 —— `MultiParagraph` 会按这个宽度
             * 建段落布局，超宽直接把主线程算死（实测 ANR、进程被杀）。
             * 16384px ≈ 5460dp，比任何一行代码都宽得多，够用。
             */
            constraints = Constraints(maxWidth = 16384),
            softWrap = false,
        )
        val natural = (0 until measured.lineCount).maxOfOrNull { measured.getLineRight(it) } ?: 0f
        with(density) { natural.coerceAtMost(16384f).toDp() }
    }

    // 行号栏：只编一次号，行数变了才重算
    val lines = remember(shown) { shown.count { it == '\n' } + 1 }
    val gutter = remember(lines) { (1..lines).joinToString("\n") }

    MarkdownCodeBackground(
        color = colors.codeBackground,
        shape = RoundedCornerShape(cornerSize),
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        showHeader = false,
        language = language,
        code = shown,
    ) {
        Row(modifier = Modifier.padding(blockPadding)) {
            /*
             * 行号栏。三件事是刻意的：
             *
             * 1. **一个 Text 装下所有行号**（`"1\n2\n3…"`），而不是每行一个 composable ——
             *    既省组合，行高又天然等于"一行"；几百行也只是两个 Text 节点。
             * 2. 放在横向滚动**外面** —— 代码左右滚动时行号不动，始终看得见。
             * 3. 和代码用**同一个** `MarkdownBasicText` —— 连 font padding 都一致，
             *    否则第一行基线就会差一点，越往下越明显。
             */
            if (lines > 1) {
                MarkdownBasicText(
                    text = AnnotatedString(gutter),
                    style = style,
                    color = LocalChatColors.current.placeholder,
                    textAlign = TextAlign.End,
                    modifier = Modifier.padding(end = GutterGap),
                )
            }
            Box(
                modifier = Modifier
                    // 横滚优先于消息索引的左滑（§34.4）
                    .preferHorizontalScroll()
                    .horizontalScroll(rememberScrollState()),
            ) {
                MarkdownBasicText(
                    text = highlighted,
                    style = style,
                    modifier = Modifier.requiredWidth(codeWidth),
                )
            }
        }
    }
}

/**
 * 收起时最多渲染多少行。
 *
 * 折叠高度是 300dp（约 12 行等宽文字），这里给四倍余量 ——
 * 字体放大到极限也盖得住，同时把排版量钉在上限内。
 */
private const val CollapsedRenderLines = 60

/** 行号栏与代码之间的间距。 */
private val GutterGap = 12.dp

/** 只保留前 [n] 行（截在行尾换行之后）。行数不足则原样返回。 */
internal fun String.truncatedToLines(n: Int): String {
    var index = 0
    var line = 0
    while (line < n) {
        val next = indexOf('\n', index)
        if (next < 0) return this
        index = next + 1
        line++
    }
    return substring(0, index)
}

/** 一条已定位的高亮。用自有的类型，便于单测直接比对。 */
internal data class HighlightSpan(val start: Int, val end: Int, val style: SpanStyle)

/**
 * 增量高亮。
 *
 * ### 为什么不用库自己的快照机制
 *
 * `Highlights` 内部确实有增量（`CodeAnalyzer.analyze(code, language, snapshot)` +
 * `CodeComparator`），但它的 `code` 在 Kotlin 侧是 **private**、语言与主题是
 * **只读** —— 每帧只能 `Builder().build()` 一个新实例（库自己就是这么用的），
 * 新实例的 snapshot 是空的，等于整段重算。这也正是报告里那句
 * "mikepenz 不给增量接口"的由来。所以边界自己划。
 *
 * ### 稳定前缀怎么划
 *
 * 新代码若是旧代码的**纯追加**（流式就是这个形态），则除**最后一行**以外的
 * 高亮全部复用 —— 最后一行的 token 可能延伸到新追加的内容里，必须重算。
 *
 * 代码以换行结尾时最后一行是空的，等价于"整段都稳定"，正好是流式里最常见的
 * 时刻（刚换行）。
 *
 * ### 为什么不用库的 `CodeHighlight`
 *
 * 它只给了读取用的字段，没有带偏移的构造入口。转成自有的 [HighlightSpan]
 * （顺手把偏移补上）之后，"复用"就只是列表拼接。
 */
internal class IncrementalHighlight {

    private var code: String = ""
    private var language: String? = null
    private var dark: Boolean = false
    private var spans: List<HighlightSpan> = emptyList()

    private var builder: Highlights.Builder? = null
    private var builderLanguage: String? = null
    private var builderDark: Boolean? = null

    /** 取 [code] 的高亮结果。同一份内容重复调用不重算。 */
    fun of(code: String, language: String?, dark: Boolean): AnnotatedString {
        val spans = spansFor(code, language, dark)
        return buildAnnotatedString {
            append(code)
            spans.forEach { addStyle(it.style, it.start, it.end) }
        }
    }

    /** 拆出来是为了单测能直接比对"增量结果 vs 整段重算"。 */
    internal fun spansFor(code: String, language: String?, dark: Boolean): List<HighlightSpan> {
        if (code == this.code && language == this.language && dark == this.dark) return spans

        spans = when {
            // 不是纯追加（换版本 / 换语言 / 换主题 / 编辑）→ 整段重算
            language != this.language || dark != this.dark || !code.startsWith(this.code) ->
                compute(code, language, dark, 0)

            else -> {
                // 稳定前缀 = 上一版的**最后一行之前**；最后一行可能还在延伸，一起重算
                val from = this.code.lastIndexOf('\n') + 1
                spans.filter { it.end <= from } + compute(code, language, dark, from)
            }
        }
            /*
             * 按起点排序。
             *
             * 库返回的顺序是**定位器的顺序**（标点、数字、关键字、字符串…），
             * 而增量结果是"前缀片段 + 后缀片段"，两者顺序天然不同。
             * 高亮片段互不重叠（前缀的 `end <= from`，后缀从 `from` 起），
             * 所以顺序**不影响渲染**；排一下是为了结果确定、也便于单测逐条比对。
             */
            .sortedBy { it.start }
        this.code = code
        this.language = language
        this.dark = dark
        return spans
    }

    /** 高亮 [code] 从 [from] 起的后半段，并把偏移补回去。 */
    private fun compute(code: String, language: String?, dark: Boolean, from: Int): List<HighlightSpan> {
        if (from >= code.length) return emptyList()
        val syntaxLanguage = language?.let { SyntaxLanguage.getByName(it) }
        val raw = builderFor(language, dark)
            .code(code.substring(from))
            .let { if (syntaxLanguage != null) it.language(syntaxLanguage) else it }
            .build()
            .getHighlights()
        return raw.map { it.toSpan(from) }
    }

    /** 主题与语言只影响配色/分词，按需重建一次并缓存。 */
    private fun builderFor(language: String?, dark: Boolean): Highlights.Builder {
        val existing = builder
        if (existing != null && builderLanguage == language && builderDark == dark) return existing
        return Highlights.Builder()
            .theme(SyntaxThemes.default(darkMode = dark))
            .also {
                builder = it
                builderLanguage = language
                builderDark = dark
            }
    }

    private fun CodeHighlight.toSpan(offset: Int) = HighlightSpan(
        start = location.start + offset,
        end = location.end + offset,
        style = when (this) {
            /*
             * ⚠️ **必须补 alpha。**
             *
             * 库给的 `rgb` 高位是 0，`Color(rgb)` 解出来是**全透明**的 ——
             * 高亮的字符会直接看不见。实测：CSS 块里 `{` `:` `;` `}` 整批消失，
             * 只剩下没被高亮的标识符（库自己写的是 `Color(it.rgb).copy(alpha = 1f)`）。
             */
            is ColorHighlight -> SpanStyle(color = Color(rgb).copy(alpha = 1f))
            is BoldHighlight -> SpanStyle(fontWeight = FontWeight.Bold)
        },
    )
}
