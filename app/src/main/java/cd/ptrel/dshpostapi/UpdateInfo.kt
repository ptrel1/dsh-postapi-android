package cd.ptrel.dshpostapi

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONException
import org.json.JSONObject

/**
 * 服务端发布的一个版本。
 *
 * 字段与 GET /api/dsh/v1/app-version 的成功响应一一对应：
 *   { ok, version, code, notes, publishedAt, size, sha256, filename, url, apkReady }
 *
 * 注意 [apkUrl] 是**相对路径**（例如 /api/dsh/v1/app-latest.apk），
 * 服务端刻意不写死 host（反代/多域名场景下由客户端决定访问哪个域名），
 * 因此这里只保留相对路径，绝对地址在 [resolveApkUrl] 里用登录时的 host 拼出来。
 */
data class UpdateInfo(
    /** 展示用版本号，如 "0.1.1"。 */
    val version: String,
    /** 单调递增的版本码，与 Android 的 versionCode 同口径，比较只用它。 */
    val code: Int,
    /** 更新说明（可能为空字符串）。 */
    val notes: String,
    /** 发布时间戳（毫秒），<= 0 表示服务端没给。 */
    val publishedAt: Long,
    /** 安装包字节数，<= 0 表示未知。用于磁盘空间预检。 */
    val size: Long,
    /** 安装包 sha256（小写十六进制）。空字符串表示服务端没给，此时跳过校验但记日志。 */
    val sha256: String,
    /** 下载文件名，只用于给本地文件起名，不可信（会被净化）。 */
    val filename: String,
    /** 相对路径，例如 /api/dsh/v1/app-latest.apk 。 */
    val apkUrl: String,
    /** 服务端是否已经放好安装包。false 时不应发起下载。 */
    val apkReady: Boolean,
) {

    /**
     * 拼出可下载的绝对地址。
     *
     * @param hostBaseUrl 登录时用的服务器基址（不含尾斜杠），例如 https://dsh.example.com 。
     */
    fun resolveApkUrl(hostBaseUrl: String): String {
        // 服务端理论上只给相对路径，但为防它某天返回绝对地址（或带协议头的地址），
        // 两种情况分开处理：绝对地址原样用，相对路径拼到登录 host 上。
        if (apkUrl.startsWith("http://") || apkUrl.startsWith("https://")) return apkUrl
        val base = hostBaseUrl.trimEnd('/')
        val path = if (apkUrl.startsWith("/")) apkUrl else "/" + apkUrl
        return base + path
    }

    companion object {

        /**
         * 解析 app-version 响应体。
         *
         * 为什么返回 null 而不是抛异常：调用方（UpdateClient）的语义是"拿不到就静默"，
         * 任何字段缺失都等价于"这次查不到更新"，不应该变成一条需要用户处理的错误。
         *
         * 注意 ok=false 的 200 响应也算 null：服务端当前的"未配置"是 503，
         * 但契约里 ok 字段存在，按 ok 判断比按状态码判断更稳。
         */
        fun parse(text: String): UpdateInfo? {
            return try {
                val root = JSONObject(text)
                if (!root.optBoolean("ok", false)) return null
                val code = root.optInt("code", 0)
                if (code <= 0) return null
                UpdateInfo(
                    version = root.optString("version", ""),
                    code = code,
                    notes = root.optString("notes", ""),
                    publishedAt = root.optLong("publishedAt", 0L),
                    size = root.optLong("size", 0L),
                    sha256 = root.optString("sha256", "").trim().lowercase(),
                    filename = root.optString("filename", ""),
                    apkUrl = root.optString("url", ""),
                    apkReady = root.optBoolean("apkReady", false),
                )
            } catch (e: JSONException) {
                null
            }
        }
    }
}

