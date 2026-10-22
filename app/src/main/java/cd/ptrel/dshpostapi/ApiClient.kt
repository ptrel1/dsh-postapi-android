package cd.ptrel.dshpostapi

import android.net.Uri
import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * mobile-login 的最小 HTTP 客户端。
 *
 * 为什么不用 Retrofit / 序列化框架：
 *   整个 MVP 只有一个 POST 和一个响应结构。引 Retrofit 会带来 converter 与注解处理，
 *   收益为负；org.json 是 Android 平台自带的，零额外依赖，解析这种扁平结构完全够用。
 *
 * 线程模型：所有回调统一经 [mainHandler] 投递回主线程，
 *   调用方（Activity）不必自己切线程，也不必处理网络异常。
 */
object ApiClient {

    /** 插件侧 apiPrefix 默认 /api/dsh/v1（lib/index.js:1203）。 */
    private const val API_PREFIX = "/api/dsh/v1"
    private const val MOBILE_LOGIN_PATH = API_PREFIX + "/mobile-login"

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 单例 OkHttpClient。
     *
     * 超时取值理由：
     *   - connect 10s：公网 + frp 隧道通常 2s 内建连，10s 覆盖慢网又不至于让
     *     写错地址的用户等太久；
     *   - read 20s：服务端首次登录要扫日志解析启动令牌（有 60s 缓存，首次较慢）；
     *   - write 10s：请求体只几百字节，不需要长等。
     *
     * 不配置任何 Cache：登录响应含凭据，绝不能落盘。
     */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /** 归一化后的服务器地址。 */
    data class HostSpec(
        /** 不含尾部斜杠的完整基址，例如 https://dsh.example.com 或 http://192.168.1.10:3080 。 */
        val baseUrl: String,
        /** 只有 http / https 两种。 */
        val scheme: String,
        /** authority（host 或 host:port），原样回传给服务端拼 launchUrl。 */
        val authority: String,
    )

    /**
     * 解析用户输入的服务器地址。
     *
     * 为什么客户端也要校验一遍：
     *   服务端对 host/scheme 做白名单校验，非法一律 400（防注入进 launchUrl），
     *   但 400 的响应体不说明是哪一段非法。客户端做同口径的宽松前置校验，
     *   可以把"地址写错"和"账号密码错"区分开，给出不同提示。
     *
     * 校验口径放宽的地方（有意为之）：
     *   - 允许不带 scheme，默认补 https；
     *   - 允许 http（内网联调常直连 http://IP:端口）；
     *   - 允许带端口。
     *
     * @return [HostSpec]，或 null 表示无法解析（含路径/查询/片段的情况）。
     */
    fun parseHost(rawInput: String): HostSpec? {
        var text = rawInput.trim()
        if (text.isEmpty()) return null
        val lower = text.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            text = "https://" + text
        }
        while (text.endsWith("/")) text = text.dropLast(1)

        val uri: Uri = try {
            Uri.parse(text)
        } catch (e: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null

        val authority = uri.authority?.lowercase() ?: return null
        if (authority.isEmpty()) return null

        // 服务端的 launchUrl 只由 scheme + authority 拼成，带路径的输入会导致
        // WebView 加载到错的地址（例如用户粘贴了 https://host/login 的完整链接）。
        // 这里直接拒绝，比静默裁剪更诚实。
        val path = uri.path
        if (path != null && path.isNotEmpty() && path != "/") return null
        if (uri.query != null || uri.fragment != null) return null

        return HostSpec(baseUrl = scheme + "://" + authority, scheme = scheme, authority = authority)
    }

