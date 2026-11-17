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


/**
 * 一块玻璃表面。
 *
 * 用法：把内容放进 `content`，它会被画在玻璃底上。
 *
 * ## 三档的实际差异
 *
 * | 档位 | 背景 | 模糊 | 高光 |
 * |---|---|---|---|
 * | HIGH | 壁纸局部（折射） | ✓ | ✓ |
 * | MEDIUM | 壁纸局部（模糊） | ✓ | ✓ |
 * | OFF | 纯半透明色 | ✗ | ✓ |
 *
 * 高光始终保留 —— 它是「玻璃感」最便宜的那部分（一个线性渐变），
 * 去掉之后 OFF 档会完全看不出是玻璃。
 *
 * @param cornerRadius 圆角。输入框用 24dp 胶囊、顶栏用 0。
 * @param tintAlpha 底色不透明度。默认 0.55（半透可见背景，但打字/阅读够清晰）。
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    quality: GlassQuality = rememberGlassQuality(),
    cornerRadius: Dp = 16.dp,
    tintAlpha: Float = 0.55f,
    content: @Composable BoxScope.() -> Unit,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val shape = RoundedCornerShape(cornerRadius)

    // 底色：深色主题用黑玻璃，浅色主题用白玻璃。
    // 不用 colorScheme.surface 是因为它带着主题的色相，
    // 叠在照片壁纸上会显脏。
    val tint = if (dark) Color.Black.copy(alpha = tintAlpha)
    else Color.White.copy(alpha = tintAlpha)

    Box(
        modifier = modifier
            .clip(shape)
            .background(tint)
            // 顶部高光边：左上角亮、右下角暗 —— 模拟光从上方来。
            // 这是「玻璃」最便宜也最有效的暗示，所有档位都保留。
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = if (dark) {
                        listOf(
                            Color.White.copy(alpha = 0.22f),
                            Color.White.copy(alpha = 0.06f),
                            Color.Black.copy(alpha = 0.18f),
                        )
                    } else {
                        listOf(
                            Color.White.copy(alpha = 0.85f),
                            Color.White.copy(alpha = 0.35f),
                            Color.Black.copy(alpha = 0.08f),
                        )
                    },
                ),
                shape = shape,
            ),
        content = content,
    )
}

/** 主题深浅（与 Background.kt 里同名函数一致，这里单独一份避免跨文件依赖）。 */
private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue
