package cd.ptrel.dshpostapi

import android.content.Context

/**
 * 「检查更新」用的会话上下文 —— **只是持久层的一层只读视图，不存任何数据**。
 *
 * ==================== v0.3.0 的重大改动（改代码前必读）====================
 * v0.2.0 这里是**独立的**内存单例：登录成功后 LoginActivity 调 [set] 把
 * host + cookieName + cookieValue 写进来，进程结束即丢。
 *
 * v0.3.0 引入账号本地记忆后，同一份 cookie 会同时存在于两处（加密存储 + 本单例），
 * 那是**两套真相**：退出登录清了存储却忘了清单例 → 下一次检查更新还能带着
 * 已作废的 cookie 发请求；反之清早了 → 检查更新静默失效，且没有任何日志能定位。
 *
 * 所以这里被改成**纯代理**：每次 [get] 都直接问 [AccountStore]。
 *   - 单一真相：cookie 只在 AccountStore 里；
 *   - 退出登录（AccountStore.clearAll）之后，[get] 自然返回 null，无需调用方协调；
 *   - [set] / [clear] 保留但**改语义**：[set] 变成空操作（兼容旧调用点、避免误用），
 *     [clear] 保留为 no-op 并标注 @Deprecated，真正要清请走 AccountStore。
 *
 * 为什么还保留这个 object，而不是让 Updates 直接调 AccountStore：
 *   调用点（Updates.checkQuietly / startDownload）只关心"有没有可用的会话 + 它的
 *   host 与 cookie 是什么"，不需要知道背后是加密存储还是别的东西。
 *   保留这层薄壳，将来若改成"每次从 CookieManager 现读"，只改这一个文件。
 *
 * 安全边界：
 *   - cookie 值不进日志、不进 UI、不进 Intent extras；
 *   - 本类不缓存任何凭据，避免出现"退出登录后内存里还留着一份"的窗口。
 */
object UpdateSession {

    /** 当前会话：直接从加密存储派生。没有账号 / 没有 cookie 时为 null。 */
    data class Current(
        /** 登录时用的服务器基址，不含尾斜杠。 */
        val hostBaseUrl: String,
        /** 插件会话 cookie 名（服务端下发，通常是 dsh_postapi_session）。 */
        val cookieName: String,
        /** 插件会话 cookie 值。 */
        val cookieValue: String,
    )

    /**
     * 读取当前会话。
     *
     * 注意**不判断 cookie 是否过期**：检查更新/下载是"顺带做"的事，过期了服务端会
     * 自己回 401，Updates 里静默处理即可；这里若加过期判断，反而要引入时钟比较，
     * 而两处时钟口径（写入端 / 读取端）一旦不一致就会变成"偶尔不检查更新"的悬案。
     */
    fun get(context: Context): Current? {
        val account = AccountStore.load(context) ?: return null
        if (account.cookieName.isEmpty() || account.cookieValue.isEmpty()) return null
        return Current(account.host, account.cookieName, account.cookieValue)
    }

    /**
     * 兼容旧调用点的空操作。
     *
     * v0.2.0 的 LoginActivity 在这里写 cookie；v0.3.0 起 crypto 只由
     * AccountStore.saveAfterLogin 写入（那是**唯一**的写入点）。
     * 保留这个方法是为了让"有人按旧签名调用"时不至于编译不过，
     * 同时用 @Deprecated 在 IDE 里标红，防止新代码又长出第二套存储。
     */
    @Deprecated("cookie 只由 AccountStore.saveAfterLogin 写入，不要再从这里塞第二份")
    fun set(hostBaseUrl: String, cookieName: String, cookieValue: String) {
        // 故意不实现：本类不再持有任何状态。
    }

    /** 兼容旧调用点的空操作。真正的清理走 AccountStore.clearAll / clearCredentials。 */
    @Deprecated("请改用 AccountStore.clearAll / clearCredentials")
    fun clear() {
        // 故意不实现：本类不再持有任何状态。
    }
}