    /**
     * 调用 mobile-login。
     *
     * 请求体（与服务端字段严格一致，见 README「鉴权链路」）：
     *   { "username": "...", "password": "...", "scheme": "https", "host": "<authority>" }
     *
     * 为什么显式传 scheme/host，而不是让服务端从 Host 头推断：
     *   经 frp / nginx 反代时服务端看到的 Host 头可能是内网地址（127.0.0.1:3080）。
     *   launchUrl 会照抄那个值，WebView 于是去加载手机根本访问不到的地址。
     *   客户端清楚自己是从哪个域名登录的，显式回传是唯一可靠的办法。
     *
     * @param onDone 一定在主线程被调用一次；成功给 LoginResult，失败给 LoginError。
     */
    fun login(
        host: HostSpec,
        username: String,
        password: String,
        onDone: (LoginResult?, LoginError?) -> Unit,
    ) {
        val payload: String = try {
            JSONObject()
                .put("username", username)
                .put("password", password)
                .put("scheme", host.scheme)
                .put("host", host.authority)
                .toString()
        } catch (e: JSONException) {
            postResult(onDone, null, LoginError.InvalidInput(e.message ?: "cannot build request"))
            return
        }

        val request = Request.Builder()
            .url(host.baseUrl + MOBILE_LOGIN_PATH)
            .post(payload.toRequestBody(JSON_MEDIA))
            // UA 必须与 WebView 的默认 UA 同属"mobile"类别。
            // 插件用 UA 设备类别做会话指纹的硬门（lib/index.js:281）：登录用的 UA 类别
            // 与 WebView 内请求的 UA 类别不一致，会话会被判失配并最终吊销。
            // WebView 的默认 UA 形如 "Mozilla/5.0 (Linux; Android 10; ...) ... Chrome/... Mobile Safari/..."
            // 这里显式写成同族形态，降低两边被判成不同类别的概率。
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 DSH-PostAPI-Android/0.1",
            )
            .header("Accept", "application/json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val mapped: LoginError = if (e is java.net.SocketTimeoutException) {
                    LoginError.Timeout
                } else {
                    LoginError.Network(e.message ?: e.javaClass.simpleName)
                }
                postResult(onDone, null, mapped)
            }

            override fun onResponse(call: Call, response: Response) {
                // 注意：这里不用 response.use { ... return } —— `use` 接收的是内联 lambda，
                // 里面的裸 return 会做"非局部返回"，把 onResponse 一起退出。
                // 虽然能编译，但读起来极难判断到底返回到了哪一层；改为显式 try/finally 关闭。
                try {
                    val text: String = try {
                        response.body?.string() ?: ""
                    } catch (e: IOException) {
                        postResult(
                            onDone, null,
                            LoginError.Network("read body: " + (e.message ?: "unknown")),
                        )
                        return
                    }
                    if (response.code == 200) {
                        val parsed = parseLoginResult(text)
                        if (parsed == null) {
                            postResult(onDone, null, LoginError.BadResponse(snippet(text)))
                        } else {
                            postResult(onDone, parsed, null)
                        }
                    } else {
                        postResult(onDone, null, mapHttpError(response.code, text))
                    }
                } finally {
                    // 必须关闭：不关会泄漏连接，OkHttp 的连接池很快被耗尽
                    // ——症状是"登录几次后所有请求都超时"，极难定位。
                    response.close()
                }
            }
        })
    }

    /** 把状态码映射成语义错误。文案由 UI 层决定，这里只做归类。 */
    private fun mapHttpError(code: Int, body: String): LoginError = when (code) {
        400 -> LoginError.InvalidInput("server rejected host/scheme (HTTP 400)")
        401 -> LoginError.InvalidCredentials
        403 -> LoginError.Http(code, snippet(body))
        404, 405 -> LoginError.EndpointNotFound
        413 -> LoginError.Http(code, snippet(body))
        429 -> LoginError.TooManyAttempts
        503 -> LoginError.ServiceUnavailable
        else -> LoginError.Http(code, snippet(body))
    }

    /**
     * 解析成功响应体。
     *
     * 为什么逐字段校验而不是直接 getString：服务端一旦改字段名，
     * 我们宁可报"响应结构不符"（用户能拿着错误码找运维），
     * 也不要在后续用空字符串去 loadUrl 得到一片空白。
     */
    private fun parseLoginResult(text: String): LoginResult? {
        return try {
            val root = JSONObject(text)
            if (!root.optBoolean("ok", false)) return null
            val launchUrl = root.optString("launchUrl", "")
            if (launchUrl.isEmpty()) return null
            // 必须在加载前拒绝非 http(s) 的 launchUrl：一个被篡改的响应
            // 若给出 javascript: / file: 之类，WebView 会真的去执行/读取。
            val schemeOk = launchUrl.startsWith("https://") || launchUrl.startsWith("http://")
            if (!schemeOk) return null

            val cookie = root.optJSONObject("sessionCookie") ?: return null
            val name = cookie.optString("name", "")
            val value = cookie.optString("value", "")
            if (name.isEmpty() || value.isEmpty()) return null

            LoginResult(
                launchUrl = launchUrl,
                cookieName = name,
                cookieValue = value,
                cookieMaxAgeSeconds = cookie.optInt("maxAgeSeconds", 86400),
                username = root.optString("username", ""),
                role = root.optString("role", ""),
            )
        } catch (e: JSONException) {
            null
        }
    }

    /** 响应体只留前 160 字符做排查线索——过长会把错误区撑开，也容易夹带敏感内容。 */
    private fun snippet(body: String): String {
        val oneLine = body.replace('\n', ' ').replace('\r', ' ').trim()
        return if (oneLine.length <= 160) oneLine else oneLine.substring(0, 160) + "…"
    }

    private fun postResult(
        onDone: (LoginResult?, LoginError?) -> Unit,
        result: LoginResult?,
        error: LoginError?,
    ) {
        mainHandler.post { onDone(result, error) }
    }
}
