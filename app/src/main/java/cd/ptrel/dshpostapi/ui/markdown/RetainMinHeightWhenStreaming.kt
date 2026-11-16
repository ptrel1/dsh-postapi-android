package cd.ptrel.dshpostapi.ui.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout

/**
 * 流式期间「高度只增不减」。
 *
 * ### 为什么需要它
 *
 * 流式正文是**半成品语法**。未闭合的 ``` 会被当成行内代码、未闭合的 `**`
 * 会被当成普通文本 —— 于是同一段内容在这一帧和下一帧之间**换了排版**，
 * 高度可能**变矮**，下一帧又变回来。
 *
 * 在"底部为原点"的列表里，底边是钉住的，但**条目内部**的文字会因此上下跳；
 * 而高度一缩，视口也要跟着重算 —— 表现就是滚动时"抽一下"、内容乱动。
 *
 * 把高度钉在**历史最大值**上，这类抖动就没了：只会长，不会缩。
 * 流式结束（[streaming] 变 false）后恢复自然高度，多余的空白自动收回。
 *
 * ### 实现上有两个坑
 *
 * 1. **必须先按自然高度测**（`minHeight = 0`）再自己定最终高度。
 *    若把 `heightIn(min)` 加在自己外层再读高度，读到的就是被下限抬过的值 ——
 *    每帧涨一点，棘轮式一路涨上去，回不来了。
 * 2. **历史最大值用 `intArrayOf` 而不是 `mutableStateOf`**：布局期写 state
 *    会额外触发一轮重排；这里不需要 —— 子内容尺寸一变，layout 块本来就会重跑。
 *
 * @param streaming 为 true 时启用下限；为 false 时恢复自然高度。
 * @param resetKey  内容换了"身份"时传一个变化的值（如块 key / 消息 id + 版本号），
 *                  用来丢弃上一份内容记住的高度，避免留下多余空白。
 */
@Composable
internal fun Modifier.retainMinHeightWhileStreaming(
    streaming: Boolean,
    resetKey: Any?,
): Modifier {
    val maxHeight = remember(resetKey) { intArrayOf(0) }
    return this.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minHeight = 0))
        if (streaming && placeable.height > maxHeight[0]) {
            maxHeight[0] = placeable.height
        }
        val height = if (streaming) maxOf(placeable.height, maxHeight[0]) else placeable.height
        layout(placeable.width, height) {
            /*
             * ⚠️ **顶部对齐**（列表已改成顶部为原点，见 MessageList）。
             *
             * 锚点钉在条目顶边 → 内容缩短时缺口要落在**下方**：文字原地不动，
             * 只是底下多出一段（撑住高度，不推动任何东西）。若改成底部对齐，
             * 缺口会跑到上方、文字整体下移 —— 那才是"抽一下"。
             */
            placeable.place(0, 0)
        }
    }
}
