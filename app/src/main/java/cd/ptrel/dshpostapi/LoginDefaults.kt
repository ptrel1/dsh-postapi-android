package cd.ptrel.dshpostapi

import android.content.Context
import android.content.SharedPreferences

/**
 * 登录页的**非机密**预填记忆（服务地址）。
 *
 * ==================== 为什么它和 AccountStore 分开 ====================
 * 这两类数据的安全级别完全不同，混在一起会有两个坑：
 *
 *   1. **性能**：加密存储首次读要初始化 Keystore（200~500ms）。而"上次用的服务地址"
 *      在**退出登录之后**也要能预填（否则用户每次退出都要重敲一遍域名），
 *      如果它也在加密库里，退出登录时就不能整份删库，逻辑会变得很绕。
 *   2. **失效耦合**：Keystore 一旦失效（系统升级/换机），加密库整份作废。
 *      此时用户名/密码确实该忘，但"用户连的是哪台服务器"没有任何理由忘 ——
 *      把它放在普通 SharedPreferences 里，Keystore 挂掉后仍能正常预填。
 *
 * ⚠️ 这个文件**绝不能**出现 password / cookie / token 任何字段。
 *    改动前先看 [AccountStore] 的字段表：机密项只在那边。
 *
 * 为什么放在普通 SharedPreferences 就安全：
 *   存的是「https://dsh.example.com」这种公开地址，不含任何可用于鉴权的信息。
 *   Android 的 MODE_PRIVATE 已限制为应用私有目录（其它 App 读不到）；
 *   它随 allowBackup=false 也不会被云备份带走。
 */
object LoginDefaults {

    private const val FILE = "dsh_login_prefs"
    private const val KEY_LAST_HOST = "last_host"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * 上次用过的服务地址（归一化后的基址）；null 表示从没登录过。
     *
     * 解析失败（存储被写坏、旧版本残留格式）时返回 null 而不是抛异常 ——
     * 预填失败最多是用户重敲一遍地址，不值得让 App 崩。
     */
    fun lastHost(context: Context): String? {
        return try {
            val raw = prefs(context).getString(KEY_LAST_HOST, "").orEmpty()
            if (raw.isEmpty()) null else raw
        } catch (e: Exception) {
            null
        }
    }

    /** 登录成功后记录。**只记 host**，不记用户名密码（那在 AccountStore）。 */
    fun rememberHost(context: Context, host: String) {
        try {
            prefs(context).edit().putString(KEY_LAST_HOST, host).apply()
        } catch (e: Exception) {
            // 记不住只是少一次预填，不影响任何主流程。
        }
    }
}
