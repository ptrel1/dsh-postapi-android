package cd.ptrel.dshpostapi.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

/**
 * 全局排版基准。
 *
 * **「整体都偏大 / 偏小」时唯一该改的地方。**
 *
 * 整套字号（正文、标题、标签、代码块、markdown 各级标题）都由它算出来，
 * 一起等比缩放 —— 逐个手改那二十来个数字，比例会因为四舍五入走样，
 * 下次想再调又得重来一遍。
 *
 * 0.925 = 设置里「字体大小」滑块中「较小」那一档的原值。
 * 换句话说：原来要手动滑一档才舒服，现在把它变成了默认。
 */
private const val TypeScale = 0.925f

/**
 * 一个排版样式的统一构造入口。
 *
 * 传进来的都是**基准值**（缩放前），乘完 [TypeScale] 才是实际字号 ——
 * 所以下面读到的数字就是"设计稿上的数字"，不用心算。
 */
private fun textStyle(
    size: Float,
    lineHeight: Float,
    weight: FontWeight = FontWeight.Normal,
    family: FontFamily = FontFamily.Default,
): TextStyle = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = (size * TypeScale).sp,
    lineHeight = (lineHeight * TypeScale).sp,
    letterSpacing = 0.sp,
)

/**
 * 排版规格对齐 DeepSeek 客户端（基准值，实际渲染会再乘 [TypeScale]）：
 * 正文 17sp / 行高约 1.7，标题 22-18sp Bold，分组小标签 13sp。
 */
val SimpleChatTypography = Typography(
    // 正文：AI 回复与用户气泡共用，行高宽松，长文阅读友好
    bodyLarge = textStyle(size = 17f, lineHeight = 29f),
    bodyMedium = textStyle(size = 15f, lineHeight = 24f),
    bodySmall = textStyle(size = 13f, lineHeight = 20f),
    // 标题
    titleLarge = textStyle(size = 22f, lineHeight = 30f, weight = FontWeight.Bold),
    titleMedium = textStyle(size = 17f, lineHeight = 24f, weight = FontWeight.SemiBold),
    titleSmall = textStyle(size = 15f, lineHeight = 22f, weight = FontWeight.Medium),
    // 标签：分组小标题、页脚提示
    labelMedium = textStyle(size = 13f, lineHeight = 18f),
    labelSmall = textStyle(size = 12f, lineHeight = 16f),
)

// ── Markdown 用样式 ────────────────────────────────────────
//
// 下面这几个**刻意派生自 `SimpleChatTypography`，不再各写一份相同的数值**。
//
// 先前 `MarkdownBodyStyle` 与 `bodyLarge`、`ProcessTextStyle` 与 `bodyMedium`
// 是把一样的数手抄了两遍；哪天改字号忘了同步，表现就是"流式结束的瞬间
// 字号跳一下"——用户看得见，但很难联想到是两处声明不同步。
// 派生之后，这种可能从源头消失。

/**
 * Markdown 正文 = 全局正文 + **按语种分档的对齐与行距**（多语言 §4）。
 *
 * 分档按**界面语言**：中文界面走中文档，英文界面走西文档。
 *
 * **中文档：两端对齐。** 中文没有词间空格，天然就是"一格一字"的方块排版 ——
 * 左对齐时右侧会参差，一行长一行短，长文读起来很毛躁。两端对齐把行内
 * 多余的空隙摊开，右侧就齐了。
 *
 * **西文档：左对齐。** 西文的词间空格是天然的断行点，两端对齐会把空格拉得
 * 宽窄不一，右侧齐了、词距反而更乱；左对齐（ragged right）才是西文正文的
 * 常规做法。行高也比中文紧一档 —— 29/17 ≈ 1.7 是给方块字留的呼吸，
 * 西文 1.6 上下就够，长文更紧凑。断行交给 HighQuality 的分词算法
 * （长单词的落点比 Simple 档准；**不开连字符** —— 聊天正文里断词很碍读）。
 *
 * 用派生而不是另起一份样式：字号仍然只有 `bodyLarge` 一个来源，
 * 这里**只声明刻意的差异**（对齐、行高、断行）。
 *
 * 流式阶段与定稿阶段都走这个样式，所以两阶段的对齐方式一致。
 *
 * 两份样式都是**常量**（不是 `get()`）：`.copy` 会新建 TextStyle，挂在组合里的
 * 样式每帧读一次，白白的分配没必要。[MarkdownBlockView] 每块都会读它，
 * 分配一次就该是全部。
 */
