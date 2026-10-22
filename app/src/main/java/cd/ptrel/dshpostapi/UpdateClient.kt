package cd.ptrel.dshpostapi

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 检查更新 + 下载安装包的最小 HTTP 客户端。
 *
 * 设计取舍（与 ApiClient 保持同一套理由，见 ApiClient 文件注释）：
 *   - 仍用 OkHttp + org.json，不引任何序列化/下载框架；
 *   - 下载不用系统 DownloadManager：它跑在独立进程，**带不上我们的会话 cookie**，
 *     也无法在同进程里对落盘文件做 sha256 校验（校验必须发生在交给安装器之前）。
 *
 * 线程模型：
 *   - 检查更新的回调经 [mainHandler] 投递回主线程（调用方可直接更新 UI）；
 *   - 下载在 OkHttp 自己的线程上跑，进度/结果回调同样投递回主线程。
 *
 * 超时取值：
 *   - 检查更新：connect 5s / read 5s —— 用户要求 5 秒，且它是登录后"顺手做"的事，
 *     任何情况下都不该拖住主流程；
 *   - 下载：connect 10s / read 30s，**不设 callTimeout** —— 6MB 在弱网下可能好几分钟，
 *     设总超时会让弱网用户永远下不完；用 readTimeout 兜住"完全卡死"这一情形。
 */
object UpdateClient {

    private const val TAG = "UpdateClient"
    private const val API_PREFIX = "/api/dsh/v1"
    private const val APP_VERSION_PATH = API_PREFIX + "/app-version"

    /** 下载落点文件名（固定名 + 版本码）；固定名让"重下载"能直接覆盖上一次的残留。 */
    private const val APK_FILE_PREFIX = "dsh-postapi-"

    private val mainHandler = Handler(Looper.getMainLooper())

    private val checkClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    private val downloadClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // 下载会持续几十秒以上，给足建连时间；readTimeout 才是"卡死"的判据：
            // 30s 内一个字节都没到，说明链路真的死了，此时报错让用户重试比干等更好。
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            // 进度回调按块触发，自动重试会让"已下一半"变成两倍流量，关掉。
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * 缓存目录里的安装包落点。
     *
     * 为什么用 cacheDir 的 updates 子目录而不是 getExternalFilesDir：
     *   external 目录在部分机型/存储卡上不可用（返回 null），且 cacheDir 一定存在、
     *   不需要任何存储权限。缺点是系统"清理缓存"可能删掉它 —— 但那时安装流程早已结束，
     *   无影响。
     *
     * ⚠️ FileProvider 的 file_paths.xml 必须暴露同一路径（见 res/xml/file_paths.xml）。
     */
    fun apkTarget(context: android.content.Context, code: Int): File {
        val dir = File(context.cacheDir, "updates")
        return File(dir, APK_FILE_PREFIX + code + ".apk")
    }

    /**
     * UA 与 ApiClient/WebView 必须同属 mobile 类别。
     *
     * 插件用 UA 设备类别做会话指纹的硬门（见 README §5.7）。这里若用
     * "DSH-PostAPI-Updater" 之类的裸 UA，会被归到 other ⇒ 会话指纹失配，
     * 且失配累计 3 次会**吊销 sid**，代价远大于多写一长串 UA。
     * 末尾保留 App 标识，便于服务端访问日志里区分来源。
     */
    private fun mobileUserAgent(): String =
        "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 DSH-PostAPI-Android/" +
            BuildConfig.VERSION_NAME

