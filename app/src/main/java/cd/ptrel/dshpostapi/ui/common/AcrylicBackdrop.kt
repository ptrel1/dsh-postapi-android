package cd.ptrel.dshpostapi.ui.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

/**
 * 亚克力背景（v0.21.0）。
 *
 * ## 什么是亚克力
 *
 * Windows 的 Acrylic 材料 = **模糊 + 噪点 + 色调**。
 * 与单纯的磨砂玻璃相比，噪点让它看起来像「有颗粒的实体」，而不是「一层雾」。
 *
 * ## 为什么现在能做
 *
 * 之前放弃模糊，是因为顶栏/输入栏的坐标换算复杂。
 * 但**输入栏固定在屏幕底部、高度已知**，背后就是壁纸的固定区域 ——
 * 只需要在「壁纸变化 / 高度变化」时算一次，之后一直复用。
 *
 * ## 实现路径
 *
 * ```
 * 壁纸 Bitmap
 *   ↓ 按输入栏在屏幕中的绝对位置裁剪（onGloballyPositioned 给的坐标）
 * 目标区域
 *   ↓ 降采样到 1/4（计算量 → 1/16，模糊是低通，看不到损失）
 * 小图
 *   ↓ RenderEffect 模糊（API 31+）或手写 box blur（更低版本）
 * 模糊结果
 * ```
 *
 * ## 降采样的依据
 *
 * 模糊本质是低通滤波，输出里没有高频信息。
 * 1/4 缩放后再模糊，与全尺寸模糊的观感差异不可见 —— 这是各家做磨砂玻璃的标准做法。
 */
object AcrylicBackdrop {

    /** 缓存：同一组 (壁纸指纹, 区域, 半径) 只算一次。 */
    private var cacheKey: String? = null
    private var cached: androidx.compose.ui.graphics.ImageBitmap? = null

    /**
     * 生成（或复用）一块亚克力背景。
     *
     * @param path 壁纸路径
     * @param left 目标区域在屏幕中的左边界（px）
     * @param top 上边界
     * @param width 宽（px）
     * @param height 高（px）
     * @param screenW 屏幕宽（px），用于把屏幕坐标换成壁纸坐标
     * @param screenH 屏幕高（px）
     * @param blurRadius 模糊半径（px，按目标区域算）
     * @return 可直接画的 ImageBitmap；任何环节失败返回 null（调用方退回纯色）
     */
    @Synchronized
    fun build(
        path: String?,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        screenW: Int,
        screenH: Int,
        blurRadius: Int = 18,
    ): androidx.compose.ui.graphics.ImageBitmap? {
        if (path.isNullOrBlank() || width <= 0 || height <= 0 || screenW <= 0 || screenH <= 0) return null

        val stamp = try {
            val f = File(path)
            if (f.isFile) (f.length() shl 32) xor f.lastModified() else 0L
        } catch (e: Exception) { 0L }
        val key = path + "#" + stamp + "#" + left + "," + top + "," + width + "x" + height + "#" + blurRadius
        if (key == cacheKey) return cached

        val full = try { BitmapFactory.decodeFile(path) } catch (e: Exception) { null } ?: return null

        // 壁纸是按 ContentScale.Crop 铺满屏幕的：取「覆盖屏幕」的缩放比，
        // 再据此把屏幕坐标换算成壁纸像素坐标。
        val scale = maxOf(screenW.toFloat() / full.width, screenH.toFloat() / full.height)
        val drawW = full.width * scale
        val drawH = full.height * scale
        val offsetX = (drawW - screenW) / 2f
        val offsetY = (drawH - screenH) / 2f

        val srcLeft = ((left + offsetX) / scale).toInt().coerceIn(0, (full.width - 1).coerceAtLeast(0))
        val srcTop = ((top + offsetY) / scale).toInt().coerceIn(0, (full.height - 1).coerceAtLeast(0))
        val srcW = (width / scale).toInt().coerceAtLeast(1)
        val srcH = (height / scale).toInt().coerceAtLeast(1)
        val safeW = srcW.coerceAtMost(full.width - srcLeft).coerceAtLeast(1)
        val safeH = srcH.coerceAtMost(full.height - srcTop).coerceAtLeast(1)

        val slice = try {
            Bitmap.createBitmap(full, srcLeft, srcTop, safeW, safeH)
        } catch (e: Exception) { return null } catch (o: OutOfMemoryError) { return null }

        // 降采样到 1/4，省 15/16 的计算量
        val dw = (safeW / 4).coerceAtLeast(1)
        val dh = (safeH / 4).coerceAtLeast(1)
        val small = try { Bitmap.createScaledBitmap(slice, dw, dh, true) }
            catch (o: OutOfMemoryError) { slice }
        if (small !== slice) slice.recycle()

        val blurred = boxBlur(small, (blurRadius / 4).coerceAtLeast(1))
        val result = blurred.asImageBitmap()
        cached = result
        cacheKey = key
        return result
    }

    /**
     * 简易 box blur（三次迭代近似高斯）。
     *
     * 为什么不用 RenderEffect：它作用在**控件**上，而我们要先算出一张位图再画。
     * 纯 Kotlin 实现对小尺寸（1/4 后通常 100×60）完全够快，且不挑 API。
     *
     * 三次 box blur 是高斯的标准近似（中心极限定理），与单次大半径相比边缘更柔和。
     */
    private fun boxBlur(src: Bitmap, radius: Int): Bitmap {
        if (radius < 1) return src
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        var cur = pixels
        repeat(3) {
            cur = blurPass(cur, w, h, radius)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(cur, 0, w, 0, 0, w, h)
        return out
    }

    /** 一次水平+垂直的 box 滤波。 */
    private fun blurPass(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val tmp = IntArray(src.size)
        val out = IntArray(src.size)
        // 水平
        for (y in 0 until h) {
            var r = 0; var g = 0; var b = 0; var a = 0; var n = 0
            for (x in -radius..w - 1 + radius) {
                if (x + radius < w) {
                    val p = src[y * w + (x + radius)]
                    r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; a += (p ushr 24); n++
                }
                if (x - radius - 1 >= 0) {
                    val p = src[y * w + (x - radius - 1)]
                    r -= (p shr 16) and 0xFF; g -= (p shr 8) and 0xFF; b -= p and 0xFF; a -= (p ushr 24); n--
                }
                if (x in 0 until w && n > 0) {
                    tmp[y * w + x] = ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
                }
            }
        }
        // 垂直
        for (x in 0 until w) {
            var r = 0; var g = 0; var b = 0; var a = 0; var n = 0
            for (y in -radius..h - 1 + radius) {
                if (y + radius < h) {
                    val p = tmp[(y + radius) * w + x]
                    r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; a += (p ushr 24); n++
                }
                if (y - radius - 1 >= 0) {
                    val p = tmp[(y - radius - 1) * w + x]
                    r -= (p shr 16) and 0xFF; g -= (p shr 8) and 0xFF; b -= p and 0xFF; a -= (p ushr 24); n--
                }
                if (y in 0 until h && n > 0) {
                    out[y * w + x] = ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
                }
            }
        }
        return out
    }

    /** 壁纸或布局变化时清缓存。 */
    @Synchronized
    fun invalidate() {
        cacheKey = null
        cached = null
    }
}
