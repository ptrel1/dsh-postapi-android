package cd.ptrel.dshpostapi.ui.markdown

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 带公式渲染的正文：按 [splitMath] 把内容切成文本段与公式段，交替渲染。
 *
 * - 文本段交给 [MarkdownText]（原有的一整套 Markdown / 代码块 / 流式渲染都不变）
 * - 公式段交给 `latex-renderer`（Compose 原生 Canvas 渲染，**不用 WebView**）
 *
 * ### 为什么不用 WebView + KaTeX
 * 每个公式一个 WebView 实例，在长消息列表里滚动会明显掉帧 —— 而那正是本项目
 * 要解决的第一个问题（见 PRD「轻量流畅是第一要义」）。原生 Canvas 渲染没有这层开销。
 *
 * ### 代价：行内公式不跟着文字走
 * 公式是独立的一块，行内公式也会**独自占一行**。这是"不引 WebView"必然的取舍：
 * 要做到真正的行内混排，得把公式排版成一段 `AnnotatedString` 并塞进
 * Markdown 渲染器的内联流里，那是另一个量级的工程。对长文创作来说，
 * 公式偶尔出现，独占一行读得懂；而 WebView 卡顿是每一条消息都要付的代价。
 */
@Composable
fun MarkdownWithMath(
    content: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
) {
    // 切分是纯字符串操作，跟着内容走；正文没变就不重切
    val segments = remember(content) { splitMath(content) }

    // 没有公式：直接走原来的路径，一个字节的额外开销都没有
    if (segments.size == 1 && segments.first() is ContentSegment.Text) {
        MarkdownText(
            content = (segments.first() as ContentSegment.Text).text,
            modifier = modifier,
            streaming = streaming,
        )
        return
    }

    val fontSize = LatexFontSize

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        segments.forEach { segment ->
            when (segment) {
                is ContentSegment.Text -> MarkdownText(
                    content = segment.text,
                    streaming = streaming,
                )

                // ⚠️ v0.10.0：暂以等宽文本显示公式源码，未接 latex-renderer。
                //
                // 原因：latex-renderer（io.github.huarangmeng:latex-renderer:1.7.3）
                // 在本项目当前工具链（AGP 9.4.1 / Kotlin 2.4.10）下解析失败，
                // 先降级成源码显示 —— 公式内容不丢，只是不渲染。
                //
                // 装回渲染器的步骤：把下面两行依赖加进 app/build.gradle.kts，
                // 然后把这段换回 Latex(...)：
                //   implementation("io.github.huarangmeng:latex-renderer:1.7.3")
                //   implementation("io.github.huarangmeng:latex-fonts-katex:1.7.3")
                //
                // 为什么值得装回去：公式排版用原生 Canvas（不用 WebView），
                // 长列表滚动不掉帧 —— 这是这个库被选中的唯一理由。
                is ContentSegment.Formula -> Text(
                    text = (if (segment.display) "$$ " else "$ ") + segment.tex +
                        (if (segment.display) " $$" else " $"),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = fontSize,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = if (segment.display) 4.dp else 0.dp),
                )
            }
        }
    }
}

/**
 * 公式字号。与正文同档（17sp 左右），不随系统字号线性放大 ——
 * 公式排版对字号敏感，过大会撑破行宽，过小则看不清上下标。
 */
private val LatexFontSize = 17.sp