    /**
     * 调用 GET /api/dsh/v1/app-version 检查更新。
     *
     * @param hostBaseUrl 登录时用的服务器基址（不含尾斜杠）。
     * @param cookieName 插件会话 cookie 名。
     * @param cookieValue 插件会话 cookie 值。
     * @param onDone 一定在主线程被调用一次。成功给 [UpdateInfo]，失败给 [UpdateError]。
     *   调用方对检查阶段的失败应当**静默**（只记日志）。
     */
    fun checkUpdate(
        hostBaseUrl: String,
        cookieName: String,
        cookieValue: String,
        onDone: (UpdateInfo?, UpdateError?) -> Unit,
    ) {
        val request = Request.Builder()
            .url(hostBaseUrl.trimEnd('/') + APP_VERSION_PATH)
            .header("Cookie", cookieName + "=" + cookieValue)
            .header("User-Agent", mobileUserAgent())
            .header("Accept", "application/json")
            .build()

        checkClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val mapped: UpdateError = if (e is java.net.SocketTimeoutException) {
                    UpdateError.Timeout
                } else {
                    UpdateError.Network(e.message ?: e.javaClass.simpleName)
                }
                postCheck(onDone, null, mapped)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val text: String = try {
                        response.body?.string() ?: ""
                    } catch (e: IOException) {
                        postCheck(onDone, null, UpdateError.Network("read body: " + (e.message ?: "unknown")))
                        return
                    }
                    when (response.code) {
                        200 -> {
                            val info = UpdateInfo.parse(text)
                            if (info == null) {
                                postCheck(onDone, null, UpdateError.BadResponse(snippet(text)))
                            } else {
                                postCheck(onDone, info, null)
                            }
                        }
                        // 503 = 服务端没配置分发。按用户要求"静默处理"，但仍记日志，
                        // 否则将来排查"为什么没提示更新"时没有任何线索。
                        503 -> postCheck(onDone, null, UpdateError.NotConfigured)
                        else -> postCheck(onDone, null, UpdateError.Http(response.code, snippet(text)))
                    }
                } finally {
                    // 不关闭会泄漏连接（症状：检查几次后所有请求都超时）。
                    response.close()
                }
            }
        })
    }

    /**
     * 下载安装包并校验 sha256。
     *
     * 校验位置说明（用户明确要求）：**校验通过之前，文件一直以 `.part` 后缀存在**，
     * 通过后才 rename 成 [target]。好处：进程中途被杀也不会留下"看起来完整、
     * 实际是半截"的 APK（装截断的 APK 会得到看不懂的解析错误）。
     *
     * @param apkUrl 绝对地址（由 [UpdateInfo.resolveApkUrl] 拼好）。
     * @param expectedSha256 期望的 sha256（小写十六进制）；空串表示服务端没给，跳过校验但记 WARN。
     * @param expectedSize 期望字节数；<= 0 表示未知。用于磁盘预检与截断检测。
     * @param onProgress 主线程回调 (已下载字节, 总字节或 -1)。总字节为 -1 时用不确定进度条。
     * @param onDone 主线程回调一次。成功给落盘后的 File，失败给 [UpdateError]。
     * @return 可取消的 [Call]；取消后 onDone 收到 [UpdateError.Cancelled]。
     */
    fun download(
        apkUrl: String,
        cookieName: String,
        cookieValue: String,
        target: File,
        expectedSha256: String,
        expectedSize: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        onDone: (File?, UpdateError?) -> Unit,
    ): Call {
        val request = Request.Builder()
            .url(apkUrl)
            .header("Cookie", cookieName + "=" + cookieValue)
            .header("User-Agent", mobileUserAgent())
            .header("Accept", "application/vnd.android.package-archive")
            .build()

        val call = downloadClient.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val mapped: UpdateError = when {
                    call.isCanceled() -> UpdateError.Cancelled
                    e is java.net.SocketTimeoutException -> UpdateError.Timeout
                    else -> UpdateError.Network(e.message ?: e.javaClass.simpleName)
                }
                postDownload(onDone, null, mapped)
            }

            override fun onResponse(call: Call, response: Response) {
                val partFile = File(target.parentFile, target.name + ".part")
                try {
                    if (!response.isSuccessful) {
                        // 只有 200/206 是字节流；401/403 说明 cookie 没带上或已过期。
                        val body = try {
                            snippet(response.body?.string() ?: "")
                        } catch (e: IOException) {
                            ""
                        }
                        postDownload(onDone, null, UpdateError.Http(response.code, body))
                        return
                    }
                    val body = response.body
                    if (body == null) {
                        postDownload(onDone, null, UpdateError.Storage("empty response body"))
                        return
                    }

                    // 总长度优先用服务端给的 Content-Length（206 时是**本次区间**长度），
                    // 缺失时退回清单里的 size；两者都拿不到时 -1 ⇒ 不确定进度条。
                    val contentLength = body.contentLength()
                    val total = when {
                        contentLength > 0 -> contentLength
                        expectedSize > 0 -> expectedSize
                        else -> -1L
                    }

                    val dir = target.parentFile
                    if (dir != null && !dir.exists() && !dir.mkdirs()) {
                        postDownload(onDone, null, UpdateError.Storage("cannot create " + dir.absolutePath))
                        return
                    }

                    // 磁盘空间预检：写之前看一眼，比"写到一半 ENOSPC"更早给出明确原因。
                    // 预留 1MB 余量给文件系统元数据与 .part → 正式文件的 rename。
                    if (total > 0) {
                        val need = total + (1L shl 20)
                        val free = dir?.usableSpace ?: -1L
                        if (free in 0 until need) {
                            postDownload(onDone, null, UpdateError.NoSpace(need, free))
                            return
                        }
                    }

                    var downloaded = 0L
                    var lastEmit = 0L
                    try {
                        body.byteStream().use { input ->
                            FileOutputStream(partFile).use { output ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    if (call.isCanceled()) {
                                        throw IOException("cancelled")
                                    }
                                    val read = input.read(buf)
                                    if (read < 0) break
                                    output.write(buf, 0, read)
                                    downloaded += read
                                    // 节流：至少每 200ms 或到达末尾才回调一次，
                                    // 避免主线程被进度事件刷满（6MB / 64KB ≈ 100 次，尚可，
                                    // 但慢网下 read 返回小于缓冲区时会成百上千次）。
                                    val now = System.currentTimeMillis()
                                    if (now - lastEmit >= 200L || (total > 0 && downloaded >= total)) {
                                        lastEmit = now
                                        postProgress(onProgress, downloaded, total)
                                    }
                                }
                                output.flush()
                                // fsync：保证 rename 之后的文件真的落到闪存上，
                                // 否则断电时可能留下一个长度对、内容空洞的文件。
                                output.fd.sync()
                            }
                        }
                    } catch (e: IOException) {
                        partFile.delete()
                        val mapped: UpdateError = when {
                            call.isCanceled() -> UpdateError.Cancelled
                            e is java.net.SocketTimeoutException -> UpdateError.Timeout
                            else -> UpdateError.Network(e.message ?: e.javaClass.simpleName)
                        }
                        postDownload(onDone, null, mapped)
                        return
                    } catch (e: SecurityException) {
                        partFile.delete()
                        postDownload(onDone, null, UpdateError.Storage(e.message ?: "security exception"))
                        return
                    }

                    // 截断检测：服务端说了多大，下完却不够 ⇒ 传输被中断，别交给安装器。
                    if (total > 0 && downloaded != total) {
                        partFile.delete()
                        postDownload(
                            onDone, null,
                            UpdateError.Network("truncated: got " + downloaded + " of " + total),
                        )
                        return
                    }

                    // ===== sha256 校验（用户要求的位置：交给安装器之前、rename 之前）=====
                    // 校验的是 .part 文件的实际字节。校验失败立刻删除：
                    // 绝不能把一个校验不过的 APK 留在磁盘上，否则用户下次点安装会装上损坏/来路不明的包。
                    if (expectedSha256.isNotEmpty()) {
                        val actual = try {
                            sha256Of(partFile)
                        } catch (e: IOException) {
                            partFile.delete()
                            postDownload(onDone, null, UpdateError.Storage("hash read failed: " + (e.message ?: "")))
                            return
                        }
                        if (!actual.equals(expectedSha256, ignoreCase = true)) {
                            partFile.delete()
                            postDownload(onDone, null, UpdateError.ChecksumMismatch(expectedSha256, actual))
                            return
                        }
                    } else {
                        Log.w(TAG, "server did not provide sha256, skipping integrity check")
                    }

                    // 校验通过 ⇒ 落到最终文件名（顺带清掉上一次的残留）。
                    if (target.exists()) target.delete()
                    if (!partFile.renameTo(target)) {
                        partFile.delete()
                        postDownload(onDone, null, UpdateError.Storage("cannot rename to " + target.name))
                        return
                    }
                    postDownload(onDone, target, null)
                } finally {
                    response.close()
                }
            }
        })
        return call
    }

    /** 流式计算 sha256，不把 6MB 整个读进内存（大包时差别明显）。失败抛 IOException。 */
    @Throws(IOException::class)
    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                digest.update(buf, 0, read)
            }
        }
        val bytes = digest.digest()
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v < 0x10) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }

    /** 响应体只留前 160 字符做排查线索。 */
    private fun snippet(body: String): String {
        val oneLine = body.replace('\n', ' ').replace('\r', ' ').trim()
        return if (oneLine.length <= 160) oneLine else oneLine.substring(0, 160) + "…"
    }

    private fun postCheck(
        onDone: (UpdateInfo?, UpdateError?) -> Unit,
        info: UpdateInfo?,
        error: UpdateError?,
    ) {
        mainHandler.post { onDone(info, error) }
    }

    private fun postDownload(onDone: (File?, UpdateError?) -> Unit, file: File?, error: UpdateError?) {
        mainHandler.post { onDone(file, error) }
    }

    private fun postProgress(onProgress: (Long, Long) -> Unit, downloaded: Long, total: Long) {
        mainHandler.post { onProgress(downloaded, total) }
    }
}