/**
 * 「稍后」记录：同一版本只提示一次。
 *
 * 为什么用 SharedPreferences 而不是内存变量：
 *   用户点「稍后」的语义是"这个版本别再问我了"，跨进程重启后仍然成立。
 *   只存 code（整数），不存 cookie / 账号 —— 它不含任何凭据。
 *
 * 为什么记 code 而不是 version 字符串：
 *   code 单调递增是发布流程的一部分，即使版本号写错（0.1.2 发成 0.1.1），
 *   code 仍然能正确表达"新/旧"。
 */
object UpdatePrefs {
    private const val FILE = "dsh_update_prefs"
    private const val KEY_SKIPPED_CODE = "skipped_code"

    /** 上一次听服务端说的"最新 code"。见 [lastKnownLatestCode] / [reconcile]。 */
    private const val KEY_LAST_KNOWN_LATEST = "last_known_latest_code"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 用户上次点「稍后」的版本码；0 表示没有记录。 */
    fun skippedCode(context: Context): Int = prefs(context).getInt(KEY_SKIPPED_CODE, 0)

    fun markSkipped(context: Context, code: Int) {
        prefs(context).edit().putInt(KEY_SKIPPED_CODE, code).apply()
    }

    /**
     * 这个版本是否应当提示。
     *
     * 判据：服务端 code 比本机大（有新版本），且不是用户已经忽略的那个 code。
     * 用 > 而不是 !=：万一本机是"比线上还新"的调试包，不该弹窗劝降级。
     */
    fun shouldPrompt(context: Context, remoteCode: Int): Boolean {
        return remoteCode > BuildConfig.VERSION_CODE && remoteCode != skippedCode(context)
    }

    /**
     * 已经确认过的最新版本码（v0.4.0 新增）。
     *
     * 与 [KEY_SKIPPED_CODE] 的区别 —— 这是**两个正交的状态**，不要合并：
     *   · skipped_code：用户点了「稍后」⇒ 这个版本别弹更新框；
     *   · last_known_latest_code：服务端上一次告诉我们的最新 code ⇒ 用来判断
     *     「装好了没」以及「用户主动检查时该说'已是最新'还是'发现新版本'」。
     *
     * 为什么需要它（问题 ②的深层根因）：
     *   用户点「立即更新」后安装成功，但 skipped_code 可能还残留着上一次的记录；
     *   若服务端换个 code 重新发布（版本号没变），旧的 skipped_code 就与新版无关了 ——
     *   而 App 根本不知道自己曾经"更新到了哪个版本"，也就没法给用户
     *   「已是最新」这个反馈。见 [reconcile]。
     */
    fun lastKnownLatestCode(context: Context): Int = prefs(context).getInt(KEY_LAST_KNOWN_LATEST, 0)

    fun markLatestCode(context: Context, code: Int) {
        if (code <= 0) return
        prefs(context).edit().putInt(KEY_LAST_KNOWN_LATEST, code).apply()
    }

    /**
     * 版本状态自洽（v0.4.0，问题 ②）。
     *
     * 每次**成功**读到服务端版本清单后调用一次，做两件事：
     *   1. 记录 [KEY_LAST_KNOWN_LATEST]；
     *   2. 若本机 code 已经 = 服务端 code（用户已经装上了最新版），
     *      **清除 skipped_code** —— 它属于上一个还没装的版本，留着毫无意义，
     *      而且会在下次服务端发新版时造成"明明没点过稍后，却被当成忽略过"的错觉。
     *
     * ⚠️ 只在本机 >= 服务端时清理：服务端比本机新（真的有待装更新）时，
     *    skipped_code 仍然有效，用户点过的「稍后」必须被尊重。
     */
    fun reconcile(context: Context, remoteCode: Int) {
        if (remoteCode <= 0) return
        markLatestCode(context, remoteCode)
        if (remoteCode <= BuildConfig.VERSION_CODE && skippedCode(context) != 0) {
            prefs(context).edit().remove(KEY_SKIPPED_CODE).apply()
        }
    }
}
