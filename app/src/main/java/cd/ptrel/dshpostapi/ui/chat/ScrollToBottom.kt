package cd.ptrel.dshpostapi.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import cd.ptrel.dshpostapi.ui.theme.LocalChatColors
import kotlinx.coroutines.delay

/**
 * 聊天流的滚动助手。
 *
 * ## 前提：列表是**顶部为原点**（对齐官方）
 *
 * `MessageList` 用 `reverseLayout = false`：最旧一条在下标 0（视觉顶部），
 * 最新的在最后。**这是为流式服务的** —— 锚点钉在条目的**顶边**，于是流式消息
 * （永远是最后一条）在自己的**底边**长高，也就是长在视口**下方**：
 * 屏上已显示内容纹丝不动，一行业务代码都不需要。
 *
 * 反过来用 `reverseLayout = true`（锚点钉底边）：同一条消息在底边继续长，
 * 就把已显示内容整体往上顶（实测 0.5s 滑走 861px，手拦不住）。
 *
 * 落点"点进对话就是底部"由 `ChatScreen` 的
 * `LazyListState(initialFirstVisibleItemIndex = Int.MAX_VALUE)` 解决 ——
 * 下标给到末尾之外，LazyList 自动夹到最后一条。**是布局参数，不是一次滚动**，
 * 首帧就在底部，不需要重试、不需要等高度量出来。
 */

/** 回到底部。给一个超大 delta，`ScrollableState` 会自动夹到内容末尾。 */
suspend fun LazyListState.scrollToBottom() {
    scrollBy(1_000_000_000f)
}

/**
 * 是否已贴底。
 *
 * 顶部为原点，"贴底"= **不能再往前滚**（已在内容末尾）。O(1)，
 * 不依赖任何一条的高度、也不需要减总高。
 */
fun LazyListState.isAtBottom(): Boolean = !canScrollForward

/**
 * 到列表底部还差多少像素。
 *
 * 只在**最后一条已经可见**时算得出来 —— LazyList 的条目高度是按需量的，
 * 视口外的条目高度未知。那种情况返回 [Float.POSITIVE_INFINITY]，语义是
 * "够不着，直接跳过去"（见 [followBottom]）。
 *
 * 判据与 `canScrollForward` 同源：**底部内边距也算内容的一部分** ——
 * 让开输入栏的那段留白就在列表自己身上（`contentPadding`），
 * 所以减的是 `viewportEndOffset - afterContentPadding`。
 */
fun LazyListState.distanceToBottom(): Float {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return 0f
    if (last.index != info.totalItemsCount - 1) return Float.POSITIVE_INFINITY
    return (last.offset + last.size + info.afterContentPadding - info.viewportEndOffset)
        .coerceAtLeast(0)
        .toFloat()
}

/**
 * 跟底时每帧吃掉剩余距离的比例。见 [followBottom]。
 *
 * 取 0.7：一步吃掉七成，两三帧就贴住；同时剩下的三成把"内容更新之间的台阶"
 * 摊平。再高（→1）就退回"每帧一把跳到位"，再低则滞后明显。
 */
private const val FollowFraction = 0.7f

/**
 * 贴底跟随的**一帧**：向底部**逼近**，而不是一把跳过去。
 *
 * ### 为什么不能直接 `scrollToBottom()`
 *
 * 内容是**离散**地来的（数据层每 50ms 提交一批），而屏是 60Hz ——
 * "每帧 `scrollBy(1e9)`" 具体表现就是**每 50ms 挪一个台阶**。
 * 台阶的大小 = 那一批 token 长出来的高度，往往是一整行。
 *
 * 改成"每帧吃掉剩余距离的 [FollowFraction]"之后，运动是**连续**的：
 * 内容更新之间那几个静止帧也被填上了位移。
 *
 * ### 代价（明确接受）
 *
 * 稳态滞后 = 增长速率 / [FollowFraction]（按帧算）—— 也就是**最新那一行会
 * 被裁掉不到一行**。观感从"字是当场蹦出来的"变成"字正在被写出来"。
 * 这是拿"最后一行必须完整可见"换"运动连续"，是刻意的取舍。
 *
 * 最后一条还在视口外（`distanceToBottom` 返回无穷）时不算跟随，是**追平** ——
 * 那次直接跳，没有台阶可摊。
 */
