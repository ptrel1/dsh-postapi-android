package cd.ptrel.dshpostapi.ui.chat

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import cd.ptrel.dshpostapi.R
import cd.ptrel.dshpostapi.ui.common.AppShadow
import cd.ptrel.dshpostapi.ui.common.SoftShape
import cd.ptrel.dshpostapi.ui.common.Space
import cd.ptrel.dshpostapi.ui.theme.LocalChatColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * 对话内消息索引（§34）。
 *
 * 在消息区**左滑跟手**，从右侧滑出一张索引 —— 只列用户消息、每条一行截断、
 * 当前所在的那条高亮，点一条就跳过去。
 */

/** 面板宽度占消息区宽度的比例（§34.2）—— 瞄一眼跳过去，不是浏览。 */
private const val IndexWidthFraction = 0.36f

/** 松手落位阈值：露出不到面板宽度的这么多就弹回（§34.2）。 */
private const val OpenThresholdFraction = 0.25f

/** 甩动速度阈值（px/s）：甩得快就不看位移。 */
private const val FlingVelocityPx = 800f

/** 遮罩最暗时的不透明度。 */
private const val ScrimMaxAlpha = 0.32f

/** 面板上下边缘的渐隐高度（§34.2「文字在消失处渐隐」）。 */
private val IndexFadeHeight = 20.dp

/** 列表上下留白 —— 渐隐蒙在它上面，不留白会把第一行吃掉。 */
private val IndexEdgePadding = 12.dp

/** 面板贴着屏幕右缘，所以**左边**两角是圆的（抽屉是右边两角）。 */
private val IndexPanelShape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)

// ── 横滚优先：能横滚的块登记自己 ──────────────────────────────

/**
 * 一块"能横滚"的区域（代码块、宽表格）。[bounds] 是它在根坐标里的位置。
 *
 * 只有 [rect] 参与判断 —— bounds 由布局回调写入，读它的是手势处理器（不是组合），
 * 不需要是快照状态。
 */
internal class HorizontalScrollRegion {
    /** 没布局过就是 [Rect.Zero]，`contains` 恒假 —— 正是要的。 */
    var bounds: Rect = Rect.Zero

    fun contains(rootPoint: Offset): Boolean = bounds.contains(rootPoint)
}

/** 消息区内所有能横滚的块。手势处理器按落点查它，落在里面就让位（§34.4）。 */
internal val HorizontalScrollRegions = mutableListOf<HorizontalScrollRegion>()

/**
 * 声明"这一块能横滚，横滚优先于消息索引的左滑"（§34.4）。
 *
 * ### 为什么必须显式登记
 *
 * 想当然的做法是靠 Compose 的主事件遍历"从子到父"让内层 `horizontalScroll`
 * 先消费 —— **实测不成立**：父层的手势节点先拿到事件并吃掉分步，内层横滚一次都
 * 试不上（把索引手势一关，代码立刻就能横滚，对照实验见 §34 的实现记录）。
 *
 * 所以改成按**落点**判：手指按下时落在登记过的横滚区域里，这次手势整段让给它。
 */
@Composable
internal fun Modifier.preferHorizontalScroll(): Modifier {
    val region = remember { HorizontalScrollRegion() }
    DisposableEffect(region) {
        HorizontalScrollRegions.add(region)
        onDispose { HorizontalScrollRegions.remove(region) }
    }
    return onGloballyPositioned { region.bounds = it.boundsInRoot() }
}

// ── 手势 ─────────────────────────────────────────────────

/**
 * 索引的手势（§34）：水平拖动跟手，越过分步才开始认，落点在横滚块上就整段让位。
 *
 * 自己写而不是用 `Modifier.draggable` —— 后者抢内层横滚（见 [preferHorizontalScroll]）。
 * 只认越过分步的水平拖动，所以**不碰**点击（AI＝看元信息、用户＝编辑）与长按菜单。
 */
