package cd.ptrel.dshpostapi

import android.app.Activity
import android.content.DialogInterface
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File

/**
 * 「检查更新」的 UI 编排：静默检查 → 弹窗 → 下载（带进度）→ 校验 → 安装。
 *
 * 三条贯穿全文件的约束（改代码前先读）：
 *   1. **检查阶段失败一律静默**：只写 Log，不 Toast、不弹窗。用户没有主动要求检查更新，
 *      打扰是负收益（尤其在内网/离线场景，每次登录都弹一次"检查更新失败"很烦）。
 *   2. **不强制更新**：弹窗只有「稍后」与「立即更新」。没有"退出 App"这种选项。
 *   3. **cookie 不落日志**：进入这里的 cookie 只用于发请求，任何日志都不打印它的值。
 *
 * 生命周期：所有入口都持有 Activity 引用，回调前检查 `isFinishing || isDestroyed`，
 * 避免"用户退出后回调仍去操作已销毁的 Activity"。
 */
object Updates {

    private const val TAG = "Updates"

    /** 防止重复检查：登录后与 WebView 首次加载后各调用一次时，5 秒内的第二次直接丢弃。 */
    @Volatile
    private var lastCheckAt: Long = 0L
    private const val CHECK_DEBOUNCE_MS = 5000L

    /** 正在进行的检查/下载；避免用户连点「立即更新」产生两条下载。 */
    @Volatile
    private var active: Cancelable? = null

    private interface Cancelable {
        fun cancel()
    }

    /**
     * 静默检查更新。
     *
     * 调用时机（用户指定）：
     *   - 登录成功后（此时才有 cookie）—— LoginActivity；
     *   - WebView 首次加载成功后 —— WebViewActivity（用 UpdateSession 里存的会话上下文）。
     *
     * **不要**在 App 启动时调用：那时还没有 cookie，请求必然 401。
     */
    fun checkQuietly(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        // v0.3.0：会话上下文改为从加密存储现读（UpdateSession 是 AccountStore 的只读代理）。
        // 仍然是"没有会话就静默返回"——首装 / 已退出登录时它是常态，不是错误。
        val session = UpdateSession.get(activity)
        if (session == null) {
            // 没有会话（首装、刚退出登录、自动重登失败已清凭据）⇒ 无从检查。
            // 这不是错误，不需要日志噪音。
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastCheckAt < CHECK_DEBOUNCE_MS) return
        lastCheckAt = now

        UpdateClient.checkUpdate(
            hostBaseUrl = session.hostBaseUrl,
            cookieName = session.cookieName,
            cookieValue = session.cookieValue,
        ) { info, error ->
            if (activity.isFinishing || activity.isDestroyed) return@checkUpdate
            if (error != null) {
                // 静默：只记日志。503（未配置）是**正常状态**，用 info 级；其它用 warn 级。
                if (error is UpdateError.NotConfigured) {
                    Log.i(TAG, "update check: " + error.debugText() + " (normal when distribution is off)")
                } else {
                    Log.w(TAG, "update check failed: " + error.debugText())
                }
                return@checkUpdate
            }
            if (info == null) return@checkUpdate
            if (info.code <= BuildConfig.VERSION_CODE) {
                Log.i(TAG, "update check: up to date (remote=" + info.code + " local=" + BuildConfig.VERSION_CODE + ")")
                return@checkUpdate
            }
            if (!UpdatePrefs.shouldPrompt(activity, info.code)) {
                Log.i(TAG, "update check: remote=" + info.code + " skipped by user")
                return@checkUpdate
            }
            if (!info.apkReady) {
                // 清单在、包没上传：静默（这是发布流程的中间态，用户无能为力）。
                Log.i(TAG, "update check: remote=" + info.code + " apkReady=false, not prompting")
                return@checkUpdate
            }
            showPrompt(activity, info)
        }
    }

