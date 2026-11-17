package cd.ptrel.dshpostapi.ui.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cd.ptrel.dshpostapi.AppPrefs


/*
 * 玻璃质感表面（v0.17.0）。
 *
 * ## 为什么要分档
 *
 * 真液体玻璃（边缘折射）只有 Android 13+ 能做（AGSL RuntimeShader 是 API 33）;
 * Android 12 能模糊（RenderEffect 是 API 31）;
 * 更老的设备什么硬件加速滤镜都没有。
 *
 * 三档共享同一套**视觉语言**（半透明 + 顶部高光边 + 圆角），
 * 只是保真度不同。用户在老设备上看到的是「玻璃感弱一点」，不是「另一个 App」。
 *
 * ## 性能策略（关键）
 *
 * 1. **静态区域不重算**：顶栏 / 输入框背后是壁纸的固定区域，
 *    壁纸不变 → 模糊结果不变 → 用 remember 缓存。开销 ≈ 0。
 * 2. **降采样**：模糊前把区域缩到 1/4，模糊后再放大。
 *    计算量降到 1/16，观感几乎无差（blur 本身就是低频信息）。
 * 3. **档位可关**：老设备 / 省电场景设 OFF，完全不碰 GPU。
 *
 * ## 已知限制
 *
 * API 31-32 的 `Modifier.blur()` **无法只模糊「背后」** —— 它模糊的是元素自身。
 * 所以这里不用它，改成「先解码壁纸局部 → 降采样 → GPU 模糊 → 画成背景图」。
 * 这样既只影响目标区域，又能缓存。
 */
/**
 * 玻璃档位。
 *
 * [HIGH] 需要 API 33+；在更低版本上自动降级为 [MEDIUM]。
 */
enum class GlassQuality { HIGH, MEDIUM, OFF }

/** 从设置读档位，并按设备能力降级。 */
@Composable
fun rememberGlassQuality(): GlassQuality {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    return remember(ctx) {
        val stored = AppPrefs.getGlassQuality(ctx)
        when (stored) {
            AppPrefs.GLASS_OFF -> GlassQuality.OFF
            AppPrefs.GLASS_HIGH -> if (Build.VERSION.SDK_INT >= 33) GlassQuality.HIGH else GlassQuality.MEDIUM
            else -> GlassQuality.MEDIUM
        }
    }
}

/* ═══ 玻璃质感：不靠模糊，靠光学层次（v0.17.2 定稿）═══
 *
 * ## 为什么最终不做模糊
 *
 * 试过两条路都不划算：
 *
 *   ① `Modifier.blur()` —— 模糊的是**元素自身**，会把文字一起糊掉。
 *      要做「透过玻璃看背后」必须另画一份壁纸并对齐坐标，
 *      而玻璃块往往只是屏幕的一条（56dp 顶栏），
 *      需要把全屏尺寸传进来做换算 —— 复杂且容易错位。
 *
 *   ② 截屏 + PixelCopy —— 异步、慢，且每帧都要重来。
 *
 * ## 真正决定观感的不是模糊
 *
 * 用户看「像不像玻璃」，靠的是这几样：
 *
 *   · **色罩够不够压住背景** —— 不够时文字混在照片里（用户反馈过）
 *   · **边缘有没有厚度** —— 硬边看起来是「盖了块板子」
 *   · **有没有高光** —— 光从上方来，上沿亮、下沿暗
 *
 * 模糊只影响「背后那层糊不糊」，在**小面积控件**上贡献很小。
 * 苹果的 Liquid Glass 在按钮这类小控件上本来就几乎不模糊，
 * 靠的是折射与高光。
 *
 * ## 所以这里做三层光学
 *
 *   1. 色罩（tint）：压住背景保证可读
 *   2. 内高光：顶部一条由亮到透明的渐变，模拟玻璃上沿的反射
 *   3. 边缘：上沿亮、下沿暗的 1px 描边，给出厚度
 */

/**
 * 玻璃表面。
 *
 * @param cornerRadius 圆角。顶栏传 0（贴边），输入框传自身圆角。
 * @param tintAlpha 色罩不透明度。0.78 是实测能压住复杂照片、又不至于变成实心块的值。
 * @param topHighlight 是否画顶部内高光。贴边的顶栏在状态栏下方，画了反而脏，可关。
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    quality: GlassQuality = rememberGlassQuality(),
    cornerRadius: Dp = 16.dp,
    tintAlpha: Float = 0.78f,
    topHighlight: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val shape = RoundedCornerShape(cornerRadius)

    // OFF 档不画任何光学层：用户明确要省电时，连渐变都不该有。
    val active = quality != GlassQuality.OFF

    // 色罩：深色主题用黑玻璃，浅色主题用白玻璃。
    // 不用 colorScheme.surface 是因为它带着主题色相，叠在照片上会显脏。
    val tint = if (dark) Color.Black.copy(alpha = tintAlpha)
    else Color.White.copy(alpha = tintAlpha)

    Box(
        modifier = modifier
            .clip(shape)
            .background(tint)
            // 内高光 + 边缘厚度：一次 drawWithContent 画完，避免多次叠加。
            .then(
                if (active) Modifier.drawWithContent {
                    drawContent()
                    // ② 顶部内高光：从上方 18% 处开始淡出，模拟玻璃上沿反射。
                    if (topHighlight) {
                        val h = size.height * 0.45f
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = if (dark) {
                                    listOf(Color.White.copy(alpha = 0.10f), Color.Transparent)
                                } else {
                                    listOf(Color.White.copy(alpha = 0.55f), Color.Transparent)
                                },
                                startY = 0f,
                                endY = h,
                            ),
                            size = androidx.compose.ui.geometry.Size(size.width, h),
                        )
                    }
                } else Modifier
            )
            // ③ 边缘：上沿亮、下沿暗。用渐变描边而不是纯色，才像有厚度。
            .then(
                if (active) Modifier.border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = if (dark) {
                            listOf(
                                Color.White.copy(alpha = 0.20f),
                                Color.White.copy(alpha = 0.04f),
                                Color.Black.copy(alpha = 0.28f),
                            )
                        } else {
                            listOf(
                                Color.White.copy(alpha = 0.90f),
                                Color.White.copy(alpha = 0.40f),
                                Color.Black.copy(alpha = 0.10f),
                            )
                        },
                    ),
                    shape = shape,
                ) else Modifier
            ),
        content = content,
    )
}
private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue
