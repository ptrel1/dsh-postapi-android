package cd.ptrel.dshpostapi.ui.common

import android.graphics.BitmapFactory
import java.io.File

/**
 * 壁纸明暗检测（v0.22.0）。
 *
 * ## 解决什么问题
 *
 * 主题（浅色/深色）与壁纸（亮/暗）是**两个独立维度**，可能方向相反：
 * 用户在深色壁纸上用浅色主题 → 正文是深灰，几乎看不见；
 * 表格底是近白色，在深红壁纸上刺眼得像一块补丁。
 *
 * 用户截图里正是这种：表格纯白、正文深色、壁纸深红。
 *
 * ## 做法
 *
 * 读壁纸的平均亮度，与主题方向比对。方向相反时，**聊天页局部反转配色** ——
 * 深壁纸配浅色文字、浅壁纸配深色文字。
 *
 * 只影响聊天页（不碰列表页/设置页）：那两页没有大面积文字压在壁纸上。
 *
 * ## 为什么采样而不是全量
 *
 * 壁纸可能 4000×3000，全量遍历要几十毫秒且吃内存。
 * 均匀取 64×64 个点已经足够判断「整体偏亮还是偏暗」。
 */
object WallpaperLuma {

    /** 缓存：路径 + 修改时间戳做键。 */
    private var cachedKey: String? = null
    private var cachedLuma: Float = -1f

    /** 最暗 25% 区域的平均亮度。文字压在深色区时，这个值才是判据。 */
    @Volatile
    private var p25: Float = -1f

    /**
     * 壁纸平均亮度（0~1）；无法读取返回 -1。
     *
     * @param path 壁纸文件路径；null / 空 / 文件不存在 → -1
     */
    @Synchronized
    fun of(path: String?): Float {
        if (path.isNullOrBlank()) return -1f
        val stamp = try {
            val f = File(path)
            if (f.isFile) (f.length() shl 32) xor f.lastModified() else 0L
        } catch (e: Exception) { 0L }
        val key = path + "#" + stamp
        if (key == cachedKey) return cachedLuma

        val luma = try {
            // 先只读尺寸，再按 1/8 降采样解码 —— 比全量解码快一个数量级，
            // 且对「整体偏亮还是偏暗」这个判断完全够用。
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                -1f
            } else {
                var sample = 1
                while (opts.outWidth / (sample * 2) >= 64 && opts.outHeight / (sample * 2) >= 64) sample *= 2
                val dec = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = BitmapFactory.decodeFile(path, dec)
                if (bmp == null) {
                    -1f
                } else {
                    val w = bmp.width; val h = bmp.height
                    val px = IntArray(w * h)
                    bmp.getPixels(px, 0, w, 0, 0, w, h)
                    // 逐像素算亮度，排序后取分位数 ——
                    // **平均亮度会掩盖局部反差**：上半部浅、下半部深的壁纸平均 0.52，
                    // 但正文压在深色区时依然看不清（用户实测）。
                    //
                    // 取 P25（最暗的四分之一）更能代表「文字最可能压在什么亮度上」。
                    var sum = 0f
                    val lumas = FloatArray(px.size)
                    for (i in px.indices) {
                        val p = px[i]
                        val l = 0.299f * ((p shr 16) and 0xFF) / 255f +
                            0.587f * ((p shr 8) and 0xFF) / 255f +
                            0.114f * (p and 0xFF) / 255f
                        lumas[i] = l
                        sum += l
                    }
                    bmp.recycle()
                    if (px.isEmpty()) {
                        -1f
                    } else {
                        lumas.sort()
                        p25 = lumas[(lumas.size * 25 / 100).coerceIn(0, lumas.size - 1)]
                        sum / px.size
                    }
                }
            }
        } catch (e: Exception) {
            -1f
        } catch (o: OutOfMemoryError) {
            -1f
        }

        cachedKey = key
        cachedLuma = luma
        return luma
    }

    /**
     * 在「配色跟随壁纸」模式下，页面该用深色还是浅色。
     *
     * ## 为什么是二档而不是三档
     *
     * 早先用了 `<0.60 暗 / >0.72 亮 / 中间保持` 的三档判定，
     * 但中间地带恰恰是最需要决定的区间 —— 保持系统主题等于把问题留在原地。
     *
     * 现在以 0.5 为界二分：**明暗只由壁纸决定**，不掺系统主题。
     * 想跟随系统就关掉这个开关（见 AppPrefs.isThemeFollowWallpaper）。
     *
     * @return true = 该用深色（壁纸偏暗），false = 该用浅色
     */
    fun preferDark(luma: Float): Boolean {
        if (luma < 0f) return false
        // 用 P25 而不是平均值：平均会被亮区拉高，掩盖「文字压在暗处」的问题。
        //
        // 阈值 0.62 是实测出来的：用户那张「上浅下深」的壁纸平均 0.52、P25 约 0.35，
        // 判定为暗 → 用浅色文字，正文立刻可读。
        // 只给平均值时，0.52 恰好落在断点附近，来回翻。
        val ref = if (p25 >= 0f) p25 else luma
        return ref < 0.62f
    }
}