internal fun Modifier.messageIndexDrag(state: MessageIndexDragState): Modifier = composed {
    var origin by remember { mutableStateOf(Offset.Zero) }

    onGloballyPositioned { origin = it.boundsInRoot().topLeft }
        .pointerInput(state) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val rootPoint = origin + down.position

                if (HorizontalScrollRegions.any { it.contains(rootPoint) }) {
                    // 这次手势交给底下的横滚 —— **完全不等事件**，别挡着它
                    return@awaitEachGesture
                }

                val tracker = VelocityTracker()
                tracker.addPosition(down.uptimeMillis, down.position)

                val slop = viewConfiguration.touchSlop
                var acc = 0f
                var claimed = false

                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.changedToUpIgnoreConsumed()) break
                    if (change.isConsumed) break

                    val dx = change.positionChange().x
                    tracker.addPosition(change.uptimeMillis, change.position)

                    if (!claimed) {
                        acc += dx
                        /*
                         * 面板**收起时只认左滑**。
                         *
                         * 右滑是会话抽屉的（左边缘右滑开抽屉）—— 这里一认就会把抽屉吃掉：
                         * 本层不是 `scrollable`，不参与嵌套滚动，消费了就谁也拿不回去。
                         * 所以右滑整段让出去，一个字节都不消费。
                         */
                        if (state.revealed <= 0f && acc > 0f) break
                        if (abs(acc) >= slop) {
                            claimed = true
                            change.consume()
                            state.onDragDelta(acc - if (acc > 0f) slop else -slop)
                        }
                    } else {
                        change.consume()
                        state.onDragDelta(dx)
                    }
                }

                /*
                 * 只有**真接管过**才算松手。
                 *
                 * 否则在遮罩上点一下也会走到 `onDragStopped`，而此刻面板是全开的
                 * （位移 0 ≥ 25% 阈值）→ 落位回"留下"，刚被遮罩点掉的面板又被拉回来。
                 * 实测现象就是"点空白处关不掉"。
                 */
                if (claimed) state.onDragStopped(tracker.calculateVelocity().x)
            }
        }
}

// ── 状态 ─────────────────────────────────────────────────

/**
 * 索引面板的手势状态（§34）。
 *
 * 开索引是**导航**动作，不碰 `detached`；只有真的跳走（视口离开底部）才停跟随，
 * 回到 `atBottom` 自动恢复（§34.4）。跳转由调用方做。
 */
@Stable
class MessageIndexDragState internal constructor(
    private val scope: CoroutineScope,
) {
    /** 已露出的宽度（px）。0 = 全收起。拖动与落位动画都只写它。 */
    var revealed by mutableFloatStateOf(0f)
        private set

    /**
     * 面板宽度（px）。
     *
     * 全收起时面板不参与组合，所以宽度由外层用约束算出来喂进来 —— 拖动的钳制与
     * 落位阈值都要它。
     */
    var panelWidthPx by mutableFloatStateOf(0f)

    /** 松手后**留下**了。给返回键用 —— 拖动过程中不翻（§34.4）。 */
    var isOpen by mutableStateOf(false)
        private set

    private var settleJob: Job? = null

    /** 展开度 0..1，给遮罩透明度用。 */
    val progress: Float
        get() = if (panelWidthPx <= 0f) 0f else (revealed / panelWidthPx).coerceIn(0f, 1f)

    /** 手指拖动。`delta` 是手指的水平位移（右为正）；左滑要露出面板，所以取负。 */
    fun onDragDelta(delta: Float) {
        if (panelWidthPx <= 0f) return
        settleJob?.cancel()
        revealed = (revealed - delta).coerceIn(0f, panelWidthPx)
    }

    /** 松手：按位移阈值、以及甩动速度，决定留下还是弹回（§34.2）。 */
    fun onDragStopped(velocity: Float) {
        if (panelWidthPx <= 0f) return
        val open = when {
            velocity <= -FlingVelocityPx -> true
            velocity >= FlingVelocityPx -> false
            else -> revealed >= panelWidthPx * OpenThresholdFraction
        }
        settle(if (open) panelWidthPx else 0f)
    }

    /** 收起（点遮罩 / 右滑到底 / 返回键）。 */
    fun collapse() = settle(0f)

    private fun settle(target: Float) {
        settleJob?.cancel()
        isOpen = target > 0f
        settleJob = scope.launch {
            animate(
                initialValue = revealed,
                targetValue = target,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ) { value, _ -> revealed = value }
        }
    }
}

@Composable
fun rememberMessageIndexDragState(): MessageIndexDragState {
    val scope = rememberCoroutineScope()
    return remember(scope) { MessageIndexDragState(scope) }
}

// ── 面板 ─────────────────────────────────────────────────

/**
 * 消息索引的遮罩 + 面板（§34）。
 *
 * 整层盖在消息区**最上面**：索引开着的时候别的都按不动，面板占满高度、贴右缘。
 *
 * ⚠️ 全收起时**不参与组合**（只留手势那一条）—— 否则一块透明的整屏节点会把底下的
 * 消息点击吃掉（AI＝看元信息、用户＝编辑、长按＝菜单，§34.4）。
 */