fun markdownBodyStyle(cjk: Boolean): TextStyle =
    if (cjk) MarkdownBodyCjkStyle else MarkdownBodyWesternStyle

/** 中文档正文：两端对齐。 */
private val MarkdownBodyCjkStyle: TextStyle =
    SimpleChatTypography.bodyLarge.copy(textAlign = TextAlign.Justify)

/** 西文档正文：左对齐、行高紧一档、断行走 HighQuality（长单词落点更准，仍不开连字符）。 */
private val MarkdownBodyWesternStyle: TextStyle =
    SimpleChatTypography.bodyLarge.copy(
        textAlign = TextAlign.Start,
        lineHeight = (SimpleChatTypography.bodyLarge.lineHeight.value * 0.93f).sp,
        lineBreak = LineBreak.Paragraph,
    )

/** 过程面板（思考区）文字 = 全局次级文字。 */
val ProcessTextStyle: TextStyle get() = SimpleChatTypography.bodyMedium

/**
 * Markdown 的六级标题。
 *
 * 刻意比 Material 的 `headlineLarge` 那一套**小一大截** ——
 * 聊天窗口里 32sp 的 H1 比正文还抢眼，读起来像文档而不像对话。
 * 这里让 H1 只比正文大一档。
 *
 * **六级一律 `Bold`**：层级靠字号递减表达，不再靠字重分级。
 * 早先 H1–H3 用 Bold、H4–H5 用 SemiBold、H6 用 Medium，
 * 小标题在长文里显得发灰、认不出是标题。
 */
val MarkdownHeadingStyles: List<TextStyle> = listOf(
    textStyle(size = 20f, lineHeight = 30f, weight = FontWeight.Bold),
    textStyle(size = 19f, lineHeight = 28f, weight = FontWeight.Bold),
    textStyle(size = 18f, lineHeight = 27f, weight = FontWeight.Bold),
    textStyle(size = 17f, lineHeight = 26f, weight = FontWeight.Bold),
    textStyle(size = 16f, lineHeight = 25f, weight = FontWeight.Bold),
    textStyle(size = 16f, lineHeight = 25f, weight = FontWeight.Bold),
)

/** 表格内文字：比正文小一档，否则窄屏下三列就挤爆了。 */
val MarkdownTableStyle: TextStyle = textStyle(size = 15f, lineHeight = 22f)

/**
 * 列表项目符号与序号（`bullet` / `ordered`）。
 *
 * ⚠️ 库里这两档样式**只作用于符号本身**，不影响列表项正文 —— 所以可以放心放大：
 * 正文是 CJK，`•` 与 `1.` 按正文字号排出来只有几个像素，几乎看不见。
 * 行高与正文对齐，符号才会落在首行文字的中线上。
 */
val MarkdownMarkerStyle: TextStyle = textStyle(size = 21f, lineHeight = 29f)

/** 行内代码：比代码块略大一档，视觉重量才跟正文接近。 */
val MarkdownInlineCodeStyle: TextStyle =
    textStyle(size = 15f, lineHeight = 22f, family = FontFamily.Monospace)

/** 代码块等宽样式。 */
val CodeTextStyle: TextStyle =
    textStyle(size = 14f, lineHeight = 22f, family = FontFamily.Monospace)
