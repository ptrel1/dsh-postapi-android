package cd.ptrel.dshpostapi

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * 把下载好的 APK 交给系统安装器。
 *
 * ================= 两条必须同时满足的前提 =================
 * 1. FileProvider：Android 7 (API 24) 起禁止用 file:// 给别的应用传文件
 *    （FileUriExposedException）。必须用 content:// + FileProvider 授权。
 *    本 App 的 authority 是 `${applicationId}.fileprovider` = cd.ptrel.dshpostapi.fileprovider，
 *    与 AndroidManifest 的 <provider> 和 res/xml/file_paths.xml 三处必须一致。
 * 2. REQUEST_INSTALL_PACKAGES：Android 8 (API 26) 起安装未知来源应用需要
 *    "应用专属"开关（不再是全局开关），必须运行时检查 canRequestPackageInstalls()。
 *    未授权时**不能**直接调安装 Intent —— 系统会静默失败或弹一个无从下手的提示。
 * ==========================================================
 *
 * 为什么不静默用 PackageInstaller 的 Session API：
 *   它会绕过"安装确认"界面，行为因 ROM 而异，且对 debug 包/未签名包更容易翻车。
 *   用 ACTION_VIEW + 系统安装器是用户最熟悉、兼容性最好的路径。
 */
object ApkInstaller {

    private const val TAG = "ApkInstaller"
    private const val REQ_INSTALL_PERMISSION = 4011

    /** 是否需要（并且缺少）"安装未知应用"授权。
     *  API < 26 没有这个开关，安装时由系统统一提示（也有的 ROM 需要去设置里开），返回 false。 */
    fun needsInstallPermission(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return !activity.packageManager.canRequestPackageInstalls()
    }

    /**
     * 打开本 App 的「安装未知应用」设置页。
     *
     * 为什么要 try/catch：少数 ROM 没有实现该 Action，会抛 ActivityNotFoundException。
     * 此时退回本 App 的详情页，用户仍能自己找到那个开关，不至于"点了没反应"。
     */
    fun openInstallPermissionSettings(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:" + activity.packageName))
            try {
                activity.startActivityForResult(intent, REQ_INSTALL_PERMISSION)
                return
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "MANAGE_UNKNOWN_APP_SOURCES not handled, falling back to app details")
            }
        }
        try {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:" + activity.packageName)),
            )
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "APPLICATION_DETAILS_SETTINGS not handled either")
        }
    }

    /**
     * 拉起系统安装器。调用前**必须**已确认 [needsInstallPermission] 为 false，
     * 否则返回 false 并附带原因，由调用方去引导授权。
     *
     * @return true = 安装器已被拉起；false = 没拉起来（调用方应提示用户）。
     */
    fun install(activity: Activity, apkFile: File): Result {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            return Result.Failure("apk file missing or empty")
        }
        if (needsInstallPermission(activity)) {
            return Result.NeedsPermission
        }
        val uri: Uri = try {
            FileProvider.getUriForFile(activity, fileProviderAuthority(activity), apkFile)
        } catch (e: IllegalArgumentException) {
            // 典型原因：apkFile 不在 file_paths.xml 声明的目录里。
            // 这条错误一定要原样记下来 —— 否则表现只是"点安装没反应"。
            Log.e(TAG, "FileProvider refused path: " + e.message)
            return Result.Failure("FileProvider refused path (check file_paths.xml)")
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            activity.startActivity(intent)
            Result.Started
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "no installer activity: " + e.message)
            Result.Failure("no package installer on this device")
        }
    }

    /** authority 与 AndroidManifest 的 <provider android:authorities> 完全一致。 */
    fun fileProviderAuthority(activity: Activity): String =
        activity.packageName + ".fileprovider"

    sealed class Result {
        /** 安装器已拉起（是否真的装成功由系统界面决定，本 App 无权知道）。 */
        data object Started : Result()

        /** 缺少「安装未知应用」授权，调用方应引导用户去设置页。 */
        data object NeedsPermission : Result()

        /** 其它失败（FileProvider 配置错 / 文件丢失 / 设备没有安装器）。 */
        data class Failure(val reason: String) : Result()
    }
}
