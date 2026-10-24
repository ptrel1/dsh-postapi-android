@file:Suppress("DEPRECATION") // 见文件内「关于 deprecation 警告」一节：EncryptedSharedPreferences 1.1.0 仍是稳定且安全的选择

package cd.ptrel.dshpostapi

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore

// ==================== 关于 deprecation 警告 ====================
// androidx.security:security-crypto 1.1.0（2025-07 稳定版）把 EncryptedSharedPreferences
// 与 MasterKey 标成了 @Deprecated：官方认为"库本身仍是安全的"，但计划把 API 迁到
// 一个独立新库，1.1.0 是它的最后一个稳定版本。
//
// 为什么**仍然**用它，而不是换别的：
//   · 迁移目标库在 2026-10 尚未发布稳定版，换成 alpha 版等于把用户的密码押在
//     一个会随时改 API 的实验库上；
//   · 裸用 Jetpack Security Crypto 之外的方案（自己拿 Keystore 加 Tink 拼）会多出
//     密钥轮换、IV 管理、文件格式版本等一堆手写加密逻辑 —— 那才是真正的高风险；
//   · 1.1.0 是稳定版且有长期安全维护承诺，deprecation 是"将来迁移"，不是"不安全"。
//
// 这里的 @Suppress 只是让编译输出干净（否则 11 条警告会掩盖真正的警告），
// 并把上述判断写进代码，避免后来者看到警告就随手替换成别的东西。
@Suppress("DEPRECATION")

/**
 * 账号本地记忆（加密持久层）。
 *
 * ==================== 为什么需要它 ====================
 * 用户原话：「应该有账号本地记忆的功能，避免反复输入」。
 * v0.2.0 之前的语义是「一次登录 = 一次会话，重启要重新登录」（旧 README §5.5），
 * 实际使用时每天都要重新敲一遍账号密码 —— 这是真实的摩擦。
 *
 * 现在存的字段（全部在本文件里，别处不许再存一份）：
 *
 * | key         | 内容                                  | 机密 | 用途 |
 * | :---------- | :------------------------------------ | :--- | :--- |
 * | host        | 服务基址（如 https://dsh.example.com）| 否   | 预填 + 判断是否换服务器 |
 * | username    | 用户名                                | 否   | 预填 |
 * | password    | 密码                                  | **是** | cookie 失效时自动重登 |
 * | cookieName  | 插件会话 cookie 名                    | 否   | 种进 WebView |
 * | cookieValue | 插件会话 cookie 值                    | **是** | 跳过登录页直接进 WebView |
 * | cookieExp   | cookie 过期时间戳（ms）                | 否   | 判断能否直接进 WebView |
 * | lastLogin   | 上次成功登录时间戳（ms）               | 否   | 仅排查用 | *
 * 清除语义分两种（v0.4.0，问题 ③ —— 改代码前必读）：
 *   · [clearSession] 退出登录：只清 cookie，**保留 host / username / password**；
 *   · [clearAll]     清除账号：连 host / username / password 一起清。
 * 保留密码后如何避免"退出登录被自动重登撤销"：见 [clearSession] 的注释（EXTRA_AUTO_LOGIN）。
 *
 * 注意 [KEY_COOKIE_EXP] 是**我们自己算的保守估算**（登录时刻 + maxAgeSeconds），
 * 不是服务端给的绝对时间 —— 服务端只下发 maxAgeSeconds。宁可低估（多一次自动重登，
 * 用户无感），不要高估（进 WebView 拿到一片 401 错误页）。
 *
 * ==================== 加密方案 ====================
 * EncryptedSharedPreferences + MasterKey.AES256_GCM：
 *   - 密钥**只存在于 Android Keystore**（硬件后端可用时由 TEE/StrongBox 保护），
 *     不进 App 进程内存之外的任何地方，也不进文件；
 *   - 文件内容 AES-256-GCM 加密，key 名本身也加密（valueEncryptionScheme 之外的
 *     keyset 由 Tink 管理），改文件名/改值都会导致 AEAD 校验失败而不是静默读到垃圾；
 *   - ⚠️ **不要**改成普通 SharedPreferences —— 那等于把明文密码写在
 *     /data/data/<pkg>/shared_prefs/ 里，root 或备份即可读走。
 *
 * ==================== 失败一律「清空 + 当没账号」====================
 * 以下情形都会导致 [EncryptedSharedPreferences.create] 或读取抛异常，**必须**兜住：
 *   - 恢复出厂 / 换机：Keystore 里的密钥没了，但密文文件还在（若被云备份恢复——
 *     本工程已设 allowBackup=false 从源头阻断，但用户可能用第三方备份工具）；
 *   - 系统和安全补丁升级后 Keystore 密钥失效（少量机型的已知问题）；
 *   - 用户清除「凭据存储」但没清 App 数据。
 * 处理方式：**删掉整个文件 + MasterKey 条目，当作没有账号**，返回 null / 写入失败。
 * 绝不把异常抛给 UI 层 —— 否则表现是「App 一启动就崩」，用户无法自救。
 *
 * ==================== 安全红线（改代码前先读）====================
 *   1. **密码 / cookie 值绝不进日志**：本文件只有 Log.w/e 记录「哪类失败」，
 *      不记录任何字段值。不要为了排查方便打印 data 集合。
 *   2. **绝不进 Intent / Bundle**：所有读写都在本进程内完成，
 *      Activity 之间通过 [AccountStore] 的静态方法取，不序列化传递。
 *   3. 本类所有方法都做整体 try/catch：存储层故障不能影响登录主流程。
 */
