package cd.ptrel.dshpostapi

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * 非机密的界面偏好（明文 SharedPreferences）。
 *
 * ==================== 为什么不复用 AccountStore ====================
 * AccountStore 是 **EncryptedSharedPreferences**，它存的是密码与插件会话 cookie。
 * 把「诊断模式开关」塞进去会带来三个坏处，每一个都比省一个类更贵：
 *   1. **Keystore 依赖**：EncryptedSharedPreferences 首次 create 要生成 Keystore 主密钥
 *      （几百毫秒，[AccountStore.warmUp] 专门为此在后台预热）；
 *      而开关的读写发生在**主线程的 onCheckedChange** 里 —— 一旦走那条路就是卡顿甚至 ANR。
 *   2. **可恢复性**：Keystore 失效（换机恢复 / 补丁升级）时 AccountStore 会
 *      **整文件删掉重来**（见 AccountStore.open 的 resetStorage）。若把开关放在那里，
 *      一次密钥失效会顺手把诊断开关也重置成关闭 —— 用户正在排查的现场就丢了。
 *   3. **语义**：「打开诊断」不是机密，加密它只会让读取路径变长、失败面变大。
 * 所以这里用独立的明文 prefs 文件（[PREFS_FILE]），与账号存储**完全隔离**。
 *
 * ==================== 安全边界 ====================
 * 本文件**只许**存放非机密的状态（布尔/计数/时间戳）。
 * ⚠️ 绝不要把密码、cookie、token 写进这里 —— 明文落盘，root 或备份即可读走。
 *
 * ==================== 读写代价 ====================
 * 读：getSharedPreferences 命中内存缓存后是纯内存读取（纳秒级），可以在主线程调用。
 * 写：用 **apply()** 而不是 commit() —— 它把落盘交给后台线程，不阻塞 UI。
 *     跨进程/崩溃前丢失的极端窗口可以忽略：诊断开关丢了最坏结果是"再点一次"。
 */
object AppPrefs {

    private const val TAG = "AppPrefs"

    /** 明文首选项文件名（落在 /data/data/<pkg>/shared_prefs/ 下）。 */
    private const val PREFS_FILE = "dsh_app_prefs"

    /**
     * 诊断模式开关（v0.4.8）。
     *
     * 为什么持久化：排查侧栏问题需要重启 App 复现（重启是复现条件之一），
     * 每次重启都要重新打开开关会让"这次到底开没开"变成一个不确定变量。
     *
     * ⚠️ 默认 **false** —— 默认开启会让所有用户都看到诊断浮层并在页面上做 500ms 轮询。
     */
    const val KEY_SIDEBAR_DIAG = "sidebar_diag"

    /** [KEY_SIDEBAR_DIAG] 的默认值（= 关闭）。UI 回填与 JS 调用都要用这一个常量。 */
    const val DEFAULT_SIDEBAR_DIAG = false

    /**
     * 桌面版网站开关（v0.4.11）。
     *
     * ==================== 为什么「桌面版」只是改 viewport ====================
     * DSH 前端是**纯 CSS 响应式**，不看 User-Agent（apps/web/index.html 里只有一行
     * `<meta name="viewport" content="width=device-width, initial-scale=1" />`）。
     * 所以「切换桌面版」的本质 = 把 viewport 的 width 改成固定值（见 WebViewActivity），
     * 而不是伪装 UA。UA 只在不看它的前端上是纯噪声，还会干扰服务端的分流判断。
     *
     * ==================== 为什么不清它（退出登录时）====================
     * 这是**显示偏好**，不是账号信息。换个人登录时他用的是同一块屏幕，
     * 屏幕尺寸决定了他需要哪种布局；把它随账号清掉只会让下一个人重新调一遍。
     * ⚠️ 与 AccountStore 里的 host/username/password 是**两回事**，别合并。
     */
    const val KEY_DESKTOP_MODE = "desktop_mode"

    /** [KEY_DESKTOP_MODE] 的默认值（= 手机版，即首次安装的形态）。 */
    const val DEFAULT_DESKTOP_MODE = false

    /**
     * 取 prefs 实例。
     *
     * 失败返回 null（极少数 ROM 上 getSharedPreferences 可能抛），调用方按默认值处理。
     * 不在主线程做任何 IO：这里只是拿一个已打开的句柄。
     */
    private fun open(context: Context): SharedPreferences? {
        return try {
            context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        } catch (e: Exception) {
            Log.w(TAG, "prefs unavailable (" + e.javaClass.simpleName + ")")
            null
        }
    }

    /** 读诊断模式开关；存储不可用时返回 [DEFAULT_SIDEBAR_DIAG]（= 关闭，fail-closed）。 */
    fun isSidebarDiagEnabled(context: Context): Boolean {
        val prefs = open(context) ?: return DEFAULT_SIDEBAR_DIAG
        return try {
            prefs.getBoolean(KEY_SIDEBAR_DIAG, DEFAULT_SIDEBAR_DIAG)
        } catch (e: Exception) {
            // ClassCastException：文件里同名键被写成了别的类型（手工改过 / 版本回退）。
            // 当作默认值，不中断任何流程。
            Log.w(TAG, "read " + KEY_SIDEBAR_DIAG + " failed (" + e.javaClass.simpleName + ")")
            DEFAULT_SIDEBAR_DIAG
        }
    }

