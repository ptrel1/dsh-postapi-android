package cd.ptrel.dshpostapi

/**
 * 登录失败的分类。
 *
 * 为什么要分类而不是直接透传错误字符串：
 *   每种失败对用户意味着不同的下一步动作 —— 凭据错要改输入、限流要等、
 *   503 要找管理员、404 要查域名配置。把状态码映射成语义分支，UI 才能给出
 *   "能指向真因"的文案，而不是统一的"登录失败"。
 */
sealed class LoginError {
    /** 网络层失败（DNS / 连接被拒 / TLS / 断网）。[detail] 是原始异常信息，用于排查。 */
    data class Network(val detail: String) : LoginError()

    /** 超时。单独一类是因为"稍后重试"可能有效，与连接被拒的处理不同。 */
    data object Timeout : LoginError()

    /** 401：凭据错误。服务端刻意不区分用户不存在 / 密码错 / 被禁用（防用户名枚举）。 */
    data object InvalidCredentials : LoginError()

    /** 429：按 IP 限流（5 次失败锁 10 分钟）。 */
    data object TooManyAttempts : LoginError()

    /** 503：服务端读不到启动令牌，需要管理员看 dsh web 日志。 */
    data object ServiceUnavailable : LoginError()

    /** 404 / 405：该地址上没有这个接口（域名或反代配错）。 */
    data object EndpointNotFound : LoginError()

    /** 其它非 2xx 状态码。 */
    data class Http(val code: Int, val body: String) : LoginError()

    /** 200 但响应体结构不符预期（字段缺失 / 类型不对 / ok 不为 true）。 */
    data class BadResponse(val detail: String) : LoginError()

    /** 参数在客户端就被拦下，没有发请求。 */
    data class InvalidInput(val message: String) : LoginError()
}

/** 给排查用的原始描述（附在错误区小字里，不替代用户向文案）。 */
fun LoginError.debugText(): String = when (this) {
    is LoginError.Network -> "network: " + detail
    LoginError.Timeout -> "timeout"
    LoginError.InvalidCredentials -> "HTTP 401"
    LoginError.TooManyAttempts -> "HTTP 429"
    LoginError.ServiceUnavailable -> "HTTP 503"
    LoginError.EndpointNotFound -> "HTTP 404/405"
    is LoginError.Http -> "HTTP " + code + (if (body.isEmpty()) "" else ": " + body)
    is LoginError.BadResponse -> "bad response: " + detail
    is LoginError.InvalidInput -> "invalid input: " + message
}