object AccountStore {

    private const val TAG = "AccountStore"

    /** 加密 SharedPreferences 的文件名（子目录 shared_prefs/ 下）。 */
    private const val PREFS_FILE = "dsh_account_secure"

    /** Keystore 里的主密钥别名。改名等于让已有密文全部作废（会被当 Keystore 异常清掉）。 */
    private const val MASTER_KEY_ALIAS = "dsh_postapi_master_key"

    const val KEY_HOST = "host"
    const val KEY_USERNAME = "username"
    const val KEY_PASSWORD = "password"
    const val KEY_COOKIE_NAME = "cookie_name"
    const val KEY_COOKIE_VALUE = "cookie_value"
    const val KEY_COOKIE_EXP = "cookie_exp"
    const val KEY_LAST_LOGIN = "last_login"

    /**
     * 内存缓存：避免每次读都走一遍 Keystore + AES 解密（解密 ~ms 级，但会被 UI 频繁调用）。
     *
     * 为什么可以缓存：进程生命周期内只有本进程会写这个文件，不存在"外部改了但我们不知道"。
     * 写入路径（[saveAccount] / [clearAll]）同步更新缓存，不会出现缓存与磁盘不一致。
     */
    @Volatile
    private var cached: StoredAccount? = null
    @Volatile
    private var loaded: Boolean = false

    /**
     * 一次完整的「账号 + 会话」快照。
     *
     * [host] 是**归一化过的基址**（无尾斜杠），与 [ApiClient.HostSpec.baseUrl] 同口径，
     * 这样"用户是否换了服务器"可以一次字符串比较判定。
     */
    data class StoredAccount(
        /** 服务基址，例如 https://dsh.example.com 或 http://192.168.1.10:3080 。 */
        val host: String,
        val username: String,
        /** 密码。⚠️ 机密，禁止日志 / 禁止 Intent。 */
        val password: String,
        val cookieName: String,
        /** ⚠️ 机密，禁止日志 / 禁止 Intent。 */
        val cookieValue: String,
        /** cookie 过期时间戳（ms，0 表示未知/不缓存）。 */
        val cookieExp: Long,
        /** 上次成功登录时间戳（ms）。 */
        val lastLogin: Long,
    ) {
        /** 目前是否有可用的（未过期的）会话 cookie。 */
        fun hasFreshCookie(now: Long = System.currentTimeMillis()): Boolean =
            cookieName.isNotEmpty() && cookieValue.isNotEmpty() && cookieExp > now
    }

