package cd.ptrel.dshpostapi.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cd.ptrel.dshpostapi.R
import cd.ptrel.dshpostapi.data.Res
import cd.ptrel.dshpostapi.ui.common.AppIcons
import cd.ptrel.dshpostapi.ui.common.PillShape
import cd.ptrel.dshpostapi.ui.theme.LocalChatColors

/**
 * 思考栏的**唯一**标题行 —— 两个协议通道共用。
 *
 * ### 为什么要有这个文件
 *
 * 同一件事（"思考过没有、用了多久"）原先在两个面板里各写了一套：
 * 扩展通道（`TimelinePanel`）是「🧠 已思考（用时 3 秒）」，OpenAI 通道
 * （`ProcessPanel`）是「已深度思考 · 3 秒」且没有图标、左边还差 8dp。
 * 同一个 App 里同一行信息两种长相，看起来像两个功能。
 *
 * 现在措辞、图标、间距、点击热区只有一份（[thinkingLabel] 管措辞）。
 * 两个面板各自的**差异**只留在该留的地方：扩展通道多出搜索/浏览步骤，
 * OpenAI 通道只有纯思维链。
 *
 * @param label    标题文案，用 [thinkingLabel] 生成。
 * @param enabled  能否点开折叠区。流式期间为 false（展开会把进度顶走）。
 */
@Composable
internal fun ThinkingHeader(
    label: String,
    expanded: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current

    Row(
        modifier = modifier
            .clip(PillShape)
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            // 往左顶回 8dp：让**图标**的左缘与正文左边界对齐。
            // （只是视觉左移，不改变布局宽度 —— 用 offset 而不是负 padding）
            .offset(x = (-8).dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = AppIcons.Brain,
            contentDescription = null,
            tint = colors.processText,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = colors.processText,
        )
        // 进行中不给箭头：还在写，没有"折叠"这个动作可做
        if (enabled) {
            Icon(
                imageVector = if (expanded) AppIcons.ChevronUp else AppIcons.ChevronDown,
                contentDescription = stringResource(
                    if (expanded) R.string.process_collapse else R.string.process_expand,
                ),
                tint = colors.processText,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * 思考栏标题文案 —— **两个通道共用这一套措辞**。
 *
 * 改为 `Res.get` 而不是 `stringResource`：调用点要传字符串给 [ThinkingHeader]，
 * 而在组合外取字符串更省事（[Res.get] 内部走 `LocalContext`，见它的实现）。
 */
internal fun thinkingLabel(
    running: Boolean,
    seconds: Long?,
    stopped: Boolean = false,
): String = when {
    running -> Res.get(R.string.thinking_running)
    stopped -> Res.get(R.string.thinking_stopped)
    seconds != null -> Res.get(R.string.thinking_done_seconds, seconds)
    else -> Res.get(R.string.thinking_done)
}
