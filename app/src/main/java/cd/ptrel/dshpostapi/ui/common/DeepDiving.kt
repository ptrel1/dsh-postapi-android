package cd.ptrel.dshpostapi.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp


/*
 * 「深度求索中」的运行指示器（v0.15.4）—— 参数全部取自官方 Web。
 *
 * 之前的实现有三处与官方不符（用户截图指出）：
 *   ① 用的是整条鲸鱼 logo；官方用的是**鲸鱼尾巴**（16x16 描边路径）
 *   ② 颜色用的是 onSurfaceVariant（灰）；官方有专门的 deep-diving 蓝
 *   ③ 时长写成 "53s"；官方是 "53秒" / "1分3秒"
 *
 * 官方源码位置：
 *   RunningWhaleTail  dsh-client-ui-chat/lib/client.js:5554
 *   RunningStatus     dsh-client-ui-chat/lib/client.js:5599
 *   配色              dsh-client-ui-theme（--dsw-alias-label-deep-diving）
 */

/** 官方 REST_PATH：鲸鱼尾巴的 16x16 描边路径。 */
private const val TAIL_PATH = "M8.844 13.742C8.967 12.328 8.45 10.4 8.45 9.65C8.45 8.94 8.88 8.43 9.6 8.43C11.285 8.43 12.106 8.281 12.685 8.104C13.71 7.791 14.585 6.768 15.055 5.945C15.137 5.803 14.99 5.641 14.829 5.671C13.829 5.86 12.828 5.376 11.827 4.978C10.659 4.514 9.491 4.707 8.935 4.876C8.805 4.915 8.658 4.819 8.636 4.686C8.468 3.643 7.405 2.615 5.498 2.238C4.54 2.048 3.748 1.574 3.347 1.202C3.252 1.113 3.088 1.125 3.03 1.242C2.628 2.059 2.168 3.82 5.248 6.115C5.82 6.494 6.31 6.785 6.574 7.637C6.72 8.104 6.157 9.168 6.061 9.368C5.157 11.27 5.089 12.19 4.926 13.742"

private val TailPath: Path by lazy {
    PathParser().apply { parsePathString(TAIL_PATH) }.toPath()
}


/**
 * 官方「深度求索中」的蓝色。
 *
 * CSS 原文（浅色）：
 * ```
 * --dsw-alias-label-deep-diving: color-mix(in srgb, #4176e6 70%, #172554)
 * ```
 * 深色主题换成 `color-mix(#5686fe 55%, #adb2b8)`（deepseek-450 + neutral-bluish-400）。
 * 这里用 color-mix 的等价算术（线性加权）还原。
 */
@Composable
fun deepDivingColor(): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (dark) mixHex(0xFF5686FE, 0xFFADB2B8, 0.55f) else mixHex(0xFF4176E6, 0xFF172554, 0.70f)
}

private fun mixHex(a: Long, b: Long, weight: Float): Color {
    fun ch(v: Long, shift: Int) = ((v shr shift) and 0xFF).toFloat()
    val r = ch(a, 16) * weight + ch(b, 16) * (1 - weight)
    val g = ch(a, 8) * weight + ch(b, 8) * (1 - weight)
    val bl = ch(a, 0) * weight + ch(b, 0) * (1 - weight)
    return Color(r.toInt(), g.toInt(), bl.toInt())
}

private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue


/**
 * 摆动的鲸鱼尾巴。
 *
 * 官方是 APNG 资源（源码注释：*"the APNG asset owns its animation timing"*），
 * 我们拿不到那个 APNG，用旋转近似 —— 幅度参考官方首页 logo 的
 * `rotate(-4deg) → 1.6deg`，周期 1.6s，但这里放到 ±8deg/4deg，
 * 因为 16dp 的小图标上 5.6 度摆幅几乎看不出来。
 *
 * ⚠️ 这是**近似**，不是逐帧复刻。官方运行指示器的真实帧序列在那个 APNG 里。
 */
@Composable
fun WhaleTail(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    swishing: Boolean = true,
    color: Color = deepDivingColor(),
) {
    if (!swishing) {
        Canvas(modifier.size(size)) { drawTail(color) }
        return
    }
    val t by rememberInfiniteTransition(label = "tail").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "tailT",
    )
    val angle = when {
        t < 0.35f -> ease(0f, -8f, t / 0.35f)
        t < 0.70f -> ease(-8f, 4f, (t - 0.35f) / 0.35f)
        else -> ease(4f, 0f, (t - 0.70f) / 0.30f)
    }
    Canvas(modifier.size(size)) {
        withTransform({
            rotate(angle, pivot = Offset(this.size.width * 0.5f, this.size.height * 0.75f))
        }) { drawTail(color) }
    }
}

private fun ease(from: Float, to: Float, f: Float): Float {
    val c = f.coerceIn(0f, 1f)
    val e = ((1f - kotlin.math.cos(c * Math.PI)) / 2f).toFloat()
    return from + (to - from) * e
}

private fun DrawScope.drawTail(color: Color) {
    val s = size.width / 16f
    scale(s, s, pivot = Offset.Zero) {
        drawPath(TailPath, color, style = Stroke(width = 1.2f))
    }
}


/**
 * 官方时长格式。
 *
 * i18n 原文（dsh-client-ui-chat）：
 * ```
 * "duration.seconds":        "{seconds}秒"
 * "duration.compactMinutes": "{minutes}分{seconds}秒"
 * ```
 * 所以 53 秒 → "53秒"，63 秒 → "1分3秒"。
 */
fun formatRunDuration(seconds: Long): String {
    val s = seconds.coerceAtLeast(1)
    return if (s < 60) s.toString() + "秒" else (s / 60).toString() + "分" + (s % 60).toString() + "秒"
}