    /** 发现新版本弹窗。[info] 非空且 [UpdateInfo.apkReady] 已为 true。 */
    private fun showPrompt(activity: Activity, info: UpdateInfo) {
        val message = if (info.notes.isBlank()) {
            activity.getString(R.string.update_message_no_notes)
        } else {
            info.notes
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_title, info.version))
            .setMessage(message)
            // 不可取消（点外部不关闭）：误触关闭与「稍后」语义不同，
            // 而「稍后」会写"不再提示该版本"，误触代价太大。
            .setCancelable(false)
            .setPositiveButton(R.string.update_action_update) { _, _ ->
                startDownload(activity, info)
            }
            .setNegativeButton(R.string.update_action_later) { _, _ ->
                UpdatePrefs.markSkipped(activity, info.code)
                Log.i(TAG, "update " + info.code + " skipped by user")
            }
            .create()
        dialog.show()
    }

    /**
     * 下载 + 校验 + 安装。
     *
     * 进度用 "**不可取消的进度对话框 + 百分数文字**" 而不是通知：
     *   - 本流程从用户点「立即更新」开始，全程在前台（下载期间用户就在看着），
     *     通知反而多一道权限（API 33 需要 POST_NOTIFICATIONS）与多一个可见性状态；
     *   - 6MB 通常在数秒~数十秒内完成，进度对话框足够；
     *   - 对话框本身给出"取消"按钮，等于把"可以重试"变成了"用户可控"。
     */
    private fun startDownload(activity: Activity, info: UpdateInfo) {
        val session = UpdateSession.get(activity)
        if (session == null) {
            // 极端情形：登录会话上下文丢了（进程被系统回收后重建了界面）。
            // 这时无法带 cookie 下载，明确告诉用户而不是静默失败。
            Toast.makeText(activity, R.string.update_need_relogin, Toast.LENGTH_LONG).show()
            return
        }
        if (active != null) {
            Log.i(TAG, "download already in progress, ignoring duplicate request")
            return
        }

        val target = UpdateClient.apkTarget(activity, info.code)
        val dialogView: View = LayoutInflater.from(activity).inflate(R.layout.dialog_update_progress, null)
        val progressBar = dialogView.findViewById<LinearProgressIndicator>(R.id.updateProgress)
        val detailText = dialogView.findViewById<TextView>(R.id.updateProgressText)
        progressBar.isIndeterminate = true
        detailText.text = activity.getString(R.string.update_download_starting)

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_title, info.version))
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton(R.string.update_action_cancel, null)
            .create()

        // 先在点击监听里取消下载；对话框关闭由下载回调统一负责，避免"关两次"。
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener {
                active?.cancel()
            }
        }
        dialog.show()

        val call = UpdateClient.download(
            apkUrl = info.resolveApkUrl(session.hostBaseUrl),
            cookieName = session.cookieName,
            cookieValue = session.cookieValue,
            target = target,
            expectedSha256 = info.sha256,
            expectedSize = info.size,
            onProgress = { downloaded, total ->
                if (activity.isFinishing || activity.isDestroyed) return@download
                if (total > 0) {
                    progressBar.isIndeterminate = false
                    progressBar.max = 100
                    progressBar.setProgressCompat(((downloaded * 100) / total).toInt(), true)
                    detailText.text = activity.getString(
                        R.string.update_download_progress,
                        humanSize(downloaded),
                        humanSize(total),
                        ((downloaded * 100) / total).toInt(),
                    )
                } else {
                    progressBar.isIndeterminate = true
                    detailText.text = activity.getString(R.string.update_download_progress_unknown, humanSize(downloaded))
                }
            },
        ) { file, error ->
            active = null
            if (activity.isFinishing || activity.isDestroyed) return@download
            dialog.dismiss()
            if (file != null) {
                Log.i(TAG, "download ok: code=" + info.code + " bytes=" + file.length())
                installOrGuide(activity, file)
            } else if (error != null) {
                if (error == UpdateError.Cancelled) {
                    Log.i(TAG, "download cancelled by user")
                    return@download
                }
                Log.w(TAG, "download failed: " + error.debugText())
                showDownloadError(activity, info, error)
            }
        }
        active = object : Cancelable {
            override fun cancel() {
                call.cancel()
            }
        }
    }

    /** 安装；缺「安装未知应用」授权时先引导去设置页。 */
    private fun installOrGuide(activity: Activity, apkFile: File) {
        when (val result = ApkInstaller.install(activity, apkFile)) {
            ApkInstaller.Result.Started -> Unit
            ApkInstaller.Result.NeedsPermission -> {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.update_permission_title)
                    .setMessage(R.string.update_permission_message)
                    .setCancelable(false)
                    .setPositiveButton(R.string.update_permission_action) { _, _ ->
                        ApkInstaller.openInstallPermissionSettings(activity)
                    }
                    .setNegativeButton(R.string.update_action_later, null)
                    .show()
            }
            is ApkInstaller.Result.Failure -> {
                Log.e(TAG, "install failed: " + result.reason)
                Toast.makeText(
                    activity,
                    activity.getString(R.string.update_install_failed, result.reason),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /** 下载失败弹窗：说清原因 + 给「重试」。不把技术原因删掉（那是排查唯一线索）。 */
    private fun showDownloadError(activity: Activity, info: UpdateInfo, error: UpdateError) {
        val message = when (error) {
            is UpdateError.NoSpace -> activity.getString(
                R.string.update_error_no_space,
                humanSize(error.needBytes),
                humanSize(error.freeBytes),
            )
            is UpdateError.ChecksumMismatch -> activity.getString(R.string.update_error_checksum)
            is UpdateError.Timeout -> activity.getString(R.string.update_error_timeout)
            is UpdateError.Http -> activity.getString(R.string.update_error_http, error.code)
            is UpdateError.ApkNotReady -> activity.getString(R.string.update_error_not_ready)
            else -> activity.getString(R.string.update_error_generic)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_error_title)
            .setMessage(message + "\n\n" + error.debugText())
            .setCancelable(false)
            .setPositiveButton(R.string.update_action_retry) { _, _ ->
                startDownload(activity, info)
            }
            .setNegativeButton(R.string.update_action_close, null)
            .show()
    }

    /** 人类可读的字节数（6.2 MB）。只用于文案，不参与任何判断。 */
    private fun humanSize(bytes: Long): String {
        if (bytes < 0) return "?"
        if (bytes < 1024) return bytes.toString() + " B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(java.util.Locale.US, "%.0f KB", kb)
        val mb = kb / 1024.0
        return String.format(java.util.Locale.US, "%.1f MB", mb)
    }

    /** Activity 销毁时调用：取消在途下载，避免回调打到已销毁的界面。 */
    fun onActivityDestroyed() {
        active?.cancel()
        active = null
    }
}
