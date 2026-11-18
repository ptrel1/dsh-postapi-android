package cd.ptrel.dshpostapi.compose

import android.content.Context
import android.net.Uri
import cd.ptrel.dshpostapi.AppPrefs

/**
 * 背景图落盘（v0.20.0）。
 *
 * ## 为什么抽成独立文件
 *
 * 原本只有会话列表页能选背景，逻辑写在 ComposeSessionsActivity 里。
 * v0.20.0 把「背景图」搬进聊天页的设置弹层，两处都要用 ——
 * 复制一份必然漂移（下一个人只改一处）。
 *
 * ## 为什么用带时间戳的新文件名
 *
 * 覆盖固定路径会踩两个坑（v0.13.1 修复过）：
 *   ① Compose 的 `remember(path)` 只认字符串，path 不变就不重新解码
 *      → 用户换图后界面还是上一张
 *   ② 就算改成看 mtime，同尺寸图片在同一秒内替换时 mtime 可能不变
 *      → 仍然不刷新
 *
 * 换成新文件名后 path 本身就变了，两个坑一起消失。
 * 旧文件在写入成功后删掉，私有目录里始终只有一张图。
 *
 * @return 新文件的绝对路径；失败返回 null（调用方提示用户）
 */
internal fun saveBackgroundImage(context: Context, uri: Uri): String? {
    return try {
        val dir = java.io.File(context.filesDir, "background")
        if (!dir.exists() && !dir.mkdirs()) return null
        val target = java.io.File(dir, "bg-" + System.currentTimeMillis() + ".img")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        // 写成功了才清理旧图：万一新图写失败，旧的还能用。
        try {
            val previous = AppPrefs.getBackgroundPath(context)
            if (previous != null && previous != target.absolutePath) {
                java.io.File(previous).delete()
            }
        } catch (e: Exception) {
            // 删旧图失败不影响使用，只是多占一份空间
        }
        AppPrefs.setBackgroundPath(context, target.absolutePath)
        target.absolutePath
    } catch (e: Exception) {
        android.util.Log.w("Background", "save failed (" + e.javaClass.simpleName + ")")
        null
    }
}
