package cd.ptrel.dshpostapi.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import cd.ptrel.dshpostapi.ui.theme.LocalChatColors
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 分段控件：选中指示器为可拖动的滑动胶囊。
 *
 * 视觉对齐参考文献：外框圆角胶囊 + 极淡描边，选中项浅蓝底 + 品牌蓝字，
 * 指示器可左右拖动，松手后吸附到最近的一段。
 *
 * @param options 选项列表
 * @param selected 当前选中项
 * @param label 选项文案
 * @param icon 选项图标（可为空）
 */
@Composable
fun <T> SegmentedSwitch(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    icon: (T) -> ImageVector? = { null },
) {
    require(options.isNotEmpty()) { "SegmentedSwitch 至少需要一个选项" }

    val colors = LocalChatColors.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val pill = RoundedCornerShape(percent = 50)
    val indicator = remember { Animatable(0f) }

    var trackWidthPx by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }

    val segmentWidthPx = if (trackWidthPx > 0) trackWidthPx.toFloat() / options.size else 0f
    val selectedIndex = options.indexOf(selected).coerceAtLeast(0)

    // 外部切换选中项时，把指示器滑过去（拖动过程中不干预）
    LaunchedEffect(selectedIndex, segmentWidthPx) {
        if (segmentWidthPx > 0f && !dragging) {
            indicator.animateTo(
                targetValue = selectedIndex * segmentWidthPx,
                animationSpec = tween(durationMillis = 220),
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(pill)
            .border(1.dp, colors.outline, pill)
            .padding(3.dp)
            .onSizeChanged { trackWidthPx = it.width }
            // 拖动挂在**父级**：子级的 clickable 先拿到事件且不会消费 down，
            // 越过 touch slop 后由这里消费 → clickable 自动取消。
            // 之前把拖动层盖在最上面，点击能否穿透全靠事件分发细节，不可靠 ——
            // 表现就是"只能拖、点不动"。
            .pointerInput(segmentWidthPx, options.size) {
                if (segmentWidthPx <= 0f) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onDragCancel = {
                        dragging = false
                        scope.launch { snap(indicator, selectedIndex * segmentWidthPx) }
                    },
                    onDragEnd = {
                        dragging = false
                        val maxIndex = options.size - 1
                        val target = (indicator.value / segmentWidthPx)
                            .roundToInt()
                            .coerceIn(0, maxIndex)
                        onSelect(options[target])
                        scope.launch { snap(indicator, target * segmentWidthPx) }
                    },
                    onHorizontalDrag = { change, delta ->
                        change.consume()
                        val maxOffset = (options.size - 1) * segmentWidthPx
                        scope.launch {
                            indicator.snapTo(
                                (indicator.value + delta).coerceIn(0f, maxOffset),
                            )
                        }
                    },
                )
            },
    ) {
        // ── 滑动指示器 ──────────────────────────────────
        if (segmentWidthPx > 0f) {
            Box(
                modifier = Modifier
                    .width(with(density) { segmentWidthPx.toDp() })
                    .fillMaxHeight()
                    .offset { IntOffset(indicator.value.roundToInt(), 0) }
                    .clip(pill)
                    .background(colors.capsuleSelected),
            )
        }

        // ── 选项行 ─────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(pill)
                        .clickable { onSelect(option) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    icon(option)?.let {
                        Icon(
                            imageVector = it,
                            contentDescription = null,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(17.dp),
                        )
                        Box(Modifier.size(6.dp))
                    }
                    Text(
                        text = label(option),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

    }
}

private suspend fun snap(indicator: Animatable<Float, *>, target: Float) {
    indicator.animateTo(
        targetValue = target,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
    )
}

/** 分段控件下方的说明文字。 */
@Composable
fun SegmentCaption(text: String, modifier: Modifier = Modifier) {
    val colors = LocalChatColors.current
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = colors.placeholder,
        modifier = modifier,
    )
}