    /**
     * 打开（或首次创建）加密存储。
     *
     * @return null 表示存储不可用（Keystore 异常 / 文件损坏）。此时调用方必须按
     *   「没有账号」处理 —— 登录页照常可用，只是不记忆。
     *
     * ⚠️ 首次调用会生成 Keystore 密钥，**要几百毫秒**。绝不要在主线程调用
     * （本 App 的所有入口都在 Application 的后台线程里预读，见 [warmUp]）。
     */
    private fun open(context: Context): SharedPreferences? {
        val appContext = context.applicationContext
        return try {
            val masterKey = MasterKey.Builder(appContext, MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            // 典型来源：KeyStoreException / GeneralSecurityException / IOException
            // （"Failed to read key from keystore" / "Could not decrypt" / AEADBadTagException）。
            // 只记录异常类型，不记录 message 里的键值片段（虽然 Tink 不会把明文放进 message，
            // 但这条约束要守死，免得将来换了实现库就漏）。
            Log.w(TAG, "secure storage unavailable (" + e.javaClass.simpleName + "), resetting")
            resetStorage(appContext)
            null
        }
    }

    /**
     * 彻底重置：删加密文件 + 删 Keystore 主密钥。
     *
     * 为什么两个都删：
     *   - 只删文件：Keystore 里残留的密钥没有用，但下次 create 会复用（无害）；
     *   - 只删密钥：密文文件还在，create 能成功但**每次解密都失败**，
     *     表现是"每次启动都要重新登录，而且永远记住不了" —— 更难查。
     * 所以一起删，让下一次从干净状态重新生成。
     *
     * 这里吞掉所有异常：删除失败也不能崩（磁盘只读、并发删除都会抛）。
     */
    private fun resetStorage(context: Context) {
        cached = null
        loaded = true
        try {
            context.applicationContext.deleteSharedPreferences(PREFS_FILE)
        } catch (e: Exception) {
            Log.w(TAG, "delete prefs failed (" + e.javaClass.simpleName + ")")
        }
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            if (keyStore.containsAlias(MASTER_KEY_ALIAS)) {
                keyStore.deleteEntry(MASTER_KEY_ALIAS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "delete master key failed (" + e.javaClass.simpleName + ")")
        }
    }

    /**
     * 读取当前保存的账号。
     *
     * @return null = 没有账号 / 存储不可用 / 数据不完整。三种情况对调用方的语义相同：
     *   「走登录页，别预填密码」。
     */
    fun load(context: Context): StoredAccount? {
        if (loaded) return cached

        val prefs = open(context) ?: return null
        val account = try {
            val host = prefs.getString(KEY_HOST, "").orEmpty()
            val username = prefs.getString(KEY_USERNAME, "").orEmpty()
            if (host.isEmpty() || username.isEmpty()) {
                // 没有任何账号（首次安装 / 已退出登录）：
                // 这是**正常状态**，不要当成异常去 Log.w，更不要去 reset。
                null
            } else {
                StoredAccount(
                    host = host,
                    username = username,
                    password = prefs.getString(KEY_PASSWORD, "").orEmpty(),
                    cookieName = prefs.getString(KEY_COOKIE_NAME, "").orEmpty(),
                    cookieValue = prefs.getString(KEY_COOKIE_VALUE, "").orEmpty(),
                    cookieExp = prefs.getLong(KEY_COOKIE_EXP, 0L),
                    lastLogin = prefs.getLong(KEY_LAST_LOGIN, 0L),
                )
            }
        } catch (e: Exception) {
            // 解密失败（密文损坏 / Keystore 密钥已变）会在这里抛，而不是在 open() 里。
            // 必须在这里兜住：这是"系统升级后 App 一打开就崩"的典型来源。
            Log.w(TAG, "decrypt failed (" + e.javaClass.simpleName + "), resetting")
            resetStorage(context)
            null
        }
        cached = account
        loaded = true
        return account
    }

    /**
     * 登录成功后写入：host / username / password / cookie / cookieExp / lastLogin。
     *
     * 语义是**整体覆盖**（不是逐字段合并）：一次成功登录后的状态就是全部真相，
     * 逐字段合并会在「换账号」时留下上一个账号的残留字段。
     *
     * @return false 表示写入失败（存储不可用）。调用方应当继续进入 WebView，
     *   只是本次登录不被记忆 —— 绝不能因为"记不住"就拦住用户使用。
     */
    fun saveAfterLogin(
        context: Context,
        host: String,
        username: String,
        password: String,
        cookieName: String,
        cookieValue: String,
        cookieMaxAgeSeconds: Int,
    ): Boolean {
        val now = System.currentTimeMillis()
        // cookieExp 用「登录时刻 + 服务端给的 maxAge」保守估算。maxAge <= 0 时视为"会话级
        // cookie"，无法预估，记 0 ⇒ hasFreshCookie() 永远为 false，下次启动走自动重登。
        val exp = if (cookieMaxAgeSeconds > 0) now + cookieMaxAgeSeconds * 1000L else 0L
        val account = StoredAccount(host, username, password, cookieName, cookieValue, exp, now)
        val ok = write(context, account)
        return ok
    }

    /**
     * 只更新会话 cookie（不改账号密码）。
     *
     * 用途：WebView 内的会话被服务端续期后，我们对新 cookie 不可见
     * （CookieManager 能读，但 HttpOnly 的那部分读不到，且 URL 归属容易踩坑），
     * 所以这里仅在"确认过会话仍然有效"时把过期时间往后推 —— 见 [touchSession]。
     */
    private fun write(context: Context, account: StoredAccount): Boolean {
        val prefs = open(context) ?: return false
        // 先写内存缓存：即使磁盘写入失败，本次进程内也应当记得住（用户体验优先），
        // 但返回值如实告知 false，让调用方知道"重启后可能还要重新登录"。
        cached = account
        loaded = true
        return try {
            prefs.edit()
                .putString(KEY_HOST, account.host)
                .putString(KEY_USERNAME, account.username)
                .putString(KEY_PASSWORD, account.password)
                .putString(KEY_COOKIE_NAME, account.cookieName)
                .putString(KEY_COOKIE_VALUE, account.cookieValue)
                .putLong(KEY_COOKIE_EXP, account.cookieExp)
                .putLong(KEY_LAST_LOGIN, account.lastLogin)
                .commit() // 故意用 commit 而不是 apply：登录成功 → 立刻跳 WebView，
                          // 进程若马上被系统回收，apply 的异步写入可能还没落盘，
                          // 结果就是"明明勾了记住却又要重登"。commit 只在后台线程调用。
        } catch (e: Exception) {
            Log.w(TAG, "save failed (" + e.javaClass.simpleName + ")")
            false
        }
    }

    /**
     * 清除**密码**（保留 host / username 以便预填），并把 cookie 一并作废。
     *
     * ===== 这是「必须先做，否则会无限重试」的那一次清除 =====
     * 触发条件由调用方判定，只允许两种（见 LoginFlow 的边界决策表）：
     *   ① 自动重登返回 401 / 403 —— 密码已改 / 账号被禁用 / 会话被吊销；
     *   ② 用户在官网改过密码。
     * **网络错误、429、5xx 一概不清**（不是凭据问题，清了等于让用户白输一遍）。
     *
     * cookie 一起清：既然密码都失效了，那枚 cookie 也一定是无效的。
     * 留着它会让下次启动先走"cookie 未过期 → 直接进 WebView"的错误分支，
     * 得到一片 401 页面，再来一次自动重登 —— 白白多绕一圈。
     *
     * ⚠️ 与 [clearSession] 的关系（容易搞混，必读）：
     *    本方法清 **密码 + cookie**，因为服务端已经否定了这组凭据；
     *    [clearSession] 只清 **cookie**，因为"退出登录"不代表密码错。
     *    两者不可互换：拿 clearSession 处理 401 会导致"密码还在 → 每次都自动重登 →
     *    每次都 401"，白白烧服务端的失败计数（5 次锁 10 分钟）。
     */
    fun clearCredentials(context: Context) {
        val current = load(context)
        if (current == null) {
            // 没有账号可清，但可能残留半份数据；顺手清干净。
            clearAll(context)
            return
        }
        write(
            context,
            current.copy(
                password = "",
                cookieName = "",
                cookieValue = "",
                cookieExp = 0L,
            ),
        )
    }

    /** 只作废会话 cookie（保留账号密码）。用于 cookie 自我判定失效但还没验证密码的场景。 */
    fun clearCookie(context: Context) {
        val current = load(context) ?: return
        write(context, current.copy(cookieName = "", cookieValue = "", cookieExp = 0L))
    }

    /**
     * 「退出登录」：**只清会话状态**，保留账号凭据（v0.4.0，问题 ③）。
     *
     * ==================== 为什么退出登录不能清账号 ====================
     * v0.3.0 的 performLogout 调的是 [clearAll]，把主机地址、用户名、密码一起抹了。
     * 用户实测反馈：「退出登录之后地址用户名密码应该数据保存在本地不需要重复输入才对」。
     * 这个反馈是对的 —— **退出登录 = 结束会话**，不是"这台手机别留我的东西"。
     * 清账号是另一个动作（[clearAll]，放在登录页的「清除已保存的账号」）。
     *
     * 清的全部内容：cookieName / cookieValue / cookieExp（作废免密直进 WebView 的依据）。
     *
     * ==================== 密码为什么**保留**（v0.4.0 关键决策）====================
     * 用户要求：「退出登录之后地址用户名密码应该数据保存在本地不需要重复输入才对」。
     * 所以 host / username / **password** 全部保留，登录页三个框都填好，用户点一下即登录。
     *
     * 那"保留密码会不会让退出登录失效"？——不会，前提是**必须堵住自动重登的入口**：
     *   LoginFlow 的冷启动决策里有一条"有密码 ⇒ 自动重登"。如果什么都不做，
     *   用户点完退出、下次冷启动就会被自动登回去，看起来"退不出去"。
     *   堵法是两条：
     *     ① WebViewActivity.performLogout 回登录页时带 EXTRA_AUTO_LOGIN=false；
     *     ② LoginActivity 收到 false 后只预填、不发请求（见 LoginActivity.allowAutoLogin）。
     * ⚠️ 改这里之前先确认这两条还在 —— 它们是"保留密码"这个决定的前提。
     *
     * 保留：host / username / password / lastLogin（仅排查用）。
     */
    fun clearSession(context: Context) {
        val current = load(context)
        if (current == null) {
            // 没有账号可保（存储为空 / 不可用）：无需写入。
            // 注意这里**不要**走 clearAll —— 那会连 master key 一起重置，
            // 下次登录又要多花 200~500ms 生成密钥。
            return
        }
        // 只动三个 cookie 字段：账号三件套（host / username / password）原样写回。
        write(
            context,
            current.copy(
                cookieName = "",
                cookieValue = "",
                cookieExp = 0L,
            ),
        )
    }

    /**
     * 「忘记账号 / 清除已保存的账号」：清空**全部**存储（含主机地址、用户名、密码）。
     *
     * 什么时候用（v0.4.0 起）：
     *   · 登录页的「清除已保存的账号」链接（用户明确要把这台手机上的记录抹掉）；
     *   · 换服务器（host 变了，旧账号的地址/用户名/密码都不再适用，见 LoginActivity）。
     *
     * ⚠️ **不要**再把它接到「退出登录」上：那是 v0.3.0 的设计错误，
     *    用户实测反馈"退出后地址用户名密码被清空"。退出登录请用 [clearSession]。
     */
    fun clearAll(context: Context) {
        cached = null
        loaded = true
        val prefs = open(context)
        if (prefs == null) {
            // open 已经走到 resetStorage；这里再补一次，覆盖"文件存在但打不开"的情形。
            resetStorage(context)
            return
        }
        try {
            // 逐字段 remove 而不是重新 create 文件：保留 Keystore 密钥，
            // 下次登录写入时不必再生成（省掉 200~500ms）。
            prefs.edit()
                .remove(KEY_HOST)
                .remove(KEY_USERNAME)
                .remove(KEY_PASSWORD)
                .remove(KEY_COOKIE_NAME)
                .remove(KEY_COOKIE_VALUE)
                .remove(KEY_COOKIE_EXP)
                .remove(KEY_LAST_LOGIN)
                .commit()
        } catch (e: Exception) {
            Log.w(TAG, "clear failed (" + e.javaClass.simpleName + ")")
            resetStorage(context)
        }
    }

    /**
     * 在后台线程预读一次，避免主线程第一次 [load] 时触发 Keystore 初始化。
     *
     * 调用点：Application.onCreate（见 DshApp）。
     * 失败不会抛：底层已全部兜住。
     */
    fun warmUp(context: Context) {
        try {
            load(context)
        } catch (e: Exception) {
            // load 内部已兜住，这里只是最后一道保险（例如 OOM 之外的 Throwable 不用管）。
            Log.w(TAG, "warmUp failed (" + e.javaClass.simpleName + ")")
        }
    }
}
