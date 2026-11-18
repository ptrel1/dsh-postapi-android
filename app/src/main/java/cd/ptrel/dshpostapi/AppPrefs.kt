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

    // ==================== v0.9.1：上次活跃会话 ====================
    //
    // ## 为什么需要持久化 sessionId
    //
    // 20261116 ISSUE-01 实测：Compose 聊天页的 sessionId 原本只存在
    // ChatScreenViewModel 的内存里。退出聊天页或 App 重启即丢失，
    // 下次发送走「新会话」分支 ⇒ POST /chat/stream 的 sessionId 为 null
    // ⇒ 服务端每次创建新会话 ⇒ 会话列表里堆一串内容只有一条消息的游离会话。
    //
    // 用户侧表现是「我明明接着聊的，怎么又多了一个会话」。
    //
    // 存「最后一次活跃的会话」而不是整体会话列表：
    //   · 会话列表以服务端为准（/sessions），本地缓存反而会不一致；
    //   · 用户重开 App 的预期是「接着上次那段聊」，不是「回到某个固定页」。
    const val KEY_BACKGROUND_PATH = "background_path"
    const val KEY_LAST_SESSION_ID = "last_session_id"

    /** 读取上次活跃会话 id；从未记过或读取失败返回 null。 */
    /**
     * 用户自定义背景图（v0.12.0）。
     *
     * 存**绝对路径**（app 私有目录下的文件），不是 Uri ——
     * Uri 的临时读权限在进程重启后失效，而背景图要长期可用。
     * 空 = 没设置，用主题默认背景。
     */
    fun getBackgroundPath(context: Context): String? {
        return try {
            val prefs = open(context) ?: return null
            prefs.getString(KEY_BACKGROUND_PATH, null)?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "read background_path failed (" + e.javaClass.simpleName + ")")
            null
        }
    }

    fun setBackgroundPath(context: Context, path: String?): Boolean {
        return try {
            val prefs = open(context) ?: return false
            // 用 commit() 而不是 apply()：返回值需要真实的成功/失败，
            // 而 Editor.apply() 是异步的、恒返回 Unit —— 拿不到结果。
            // 背景图这种一次性写入，同步落盘的代价可以忽略。
            val editor = prefs.edit()
            if (path.isNullOrBlank()) editor.remove(KEY_BACKGROUND_PATH)
            else editor.putString(KEY_BACKGROUND_PATH, path)
            editor.commit()
        } catch (e: Exception) {
            Log.w(TAG, "write background_path failed (" + e.javaClass.simpleName + ")")
            false
        }
    }


    // ── 玻璃质感档位（v0.17.0）─────────────────────────────────
    //
    // 三档，默认 MEDIUM（模糊 + 高光）：
    //   HIGH   —— API 33+ 用 AGSL 边缘折射（真液体玻璃）
    //   MEDIUM —— API 31+ 用 Modifier.blur() 磨砂，老设备退化为半透明
    //   OFF    —— 纯半透明 + 边框，零 GPU 开销
    //
    // 为什么给 OFF：老设备（API < 31，或 GPU 弱）上磨砂每帧一次 blur，
    // 流式输出时能明显掉帧。用户需要能关掉。
    const val KEY_GLASS_QUALITY = "glass_quality"

    /** 玻璃质感档位。存的是档位名而不是序号 —— 以后加档位不打乱旧值。 */
    fun getGlassQuality(context: Context): String {
        return try {
            val prefs = open(context) ?: return GLASS_MEDIUM
            prefs.getString(KEY_GLASS_QUALITY, GLASS_MEDIUM) ?: GLASS_MEDIUM
        } catch (e: Exception) {
            Log.w(TAG, "read glass_quality failed (" + e.javaClass.simpleName + ")")
            GLASS_MEDIUM
        }
    }

    fun setGlassQuality(context: Context, quality: String): Boolean {
        return try {
            val prefs = open(context) ?: return false
            prefs.edit().putString(KEY_GLASS_QUALITY, quality).commit()
        } catch (e: Exception) {
            Log.w(TAG, "write glass_quality failed (" + e.javaClass.simpleName + ")")
            false
        }
    }

    const val GLASS_HIGH = "high"
    const val GLASS_MEDIUM = "medium"
    const val GLASS_OFF = "off"

    // ── 诊断模式（v0.19.1）─────────────────────────────────────
    //
    // 开启后聊天页显示流式帧统计浮层（watch 连接状态、帧计数、丢弃数、
    // 当前 clientId / externalId）。
    //
    // 为什么做成**用户可开**而不是常驻或隐藏手势：
    //   · 常驻会干扰正常使用（浮层挡内容）
    //   · 隐藏手势（连点 5 次之类）用户根本不知道存在
    //   · 排查流式同步问题时（多端重复/漏字/不同步），这些数字是唯一的判据
    const val KEY_DIAGNOSTICS = "diagnostics_enabled"

    fun isDiagnosticsEnabled(context: Context): Boolean {
        return try {
            open(context)?.getBoolean(KEY_DIAGNOSTICS, false) ?: false
        } catch (e: Exception) {
            false
        }
    }

    fun setDiagnosticsEnabled(context: Context, enabled: Boolean): Boolean {
        return try {
            open(context)?.edit()?.putBoolean(KEY_DIAGNOSTICS, enabled)?.commit() ?: false
        } catch (e: Exception) {
            Log.w(TAG, "write diagnostics failed (" + e.javaClass.simpleName + ")")
            false
        }
    }

    fun getLastSessionId(context: Context): String? {
        return try {
            val prefs = open(context) ?: return null
            prefs.getString(KEY_LAST_SESSION_ID, null)?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "read " + KEY_LAST_SESSION_ID + " failed (" + e.javaClass.simpleName + ")")
            null
        }
    }

    /**
     * 记住当前活跃会话。
     *
     * @param sessionId 传 null 或空串表示「清除」（用户主动新建会话时）。
     * @return false 表示写入失败（存储不可用）。调用方**不应**因此中断流程 ——
     *   记不住只会导致下次失去续聊能力，不影响本次使用。
     */
    fun setLastSessionId(context: Context, sessionId: String?): Boolean {
        return try {
            val prefs = open(context) ?: return false
            prefs.edit().apply {
                if (sessionId.isNullOrBlank()) remove(KEY_LAST_SESSION_ID)
                else putString(KEY_LAST_SESSION_ID, sessionId)
            }.apply()
            true
        } catch (e: Exception) {
            Log.w(TAG, "write " + KEY_LAST_SESSION_ID + " failed (" + e.javaClass.simpleName + ")")
            false
        }
    }

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
