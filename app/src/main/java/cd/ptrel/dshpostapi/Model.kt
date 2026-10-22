package cd.ptrel.dshpostapi

/**
 * 登录结果的数据模型。
 *
 * 字段与插件端点 POST <host>/api/dsh/v1/mobile-login 的成功响应一一对应
 * （见 dsh-postapi-bridge/lib/index.js:1730 的 sendJson 调用）：
 *
 *   { "ok": true, "username": "...", "role": "...",
 *     "launchUrl": "https://<host>/?token=<启动令牌>",
 *     "sessionCookie": { "name": "...", "value": "...", "maxAgeSeconds": 86400 },
 *     "expiresAt": 1700000000000 }
 *
 * 注意 [launchUrl] 必须一次性直接加载：官方门禁只认 GET /?token=<token>
 * 且只允许一个 token 参数（browser-auth.ts:244）。拿它去改地址栏、或拆成两步导航
 * 都会失败。这个约束在 WebViewActivity 里落地。
 */
data class LoginResult(
    val launchUrl: String,
    val cookieName: String,
    val cookieValue: String,
    val cookieMaxAgeSeconds: Int,
    val username: String,
    val role: String,
)