suspend fun LazyListState.followBottom() {
    // ⚠️ v0.16.2：改回**一次到位**。
    //
    // 早先这里是 `scrollBy(remaining * 0.7)`，注释里自认「代价是最新一行被裁掉不到一行」，
    // 说是拿「最后一行完整可见」换「运动连续」。**这个取舍是错的**：
    //
    // 它假设「滚一次之后内容不再长」，但流式每 50ms 就长一批 ——
    // 于是永远差 30% 追不上：长 10px 滚 7px，剩 3px；再长 10px 滚 9px，剩 4px……
    // 每一帧都在「几乎贴上又差一点」之间来回，坐实了「一闪一闪」（用户反馈）。
    //
    // 关键事实：列表是**顶部为原点**（reverseLayout = false），
    // 流式消息在自己的**底边**长高，屏幕上方已显示的内容纹丝不动。
    // 所以「直接跳到底」不会产生任何视觉跳变 —— 该有的平滑它本来就有。
    //
    // 0.7 那个做法真正解决的问题（每批 token 之间跳台阶）不存在：
    // 内容本来就是**离散到批**的，一次滚到底和一次滚 70% 的视觉差异，
    // 远小于「永远差一点」的持续抖动。
    val remaining = distanceToBottom()
    when {
        !remaining.isFinite() -> scrollToBottom()
        remaining > 0f -> scrollBy(remaining)
        /*
         * 兜底。走到这里意味着 `canScrollForward` 为真（调用方保证）而距离算出来是 0 ——
         * 只可能是内边距那一段的边界算不准。此时退回已知正确的"直接跳"：
         * 少一次平滑无所谓，**停死**（新字永远停在屏外）才是不能接受的。
         * 而且它落到末尾后 `canScrollForward` 就变假，这条不会一直触发。
         */
        canScrollForward -> scrollToBottom()
    }
}

/** 同 [isAtBottom]，状态化版本，供 UI 订阅（回到底部按钮的显隐）。 */
@Composable
fun rememberIsAtBottom(state: LazyListState): State<Boolean> = remember(state) {
    derivedStateOf { state.isAtBottom() }
}



/** 停手后滚动条多久淡出。 */
private const val ScrollBarFadeDelayMs = 700L

/** 滑块宽度、最短长度。 */
private val ScrollBarWidth = 3.dp
private val ScrollBarMinThumb = 28.dp

/**
 * 安卓原生那种**细滚动条**：滚动时出现、停手后淡出。
 *
 * Compose 没给 `LazyColumn` 自带滚动条，这里照原生观感做一个：细、圆头、半透明，
 * 只在滚动时可见。
 *
 * ⚠️ 位置与长度按**条目比例**估算 —— Compose 只暴露条目数，不暴露内容总高度。
 * 聊天流里条目高度差异极大（一条几行 vs 一条几十万字），所以它只是
 * "我大概在读哪一段"的提示，不是精确比例尺。
 *
 * ⚠️ **滚动状态一律在绘制期读，组合期一个都不碰。** `layoutInfo` /
 * `firstVisibleItemIndex` 滚一帧变一次，落在组合里就是**每帧重组**；再叠上
 * `BoxWithConstraints`（`SubcomposeLayout`，每次测量都要子组合一次）与
 * `offset(Dp)`（每帧重新布局），这根 3dp 的小条就成了拖垮滚动的那根稻草。
 * 现在整条滑块只是一段绘制：**不重组、不测量、不布局**，每帧至多重画一个圆角矩形。
 */
@Composable
fun VerticalScrollBar(
    state: LazyListState,
    modifier: Modifier = Modifier,
) {
    var scrolling by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collect { inProgress ->
            if (inProgress) {
                scrolling = true
            } else {
                delay(ScrollBarFadeDelayMs)
                scrolling = false
            }
        }
    }
    val fade by animateFloatAsState(
        targetValue = if (scrolling) 1f else 0f,
        animationSpec = tween(200),
        label = "scrollBarAlpha",
    )
    val thumbColor = LocalChatColors.current.processText

    Box(
        modifier = modifier
            .padding(horizontal = 3.dp)
            .width(ScrollBarWidth)
            .drawWithCache {
                val radius = size.width / 2
                val minThumb = ScrollBarMinThumb.toPx()
                onDrawBehind {
                    /*
                     * 这里的快照读（`fade`、`state.layoutInfo`）都落在**绘制阶段**：
                     * 只让这一层重画，不触发重组与重新布局。
                     */
                    if (fade <= 0.01f) return@onDrawBehind
                    val info = state.layoutInfo
                    val total = info.totalItemsCount
                    if (total <= 0) return@onDrawBehind

                    val visibleCount = info.visibleItemsInfo.size.coerceAtLeast(1)
                    val thumbFraction = (visibleCount.toFloat() / total).coerceIn(0.06f, 1f)
                    // 顶部为原点：下标 0 在视觉**顶部**，进度正着算
                    val startFraction = (state.firstVisibleItemIndex.toFloat() / total)
                        .coerceIn(0f, 1f - thumbFraction)

                    val track = size.height
                    val thumbHeight = (track * thumbFraction).coerceAtLeast(minThumb)
                    val top = (track * startFraction).coerceIn(0f, track - thumbHeight)

                    drawRoundRect(
                        color = thumbColor,
                        topLeft = Offset(0f, top),
                        size = Size(size.width, thumbHeight),
                        cornerRadius = CornerRadius(radius),
                        alpha = 0.38f * fade,
                    )
                }
            },
    )
}
