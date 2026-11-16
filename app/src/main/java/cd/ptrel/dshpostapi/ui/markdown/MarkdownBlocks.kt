package cd.ptrel.dshpostapi.ui.markdown

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 逐块渲染 Markdown —— 把单次整篇渲染换成"一块一次"。
 *
 * - **已完成的块**：文本不变 → `key` 不变 → Compose 跳过重组 → 不重解析
 * - **最后一块**（流式尾块）：只有它随 token 变化而重解析
 * - **尾块套高度下限**：半截语法引起的折行/换排版不再让高度回缩
 *
 * 分工：分块负责"降低单次解析量"（见 [splitMarkdownBlocks]），
 * 上游的帧对齐采样继续当"频率上限"（只是不许它更慢），两者叠加。
 *
 * ### 这里**没有任何出字特效**，是刻意的
 *
 * 流式期间只有一件事该发生：字按上游给了的节拍出现（见 `MarkdownText` 的
 * `rememberSmoothReveal`）。试过的三种附加效果都不划算，全部撤掉：
 *
 * | 做法 | 结果 |
 * |---|---|
 * | 段落入场的 `blur + alpha` | 每个新段落重演一次，流式期间看着像整段在闪 |
 * | 块底渐变遮罩 | 把整行洗掉，读起来是"一条线把字切了"，不是淡入 |
 * | 逐字改透明度 | 每帧重建 `AnnotatedString` 并重排，排版漂移 → 整段在抖 |
 *
 * 教训：出字的观感属于**采样节奏**，不该再用绘制层去补偿它 ——
 * 每多一层，就要多付一次"每帧重排"或"整块重绘"的代价。
 *
 * @param source      已 [normalizeSoftBreaks] 的 Markdown 文本。
 * @param streaming   是否处于流式态；为 true 时最后一块套高度下限。
 * @param blockGap    块间距（外层 Column 那一层）。
 * @param renderBlock 单块渲染器。`modifier` 用传入的那个。
 */
@Composable
internal fun MarkdownTextBlocks(
    source: String,
    streaming: Boolean,
    modifier: Modifier = Modifier,
    blockGap: Dp = 12.dp,
    renderBlock: @Composable (text: String, modifier: Modifier) -> Unit,
) {
    // 切块是 O(n) 纯计算；source 没变就不重算。
    val blocks = remember(source) { splitMarkdownBlocks(source) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(blockGap),
    ) {
        blocks.forEachIndexed { index, block ->
            val isTail = streaming && index == blocks.lastIndex
            // key 用块起始 offset：追加内容时前面的块 key 不变 → 跳过重组与重解析
            key(block.key) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    renderBlock(
                        block.text,
                        if (isTail) {
                            Modifier.retainMinHeightWhileStreaming(
                                streaming = true,
                                resetKey = block.key,
                            )
                        } else {
                            Modifier
                        },
                    )
                }
            }
        }
    }
}
