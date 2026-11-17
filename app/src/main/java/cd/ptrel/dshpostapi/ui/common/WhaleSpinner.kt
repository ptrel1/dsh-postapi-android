package cd.ptrel.dshpostapi.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * DSH 官方的加载动画（v0.15.0 起，参数照搬宿主前端，一个不改）：
 *
 *   ① BusyDots       三点依次  ·→··→···, 1.5s step-end
 *                    dsh-client-ui-* 的 _reveal-second-dot_ / _reveal-third-dot_
 *   ② BreathingDot   呼吸闪烁  opacity 1→0.35→1, 1s ease-in-out
 *                    dsh-client-ui-chat 的 eGxaPq_dsh-turn-mark-busy
 *   ③ SpinRing       旋转      rotate(360deg)
 *                    dsh-client-ui-settings-general 的 DOUpOa_spin
 *
 * ①② 用在聊天页，③ 用在「检查更新」。
 *
 * ⚠️ v0.15.9：原本还有一个 WhaleSpinner（整条鲸鱼摆动），已删除 ——
 * 官方运行指示器用的是**鲸鱼尾巴**（见 ui/common/DeepDiving.kt），
 * 整条鲸鱼那个成了死代码。
 */

/** 鲸鱼摆动周期，与官方 CSS 的 1.6s 一致。 */
private const val DOTS_PERIOD_MS = 1500

/**
 * 三点依次出现（v0.15.0）。
 *
 * 官方 CSS：
 * ```
 * @keyframes reveal-second-dot { 0%,33.32%{opacity:0} 33.33%,100%{opacity:1} }
 * @keyframes reveal-third-dot  { 0%,66.65%{opacity:0} 66.66%,100%{opacity:1} }
 * ```
 * 注意是 **step-end** 而不是渐变 —— 三个点依次「出现并保留」，
 * 一圈结束后同时消失重来。渐变会变成闪烁，那不是官方的手感。
 */
@Composable
fun BusyDots(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    fontSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
) {
    val transition = rememberInfiniteTransition(label = "dots")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(DOTS_PERIOD_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "dotsT",
    )
    // 三个点各占周期的三分之一，到点就显示（阶跃，不是渐变）
    val secondVisible = t >= 1f / 3f
    val thirdVisible = t >= 2f / 3f
    androidx.compose.foundation.layout.Row(
        modifier = modifier,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Text("·", color = color, fontSize = fontSize)
        androidx.compose.material3.Text("·", color = if (secondVisible) color else Color.Transparent, fontSize = fontSize)
        androidx.compose.material3.Text("·", color = if (thirdVisible) color else Color.Transparent, fontSize = fontSize)
    }
}

/** 呼吸闪烁周期，官方 1s。 */
private const val BREATH_PERIOD_MS = 1000

/**
 * 呼吸闪烁（v0.15.0）：opacity 1 → 0.35 → 1，1s ease-in-out。
 *
 * 官方用它标「这一轮还在跑」（dsh-turn-mark-busy）。
 * 我们用在同一处语义：执行过程里**尚未返回结果**的那一步。
 */
@Composable
fun BreathingAlpha(busy: Boolean): Float {
    if (!busy) return 1f
    val transition = rememberInfiniteTransition(label = "breath")
    val v by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(BREATH_PERIOD_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathA",
    )
    return v
}

/**
 * 旋转圆环（v0.15.0）：rotate(360deg) 匀速。
 *
 * 官方用它做设置页的「检查更新」指示。我们也放在同一语义上 ——
 * 一个通用、无品牌的「正在处理」信号。聊天页用鲸鱼，这里用圆环，
 * 是为了让两处的视觉语言有区分：鲸鱼代表「DSH 在思考」，圆环代表「网络在忙」。
 */
@Composable
fun SpinRing(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp = 2.dp,
) {
    val transition = rememberInfiniteTransition(label = "spin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spinA",
    )
    Canvas(modifier.size(size)) {
        val stroke = strokeWidth.toPx()
        val arcSize = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke)
        // 画 270° 的弧而不是整圈：整圈旋转看不出在转。
        drawArc(
            color = color,
            startAngle = angle,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = arcSize,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            ),
        )
    }
}


/**
 * 「深度求索中，用时 Ns ···」—— 与 DSH 官方文案逐字对齐（v0.15.0）。
 *
 * 官方 i18n：`chat.deepDivingFor: "深度求索中，用时 {duration} ···"`
 * （dsh-client-ui-chat/lib/client.js:5801）。注意文案自带 `···`，
 * 那是三点动画的一部分，不是标点 —— 所以这里用 BusyDots 而不是三个句号。
 *
 * ## 为什么要显示用时
 *
 * 只说「深度求索中」时，用户无法判断是「刚起步」还是「卡了 5 分钟」。
 * 秒数在走本身就是「它还活着」的证据，比任何动画都直接。
 *
 * ## 计时来源
 *
 * 由调用方传入 `startedAt`（SystemClock.elapsedRealtime）。
 * 组件自己每秒重算一次显示值 —— 用 LaunchedEffect 起一个 1s 的循环，
 * 而不是 remember 一个死值：那样秒数不会变。
 *
 * @param startedAt 这一轮开始时刻；0 表示没有在跑（此时不显示）。
 */
@Composable
fun DeepDivingLabel(
    startedAt: Long,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    if (startedAt <= 0L) return
    var elapsedSec by androidx.compose.runtime.remember(startedAt) {
        androidx.compose.runtime.mutableStateOf(0L)
    }
    androidx.compose.runtime.LaunchedEffect(startedAt) {
        // 每秒刷一次。用 while(true) + delay 而不是 withFrameNanos：
        // 秒级显示不需要 60fps 的重组，那会白白耗电。
        while (true) {
            elapsedSec = (android.os.SystemClock.elapsedRealtime() - startedAt) / 1000L
            kotlinx.coroutines.delay(1000L)
        }
    }
    androidx.compose.foundation.layout.Row(
        modifier = modifier,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Text(
            // 与官方文案一致：「深度求索中，用时 12s ·」+ 两点由 BusyDots 补
            // v0.15.6：官方时长格式 "53秒" / "1分3秒"（i18n duration.seconds/compactMinutes）。
            // 早先写的是 "53s"，与官方不一致。
            text = "深度求索中，用时 " + formatRunDuration(elapsedSec) + " ·",
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = color,
        )
        BusyDots(color = color, fontSize = 13.sp)
    }
}

