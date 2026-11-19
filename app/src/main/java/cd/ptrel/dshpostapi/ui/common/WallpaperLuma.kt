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
                    var sum = 0f
                    for (p in px) {
                        sum += 0.299f * ((p shr 16) and 0xFF) / 255f +
                            0.587f * ((p shr 8) and 0xFF) / 255f +
                            0.114f * (p and 0xFF) / 255f
                    }
                    bmp.recycle()
                    if (px.isEmpty()) -1f else sum / px.size
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
     * 该壁纸是否与主题方向**相反**（需要反转配色）。
     *
     * @param luma 壁纸平均亮度（[of] 的返回值）
     * @param isDarkTheme 当前主题是否深色
     * @return true = 深壁纸配浅主题（或反过来），正文与表格都需要换色
     */
    fun needsInversion(luma: Float, isDarkTheme: Boolean): Boolean {
        if (luma < 0f) return false
        // ⚠️ 阈值**故意不对称**（v0.22.1）。
        //
        // 实测一张「上浅下深」的壁纸平均亮度 0.52，被判为「不明显」而不反转 ——
        // 但正文实际压在底部深色区，深色文字几乎看不见。
        //
        // 平均亮度会掩盖局部反差，所以这里偏向「判定为暗」：
        //   · 平均 0.6 以下就认为该用浅色文字（深色主题）
        //   · 只有明显偏亮（>0.72）才认为浅色主题安全
        //
        // 理由是不对称的风险：
        //   深底 + 深字 = 完全看不见（不可用）
        //   亮底 + 浅字 = 勉强可读（不便但不致命）
        // 宁可多翻一次，也不要出现「看不见」。
        val wallpaperDark = luma < 0.60f
        val wallpaperLight = luma > 0.72f
        return (wallpaperDark && !isDarkTheme) || (wallpaperLight && isDarkTheme)
    }
}