@Composable
fun MessageIndexOverlay(
    state: MessageIndexDragState,
    entries: List<MessageIndexEntry>,
    listState: LazyListState,
    onJump: (MessageIndexEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current
    val density = LocalDensity.current

    /*
     * 面板自己的滚动位置。
     *
     * 必须与"当前那条"对齐：一滑出索引就从第 1 条开始排，而人在长会话的
     * 中段，高亮那条往往在视野之外 —— 索引等于白开。
     */
    val indexListState = rememberLazyListState()

    /*
     * 当前所在的那条 = 视口顶部那条所属的最近一条用户消息。
     *
     * 用 `derivedStateOf` 包住：`firstVisibleItemIndex` 滚一下就变，直接算会让整个
     * 面板每帧重组（§34.5）。
     */
    val currentId by remember(entries, listState) {
        derivedStateOf {
            val top = listState.firstVisibleItemIndex
            var id = ""
            for (entry in entries) {
                if (entry.index <= top) id = entry.id else break
            }
            id
        }
    }

    BoxWithConstraints(
        modifier = modifier,
    ) {
        // 面板宽度由约束算出来 —— 收起时面板不组合，不能靠量
        val panelWidthPx = with(density) { (maxWidth * IndexWidthFraction).toPx() }
        SideEffect { state.panelWidthPx = panelWidthPx }

        if (state.revealed > 0f) {
            /*
             * 遮罩。透明度跟展开度走，写在 `graphicsLayer` 里 —— 拖动一帧改一次，
             * 写进组合状态会让这一层连带 LazyColumn 每帧重组。
             */
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = ScrimMaxAlpha * state.progress }
                    .background(colors.scrim)
                    .clickable { state.collapse() },
            )

            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .fillMaxWidth(IndexWidthFraction)
                    // 跟手：露出多少就挪进来多少
                    .offset { IntOffset((panelWidthPx - state.revealed).roundToInt(), 0) }
                    .shadow(
                        elevation = AppShadow.RaisedElevation,
                        shape = IndexPanelShape,
                        clip = false,
                        ambientColor = AppShadow.RaisedAmbient,
                        spotColor = AppShadow.RaisedSpot,
                    )
                    .clip(IndexPanelShape)
                    .background(colors.card),
            ) {
                if (entries.isEmpty()) {
                    Text(
                        text = stringResource(R.string.msg_index_no_messages),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.placeholder,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(Space.Inline),
                    )
                } else {
                    /*
                     * 面板一露出就把列表滚到**当前那条**（§34.4）。
                     *
                     * 写在 `revealed > 0` 这一支里，效果就是"跟着面板一起出现" ——
                     * 拖动跟手的第一帧就已经在正确的位置，而不是等松手落位才跳一下。
                     * 面板开着时消息区被遮罩挡着滚不动，`currentId` 不会变，
                     * 所以不需要跟着实时走。
                     */
                    LaunchedEffect(currentId, entries) {
                        val index = entries.indexOfFirst { it.id == currentId }
                        if (index >= 0) indexListState.scrollToItem(index)
                    }
                    LazyColumn(
                        state = indexListState,
                        contentPadding = PaddingValues(vertical = IndexEdgePadding),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(items = entries, key = { it.id }) { entry ->
                            MessageIndexRow(
                                entry = entry,
                                current = entry.id == currentId,
                                onClick = { onJump(entry) },
                            )
                        }
                    }
                }

                /*
                 * 上下边缘渐隐（§34.2「文字在消失处渐隐」）。
                 *
                 * 用面板底色蒙一层，文字是**淡进面板**的，不是被一条硬边切断 ——
                 * 与消息区底部那段渐变同一套做法。
                 */
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(IndexFadeHeight)
                        .background(
                            Brush.verticalGradient(
                                listOf(colors.card, colors.card.copy(alpha = 0f)),
                            ),
                        ),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(IndexFadeHeight)
                        .background(
                            Brush.verticalGradient(
                                listOf(colors.card.copy(alpha = 0f), colors.card),
                            ),
                        ),
                )
            }
        }
    }
}

/**
 * 索引的一行。
 *
 * 节奏与会话抽屉的行一致：46dp 起步、[SoftShape]、当前条垫
 * [com.simplechat.app.ui.theme.ChatColors.fieldBackground]。**只放正文截断**，
 * 不带时间（§34.3）。
 */
@Composable
private fun MessageIndexRow(
    entry: MessageIndexEntry,
    current: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalChatColors.current
    Box(
        modifier = Modifier
            .padding(horizontal = Space.Gap, vertical = 2.dp)
            .fillMaxWidth()
            .clip(SoftShape)
            .background(if (current) colors.fieldBackground else colors.card)
            .clickable(onClick = onClick)
            .heightIn(min = 46.dp)
            .padding(horizontal = Space.Inline, vertical = 2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = entry.preview,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
