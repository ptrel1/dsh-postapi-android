package cd.ptrel.dshpostapi.ui.common

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 全局背景（v0.12.0）：用户自定义图片 + 主题色兜底。
 *
 * ## 为什么要垫一层遮罩
 *
 * 直接贴图会让文字不可读 —— 浅色图上白字消失、深色图上黑字消失。
 * 遮罩的作用是把任意图片压到「足够暗/足够亮」的中间调，让前景色始终有对比。
 *
 * 遮罩不透明度取 [SCRIM_ALPHA]，并在深色主题下更重一点：
 * 深色主题的前景是浅色文字，需要图更暗才能保证对比度。
 *
 * ## 为什么用 Bitmap 解码而不是 AsyncImage
 *
 * 本项目没引 Coil。为了一个背景图引入整个图片加载库不划算 ——
 * 背景只有一张、尺寸固定、启动时读一次就够。
 * 解码放 IO 线程，失败（文件被删/损坏）自动退回纯色背景。
 */
@Composable
fun AppBackground(
    path: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // 路径变化才重新解码；null / 空串 = 没有背景图
    val bitmap = remember(path) {
        if (path.isNullOrBlank()) null else null
    }
    var image by remember(path) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(path) {
        image = if (path.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                try {
                    val f = File(path)
                    if (!f.isFile) null
                    else BitmapFactory.decodeFile(path)?.asImageBitmap()
                } catch (e: Exception) {
                    // 文件被删/损坏：静默退回纯色，不让背景问题影响主功能
                    null
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        image?.let { bmp ->
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // 遮罩：深色主题更重，保证浅色文字在任意图上都有对比
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Color.Black.copy(alpha = if (dark) SCRIM_ALPHA_DARK else SCRIM_ALPHA_LIGHT),
                    ),
            )
        }
        content()
    }
}

/** 浅色主题的遮罩不透明度：够压住花哨的图，又不至于把图糊成一片黑。 */
private const val SCRIM_ALPHA_LIGHT = 0.55f

/** 深色主题更重 —— 前景是浅色文字，图要更暗才有对比。 */
private const val SCRIM_ALPHA_DARK = 0.72f

/** 粗略亮度，只用来判断当前主题深浅。 */
private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue
