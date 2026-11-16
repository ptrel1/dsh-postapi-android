package cd.ptrel.dshpostapi.ui.common

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset

/**
 * 全局转场规格。
 *
 * **所有页面切换都从这里取** —— 时长、缓动、位移量只定义一次。
 * 不然「设置页滑 1/4 屏、搜索页滑 1/3 屏」这种不一致会一处长出来，
 * 而转场恰恰是那种每一处都差一点、整体就是不对劲的东西。
 */

/**
 * 转场时长 300ms。
 *
 * 对齐 Android 平台自己的 Activity 切换（Material 的 medium 档）。
 * 再短会"闪一下"，再长有拖沓感 —— 平台选这个数是有原因的。
 *
 * 抽屉展开成搜索页也用它，两处节奏才是一致的。
 */
const val MotionDurationMs = 300

/**
 * 折叠 / 展开的时长 220ms。
 *
 * 比转场快一档 —— 折叠的距离往往很大（一条几万像素的消息），拖久了会像在等它。
 * **用户气泡与思考面板共用这一个数**：两处都是"折叠"，节奏不一致会很明显。
 */
const val FoldAnimMs = 220

/** 缓动：平台标准的先快后慢。 */
val MotionEasing: Easing = FastOutSlowInEasing

/**
 * 页面转场。
 *
 * ```
 * 前进 ── 新页从右侧整屏推入，旧页整屏推向左侧
 * 后退 ── 反过来
 * ```
 *
 * **两个刻意的选择**：
 *
 * 1. **整屏推，不是推一半**。出场页只推 1/3 屏的话，中途两页会在屏幕中间
 *    叠成重影；整屏推则两页首尾相接、严丝合缝，全程没有重叠也没有空档。
 * 2. **不叠淡入淡出**。位移途中两页都是半透明的，底下的空白会透出来，
 *    正是上面那团"糊"的来源。平台自己的 Activity 切换也不带淡出。
 *
 * 全屏页面之间的横向切换，本来就该是这个样子。
 *
 * @param forward 目标页比当前页**更深**（往栈里压）时为 true。
 *   同级之间互不跳转，所以只判深度就够。
 */
fun AnimatedContentTransitionScope<*>.screenTransform(forward: Boolean): ContentTransform {
    val slide = tween<IntOffset>(MotionDurationMs, easing = MotionEasing)

    val enter = slideInHorizontally(slide) { width -> if (forward) width else -width }
    val exit = slideOutHorizontally(slide) { width -> if (forward) -width else width }

    return enter togetherWith exit
}