    /**
     * 写诊断模式开关。
     *
     * @return true = 已提交给 prefs（apply 已入队）；false = 存储不可用。
     *   调用方**不要**因为 false 就回滚 UI —— 本次进程内的开关仍然是生效的
     *   （JS 侧照常调用），只是下次启动记不住。报告失败比悄悄吞掉好，但也别阻断了功能。
     */
    fun setSidebarDiagEnabled(context: Context, enabled: Boolean): Boolean {
        val prefs = open(context) ?: return false
        return try {
            prefs.edit().putBoolean(KEY_SIDEBAR_DIAG, enabled).apply()
            true
        } catch (e: Exception) {
            Log.w(TAG, "write " + KEY_SIDEBAR_DIAG + " failed (" + e.javaClass.simpleName + ")")
            false
        }
    }

    /** 读「桌面版网站」开关；存储不可用时返回 [DEFAULT_DESKTOP_MODE]（= 手机版）。 */
    fun isDesktopModeEnabled(context: Context): Boolean {
        val prefs = open(context) ?: return DEFAULT_DESKTOP_MODE
        return try {
            prefs.getBoolean(KEY_DESKTOP_MODE, DEFAULT_DESKTOP_MODE)
        } catch (e: Exception) {
            Log.w(TAG, "read " + KEY_DESKTOP_MODE + " failed (" + e.javaClass.simpleName + ")")
            DEFAULT_DESKTOP_MODE
        }
    }

    /**
     * 写「桌面版网站」开关。
     *
     * @return true = 已提交给 prefs（apply 已入队）；false = 存储不可用。
     *   与 [setSidebarDiagEnabled] 同一约定：失败**不回滚 UI**，
     *   本次进程内用户仍能切（只是下次启动记不住）。
     */
    fun setDesktopModeEnabled(context: Context, enabled: Boolean): Boolean {
        val prefs = open(context) ?: return false
        return try {
            prefs.edit().putBoolean(KEY_DESKTOP_MODE, enabled).apply()
            true
        } catch (e: Exception) {
            Log.w(TAG, "write " + KEY_DESKTOP_MODE + " failed (" + e.javaClass.simpleName + ")")
            false
        }
    }

    // ============================================================================
    // v0.5.0：使用模式（完整 WebView / 兼容原生）
    // ============================================================================

    /**
     * 界面模式键。**未设置 = 用户还没选过**（返回 null，触发选择页）。
     *
     * 为什么用 String 而不是枚举：SharedPreferences 只支持基本类型；
     * 存字符串让「未来加第三种模式」不用改存储层，且旧值可读。
     */
    const val KEY_UI_MODE = "ui_mode"

    /** 完整模式（WebView 承载 DSH 网页）。功能全，依赖系统 WebView 内核。 */
    const val UI_MODE_FULL = "full"

    /** 兼容模式（纯原生前端）。只有核心功能，但不依赖 WebView 内核。 */
    const val UI_MODE_COMPAT = "compat"

    /**
     * 读使用模式。
     *
     * @return [UI_MODE_FULL] / [UI_MODE_COMPAT] / **null = 还没选过**（调用方应显示选择页）。
     *   ⚠️ null 与「选了完整模式」是两件事 —— 不要用默认值掩盖这个区别，
     *   否则新用户永远看不到模式说明（直接进 WebView，出问题也不知道有另一条路）。
     */
    fun getUiMode(context: Context): String? {
        val prefs = open(context) ?: return null
        return try {
            val v = prefs.getString(KEY_UI_MODE, null)
            // 只认已知值：存储被改成奇怪值时当作「没选过」，重新让用户选。
            if (v == UI_MODE_FULL || v == UI_MODE_COMPAT) v else null
        } catch (e: Exception) {
            Log.w(TAG, "read " + KEY_UI_MODE + " failed (" + e.javaClass.simpleName + ")")
            null
        }
    }

    /**
     * 写使用模式。
     *
     * @return true = 已提交；false = 存储不可用（本次进程内仍生效，只是下次要重选）。
     */
    fun setUiMode(context: Context, mode: String): Boolean {
        if (mode != UI_MODE_FULL && mode != UI_MODE_COMPAT) {
            Log.w(TAG, "setUiMode: unknown mode ignored: " + mode)
            return false
        }
        val prefs = open(context) ?: return false
        return try {
            prefs.edit().putString(KEY_UI_MODE, mode).apply()
            true
        } catch (e: Exception) {
            Log.w(TAG, "write " + KEY_UI_MODE + " failed (" + e.javaClass.simpleName + ")")
            false
        }
    }
}
