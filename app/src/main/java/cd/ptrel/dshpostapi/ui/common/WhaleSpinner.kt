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
 * DSH 官方的加载动画参数（v0.15.0）—— 全部照搬宿主前端，一个不改，
 * 这样 App 与 Web 的「忙」给用户的感觉一致：
 *
 *   ① WhaleSpinner   鲸鱼摆动  rotate(-4deg)→1.6deg, 1.6s ease-in-out, 支点 50%/60%
 *                    dsh-client-ui-conversation 的 pXSMma_hero-fish-swim
 *   ② BusyDots       三点依次  ·→··→···, 1.5s step-end
 *                    dsh-client-ui-* 的 _reveal-second-dot_ / _reveal-third-dot_
 *   ③ BreathingDot   呼吸闪烁  opacity 1→0.35→1, 1s ease-in-out
 *                    dsh-client-ui-chat 的 eGxaPq_dsh-turn-mark-busy
 *   ④ SpinRing       旋转      rotate(360deg)
 *                    dsh-client-ui-settings-general 的 DOUpOa_spin
 *
 * 前三个用在本页（鲸鱼 + 三点 + 呼吸），旋转用在「检查更新」。
 */

/** 鲸鱼摆动周期，与官方 CSS 的 1.6s 一致。 */
private const val WHALE_PERIOD_MS = 1600

/**
 * 鲸鱼摆动指示器。
 *
 * 官方的 keyframes：
 * ```
 * 0%,100% { transform: none }
 * 35%     { transform: rotate(-4deg) translate(-.4px,-.9px) }
 * 70%     { transform: rotate(1.6deg) translate(.3px,.2px) }
 * ```
 * 支点 `transform-origin: 50% 60%` —— 偏下，看起来以尾根为轴。
 * 幅度很小，是「游动」不是「旋转」，这正是它耐看的原因。
 *
 * @param swishing true 持续摆动；false 静止（不创建动画，省电）。
 */
@Composable
fun WhaleSpinner(
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    swishing: Boolean = true,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    if (!swishing) {
        // 静止态直接画一帧：不运行时不该有动画在跑。
        Canvas(modifier.size(size)) { drawWhale(color) }
        return
    }
    val transition = rememberInfiniteTransition(label = "whale")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(WHALE_PERIOD_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "whaleT",
    )
    // 三段关键帧插值，与 CSS 的 0/35/70/100 对齐。
    // 官方的两个极值不对称（-4 与 +1.6），用简单往返出不来这个节奏。
    val angleDeg = when {
        t < 0.35f -> lerpAngle(0f, -4f, t / 0.35f)
        t < 0.70f -> lerpAngle(-4f, 1.6f, (t - 0.35f) / 0.35f)
        else -> lerpAngle(1.6f, 0f, (t - 0.70f) / 0.30f)
    }
    Canvas(modifier.size(size)) {
        withTransform({
            rotate(
                degrees = angleDeg,
                pivot = Offset(this.size.width * 0.5f, this.size.height * 0.6f),
            )
        }) {
            drawWhale(color)
        }
    }
}

/** CSS 的 ease-in-out 近似：余弦插值，两端减速、中间加速。 */
private fun lerpAngle(from: Float, to: Float, frac: Float): Float {
    val f = frac.coerceIn(0f, 1f)
    val e = ((1f - kotlin.math.cos(f * Math.PI)) / 2f).toFloat()
    return from + (to - from) * e
}

/** 在 Canvas 上画鲸鱼（24×24 网格缩放到控件尺寸）。 */
private fun DrawScope.drawWhale(color: Color) {
    val p = Path()
    p.addPath(WhalePath)
    scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
        drawPath(p, color)
    }
}

    private const val P0 = "M22.9168 1.43018C22.6713 1.31018 22.5658 1.53918 22.4223 1.65519C22.3733 1.69269 22.3318"
    private const val P1 = " 1.74169 22.2903 1.78669C21.9317 2.1697 21.5127 2.42121 20.9657 2.39121C20.1657 2.34621 "
    private const val P2 = "19.4827 2.59771 18.8787 3.20973C18.7502 2.45521 18.3236 2.0047 17.6746 1.71569C17.3351 1"
    private const val P3 = ".56568 16.9916 1.41518 16.7536 1.08867C16.5876 0.856163 16.5421 0.597155 16.4591 0.34164"
    private const val P4 = "7C16.4061 0.187643 16.3536 0.0301382 16.1761 0.00363739C15.9836 -0.0263635 15.9081 0.135"
    private const val P5 = "141 15.8326 0.270145C15.5306 0.822162 15.4136 1.43018 15.4251 2.0462C15.4516 3.43174 16."
    private const val P6 = "0366 4.53527 17.1991 5.3203C17.3311 5.4103 17.3651 5.5003 17.3236 5.63181C17.2441 5.9023"
    private const val P7 = "1 17.1501 6.16482 17.0671 6.43533C17.0141 6.60784 16.9351 6.64584 16.7501 6.57033C16.112"
    private const val P8 = "1 6.30383 15.5611 5.90931 15.074 5.4328C14.2475 4.63328 13.5 3.75075 12.568 3.05973C12.3"
    private const val P9 = "49 2.89822 12.13 2.74822 11.9034 2.60522C10.9524 1.68169 12.028 0.923165 12.277 0.833162"
    private const val P10 = "C12.5375 0.739159 12.3675 0.41615 11.5259 0.42015C10.6844 0.42365 9.91439 0.705658 8.932"
    private const val P11 = "86 1.08117C8.78935 1.13767 8.63835 1.17867 8.48384 1.21267C7.59332 1.04367 6.66829 1.006"
    private const val P12 = "17 5.70226 1.11517C3.88321 1.31768 2.43016 2.1777 1.36213 3.64575C0.0790928 5.4103 -0.22"
    private const val P13 = "2916 7.41536 0.146595 9.50642C0.535106 11.7105 1.66014 13.535 3.38869 14.9616C5.18125 16"
    private const val P14 = ".4406 7.24581 17.1657 9.60138 17.0266C11.0319 16.9441 12.6245 16.7526 14.421 15.2321C14."
    private const val P15 = "874 15.4576 15.3496 15.5476 16.1381 15.6151C16.7456 15.6716 17.3306 15.5851 17.7836 15.4"
    private const val P16 = "911C18.4931 15.3411 18.4441 14.6841 18.1876 14.5636C16.1081 13.595 16.5646 13.9891 16.14"
    private const val P17 = "96 13.67C17.2061 12.42 18.8202 10.1979 19.3182 7.17235C19.3672 6.83834 19.4297 6.36783 1"
    private const val P18 = "9.4222 6.09732C19.4182 5.93231 19.4562 5.86831 19.6447 5.84931C20.1657 5.78931 20.6712 5"
    private const val P19 = ".64681 21.1357 5.3913C22.4833 4.65528 23.0268 3.44624 23.1548 1.9972C23.1738 1.77569 23."
    private const val P20 = "1508 1.54668 22.9168 1.43018ZM11.1749 14.4736C9.15936 12.889 8.18184 12.3675 7.77832 12."
    private const val P21 = "39C7.40081 12.4125 7.46881 12.8445 7.55182 13.126C7.63882 13.404 7.75182 13.5955 7.91033"
    private const val P22 = " 13.8396C8.01983 14.0011 8.09533 14.2411 7.80083 14.4216C7.15181 14.8231 6.02327 14.2866"
    private const val P23 = " 5.97027 14.2601C4.65673 13.4865 3.5587 12.4655 2.78467 11.069C2.03715 9.72493 1.60314 8"
    private const val P24 = ".28289 1.53164 6.74384C1.51264 6.37233 1.62214 6.24082 1.99215 6.17332C2.47916 6.08332 2"
    private const val P25 = ".98118 6.06432 3.46769 6.13582C5.52476 6.43633 7.27581 7.35586 8.74385 8.8129C9.58188 9."
    private const val P26 = "64243 10.2159 10.634 10.8689 11.6025C11.5634 12.631 12.3105 13.611 13.262 14.4146C13.598"
    private const val P27 = " 14.6961 13.866 14.9101 14.1225 15.0681C13.349 15.1546 12.058 15.1731 11.1749 14.4746L11"
    private const val P28 = ".1749 14.4736ZM12.141 8.25988C12.141 8.09488 12.273 7.96338 12.439 7.96338C12.4765 7.963"
    private const val P29 = "38 12.5105 7.97088 12.541 7.98188C12.5825 7.99688 12.6205 8.01938 12.6505 8.05338C12.703"
    private const val P30 = "5 8.10588 12.7335 8.18088 12.7335 8.25988C12.7335 8.42489 12.6015 8.55639 12.4355 8.5563"
    private const val P31 = "9C12.2695 8.55639 12.141 8.42489 12.141 8.25988ZM15.1415 9.79893C14.949 9.87793 14.7565 "
    private const val P32 = "9.94544 14.5715 9.95294C14.2845 9.96794 13.9715 9.85143 13.8015 9.70893C13.5375 9.48742 "
    private const val P33 = "13.3485 9.36342 13.2695 8.97691C13.2355 8.8119 13.2545 8.55639 13.2845 8.40989C13.3525 8"
    private const val P34 = ".09438 13.277 7.89187 13.0545 7.70787C12.8735 7.55786 12.643 7.51636 12.39 7.51636C12.29"
    private const val P35 = "55 7.51636 12.209 7.47486 12.1445 7.44136C12.039 7.38886 11.9519 7.25735 12.035 7.09585C"
    private const val P36 = "12.0615 7.04335 12.19 6.91584 12.22 6.89334C12.5635 6.69784 12.9595 6.76184 13.326 6.908"
    private const val P37 = "34C13.6655 7.04735 13.9225 7.30236 14.292 7.66287C14.6695 8.09838 14.7375 8.21838 14.952"
    private const val P38 = "5 8.54539C15.1225 8.8009 15.277 9.06341 15.3831 9.36392C15.4471 9.55142 15.3641 9.70493 "
    private const val P39 = "15.1415 9.79893Z"

/**
 * 官方鲸鱼 logo 的路径数据（24x24 网格）。
 *
 * 来源：dsh-web-frontend 的 FISH_LOGO_PATH 常量。原样搬运，不做任何简化 ——
 * 简化路径会改变曲线，logo 就走形了；3.4KB 的字符串对 APK 体积无影响。
 */
private val WhalePath: Path by lazy {
    androidx.compose.ui.graphics.vector.PathParser().apply {
        parsePathString(
            P0 + P1 + P2 + P3 + P4 + P5 + P6 + P7 + P8 + P9 + P10 + P11 + P12 + P13 + P14 + P15 + P16 + P17 + P18 + P19 + P20 + P21 + P22 + P23 + P24 + P25 + P26 + P27 + P28 + P29 + P30 + P31 + P32 + P33 + P34 + P35 + P36 + P37 + P38 + P39,
        )
    }.toPath()
}

/** 三点依次出现的周期，官方 1.5s。 */
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
            text = "深度求索中，用时 " + elapsedSec + "s ·",
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = color,
        )
        BusyDots(color = color, fontSize = 13.sp)
    }
}

