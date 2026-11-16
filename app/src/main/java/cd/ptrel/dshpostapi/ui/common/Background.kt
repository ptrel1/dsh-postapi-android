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
    // ⚠️ 缓存键必须包含**文件修改时间**（v0.13.1 修复）。
    //
    // 用户第二次换背景时主页不更新 —— 因为 saveBackground 写的是**同一个路径**
    // （filesDir/background/bg.img，固定名以便覆盖旧图），
    // 而 remember(path) / LaunchedEffect(path) 只认字符串变化：
    // path 没变 → 不重新解码 → 界面仍是上一张图。
    //
    // 用 (路径, mtime, 大小) 三元组当键：换图后 mtime 必然变，键就变了。
    // 大小兜底覆盖「同一秒内替换且 mtime 精度不够」的边缘情况。
    // ⚠️ 不要用 remember 包住：换图时 path 不变，remember(path) 会一直返回旧 stamp，
    // 等于回到了原来的 bug。这里每次重组都读一次 stat ——
    // 一次 stat(2) 的成本远低于一次错误的重绘决策。
    val stamp = if (path.isNullOrBlank()) 0L else {
        try {
            val f = File(path)
            // mtime 与长度拼成一个 Long：长度在高位，mtime 在低位。
            // 加长度是因为某些文件系统 mtime 精度只到秒，
            // 同一秒内换图时长度变化能兜住。
            if (f.isFile) (f.length() shl 32) xor f.lastModified() else 0L
        } catch (e: Exception) {
            0L
        }
    }
    // 键 = 路径 + 时间戳；任一变都重新解码。
    val cacheKey = (path ?: "") + "#" + stamp
    var image by remember(cacheKey) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(cacheKey) {
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
