package cd.ptrel.dshpostapi

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.ValueCallback
import android.webkit.PermissionRequest
import android.provider.MediaStore
import android.content.ClipData
import android.content.ActivityNotFoundException
import android.app.Activity
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.FileProvider
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import org.json.JSONObject
import cd.ptrel.dshpostapi.databinding.ActivityWebviewBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * WebView 容器：种入插件会话 cookie，然后用一次导航加载 launchUrl。
 *
 * ============================ 本文件的两个不可改约束 ============================
 * 1. launchUrl 必须【一次导航】直接加载。
 *    官方门禁（browser-auth.ts:244）只认 GET / 且恰好一个 token 参数，
 *    校验通过才 303 + Set-Cookie 铸出官方签名 cookie。
 *    ⇒ 不能"先 loadUrl(host) 再改地址"、不能拆成两步、不能在中间插别的跳转。
 *
 * 2. cookie 必须在 loadUrl 之前种好，并且要 flush()。
 *    插件会话 cookie 是 WebView 内所有 /api/dsh/v1/ 请求（含第二版的下载接口）
 *    的鉴权依据；CookieManager 的写入是异步落盘的，不 flush 时本次进程内的
 *    后续请求有时拿不到（尤其是紧接着的导航）。
 * =============================================================================
 *
 * cookie 为什么不落 SharedPreferences：
 *   官方 cookie 是 HttpOnly + SameSite=Strict（browser-auth.ts:122），本来就取不出来；
 *   插件 cookie 虽可取，但它是可冒用的凭据，存盘等于长期留存一份可被读取的凭证。
 *   MVP 的语义就是"一次登录 = 一次会话"，重启 App 需重新登录（见 README「已知限制」）。
 */
class WebViewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LAUNCH_URL = "cd.ptrel.dshpostapi.EXTRA_LAUNCH_URL"
        const val EXTRA_COOKIE_NAME = "cd.ptrel.dshpostapi.EXTRA_COOKIE_NAME"
        const val EXTRA_COOKIE_VALUE = "cd.ptrel.dshpostapi.EXTRA_COOKIE_VALUE"
        const val EXTRA_COOKIE_MAX_AGE = "cd.ptrel.dshpostapi.EXTRA_COOKIE_MAX_AGE"

        /** 登录时用的服务基址。只用于「退出登录」后的预填，本身不是机密。 */
        const val EXTRA_HOST = "cd.ptrel.dshpostapi.EXTRA_HOST"

        /**
         * resume 模式（v0.3.0）。
         *
         * true 表示本次进入**不是**一次新登录：cookie 已经在 CookieManager 里
         * （冷启动命中未过期 cookie），或已由 LoginFlow 种好（自动重登成功）。
         * 此时本页**不再种 cookie**，避免把 Max-Age 重置成另一个值。
         *
         * 为什么用显式开关而不是"cookie 三件套是否为空"来推断：
         *   空 cookie 在本 App 里是一个合法的异常状态（存储被清但残留 intent），
         *   靠字段为空来分支会让两种情况混在一起，难以排查。
         */
        const val EXTRA_RESUME_MODE = "cd.ptrel.dshpostapi.EXTRA_RESUME_MODE"

        private const val TAG = "DshFileChooser"

        /**
         * v0.4.3 诊断日志的 TAG。真机排查用：
         *   adb logcat -s WebViewLayout
         * 单独一个 TAG（而不是复用 DshFileChooser）是为了能一行 grep 只看布局。
         */
        private const val TAG_LAYOUT = "WebViewLayout"

        /**
         * v0.4.6 性能日志的 TAG。真机排查用：
         *   adb logcat -s WebViewPerf
         * 与 [TAG_LAYOUT] 分开，便于分别 grep「键盘平移」与「流式渲染优化」两条线。
         */
        private const val TAG_PERF = "WebViewPerf"

        /**
         * v0.4.7 发送监听日志的 TAG。真机排查用：
         *   adb logcat -s WebViewSendWatch
         * 能直接看到「JS 装没装上 / 读到的时间戳 / 收键盘的瞬间」，不必猜。
         */
        private const val TAG_SEND_WATCH = "WebViewSendWatch"

        /**
         * v0.4.8 诊断模式的 TAG。真机排查用：
         *   adb logcat -s WebViewDiag
         * 打的是「开关切到了什么 / 页面加载后能不能找到 __postapiSidebarDiag /
         * 重试到第几次成功」——排查「开关点了没反应」时先看这里。
         */
        private const val TAG_DIAG = "WebViewDiag"

        /**
         * 逃生阀（v0.4.6）——**对照测试与线上急救**用，加在页面 URL 上即可：
         *
         *   ?noperf=1   不动 WebView 渲染策略：不注入 containment CSS、不设硬件层。
         *               用于对照测「卡顿到底是不是注入带来的副作用」。
         *               （注入在页面关闭前不会移除，改参数需彻底冷启一次才生效。）
         *   ?nohw=1     只跳过 LAYER_TYPE_HARDWARE。
         *               **急救开关**：LAYER_TYPE_HARDWARE 在个别 ROM 上会让 WebView
         *               白屏/花屏；真机遇到渲染异常时加这个参数启动，其余优化照旧。
         *
         * 判定方式见 [isNoPerfRequested] / [isNoHardwareLayerRequested]。
         */
        private const val PARAM_NO_PERF = "noperf"
        private const val PARAM_NO_HW = "nohw"

        /**
         * 逃生阀（v0.4.7）——`?nosendwatch=1`：完全不装「Enter 发送后收键盘」这一整套。
         *
         * 为什么不与 ?noperf=1 合并：
         *   两者要能**独立**对照。收键盘会不会误伤（例如输入法组词时被误收起）
         *   与渲染优化毫无关系；合并成一个开关就没法只关一边来定位问题。
         *
         * 关掉后：不注入 JS、也不启动 Handler 轮询（省掉每秒两次的 JSBridge 往返）。
         */
        private const val PARAM_NO_SEND_WATCH = "nosendwatch"

        /**
         * 发送轮询间隔（v0.4.7）。
         *
         * 为什么是 500ms：这是「收键盘」这种一次性动作的感知阈值 —— 人眼看不出 0.5s 的延迟，
         *   而 500ms 意味着每秒仅 2 次 evaluateJavascript 往返（几乎零成本）。
         * 为什么不用 requestAnimationFrame / 每帧：那是 60 次/秒，流式返回时正是页面最忙的时刻，
         *   等于在我们正在修的卡顿上再加一份负担。
         * 为什么不用 DOM observer：同理，消息列表每秒新增几十个节点，MutationObserver 回调频率
         *   与节点数成正比；而键盘按键频率与人打字速度同阶，天生低频。
         */
        private const val SEND_WATCH_INTERVAL_MS = 500L

        /** 文件选择器与拍照的请求码（互不相同，便于 onActivityResult 区分）。 */
        private const val REQ_FILE_CHOOSER = 4001
        private const val REQ_FILE_CAPTURE = 4002

        /**
         * v0.4.8：诊断接口的存在性检查 —— 最多试几次、每次间隔多久。
         *
         * 为什么是「轮询」而不是「onPageFinished 后 delay 500ms 一次」：
         *   __postapiSidebarDiag 由插件 client.js 在 cordis 的 apply() 里创建，
         *   而 apply 的时机与页面 load 事件**没有耦合** —— 网络慢时插件 bundle 可能
         *   晚 2~3 秒才执行完。固定 delay 只有两种结果：等太短（一次没中，诊断静默失效）
         *   或等太长（每次加载都白等）。
         *   轮询把"等多久"交给事实决定：就绪即停，未就绪最多 10 × 300ms = 3 秒。
         *
         * 为什么上限只有 3 秒：超过 3 秒还没就绪，说明插件没装 / 版本低于 v1.7.1 /
         *   页面报错了 —— 再等下去也不会出现。此时打一条 warn 并结束，用户能在
         *   logcat 与 Toast 上看到原因，而不是面对一个"看起来开着、其实没生效"的开关。
         */
        private const val DIAG_RETRY_MAX = 10
        private const val DIAG_RETRY_INTERVAL_MS = 300L

        /**
         * MIME 全通配。
         *
         * 抽成常量的目的**不是复用**，而是让"星号+斜杠+星号"这个字面量在本文件里
         * 只出现在**代码**中、不出现在**注释**里：注释里写它会把块注释提前闭合，
         * 后面整段代码被当成注释 —— v0.3.1 实测踩过（编译器报 Unclosed comment 且位置偏移）。
         */
        private const val MIME_ANY = "*/*"

        /** 子类型通配那一半（与 MIME_ANY 同理，只在代码里出现）。 */
        private const val MIME_WILDCARD = "*"

        /**
         * 桌面版网站的视口宽度（v0.4.11）。
         *
         * 为什么是 980：这是 WebView 在「没有 viewport meta」时的默认虚拟宽度，
         *   也是绝大多数移动端框架（Bootstrap / Tailwind 的断点体系）视为
         *   「桌面布局」的下限宽度。改成 1280 只会让字更小、没有更多信息量 ——
         *   在 6 吋屏上，980 已经是「按桌面栅格排版 + 缩放适配」的最优解。
         *
         * ⚠️ 只在这里定义一次：JS 注入串由它拼出来，不要在别处再写一个字面量。
         */
        private const val DESKTOP_VIEWPORT_WIDTH = 980

        /**
         * 桌面版注入用的 viewport content 原文。
         *
         * 写法依据（这一段**不要**改成宽度自适应）：
         *   · width=980       —— 固定虚拟宽度 ⇒ 触发桌面断点；
         *   · initial-scale=1 —— 桌面宽度按 1:1 下地图，不平移不缩放；
         *   · 不写 user-scalable=no / maximum-scale：DSH 的字号需要用户手动放大才能看清，
         *     禁用缩放是不可接受的副作用（iOS/Android 的无障碍规范也都反对）。
         */
        private const val DESKTOP_VIEWPORT_CONTENT =
            "width=" + DESKTOP_VIEWPORT_WIDTH + ", initial-scale=1"

        /** 拍照临时文件的"过期"阈值：超过这个时长的旧文件在下次拍照时被清掉。 */
        private const val CAPTURE_STALE_MS = 60L * 60L * 1000L

        /**
         * 拍照临时目录名。
         * ⚠️ 必须与 res/xml/file_paths.xml 里的 <cache-path name="filechooser" path="filechooser/" />
         *    一字不差，否则 FileProvider.getUriForFile 抛 IllegalArgumentException（点拍照没反应）。
         */
        private const val CAPTURE_DIR_NAME = "filechooser"
    }

    private lateinit var binding: ActivityWebviewBinding
    private var launchUrl: String = ""
    private var lastExitBackAt: Long = 0L

    /**
     * 登录时用的服务基址（不含尾斜杠）。本身不是机密。
     *
     * 用途：Intent 没带 EXTRA_HOST 时的兜底（initHostBaseUrl），以及错误文案里定位服务器。
     * ⚠️ 不要往里塞任何凭据。
     */
    private var hostBaseUrl: String = ""

    /**
     * 本次进入是否复用已有 cookie（见 [EXTRA_RESUME_MODE]）。
     *
     * 只影响两处：不再种 cookie；错误页在 401/403 时仍允许「重试」。
     */
    private var resumeMode: Boolean = false

    /**
     * 是否已经在本页做过一次「加载成功后检查更新」。
     *
     * 为什么要这个标志：onPageFinished 在单页应用里会随路由变化反复触发，
     * 不设标志就是每换一个会话都查一次更新 —— 既是无谓流量，也可能在用户
     * 正在打字时弹窗。用户要求的是「WebView 首次加载成功后检查一次」。
     */
    private var updateCheckedOnPageFinished = false

    /**
     * v0.4.6：本页是否启用性能优化（containment CSS + 硬件层）。
     *
     * 来源：launchUrl 的 ?noperf=1 逃生阀（见 [isNoPerfRequested]）。
     * 为什么在 onCreate 里就定死、而不是每次用到时现解析：
     *   一次导航里 URL 不会再变，现解析只是让"这页到底优不优化"散落成多处判断；
     *   定死成一个字段后，注入与硬件层共用同一个开关，不会出现"注入了 CSS 却没关硬件层"的错配。
     */
    private var perfEnabled: Boolean = true

    /**
     * v0.4.6：本页是否已经注入过 containment CSS。
     *
     * 为什么不每次都注入：onPageFinished 在 SPA 里会随路由重复触发（见
     * [updateCheckedOnPageFinished] 的说明），重复 appendChild 只会堆一串同 id 的 <style>。
     * 而我们的选择器是**全局**的，注入一次即对后续路由生效，不需要每页重来。
     *
     * ⚠️ 真正整页重载（reload / 链接跳转）时 document 会被换掉，旧的 <style> 也随之消失 ——
     *    所以注入前还要用 JS 侧的存在性判断兜底（见 [injectPerfCss]），不能只靠这个字段。
     */
    private var perfCssInjected = false

    /**
     * v0.4.7：本页是否启用「Enter 发送后收键盘」（false = URL 带了 ?nosendwatch=1）。
     *
     * 与 [perfEnabled] 同理，在 onCreate 里一次定死：
     *   注入与轮询共用同一个开关，不会出现「注了 JS 却没人读」或「轮询读一个永远不存在的值」。
     */
    private var sendWatchEnabled: Boolean = true

    /**
     * v0.4.7：已经在 App 侧处理过的**最大**发送时间戳（毫秒，来源见 [injectSendWatcher]）。
     *
     * 为什么存「最大值」而不是「上一次的值」：
     *   页面的 __dshLastSend 是单调递增的时间戳，判断条件写成 ts > lastHandledSendAt
     *   **天然幂等** —— 同一毫秒重复读到同一个值不会重复收键盘；
     *   若写成 "ts != last" 且中途 JS 侧被重置为 0，会把 0 当成一次新发送处理。
     *
     * 为什么不需要持久化：收键盘是瞬时 UI 行为，进程重启后从 0 开始最安全
     *   （初值为 0 时，只要页面里有过发送记录就会收一次键盘 —— 但那时页面刚加载、
     *    没有任何发送记录，__dshLastSend 是 undefined ⇒ 读出来是 0 ⇒ 不触发，见 [buildSendWatcherJs]）。
     */
    private var lastHandledSendAt: Long = 0L

    /**
     * v0.4.8：诊断模式当前是否开启。
     *
     * 来源：onCreate 时从 [AppPrefs] 读出；用户拨开关时立即更新。
     * **唯一真相就在 prefs 里**，这个字段只是本进程内的镜像 ——
     * 用字段是为了让 onPageFinished 与轮询回调不必每次读盘（虽然 prefs 是内存命中，
     * 但"一个开关散落成两处读"迟早会漂移）。
     */
    private var diagEnabled: Boolean = false

    /**
     * v0.4.11：当前是否为「桌面版网站」模式。
     *
     * 来源：onCreate 时从 [AppPrefs] 读出（key = desktop_mode，默认 false）。
     * **唯一真相在 prefs 里**，这个字段是本进程内的镜像 —— 与 [diagEnabled] 同一套纪律：
     * 用字段是为了让 configureWebView / onPageFinished 不必每次读盘，
     * 同时避免"一个开关在两处各读一次、结果漂移"。
     *
     * 影响三处（**必须一起看**，只看一处会以为它没生效）：
     *   ① [configureWebView]：useWideViewPort / loadWithOverviewMode / UA 尾巴；
     *   ② [applyDesktopViewport]：onPageFinished 时改写页面里的 <meta viewport>；
     *   ③ [setupMenu]：按钮文案（切换桌面版 / 切换移动版）。
     */
    private var desktopMode: Boolean = false

    /**
     * v0.4.8：已经因为「页面加载完成」而发起的诊断就绪检查的轮询链。
     *
     * 为什么要有 removeCallbacks：onPageFinished 在 SPA 里随路由反复触发，
     * 不先摘掉的话每次路由切换都会挂一条新的轮询链 —— 10 次 × N 条并发 JSBridge 往返，
     * 而且旧链还会在"新页面"上报一次 enable（无害但日志全是噪声，排查时更难读）。
     * 先摘再挂 ⇒ 任何时刻最多一条链（与 [restartSendWatchPolling] 同一套纪律）。
     */
    private val diagRetryHandler = Handler(Looper.getMainLooper())

    /**
     * v0.4.7：轮询用的 Handler。
     *
     * ⚠️ 它持有对本 Activity 的隐式引用（Runnable 是内部类/匿名对象）。
     *    必须在 onPause 与 onDestroy 里 removeCallbacks，否则「Activity 已销毁、消息队列里还排着下一次」⇒ 整页泄漏。
     */
    private val sendWatchHandler = Handler(Looper.getMainLooper())

    /**
     * v0.4.7：轮询任务本体。
     *
     * 流程：读页面上的 __dshLastSend → 比 [lastHandledSendAt] → 变大就收键盘 + 更新基线 → 排下一次。
     *
     * 为什么用「读 JS 变量」而不是 Java 对象桥（addJavascriptInterface）：
     *   后者需要在注入的 JS 里调用全局对象上的方法，而本 App 刻意**不**开 JS 桥接接口
     *   （攻击面：页面里任何脚本都能调到它）。evaluateJavascript 是单向只读的，暴露面为零。
     */
    private val sendWatchRunnable: Runnable = object : Runnable {
        override fun run() {
            // isFinishing / isDestroyed 双判：Activity 销毁后 evaluateJavascript 会抛
            // （WebView 已 destroy），且此时再 postDelayed 就是永久泄漏。
            if (isFinishing || isDestroyed) return
            if (!sendWatchEnabled) return
            runCatching {
                binding.webView.evaluateJavascript("window.__dshLastSend || 0") { raw ->
                    // raw 是 JS 返回值的 JSON 文本：数字返回 "1234567890"，undefined/0 返回 "0"，
                    // 某些内核回 null。三重兜底（去空白 → 去引号 → toLongOrNull）任一失败都当 0。
                    val ts = raw?.trim()?.trim('"')?.toLongOrNull() ?: 0L
                    if (ts > lastHandledSendAt) {
                        lastHandledSendAt = ts
                        hideKeyboard()
                    }
                }
            }.onFailure { t ->
                // 页面正在导航时 evaluateJavascript 可能抛（无 WebView / 已 destroy 的竞态）。
                // 吞掉：下一次轮询还会再来，不该因一次失败让整条链断掉。
                Log.w(TAG_SEND_WATCH, "poll failed: " + t.message)
            }
            sendWatchHandler.postDelayed(this, SEND_WATCH_INTERVAL_MS)
        }
    }

    /**
     * WebView 的文件选择回调。必须持有到 onActivityResult 才能把结果回传。
     *
     * ⚠️ 不持有 = 用户选完文件没有任何反应。
     * ⚠️ 覆盖前必须先把旧的回调以 null 结束掉，否则旧回调永远悬空——
     *    WebView 会认为上一次选择还没完成，后续点击不再触发 onShowFileChooser。
     */
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null

    /**
     * 拍照时的临时文件。只在 REQ_FILE_CAPTURE 往返期间有意义。
     * 用户取消拍照或启动相机失败时，这个文件可能残留，需要清理。
     */
    private var pendingCaptureFile: File? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ===== 系统栏留白（targetSdk 35 强制 edge-to-edge 的必要处理）=====
        // 详见 WindowInsetsHelper 的文件注释。要点：
        //   · padding 加在 rootFrame 上 ⇒ 状态栏区域显示 rootFrame 的背景色
        //     （= colorBackground），网页从状态栏下方开始，不会"透出网页"；
        //   · loadingView / errorView 是 rootFrame 的子 View，会一并被 padding 约束，
        //     因此它们也不会盖到状态栏区域（这是要求 3）。
        //
        // ★ v0.4.5：键盘让位改用 translationY（translateForIme = true），padding 只管系统栏。
        //
        //   演进（读的时候别只看最后一行，要看为什么前几版不够）：
        //     v0.4.2  adjustNothing + ime padding：padding 一长高 rootFrame 就重新布局，
        //             WebView 有效高度变化 ⇒ 100vh 变 ⇒ 打字重排（实测仍卡）。
        //     v0.4.3  adjustPan + handleIme = false：以为系统会整体平移窗口。
        //             实测**完全没动**、输入框被键盘盖住 —— 根因是本类调用了
        //             setDecorFitsSystemWindows(false)，等于声明「insets 我自己管」，
        //             与 adjustPan（要求系统帮我平移窗口）语义矛盾，系统两边都不做。
        //     v0.4.5  自己平移：insets 回调里设 rootFrame.translationY = -ime.bottom。
        //             只改绘制位置，布局尺寸不变 ⇒ WebView 尺寸/100vh 恒定 ⇒ 零重排，
        //             同时页面确实上移、输入框从键盘底下露出来。
        //
        //   ⚠️ 必须走 applySystemBarPadding 的这个参数，**不能**再单独调
        //      applyImeTranslation(rootFrame)：setOnApplyWindowInsetsListener 是覆盖语义，
        //      后挂的 listener 会把负责状态栏 padding 的那个挤掉（顶部内容钻到状态栏下面）。
        //   ⚠️ handleIme = false 与 translateForIme = true **成对**：键盘让位只能有一个施力点，
        //      两者同时为 true ⇒ 键盘高度让两遍。
        //   ⚠️ 与 AndroidManifest 的 adjustNothing **成对**（系统别动，我们自己平移）。
        // ===== v0.4.11：按 API 分级 —— 「接管 insets」还是「交还系统」=====
        //
        // ⚠️ v0.4.10 的失败点（读代码时先看这一段，别再走回头路）：
        //   v0.4.10 在 API 29- 上改用 padding 让位，但**仍然**调用了
        //   setDecorFitsSystemWindows(false) —— 那个标志才是问题本身。
        //   实测（华为 VRD-AL10 / Android 10 / EMUI）：三种方案（adjustPan、
        //   translationY、padding）**全部无效**，说明根因不在「怎么让位」，
        //   而在「让位所依赖的 ime.bottom 拿不到」：该值恒为 0。
        //
        //   为什么 API 29- 拿不到：
        //     setDecorFitsSystemWindows(false) 在 Android 11 之前只是把标志翻译成
        //     "别让系统避让"，并没有配套保证 ime inset 会被上报；EMUI 这类 ROM
        //     此时仍按老规矩（adjustResize）由**系统自己**处理键盘。
        //     于是：系统的做法被我们的标志关掉了一半，我们自己的方案又拿不到数据
        //     ⇒ 两头落空，页面完全不动。
        //
        // 策略（两分支互斥，且与 AndroidManifest 的 softInputMode 成对）：
        //   API 30+ (Android 11+)：**接管** —— padding 处理系统栏，translationY 处理键盘。
        //     布局尺寸零变化 ⇒ DSH 的 100vh 不变 ⇒ 打字不重排。（v0.4.5 的成果，保留）
        //   API 29- (Android 10-)：**交还系统** —— 不调 setDecorFitsSystemWindows(false)，
        //     不设 padding、不平移。状态栏由 Window 的 fitsSystemWindows 语义让开，
        //     键盘由 manifest 的 adjustResize 缩短窗口。
        //     理由：Android 10 及以下**本来就不强制 edge-to-edge**，系统默认就能正确避让；
        //     那套接管逻辑只为 targetSdk=35 在 Android 15+ 上的强制 edge-to-edge 而生。
        //
        // ⚠️ API 29- 的已知代价：adjustResize 会缩窗口 ⇒ WebView 有效高度变化 ⇒
        //    前端 100vh 跟着变 ⇒ 打字可能有重排（与 v0.4.2 同一个机制）。
        //    这是**明知取舍的选择**：键盘盖住输入框（完全不可用）比重排（可用但略卡）
        //    严重得多。若后续想消除重排，方向是让前端用 visualViewport 而不是 100vh。
        //
        // ⚠️ 分级判定只在 WindowInsetsHelper.defaultHandlesInsetsItself 里写一次，
        //    这里**不重复**判断 SDK_INT —— 两处判断迟早会漂移（一处改了另一处忘）。
        WindowInsetsHelper.applySystemBarPadding(
            binding.rootFrame,
            // 接管时才需要指定让位方式；不接管时这两个参数会被忽略（见该方法注释）。
            handleIme = false,
            translateForIme = true,
        )
        // ★ v0.4.11：softInputMode 必须与上面的"接管与否"**成对**（见 AndroidManifest 的说明）。
        // manifest 里的 static 值只能写一个，而两条路径需要不同的值 ⇒ 在这里按 API 覆写。
        // ⚠️ 调用顺序必须在 applySystemBarPadding 之后：不接管的分支里那一步不再改窗口标志，
        //    若先设 softInputMode 再交还标志，二者会在下一帧互相打架（表现为偶发闪跳）。
        applySoftInputModeForApiLevel()
        // 图标明暗跟随主题：浅色背景配深色图标，深色背景配浅色图标。
        // 不设置会出现「白图标画在白底上」看不见。
        WindowInsetsHelper.applySystemBarIconAppearance(this, isLightBackground())
        // ===== v0.4.3 临时诊断：键盘弹出/收起时打印实际尺寸 =====
        // 见 [installLayoutDiagnostics] 的说明（用 BuildConfig.DEBUG 包住，release 不执行）。
        installLayoutDiagnostics()

        val intent = intent
        launchUrl = intent.getStringExtra(EXTRA_LAUNCH_URL).orEmpty()
        val cookieName = intent.getStringExtra(EXTRA_COOKIE_NAME).orEmpty()
        val cookieValue = intent.getStringExtra(EXTRA_COOKIE_VALUE).orEmpty()
        val cookieMaxAge = intent.getIntExtra(EXTRA_COOKIE_MAX_AGE, 86400)
        resumeMode = intent.getBooleanExtra(EXTRA_RESUME_MODE, false)
        hostBaseUrl = intent.getStringExtra(EXTRA_HOST).orEmpty()

        if (launchUrl.isEmpty()) {
            // 入口参数不全只可能是被外部应用直接拉起（本 Activity 未导出，
            // 正常路径不会发生）。直接退回登录页，不要留一个白屏。
            finish()
            return
        }
        if (hostBaseUrl.isEmpty()) hostBaseUrl = deriveHostBaseUrl(launchUrl)

        // ===== v0.4.8：读「诊断模式」开关（持久化，默认关闭）=====
        // 为什么在 loadUrl 之前就读：onPageFinished 里要按它决定"页面加载完成后要不要
        //   重新 enable 一次"；等到那时再读盘会让首帧的判断多一条时序分支。
        // 读的是明文 prefs（见 AppPrefs 的说明），getSharedPreferences 命中内存缓存，
        //   主线程调用无 IO 风险。
        diagEnabled = AppPrefs.isSidebarDiagEnabled(this)
        Log.d(TAG_DIAG, "diag mode at launch: " + diagEnabled)

        // ===== v0.4.11：读「桌面版网站」开关（持久化，默认关闭 = 手机版）=====
        // 为什么必须在 configureWebView() **之前**读：那里要按它设置
        //   useWideViewPort / loadWithOverviewMode / UA 尾巴；读晚了首屏就是错的布局，
        //   而 onPageFinished 的 viewport 注入只能补救 DOM，补不了 WebSettings。
        desktopMode = AppPrefs.isDesktopModeEnabled(this)
        Log.d(TAG_LAYOUT, "desktop mode at launch: " + desktopMode)

        // ===== v0.4.6：性能优化的两个逃生阀（同一份策略，两个开关）=====
        //   ?noperf=1 全关（对照测试用）    ?nohw=1 只关硬件层（渲染异常时的急救开关）
        // 必须在 configureWebView() 之前算好：硬件层是在那里设的。
        perfEnabled = !isNoPerfRequested(launchUrl)
        // v0.4.7 的开关同理：在 configureWebView() 之前定死，注入与轮询共用。
        sendWatchEnabled = !isNoSendWatchRequested(launchUrl)

        // 非 resume 模式必须有完整 cookie 三件套；resume 模式靠 CookieManager 里已有的。
        if (!resumeMode && (cookieName.isEmpty() || cookieValue.isEmpty())) {
            finish()
            return
        }

        configureWebView()
        if (!resumeMode) {
            seedCookie(launchUrl, cookieName, cookieValue, cookieMaxAge)
        }
        setupBack()
        binding.retryButton.setOnClickListener { reload() }
        binding.reloginButton.setOnClickListener { backToLogin() }
        // ===== 右下角折叠菜单（v0.4.0）：检查更新 + 退出登录 =====
        // 为什么并入菜单而不是再加一个悬浮按钮：见 [setupMenu] 上方与布局文件的说明。
        setupMenu()

        // 关键：一次导航直接到 launchUrl。
        binding.webView.loadUrl(launchUrl)
    }

    /**
     * WebView 设置。
     *
     * 逐项理由：
     *   - JavaScriptEnabled：官方前端是 React SPA，没有 JS 就是空白页；
     *   - DomStorageEnabled：会话/草稿用 localStorage，关掉会反复丢状态；
     *   - DatabaseEnabled：旧版数据存储路径，部分 WebView 版本仍依赖；
     *   - useWideViewPort + loadWithOverviewMode：官方前端有 viewport meta，
     *     不开这两项会按 980px 虚拟宽度渲染，手机上字小到看不清；
     *   - MixedContentMode = COMPATIBILITY_MODE：https 页面里若引用 http 资源
     *     （例如内网联调时的图片），默认会被拦成空白。这里放宽的是"混合内容"，
     *     不影响主文档必须 https 的要求（release 包里 usesCleartextTraffic=false）。
     *   - 关掉 file:// 访问：本 App 不需要读本地文件，留着只是扩大攻击面。
     */
    private fun configureWebView() {
        val settings: WebSettings = binding.webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // ★ v0.4.6：关掉 WebSQL 存储（databaseEnabled 自 API 19 起已废弃）。
        //   为什么敢关：DSH 前端只依赖 localStorage（domStorageEnabled 必须保留，见上），
        //   不用 WebSQL/webSQLDatabase；留着它只会让 WebView 多初始化一套已废弃的存储后端。
        //   ⚠️ 这是本版**唯一**动的 WebSettings 项 —— 其它设置（wideViewport / mixedContent 等）
        //      都经过真机验证，不做无谓改动。
        settings.databaseEnabled = false
        // ===== v0.4.11：桌面版 / 手机版的 WebSettings 差异 =====
        // useWideViewPort 两模式都开：它只是允许「按页面自带的 viewport meta 决定
        //   布局宽度」，关掉的话两种模式都无法正确排版。
        // loadWithOverviewMode 是**真正的差别**：
        //   · 手机版 true  ：若页面没有 viewport meta，把它缩到屏幕宽（否则字小到看不清）；
        //   · 桌面版 false ：按页面 viewport 的宽度（我们注入 980）原样布局，
        //                    不再强行缩到屏幕宽 —— 这正是「桌面版」的观感来源。
        // 为什么桌面版还要注入 viewport：DSH 自带 width=device-width，
        //   只改 loadWithOverviewMode 时它仍按设备宽排版，等于没切（见 applyDesktopViewport）。
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = !desktopMode
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.mediaPlaybackRequiresUserGesture = true
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        // 混合内容：https 页面里引用 http 资源时默认被拦成空白。
        // 这里放宽为 COMPATIBILITY_MODE（按内容类型决定，图片/媒体放行）。
        // 注意它只影响"页面内子资源"，不改变"主文档必须 https"这一条 ——
        // release 包的 usesCleartextTraffic=false 才是主文档级的门禁。
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        // ===== v0.4.9：UA 追加设备指纹（诊断用）=====
        // 保留 WebView 默认 UA（含 Android + Mobile），只在**末尾追加**，不替换：
        //   服务端可能按 UA 分流，且插件按 UA 做「设备类别」判定
        //   （mobile 关键词必须在 ⇒ 追加内容里也带 Android/，见下）。
        // 为什么放 UA 而不是 JavaScriptInterface：addJavascriptInterface 暴露面更大；
        //   UA 在 navigator.userAgent 里，网页侧直接可读，零桥接。
        // 为什么需要：不同 ROM 的 ime inset 行为不同，排查「键盘不上移」必须知道具体机型。
        // ⚠️ 只加一次：本方法只在 onCreate 调一次（见 setupWebView 调用点）。
        //   若将来 reload / onPageFinished 也调它，会叠加成
        //   "… DSHApp/0.4.9 … DSHApp/0.4.9 …"。届时请改为只在首次设置（见 guard 注释）。
        run {
            val baseUa = settings.userAgentString ?: ""
            // 幂等兜底：即使调用点被复制，也不会真的追加第二次。
            if (!baseUa.contains(" DSHApp/")) {
                // ★ v0.4.11：桌面模式时追加 " Desktop"。
                //   DSH 前端**不看 UA**（纯 CSS 响应式，见 AppPrefs.KEY_DESKTOP_MODE 的说明），
                //   所以这一个词不是为了"骗"前端，而是：
                //     ① 给服务端/插件一条可读的线索（navigator.userAgent 里能看出模式）；
                //     ② 排查时日志与页面侧读数能对得上，不必猜"当时是不是桌面模式"。
                //   ⚠️ 只追加、不替换成桌面 UA：替换会把 Android/Mobile 标记抹掉，
                //      插件里按 "mobile" 做分流的地方会误判成桌面浏览器。
                settings.userAgentString = baseUa +
                    " DSHApp/" + BuildConfig.VERSION_NAME +
                    " Device/" + Build.MANUFACTURER + "-" + Build.MODEL +
                    " Android/" + Build.VERSION.RELEASE +
                    (if (desktopMode) " Desktop" else "")
                Log.d(TAG_LAYOUT, "ua=" + settings.userAgentString + " desktopMode=" + desktopMode)
            } else {
                Log.d(TAG_LAYOUT, "ua already tagged, skip append: " + baseUa)
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        // 官方前端会把静态资源挂在同源路径下，没有第三方域；
        // 但登录/重定向链路上个别浏览器实现会按第三方处理，打开更稳。
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webView, true)

        // ===== v0.4.6：硬件合成层 =====
        // 为什么设：默认的 LAYER_TYPE_NONE 表示"由系统按需决定"，WebView 内部还有一层自己的
        //   渲染管线；显式声明 LAYER_TYPE_HARDWARE 后，这个 View 的内容整体作为硬件层参与合成，
        //   滚动/内容更新引起的是层内重绘而不是整层重建，能减轻主线程与合成线程的压力。
        //   —— 这只是优化项，**不是**卡顿的根因修复（根因在主线程跑不动的 JS/DOM 上，
        //      见 injectPerfCss 的说明），所以即使这行没效果也不该怀疑这里写错了。
        //
        // ⚠️ 风险与逃生阀：个别 ROM 上 LAYER_TYPE_HARDWARE 会让 WebView 白屏/花屏。
        //   加 ?nohw=1（或 ?noperf=1）启动即可跳过，其余优化照旧。
        if (perfEnabled && !isNoHardwareLayerRequested(launchUrl)) {
            binding.webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        } else {
            Log.d(TAG_PERF, "hardware layer skipped (perfEnabled=" + perfEnabled + ")")
        }

        // 文件上传：没有这一步，网页里的 input[type=file] 点了没反应（见 installWebChromeClient 注释）。
        installWebChromeClient()

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // 导航开始即切到加载态，但**不清 error 层**里的内容——
                // 用户点"重试"时错误详情应当继续可见直到真的加载成功。
                binding.loadingView.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.loadingView.visibility = View.GONE
                binding.errorView.visibility = View.GONE
                // ===== 检查更新（v0.2.0）=====
                // 只在首次加载成功后查一次。此时插件会话 cookie 一定已随 launchUrl 的
                // 一次导航生效（否则页面本身就是 401 错误页，不会走到这里）。
                // 具体实现是异步 + 5s 超时 + 失败静默，见 Updates.checkQuietly。
                if (!updateCheckedOnPageFinished) {
                    updateCheckedOnPageFinished = true
                    Updates.checkQuietly(this@WebViewActivity)
                }
                // ===== v0.4.6：注入 containment CSS =====
                // 放在这里是因为此时 document.head 一定存在（页面已 load 完成）。
                // 为什么不更早（onPageCommitVisible / onPageStarted）：那时候 head 可能还没解析出来，
                //   appendChild 会抛异常或被后来的文档覆盖，注入等于没做。
                // 为什么不需要每页重来：选择器是全局的，SPA 路由切换不去换 document。
                //   （整页重载时 document 变了，[injectPerfCss] 内部有存在性判断兜底。）
                // 不注入也不报错：view 为 null 说明 WebView 已销毁，静默跳过。
                if (view != null) injectPerfCss(view)
                // ===== v0.4.7：注入 Enter 发送监听 + 启动轮询 =====
                // 顺序：先注入 JS（装 keydown 监听），再起轮询 —— 反过来的话第一次轮询读到的是
                //   undefined（页面还没装监听器），虽然结果也是 0 无害，但没有意义。
                // 每页都要注入（不能像 perfCssInjected 那样只做一次）：整页重载会换掉 document，
                //   旧监听随旧 document 一起消失。否则 reload 之后收键盘就永久失效了。
                if (view != null) installSendWatcher(view)
                // ===== v0.4.8：诊断模式 —— 页面加载完成后重新 enable =====
                // 为什么每次 onPageFinished 都要重来：整页重载会换掉 document，
                //   插件会在新文档里重新 apply ⇒ 新的 __postapiSidebarDiag 是"未开启"状态
                //   （enable() 的结果不落地，也不写 localStorage）。
                // 为什么不能只靠"开关打开时调一次"：那只覆盖用户拨开关的那一刻。
                // 具体时序与重试见 [applyDiagOnPageFinished]。
                if (view != null) applyDiagOnPageFinished(view)
                // ===== v0.4.11：桌面版 —— 改写页面里的 viewport meta =====
                // 为什么要放在 onPageFinished（而非 onPageStarted / 更早）：
                //   改写 <meta name=viewport> 必须在它被解析、且文档已 load 之后才有意义；
                //   更早动手会拿到一个还不存在的节点（document.querySelector 返回 null）。
                // 为什么**每次**都注入（不像 perfCss 那样有 Kotlin 侧字段挡着）：
                //   整页重载会换掉 document，旧 meta 随之消失；SPA 路由切换虽不换 document，
                //   但注入本身是幂等的（同一个 meta 再 setAttribute 一次，无副作用）。
                if (view != null) applyDesktopViewport(view)
            }

            /**
             * 主文档级失败（导航本身没成功）。
             *
             * 为什么要区分 onReceivedHttpError 与 onReceivedError：
             *   前者是"服务器回了 HTML，但状态码是错的"（401/404/500），
             *   后者是"根本没拿到响应"（断网 / DNS / 连接被拒）。
             *   两者给用户的下一步动作完全不同，所以分开处理。
             */
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                // 只处理主文档的失败；子资源（图片/字体）失败不应该整页报错。
                if (request == null || !request.isForMainFrame) return
                val code = error?.errorCode ?: 0
                val desc = error?.description?.toString().orEmpty()
                // 这里是"网络层错误码"（WebViewClient.ERROR_* 常量），不是 HTTP 状态码，
                // 文案里必须区分开 —— 否则排查时会去找服务端日志，而实际是客户端网络问题。
                showError("net error code=$code " + desc)
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?,
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request == null || !request.isForMainFrame) return
                val status = errorResponse?.statusCode ?: 0
                if (status >= 500 || status == 401 || status == 403) {
                    showError("HTTP $status " + request.url.toString())
                }
                // 4xx 的其它情况（如 404 的 favicon）不弹错误页，避免误报。
            }

            /**
             * 站外链接交给系统浏览器。
             *
             * 为什么必须做：DSH 聊天里会出现外部链接（文档、issue）。
             * 若在同一个 WebView 里跳出去，用户就回不到会话了；而我们的登录取消
             * 也无法接管一个别人的站点。
             */
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val target = request?.url ?: return false
                return shouldOverrideUrlLoadingInternal(target)
            }

            /**
             * 旧签名重载：部分 WebView 内部路径仍会调用 String 版。
             * 只实现新签名时，这些路径会走默认行为（在当前 WebView 内加载），
             * 于是站外链接会"偶发"跳走 —— 属于典型的难查不一致。这里显式补齐。
             */
            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url.isNullOrEmpty()) return false
                val target = Uri.parse(url)
                return shouldOverrideUrlLoadingInternal(target)
            }
        }
    }

    /** 新/旧两个入口共用的判断逻辑，避免两处判据漂移。 */
    private fun shouldOverrideUrlLoadingInternal(target: Uri): Boolean {
        if (isSameHostInApp(target)) return false
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, target))
            true
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(this, target.toString(), Toast.LENGTH_LONG).show()
            true
        }
    }

    /**
     * 是否与应用自身的 origin 同源（scheme + host + 端口归一化后相同）。
     *
     * 为什么不能直接比较 Uri.port：
     *   Uri.parse("https://h") 的 port 是 -1（未写端口），
     *   而页面里生成的绝对链接可能写成 https://h:443/...，port=443。
     *   直接比 -1 == 443 会判成"站外"，把本该留在 App 内的链接踢到浏览器。
     *   这里把 -1 按 scheme 的默认端口归一化。
     */
    private fun isSameHostInApp(target: Uri): Boolean {
        val base = Uri.parse(launchUrl)
        if (target.scheme != base.scheme) return false
        if (target.host != base.host) return false
        return effectivePort(target) == effectivePort(base)
    }

    /** [Uri.port] 为 -1（URL 未写端口）时按 scheme 的默认端口取值。 */
    private fun effectivePort(uri: Uri): Int {
        if (uri.port != -1) return uri.port
        return when (uri.scheme) {
            "https" -> 443
            "http" -> 80
            else -> -1
        }
    }

    /**
     * 从 launchUrl 推导服务基址（scheme + authority）。
     *
     * 只在 Intent 没带 EXTRA_HOST 时使用。launchUrl 是 "https://host/?token=..." 形式，
     * Uri.parse 之后取 scheme + authority 即可 —— **不带** path 与 query，
     * 避免把一次性 token 拼进字符串里（那是凭据，不该在别的字段里再出现一份）。
     */
    private fun deriveHostBaseUrl(url: String): String {
        return try {
            val uri = Uri.parse(url)
            val scheme = uri.scheme ?: return ""
            val authority = uri.authority ?: return ""
            scheme + "://" + authority
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 种入插件会话 cookie。
     *
     * URL 只用 scheme://authority（不带路径）：CookieManager 以 URL 决定 domain/path 归属，
     * 传 launchUrl 也未尝不可，但它带 ?token= 查询串，语义上不该参与 cookie 归属判断。
     *
     * domain 不显式写：交给 CookieManager 从 URL 推导，避免手写 domain 时
     * 漏掉前导点（.example.com 与 example.com 在 cookie 语义里不同）。
     *
     * flush() 的理由：CookieManager 的写入是异步的，不 flush 时紧随其后的
     * loadUrl 有概率读不到这枚 cookie，于是插件接口 401。这是本文件最容易被忽略的一行。
     */
    private fun seedCookie(launchUrl: String, name: String, value: String, maxAgeSeconds: Int) {
        val base = Uri.parse(launchUrl)
        val origin = base.scheme + "://" + base.authority
        val cookie = name + "=" + value + "; Path=/; Max-Age=" + maxAgeSeconds + "; SameSite=Lax"
        CookieManager.getInstance().setCookie(origin, cookie)
        CookieManager.getInstance().flush()
    }

    /**
     * 返回键语义：
     *   - 网页有历史 → 后退（用户预期如此，尤其在 SPA 里点了会话再按返回）；
     *   - 已在首页 → 连按两次退出，避免"一次误触直接退出登录"。
     *
     * 用 OnBackPressedCallback 而不是重写 onBackPressed()：
     *   后者在目标 SDK 33+ 已不推荐，且与 predictive back 手势不兼容。
     */
    private fun setupBack() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 菜单展开时，返回键的第一语义是"收起菜单"，不是"网页后退" ——
                // 否则用户在菜单上按返回，会连同网页一起退走，看起来像 App 抽风。
                if (binding.logoutMenu.visibility == View.VISIBLE) {
                    hideMenu()
                    return
                }
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                    return
                }
                val now = System.currentTimeMillis()
                if (now - lastExitBackAt < 2000L) {
                    finish()
                } else {
                    lastExitBackAt = now
                    Toast.makeText(
                        this@WebViewActivity,
                        getString(R.string.webview_exit_hint),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        })
    }

    /** 重试：先回到加载态，再重新导航到 launchUrl。
     *
     * 为什么 reload 而不是 loadUrl(launchUrl)：
     *   页面已经因为 launchUrl 的 token 兑换过 cookie，token 是一次性的；
     *   reload 复用当前文档地址（已重定向到 /），不会二次消耗 token。
     *   只有在"完全没导航成功"（currentUrl 为空）时才回退到 loadUrl。
     */
    private fun reload() {
        binding.errorView.visibility = View.GONE
        binding.loadingView.visibility = View.VISIBLE
        val current = binding.webView.url
        if (current.isNullOrEmpty()) {
            binding.webView.loadUrl(launchUrl)
        } else {
            binding.webView.reload()
        }
    }

    /**
     * 展示错误页。
     *
     * 只显示错误层、**不动 WebView**：销毁重建会丢掉页面内状态，
     * 而且用户点重试时我们希望尽可能复用同一个 WebView。
     *
     * 为什么把 401/403 归到"需要重新登录"：
     *   launchUrl 里的 token 只能兑换一次官方 cookie。若这次导航没能落上 cookie
     *   （例如中途被拦截/断网），之后 reload 拿到的仍是带 token 的地址，
     *   但那个 token 已经消费掉了 —— 表现就是一直 401。
     *   此时重试没有意义，必须回登录页重新取一张 launchUrl。
     */
    private fun showError(detail: String) {
        binding.loadingView.visibility = View.GONE
        binding.errorDetail.text = detail
        binding.errorView.visibility = View.VISIBLE

        val needsRelogin = detail.contains("HTTP 401") || detail.contains("HTTP 403")

        // ===== v0.3.0：会话已失效 ⇒ 作废本地缓存的那枚 cookie =====
        // 如果不作废，下次启动会命中"cookie 未过期 → 直接进 WebView"的分支，
        // 又看到同一个 401 错误页 —— 用户会以为 App 坏了，而且每次都白跑一轮。
        // 在这里清是安全的：能走到这条路径说明服务端刚刚用它回了 401/403。
        if (needsRelogin) {
            val appContext = applicationContext
            Thread({ AccountStore.clearCookie(appContext) }, "dsh-clear-cookie").apply {
                isDaemon = true
            }.start()
        }

        // 401/403 时「重试」隐藏：launchUrl 里的 token 只能兑换一次，
        // 重试拿到的还是已作废的 token，没有意义。
        // 但 **resume 模式**是个例外 —— 那时地址是 host 根路径、没有 token，
        // 重试确实可能失败（网络抖动），所以保留「重试」。
        val canRetry = !needsRelogin || resumeMode
        binding.retryButton.visibility = if (canRetry) View.VISIBLE else View.GONE

        // 「返回登录」的语义随场景变化，必须换文案：
        //   · 新登录（刚拿到 launchUrl）：401 说明 token 已消费 ⇒ "返回登录"是重新取一张；
        //   · resume 模式（用存储的 cookie 直接进）：401 说明 cookie 被服务端吊销 ⇒
        //     用户真正需要的是"用保存的账号密码重新登录一次" —— 而 [LoginActivity]
        //     从 WebView 返回后**不会**自动重登（它只在冷启动时自动重登）。
        //
        // ⚠️ 已知取舍（诚实记录，见 README「需真机确认」）：
        //    目前点它会回到登录页（已预填 host + username，密码框为空），
        //    用户需要重新输入密码。要真正做到"点一下自动重登"，需要
        //    ① 作废存储的 cookie，② 回到 LoginActivity 并强制走 LoginFlow 的自动重登分支。
        //    本版没有做，因为它会引入"用户主动退出到登录页"与"自动重登"两个入口的歧义。
        binding.reloginButton.text = if (needsRelogin && resumeMode) {
            getString(R.string.action_back_to_login_needs_password)
        } else {
            getString(R.string.action_back_to_login)
        }
        binding.reloginButton.visibility = View.VISIBLE
        binding.logoutButton.visibility = View.VISIBLE
    }

    /**
     * 「返回登录」：会话出问题时的出口。
     *
     * 与「退出登录」的区别（这是**两件不同的事**，不要合并）：
     *   返回登录 = 我要**重新登一次**（同一个账号）。保留存储里的账号密码，
     *              登录页会预填 host + username，密码留空；
     *   退出登录 = 结束这次会话（v0.4.0 起**保留**地址/用户名，见 performLogout）。
     *
     * 为什么保留两套：WebView 401 错误页上的用户诉求是"重新登录"，
     * 他要的是回到可用状态，不是把我记住的账号抹掉。
     * 只有明确说"退出"的人才需要清除。
     */
    private fun backToLogin() {
        finish()
    }

    // ==========================================================================
    // 右下角折叠菜单（v0.4.0）
    // ==========================================================================

    /**
     * 接线右下角菜单：悬浮按钮 = 开关，菜单里两项 = 检查更新 / 退出登录。
     *
     * ===== 为什么「检查更新」放在这个菜单里（用户让我判断并说明理由）=====
     * 反例先说清：
     *   · 右上角：官方前端顶部有标题栏/按钮，v0.3.0 的「退出登录」正是因为重叠
     *     从右上改到右下 —— 同一个坑不该踩第二次；
     *   · 再加一个悬浮按钮：两个圆钮叠在网页上，遮挡内容且必然误点；
     *   · 长按某个元素：没有任何视觉提示，用户不会发现，等于没有入口。
     * 选这个方案的理由：
     *   ① 位置已经被验证过不与官方 UI 重叠（v0.3.0 起就在用）；
     *   ② 「检查更新」与「退出登录」同属"关于本 App 的操作"，放一起符合心智模型；
     *   ③ 只增加 0 个常驻控件 —— 悬浮按钮仍是那一个 36dp 的圆点。
     *
     * 交互细节：
     *   · 点悬浮按钮 → 菜单弹出 + 遮罩出现；再点一次 → 收起；
     *   · 点遮罩空白处 → 收起（遮罩会吞掉这次点击，不会误触网页）；
     *   · 点菜单里的某一项 → 先收起菜单再执行，避免对话框与菜单叠在一起。
     */
    private fun setupMenu() {
        // 抽屉底部显示当前版本：排查问题时用户能直接报版本号，不必去系统设置里翻。
        binding.versionFooter.text = getString(
            R.string.app_menu_version_format,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
        )
        binding.logoutFab.setOnClickListener { toggleMenu() }
        binding.menuScrim.setOnClickListener { hideMenu() }
        // v0.4.11：切换桌面版 / 移动版
        // 顺序：先回填文案（按 prefs 里的状态），再挂点击 ——
        // 与本文件 setupDiagSwitch 的"先 set 再挂"同一套纪律，
        // 避免回填动作被当成一次用户点击。
        updateDesktopButtonText()
        binding.toggleDesktopButton.setOnClickListener { toggleDesktopMode() }
        binding.checkUpdateButton.setOnClickListener {
            hideMenu()
            // 用户主动触发：不受 30 分钟节流限制，且"已是最新"会有 Toast 反馈。
            Updates.checkManually(this)
        }
        binding.logoutMenuItem.setOnClickListener {
            hideMenu()
            confirmLogout()
        }
        setupDiagSwitch()
    }

    // ==========================================================================
    // v0.4.8 诊断模式（排查「侧栏自动收起失效」）
    // ==========================================================================
    //
    // 三段分工，读代码时先看这张图：
    //   ① [setupDiagSwitch]         开关 ↔ AppPrefs ↔ 抽屉里的提示条（纯 UI 与存储）
    //   ② [callDiagApi]             一次性的 enable/disable 调用（用户拨开关时）
    //   ③ [applyDiagOnPageFinished] 页面加载完成后的"确认就绪"轮询（含重试与日志）
    //
    // 为什么**没有**做「URL 参数 ?sidebardiag=1」方案（需求里留给我判断的那一条）：
    //   · 一次性 token：新登录的 launchUrl 是 "https://host/?token=..."，官方门禁
    //     （browser-auth.ts:244，见本文件顶部约束 1）要求 **GET / 且恰好一个参数**。
    //     再追加一个 &sidebardiag=1 就是两个参数 —— 有被门禁直接打回的风险，
    //     而这条路径没有真机可验证；为了一个排查开关去赌主流程的登录，不划算。
    //   · 插件在**服务端与客户端都**用 location.search 判断（client.js 里的 setupSidebarDiag
    //     正则 [?&]sidebardiag=1），而我们能改的只有"首次导航"那一刻的 URL；
    //     之后 SPA 内的路由/刷新拿到的是不带参数的地址，判断结果就跟 App 侧的开关不一致了。
    //   · 反过来更危险：URL 带上参数后，即使用户把开关**关掉**，JS 侧的 disable() 不在了
    //     （页面重载后又按 URL 装回来），会出现"用户以为关了、其实还在采集"。
    // 结论：只用 ③ 的 JS 调用（enable/disable + 轮询确认），不用 URL 参数。

    /**
     * 接线抽屉里的「诊断模式」开关。
     *
     * 顺序很关键：**先 setOnCheckedChangeListener(null) 再 setChecked，最后挂监听**。
     * CompoundButton.setChecked 会触发 listener，若先挂监听再回填状态，
     * 回填动作会被当成"用户拨了开关"⇒ 页面还没加载就先发一次 JS（无害但误导日志），
     * 更糟的是 saveDiagEnabled 会把默认值再写一遍盘。这是 CompoundButton 的经典坑。
     */
    private fun setupDiagSwitch() {
        val saved = AppPrefs.isSidebarDiagEnabled(this)
        diagEnabled = saved
        binding.diagSwitch.setOnCheckedChangeListener(null)
        binding.diagSwitch.isChecked = saved
        updateDiagHint(saved)
        binding.diagSwitch.setOnCheckedChangeListener { _, isChecked ->
            saveDiagEnabled(isChecked)
            updateDiagHint(isChecked)
            callDiagApi(if (isChecked) diagEnableJs else diagDisableJs)
        }
    }

    /**
     * 落盘 + 更新内存镜像。
     *
     * 写盘失败**不回滚 UI**：本次进程内 JS 已经（或即将）按用户的意图切换了，
     * 回滚会让"开关是关的但页面已经在采集"这种更难理解的状态出现。
     * 失败只打日志（下次启动记不住而已）。
     */
    private fun saveDiagEnabled(enabled: Boolean) {
        diagEnabled = enabled
        val ok = AppPrefs.setSidebarDiagEnabled(this, enabled)
        Log.d(TAG_DIAG, "diag mode -> " + enabled + " (persisted=" + ok + ")")
        if (!ok) Log.w(TAG_DIAG, "diag mode not persisted (prefs unavailable)")
    }

    /**
     * 抽屉里的可见提示（v0.4.8 需求 ⑤）。
     *
     * 为什么除了开关本身还要一行文字：
     *   开关表达的是"用户的选择"，而诊断**真正的影响**在网页上（右上角浮层 + 每 500ms 采集）。
     *   用户关了抽屉就看不到开关了 —— 这行字是抽屉关闭前唯一的提醒，且明确写了
     *   "排查完请关闭"。不用 Toast（一闪就没了），也不用改开关文案
     *   （"诊断模式（已开启）"会让 48dp 里折行，且开关位置本身已经表达了状态）。
     */
    private fun updateDiagHint(enabled: Boolean) {
        binding.diagHint.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    // ---- JS 接口常量（插件 v1.7.1，见 lib/client.js 的 setupSidebarDiag）----

    /**
     * 开启诊断。
     *
     * 写法说明：
     *   · 前置的 window.__postapiSidebarDiag && 是**必须**的 —— 插件没装/版本旧时直接 TypeError；
     *   · 末尾返回字符串，便于在回调里打日志判读（与 injectPerfCss / sendWatcher 同一风格）；
     *   · 包一层 try/catch：插件自己的 enable 若抛错，不能让我们这边出现未捕获的 JS 错误
     *     （那会污染页面 console，排查时更难看清真正的报错）。
     */
    private val diagEnableJs: String = "(function(){" +
        "try{" +
        "if(!window.__postapiSidebarDiag)return 'missing';" +
        "if(typeof window.__postapiSidebarDiag.enable!=='function')return 'no-enable';" +
        "window.__postapiSidebarDiag.enable();" +
        "return (typeof window.__postapiSidebarDiag.isEnabled==='function'" +
        "&&window.__postapiSidebarDiag.isEnabled())?'enabled':'called';" +
        "}catch(e){return 'error:'+(e&&e.message?e.message:String(e));}" +
        "})()"

    /** 关闭诊断。同上，只是动作相反。 */
    private val diagDisableJs: String = "(function(){" +
        "try{" +
        "if(!window.__postapiSidebarDiag)return 'missing';" +
        "if(typeof window.__postapiSidebarDiag.disable!=='function')return 'no-disable';" +
        "window.__postapiSidebarDiag.disable();" +
        "return (typeof window.__postapiSidebarDiag.isEnabled==='function'" +
        "&&window.__postapiSidebarDiag.isEnabled())?'still-enabled':'disabled';" +
        "}catch(e){return 'error:'+(e&&e.message?e.message:String(e));}" +
        "})()"

    /**
     * 用户拨开关时的那一次调用。
     *
     * 与 [applyDiagOnPageFinished] 的关系：
     *   这里是"立即意图"（页面已经加载好，插件大概率就绪，一次调用即可）；
     *   那里是"加载完成后的自愈"。两处都调 enable() 是**幂等**的：
     *   插件的 enable() 内部若已激活会直接返回（见 client.js 的 disposed 守卫），
     *   我们的 JS 里也先读 isEnabled() 判读，重复调用不会叠出两个浮层。
     *
     * 失败（'missing' / 'no-enable' / 'error:…'）时给一条 Toast：
     *   用户刚拨了开关，必须知道"这次没生效"。但**不自动回滚开关** ——
     *   开关状态代表用户意图，页面重载后 [applyDiagOnPageFinished] 还有机会补上。
     */
    private fun callDiagApi(js: String) {
        if (isFinishing || isDestroyed) return
        try {
            binding.webView.evaluateJavascript(js) { result ->
                if (isFinishing || isDestroyed) return@evaluateJavascript
                Log.d(TAG_DIAG, "diag call result=" + result)
                if (isDiagApiMissing(result)) {
                    Toast.makeText(
                        this,
                        R.string.diag_mode_unavailable,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        } catch (t: Throwable) {
            // 页面正在导航时可能抛（WebView 已 destroy 的竞态）。记一条日志即可：
            // 下一次 onPageFinished 会重新走 applyDiagOnPageFinished 的轮询。
            Log.w(TAG_DIAG, "diag call failed: " + t.message)
        }
    }

    /** JS 返回值是否为"接口不存在"这一类失败（evaluateJavascript 返回的是 JSON 文本，字符串带引号）。 */
    private fun isDiagApiMissing(raw: String?): Boolean {
        val v = raw?.trim()?.trim('"') ?: return true
        return v == "missing" || v == "no-enable" || v == "no-disable" || v.startsWith("error:")
    }

    /**
     * 页面加载完成后：若开关是开的，确认诊断接口就绪并 enable 一次。
     *
     * ==================== 为什么要轮询（需求 ③d）====================
     * __postapiSidebarDiag 由插件 client.js 在 cordis 的 apply() 阶段创建，
     * 而 apply 的时机**与页面 load 事件没有耦合**：SPA 首屏可能先把骨架画出来、
     * 插件 bundle 之后再执行。onPageFinished 触发时它有可能还不存在。
     * 固定 delay 只能赌一个数字（等短了没中、等长了白等），轮询则是"就绪即停"：
     * 最多 [DIAG_RETRY_MAX] 次 × [DIAG_RETRY_INTERVAL_MS] = 3 秒。
     *
     * ==================== 三条纪律 ====================
     *   1. **先摘后挂**：每次 onPageFinished 先 removeCallbacks，保证任何时刻最多一条链
     *      （SPA 路由切换会让 onPageFinished 反复触发，见 [restartSendWatchPolling] 同理）；
     *   2. **不静默失败**：3 秒还没就绪就打 warn（logcat 里能直接 grep 到），
     *      并在**用户刚打开开关**的情况下给 Toast —— 静默等于让用户以为诊断在跑；
     *   3. **不在关闭时轮询**：开关关着就什么都不做，避免给每个普通用户多加 10 次 JSBridge 往返。
     */
    private fun applyDiagOnPageFinished(webView: WebView) {
        diagRetryHandler.removeCallbacksAndMessages(null)
        if (!diagEnabled) return
        tryEnableDiag(webView, 1)
    }

    /**
     * 第 [attempt] 次尝试（从 1 开始）。成功即停；失败且还有次数就排下一次。
     *
     * ⚠️ 这里**不自建 Handler 实例**：复用 [diagRetryHandler]，并在每次 post 前
     *   removeCallbacksAndMessages(null) —— 否则 onDestroy 时摘不干净，回调持有 Activity。
     */
    private fun tryEnableDiag(webView: WebView, attempt: Int) {
        if (isFinishing || isDestroyed) return
        if (!diagEnabled) return // 轮询途中用户把开关关了：立刻收手
        try {
            webView.evaluateJavascript(diagEnableJs) { result ->
                if (isFinishing || isDestroyed) return@evaluateJavascript
                val missing = isDiagApiMissing(result)
                Log.d(TAG_DIAG, "page-finished enable attempt=" + attempt + " result=" + result)
                if (!missing) return@evaluateJavascript
                if (attempt >= DIAG_RETRY_MAX) {
                    // 到上限仍未就绪：给明确结论，而不是继续沉默。
                    Log.w(
                        TAG_DIAG,
                        "sidebar diag api not ready after " + DIAG_RETRY_MAX + " attempts ("
                            + (DIAG_RETRY_MAX * DIAG_RETRY_INTERVAL_MS) + "ms), last=" + result,
                    )
                    return@evaluateJavascript
                }
                diagRetryHandler.removeCallbacksAndMessages(null)
                diagRetryHandler.postDelayed({
                    tryEnableDiag(webView, attempt + 1)
                }, DIAG_RETRY_INTERVAL_MS)
            }
        } catch (t: Throwable) {
            // 导航竞态下 evaluateJavascript 可能抛：当作一次失败，继续重试
            // （不在 catch 里直接终止 —— 页面可能马上就绪）。
            Log.w(TAG_DIAG, "enable attempt=" + attempt + " threw: " + t.message)
            if (attempt < DIAG_RETRY_MAX) {
                diagRetryHandler.removeCallbacksAndMessages(null)
                diagRetryHandler.postDelayed({
                    tryEnableDiag(webView, attempt + 1)
                }, DIAG_RETRY_INTERVAL_MS)
            }
        }
    }

    /** v0.4.8：停掉诊断轮询链（onDestroy 里调用，防止回调持有已销毁的 Activity）。 */
    private fun stopDiagRetry() {
        diagRetryHandler.removeCallbacksAndMessages(null)
    }

    private fun toggleMenu() {
        if (binding.logoutMenu.visibility == View.VISIBLE) hideMenu() else showMenu()
    }

    private fun showMenu() {
        binding.menuScrim.visibility = View.VISIBLE
        binding.logoutMenu.visibility = View.VISIBLE
        // 无障碍：菜单展开时把焦点交给第一项，读屏用户才知道"多出了两个选项"。
        binding.checkUpdateButton.announceForAccessibility(getString(R.string.update_check_action))
    }

    private fun hideMenu() {
        binding.logoutMenu.visibility = View.GONE
        binding.menuScrim.visibility = View.GONE
    }

    /**
     * 「退出登录」确认弹窗。
     *
     * ===== 入口位置 & 理由（用户让我判断）=====
     * 放**两处**，因为两种诉求出现的时机不同：
     *
     * ① 页面右上角的悬浮小按钮（常驻）：会话**正常**时用户想换账号 / 借手机给别人。
     *    这时页面是 WebView 内容，没有任何原生 UI 可以挂 —— 只能悬浮。
     *    为什么是"小圆点按钮"而不是"右上角菜单"：
     *      · 官方前端自己在右上角可能有按钮，做一个展开式菜单会与之重叠，
     *        点哪一层都可能被 WebView 抢走事件；
     *      · 菜单需要额外的展开状态、点击外部关闭、返回键拦截 —— 为**一个**动作做一整套
     *        菜单是过度设计；真的需要第二个动作（如"刷新"）再加也不迟。
     *    为什么放右侧而不是左上：左上"返回箭头"位置在 SPA 里容易被误认为浏览器后退。
     *
     * ② 错误页「返回登录」按钮旁边：会话**已失效**时（401/403/断网）最需要退出。
     *    此时错误页盖住了 WebView，悬浮按钮也被盖住，所以错误页上必须有。
     *    用 TextButton（次要样式）而不是两个等宽的主按钮 —— 避免用户误点。
     *
     * 两处都走本方法（同一个确认弹窗），保证行为一致。
     */
    private fun confirmLogout() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.logout_title)
            .setMessage(R.string.logout_message)
            .setNegativeButton(R.string.logout_action_cancel, null)
            .setPositiveButton(R.string.logout_action_confirm) { _, _ -> performLogout() }
            .show()
    }

    /**
     * 真正执行退出登录。
     *
     * ==================== v0.4.0：只清会话，保留账号（问题 ③）====================
     * 用户原话：「退出登录之后地址用户名密码应该数据保存在本地不需要重复输入才对」。
     * 之前这里是 AccountStore.clearAll —— 把地址、用户名、密码一起抹掉，那是设计错误：
     * **退出登录 = 结束会话**，不是"这台手机别留我的东西"。后者是登录页的
     * 「清除已保存的账号」（AccountStore.clearAll）。
     *
     * 具体清的是【cookie】，账号三件套（host / username / password）**全部保留** ——
     * 这是用户明确要求的：「退出登录之后地址用户名密码应该数据保存在本地不需要重复输入」。
     * 登录页会把三个框都填好，用户点一下「登录」即可。
     *
     * ⚠️ 保留密码的前提是**堵住自动重登**：回登录页时带 EXTRA_AUTO_LOGIN=false，
     *    登录页只预填、不发请求（见下面的 startActivity）。否则下次冷启动会走
     *    LoginFlow 的"有密码 ⇒ 自动重登"分支，用户会觉得"退不出去"。
     *
     * 三件事**都必须做**，少一件就会留下"以为退出了其实没退"的状态：
     *
     *   ① 清会话（密码 + cookie）：下次启动不会自动重登；
     *   ② 清 CookieManager：否则官方签名 cookie（HttpOnly，我们存不下来也删不掉
     *      除 CookieManager 之外的任何地方）还在，下次进 WebView 仍是已登录状态 ——
     *      这是"退出登录后换台手机/换个人还能看到会话"的根因；
     *   ③ 清 WebView 的本地存储：官方 SPA 用 localStorage 存草稿/设置，
     *      留着等于把上一账号的使用痕迹留给下一个人。
     *
     * ⚠️ 顺序很重要：先清 CookieManager 再销毁 WebView。
     *    反过来的话，WebView 销毁过程中的某些访问可能又把内存里的 cookie 写回磁盘。
     * ⚠️ removeAllCookies 是**异步**的（回调里才是真的清完）。这里不等回调：
     *    本页马上 finish，没有后续导航会用到那枚 cookie；即使异步落盘有延迟，
     *    下次 WebView 加载时才会读 cookie，那时早已清完。
     *    但 flush() 必须调 —— 它把"清空"这个动作同步挤进磁盘队列。
     */
    private fun performLogout() {
        // ① 会话（在后台线程：AES + commit；主线程不等它）
        //    clearSession 而不是 clearAll —— 保留 host / username 供登录页预填。
        val appContext = applicationContext
        Thread({
            AccountStore.clearSession(appContext)
        }, "dsh-logout-store").apply { isDaemon = true }.start()

        // ② CookieManager
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.flush()

        // ③ WebView 的页面级状态
        //    没有 WebView.clearStorage() 这个 API —— 那是我第一版的错误记忆。
        //    真正对应的三个调用：
        //      · WebStorage（localStorage / IndexedDB / WebSQL）：WebStorage.getInstance().deleteAllData()
        //        ⚠️ 它是**全应用**级的，会连同其它 WebStorage 数据一起清 —— 本 App 只有一个 WebView，
        //        这正是"退出登录要把上一账号的痕迹清干净"想要的效果。
        //      · 表单自动填充：clearFormData()（用户可能存过输入过的搜索词）
        //      · 前进后退历史：clearHistory()，避免"退出后按返回还能回到上一账号的页面"
        WebStorage.getInstance().deleteAllData()
        binding.webView.clearFormData()
        binding.webView.clearHistory()

        // ④ 更新流程的界面状态（下载中 → 取消；否则回调会打到已销毁的界面）
        Updates.onActivityDestroyed()
        // 文件选择器：把还挂着的回调以 null 结束，并清掉拍照临时文件。
        // 不清理的话，WebView 会持有 Activity 的引用（回调里 this 指向本页），
        // 造成与 WebView 同类的泄漏；临时文件则会在缓存里越堆越多。
        runCatching { finishFileChooser(null) }
        runCatching { discardPendingCapture() }

        Toast.makeText(this, R.string.logout_done, Toast.LENGTH_SHORT).show()

        // 回登录页：startActivity 一个新的 LoginActivity 再 finish 自己。
        // ★ v0.4.0（问题 ③）：带 EXTRA_AUTO_LOGIN = false —— 登录页必须把已保存的
        //   地址 / 用户名 / **密码**都预填好，但**不能**自动登录（用户刚点的退出）。
        // 不用 CLEAR_TASK：那会把系统最近任务里的记录也清掉，行为上像"杀进程"，
        // 用户从最近任务切回时会看到一个全新的 App（失焦感）。
        // 这里用 NEW_TASK|CLEAR_TOP 语义上不合适（LoginActivity 可能已在栈里），
        // 最稳的是普通 startActivity + finish —— 栈里最多多一个登录页，
        // 退出登录是低频操作，不值得为此设计栈管理。
        startActivity(
            Intent(this, LoginActivity::class.java)
                .putExtra(LoginActivity.EXTRA_AUTO_LOGIN, false),
        )
        finish()
    }

    /**
     * 按 API 覆写 window.softInputMode，使其与「是否接管 insets」成对（v0.4.11）。
     *
     * 为什么 manifest 里那一个 static 值不够：
     *   · API 30+ 要走「系统别动 + 我们自己平移」⇒ 必须 adjustNothing；
     *   · API 29- 要走「系统自己缩窗口」      ⇒ 必须 adjustResize；
     *   两个值互斥，而 <activity> 只能写一个。用运行时覆写是唯一不改代码结构的解法。
     *
     * 为什么用 mask 覆写而不是整体赋值：
     *   softInputMode 是「可见性状态位 + 调整方式位」的组合。整体赋值会把
     *   STATE_* 之类的位清零，而在 WebView 里这些位通常由系统设置（例如
     *   STATE_ALWAYS_VISIBLE 与 STATE_HIDDEN 的切换）。只清 SOFT_INPUT_MASK_ADJUST
     *   这一组，才能保证"只改我们想改的那一位"。
     *
     * ⚠️ 必须在 setContentView 之后、键盘第一次弹出之前调用（onCreate 里）：
     *   窗口属性在 Activity 可见后修改，系统不一定会应用（需要 setAttributes 生效）。
     */
    // @Suppress：SOFT_INPUT_ADJUST_RESIZE 自 API 30 起被标记 deprecated（官方推荐改用
    //   WindowInsets 自己处理）。但我们**恰恰**是给 API 29- 用的它 —— 那一版上
    //   insets 通路拿不到 ime（见 WindowInsetsHelper 文件头），只有 adjustResize 有效。
    //   新版常量替不掉旧设备的需求，属于「已知且必要」的废弃 API 使用。
    @Suppress("DEPRECATION")
    private fun applySoftInputModeForApiLevel() {
        val attrs = window.attributes
        val takeover = WindowInsetsHelper.defaultHandlesInsetsItself
        val adjust = if (takeover) {
            // API 30+：系统别动窗口，键盘让位由 WindowInsetsHelper 的 translationY 完成。
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        } else {
            // API 29-：系统缩短窗口来避让键盘（经典老办法，兼容性最好）。
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        attrs.softInputMode =
            (attrs.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or adjust
        window.attributes = attrs
        Log.d(
            TAG_LAYOUT,
            "softInputMode applied: takeover=" + takeover +
                " mode=0x" + Integer.toHexString(attrs.softInputMode),
        )
    }

    /**
     * 当前是否为浅色背景（决定系统栏图标用深色还是浅色）。
     *
     * 判断依据用 `uiMode` 而不是读取某个颜色值：
     *   本项目的浅/深色由 `values/` 与 `values-night/` 两套资源决定，
     *   而 uiMode 的 NIGHT 位正是资源系统选择 values-night 的依据。
     *   两者同源，所以不会出现"资源是深色、图标按浅色画"的错配。
     */
    private fun isLightBackground(): Boolean {
        val nightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return nightMode != android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * ============ v0.4.3 临时诊断：键盘弹出/收起时的实际布局尺寸 ============
     *
     * 为什么要有它：用户反馈「打字还是卡」，我们需要在**真机**上回答一个问题 ——
     *   「键盘弹出时，WebView 的宽高到底有没有变？」
     * 两种可能，根因完全不同：
     *   · 高度**变了** ⇒ 布局确实在重算，重排可能是高度引起的（继续往布局方向查）；
     *   · 高度**没变**却仍卡 ⇒ 重排不是 App 布局导致的，要往 WebView 渲染/合成层查
     *     （此时 adjustPan 已经是 Activity 层能做到的上限，不该再折腾 softInputMode）。
     * 没有这条日志，两种可能只能靠猜。
     *
     * 打什么：ime 高度 + systemBars + WebView 尺寸 + rootFrame 尺寸与 padding +
     *   rootFrame.translationY（v0.4.5：验证「自己平移」是否生效，期望值 = -ime）+
     *   decorView 高度 + 实际生效的 softInputMode（防止 manifest 改了但 ROM 不照做）。
     *
     * 为什么挂在 **decorView** 上而不是 rootFrame：
     *   `ViewCompat.setOnApplyWindowInsetsListener` 对同一个 View 是**覆盖**语义 ——
     *   在 rootFrame 上再装一个会把 WindowInsetsHelper 那个挤掉，
     *   表现为「状态栏留白突然消失」。decorView 上没有别的监听器，互不干扰；
     *   而且 insets 本来就是从 decorView 往下分发的，这里拿到的是原始值。
     *
     * 为什么只在 BuildConfig.DEBUG 下安装：release 包里多留一个 insets 监听器没有收益，
     *   日志也可能被误报为"卡顿来源"。方法本身保留在代码里，便于下个版本直接复用。
     *
     * ⚠️ 尺寸必须在 layout 之后读：listener 回调时 View 可能还没重新 layout，
     *   直接读 height 会拿到上一帧的值 ⇒ post 到下一帧再读。
     * ⚠️ 这是**临时**诊断，定位完建议移除（或把它降级成按需触发，避免每次弹键盘都打日志）。
     */

    private fun installLayoutDiagnostics() {
        if (!BuildConfig.DEBUG) return
        // v0.4.9：先打一行设备指纹，再挂 insets 监听。
        // 为什么放在这里而不是 insets 回调里：机型是常量，弹一次键盘打一遍纯属刷屏；
        //   排查「键盘不上移」时只要一次就能确定是哪台 ROM。
        Log.d(
            TAG_LAYOUT,
            "device=" + Build.MANUFACTURER + " " + Build.MODEL +
                " android=" + Build.VERSION.RELEASE + " sdk=" + Build.VERSION.SDK_INT,
        )
        val decor = window.decorView
        ViewCompat.setOnApplyWindowInsetsListener(decor) { v, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.post {
                // post 的 Runnable 可能在 Activity 已销毁后执行（快速进出页面）。
                if (isFinishing || isDestroyed) return@post
                // ★ v0.4.11：一次打印**判定所需的全部量**，并给出 verdict。
                // 为什么不再只打 ime/bars：ime.bottom = 0 有两种相反的原因
                //   （insets 拿不到 / 系统已经缩了窗口），只看它会得出错误结论。
                // 判读表（TAG=WebViewLayout，过滤：adb logcat -s WebViewLayout）：
                //   verdict=insets-ok      → insets 通路正常；
                //   verdict=system-resized → 系统已按 adjustResize 缩窗口，**不该**再自己让位；
                //   verdict=insets-broken  → 键盘弹了、窗口没缩、inset 也为 0 ⇒ 真拿不到；
                //   verdict=no-keyboard    → 键盘本来就没弹（这条可忽略）。
                WindowInsetsHelper.describeImeSituation(
                    activity = this@WebViewActivity,
                    webView = binding.webView,
                    root = binding.rootFrame,
                    imeBottom = ime.bottom,
                    barsTop = bars.top,
                    barsBottom = bars.bottom,
                )
            }
            // 返回原 insets（不 CONSUMED）：这里只观测，不改变分发结果。
            insets
        }
        // 主动拉一次：让首个 insets 事件在 setup 之后立刻到达，便于对照基线尺寸。
        ViewCompat.requestApplyInsets(decor)
    }

    // ==========================================================================
    // v0.4.6 流式返回卡顿优化
    // ==========================================================================
    //
    // 现象（已真机确认，不要重新验证）：同一台手机上 Edge 浏览器流式返回不卡，
    // 本 App 的 WebView 里 FPS 掉到 1~5、主线程出现 300~400ms 的长任务成簇。
    // 定位：高频 DOM 更新（流式 token 逐段插入）时主线程处理不过来。
    //
    // 本版做的是**降低每次 DOM 变化的代价**，而不是消除 DOM 变化：
    //   · containment 让布局/绘制的重算范围收敛到消息列表内部（下述 CSS）；
    //   · 硬件层让重绘在 GPU 侧合成（见 configureWebView）；
    //   · 关掉已废弃的 WebSQL 后端（见 configureWebView）。
    // 如果真机上仍卡，下一步应该往「减少 DOM 写入次数」方向找（那是前端的事），
    // 不要再在容器层加参数 —— 容器层能做的到此为止。

    // ==========================================================================
    // v0.4.11 桌面版网站（viewport 切换）
    // ==========================================================================
    //
    // ==================== 技术前提（已确认，不要再调研）====================
    // DSH 是**纯 CSS 响应式**，不看 User-Agent。它唯一的布局输入是：
    //   apps/web/index.html:5  <meta name="viewport" content="width=device-width, initial-scale=1" />
    // 所以「桌面版」= 把 content 换成 width=<DESKTOP_VIEWPORT_WIDTH>, initial-scale=1。
    // 伪装桌面 UA 对此**毫无作用**（反而会污染服务端按 UA 做的分流）。
    //
    // ==================== 三道闸，缺一不可 ====================
    //   ① WebSettings.loadWithOverviewMode = !desktopMode     （见 configureWebView）
    //   ② WebSettings.userAgentString 末尾的 " Desktop"       （仅为可读线索，非功能依赖）
    //   ③ 本文注入的 viewport meta                            （**真正起作用的那一道**）
    // 只做 ① 时页面仍按 device-width 排版（因为 meta 还在），用户会以为"按钮没用"。

    /**
     * 把当前页面里的 <meta name="viewport"> 改成桌面宽度（或改回移动宽度）。
     *
     * 为什么用一个 JS 函数同时管两个方向，而不是"桌面版才注入、移动版就不管"：
     *   · 只注入不还原 ⇒ 切回手机版后 meta 仍是 980，必须整页 reload 才能还原 ——
     *     而我们在 [toggleDesktopMode] 里正是 reload，看似没问题，
     *     但 SPA 内的软导航（不改 document）会让"还原"永远不发生。
     *   ⇒ 每次 onPageFinished 都按当前 desktopMode **显式写一遍**，才没有残留状态。
     *
     * 转义：注入的 content 是本文件里的常量（无用户输入），
     *   但依然用 [JSONObject.quote] 包一层 —— 与本文件 injectPerfCss 保持同一写法，
     *   将来若把宽度改成可配值，不必再回头检查转义。
     *
     * 失败静默：evaluateJavascript 在页面导航竞态下可能抛，吞掉即可
     *   （下一次 onPageFinished 还会再来一次）。
     */
    private fun applyDesktopViewport(webView: WebView) {
        val content = if (desktopMode) {
            DESKTOP_VIEWPORT_CONTENT
        } else {
            // 手机版：还原成 index.html 里原本的取值。
            "width=device-width, initial-scale=1"
        }
        // 注意：这里**不判断 meta 是否存在**就直接 setAttribute；
        // 不存在时先 createElement 再 appendChild —— 有些页面（错误页 / 中间跳转页）
        // 根本没有 viewport meta，只做 querySelector 会在那类页面上静默失效。
        val js = "(function(){" +
            "var h=document.head;" +
            "if(!h)return 'no-head';" +
            "var m=document.querySelector('meta[name=viewport]');" +
            "var created=false;" +
            "if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');h.appendChild(m);created=true;}" +
            "m.setAttribute('content'," + JSONObject.quote(content) + ");" +
            // 返回 "created/updated:" + 实际写入的 content + ":" + 模式，便于 logcat 直接判读。
            "return (created?'created':'updated')+':'" +
            // 读回刚写入的 content 本身（而不是返回 Kotlin 侧变量）：
            //   只有读回才能证明 setAttribute 真的落在 DOM 上。
            "(m.getAttribute('content')||'')+':" +
            JSONObject.quote(if (desktopMode) "desktop" else "mobile") + ";" +
            "})()"
        try {
            webView.evaluateJavascript(js) { result ->
                Log.d(TAG_LAYOUT, "viewport apply: desktopMode=" + desktopMode + " result=" + result)
            }
        } catch (t: Throwable) {
            Log.w(TAG_LAYOUT, "viewport apply failed: " + t.message)
        }
    }

    /**
     * 切换桌面版 / 移动版（抽屉里那一项）。
     *
     * 顺序（**不能调换**）：
     *   ① 翻转内存镜像 + 落盘 prefs；
     *   ② 重设 WebSettings（loadWithOverviewMode / UA）—— reload 之后才生效，
     *      所以必须在 reload 之前改；
     *   ③ 更新按钮文案；
     *   ④ reload；
     *   ⑤ 收起抽屉（放在最后：reload 会触发 onPageStarted → 切加载态，
     *      抽屉若还开着会盖在 loadingView 上，看起来像"点了没反应"）。
     *
     * 为什么 reload 而不是只调 [applyDesktopViewport]：
     *   viewport 变化后页面需要重新排版；更关键的是 loadWithOverviewMode 与 UA
     *   属于 WebSettings，**只有下一次导航才会生效**。只注入 JS 会让"设置"与"DOM"
     *   两边不一致 —— 这类半生效状态最难排查。
     *
     * 为什么用 reload() 而不是 loadUrl(launchUrl)：launchUrl 里的一次性 token
     *   已经兑换过（同 [reload] 方法的说明），重放 token 会 401。
     */
    private fun toggleDesktopMode() {
        desktopMode = !desktopMode
        val persisted = AppPrefs.setDesktopModeEnabled(this, desktopMode)
        Log.d(
            TAG_LAYOUT,
            "desktop mode -> " + desktopMode + " (persisted=" + persisted + ")",
        )
        applyDesktopWebSettings()
        updateDesktopButtonText()
        hideMenu()
        reload()
    }

    /**
     * 只改「与桌面模式相关的两个 WebSettings」，不动其余设置。
     *
     * 为什么不直接复用 [configureWebView]：那里会重装 WebViewClient / WebChromeClient、
     * 重设硬件层、重算 perf 逃生阀 —— 为一个显示开关跑整套初始化，
     * 副作用远大于收益（且 WebChromeClient 重建会丢掉正在进行的文件选择）。
     *
     * ⚠️ UA 追加逻辑与 configureWebView 里那份**必须同步**（见那里的说明）：
     *   这里只负责"在/不在末尾追加 Desktop"，不重复追加版本号等指纹。
     */
    private fun applyDesktopWebSettings() {
        val settings = binding.webView.settings
        settings.loadWithOverviewMode = !desktopMode

        val currentUa = settings.userAgentString ?: ""
        // 先剥掉上一次加的 Desktop 标记，再按当前模式决定是否加回 ——
        // 不剥离的话来回切换会堆成 "Desktop Desktop Desktop"。
        var baseUa = currentUa.replace(" Desktop", "")
        if (desktopMode) baseUa = baseUa + " Desktop"
        settings.userAgentString = baseUa
        Log.d(TAG_LAYOUT, "desktop settings applied: desktopMode=" + desktopMode + " ua=" + baseUa)
    }

    /**
     * 抽屉里那一项的文字：按**点下去会发生什么**来写。
     *
     * 当前是手机版 ⇒ 显示「切换桌面版」；当前是桌面版 ⇒ 显示「切换移动版」。
     * 为什么不说"桌面版（已开启）"这种状态式文案：这是动作按钮，不是开关，
     * 用户点它时关心的是结果，不是现状（现状他自己看得出来）。
     */
    private fun updateDesktopButtonText() {
        binding.toggleDesktopButton.setText(
            if (desktopMode) R.string.switch_to_mobile else R.string.switch_to_desktop,
        )
    }

    /**
     * v0.4.6 注入的 CSS 原文。
     *
     * 选择器全部来自 DSH 源码里**手写的稳定锚点**（data-* 属性），不受 CSS Modules 哈希影响：
     *   [data-conversation-scroll]   滚动容器（消息列表所在）—— ConversationContent.tsx
     *   [data-conversation-content]  对话内容区                  —— 同上
     *   [data-composer-seat]         输入框区                    —— 同上
     *   [data-queue-dock]            排队区                      —— QueueDock.tsx
     *
     * 逐条理由：
     *   ① contain: layout paint  —— 滚动容器内的高频变化不再触发容器外（乃至页面）的布局与重绘。
     *      **只有滚动容器加 paint**：paint 会裁剪溢出内容，DSH 的弹出层（下拉菜单、tooltip）
     *      若从别的容器溢出会被切掉，所以其它容器一律只用 layout。
     *   ② contain: layout        —— 对话内容区只需隔离布局（它是滚动容器的子级，paint 由①负责）。
     *   ③ 输入框区/排队区        —— 同上，只用 layout；这样它们与消息列表互不牵连，
     *      又不会把自己可能溢出的浮层裁掉（**若真机上发现输入框区浮层被裁，先查这里**）。
     *
     * ⚠️ contain 的边界条件（写在这里防止后人乱改）：
     *   · contain: layout 让元素成为"布局封闭盒"，内部尺寸变化不外传 —— 但**不会**改变
     *     元素自身的尺寸推导：滚动容器仍由外层 flex/固定高度决定，不会被撑成 0。
     *   · contain: paint 会把元素变成"包含块"和"层叠上下文"，并对溢出做 clip ——
     *     位置绝对定位的浮层若挂在这些容器内部且超出边界，就会被裁掉（见交付说明中的待确认项）。
     *   · 不加 contain: size / strict：那会让元素忽略内容推导尺寸，滚动容器高度会塌陷。
     *
     * 用原始字符串（trimIndent）而不是字符串拼接：CSS 本来就要换行才可读，拼接版每行还得手动补。
     */
    private fun buildPerfCss(): String = """
        /* v0.4.6 DSH-PostAPI WebView 性能优化（containment） */
        [data-conversation-scroll] {
            contain: layout paint;
        }
        [data-conversation-content] {
            contain: layout;
        }
        [data-composer-seat],
        [data-queue-dock] {
            contain: layout;
        }
    """.trimIndent()

    /**
     * 把 [buildPerfCss] 注入当前文档的 <head>。
     *
     * 为什么用 evaluateJavascript 而**不是** loadUrl("javascript:...")：
     *   · loadUrl 的 javascript: 形式在 API 19+ 已废弃，且会往历史栈里塞一条不可见的记录
     *     （用户按返回会"退到同一个页面"，表现为返回键失灵）；
     *   · evaluateJavascript 走独立的 JS 执行通道，不影响导航与历史。
     *
     * 为什么要 JSONObject.quote 转义 CSS：
     *   CSS 里有换行（多行规则）。直接字符串拼接会让换行落进 JS 源码 → SyntaxError，
     *   而且是**静默失败**（回调传了 null，看不到任何报错）。quote 会统一处理引号/换行/控制字符。
     *
     * 幂等性（两道闸，各管一段，缺一不可）：
     *   · Kotlin 侧 [perfCssInjected]：拦掉 SPA 路由切换引起的重复 onPageFinished（省一次 JSBridge 往返）；
     *   · JS 侧 getElementById('dsh-perf')：**真正生效的那道**。
     *     它管的是「整页重载（reload / 跳链接）后 document 被换掉、新文档里没有这个 <style>」——
     *     注意此时 Kotlin 侧的 perfCssInjected 仍是 true，所以不能只靠字段；
     *     字段只负责「SPA 重复触发时不重复发 JS」。两者一起，才既不重复也确实注上了。
     */
    private fun injectPerfCss(webView: WebView) {
        if (!perfEnabled) {
            Log.d(TAG_PERF, "containment css skipped (?noperf=1)")
            return
        }
        if (perfCssInjected) return

        val css = buildPerfCss()
        val js = "(function(){" +
            "var d=document;" +
            "if(!d.head)return 'no-head';" +
            "if(d.getElementById('dsh-perf'))return 'exists';" +
            "var s=d.createElement('style');" +
            "s.id='dsh-perf';" +
            "s.textContent=" + JSONObject.quote(css) + ";" +
            "d.head.appendChild(s);" +
            "return 'injected';" +
            "})()"
        try {
            webView.evaluateJavascript(js) { result ->
                // 回调在主线程。result 是 JS 返回值的 JSON 形式（字符串会带引号，形如 "injected"）。
                perfCssInjected = true
                Log.d(TAG_PERF, "containment css injected: result=" + result)
            }
        } catch (t: Throwable) {
            // evaluateJavascript 在 WebView 已被 destroy 时会抛（快速进出页面的竞态）。
            // 这里不能让它冒到 UI 线程；也不把 perfCssInjected 置 true —— 下次 onPageFinished
            // 还有机会重试（该字段只在真正注入成功后才置位）。
            Log.w(TAG_PERF, "containment css inject failed: " + t.message)
        }
    }

    /**
     * v0.4.7：构造「Enter 发送」监听的 JS。
     *
     * 输出形态与 injectPerfCss 一致：一个**自执行的函数表达式**，返回字符串供日志判读
     * （'installed' / 'exists' / 'disabled'）。为什么不用箭头函数/模板字符串：
     * 旧内核（Chrome < 90）虽然支持，但这里没有任何理由依赖新语法 —— 纯 ES5 最稳。
     *
     * 为什么要 JSONObject.quote 包一层：JS 源码里没有换行，本来直接用也行；
     * 但统一走 quote 后，将来加规则（比如把锚点选择器做成可配）时不必再想转义问题。
     *
     * ── 四条**必须**写进代码的判据（每一条都对应一次误触）──
     *   · e.shiftKey 排除：Shift+Enter 是换行，不是发送。收了键盘用户就没法继续输。
     *   · e.isComposing 排除：**最容易漏的一条**。中文输入法组词时按 Enter 是「上屏选词」，
     *     不是发送；此时收键盘会打断整个输入过程。
     *     （另外补 keyCode === 229：部分老内核在组词时只给 keyCode 229 而不设 isComposing。）
     *   · closest('[data-composer-seat]')：DSH 输入区的稳定锚点（ConversationContent.tsx:177）。
     *     没有这一条，页面别处（搜索框、重命名输入框）按 Enter 也会收键盘。
     *     ⚠️ 找不到锚点时**放行**（只记 warn 后照常记录时间戳），不是直接 return ——
     *        这条锚点是 DSH 前端实现的细节，一旦上游改名，return 式写法会让功能**静默失效**，
     *        而放行式写法最坏只是退化成「别处按 Enter 也收键盘」，用户一眼能看出异常、也能报给我们。
     *        取舍依据：静默失效比误收难发现得多。
     *   · key 同时接受 'Enter' 与 'NumpadEnter'：外接键盘/小键盘的回车 key 是 NumpadEnter，
     *     手机上虽然少见，但**蓝牙外接键盘是真实场景**，漏了它那条路径就不收键盘。
     *   · capture: true：在捕获阶段先跑，避免页面自身在处理 Enter 时 stopPropagation 把我们拦掉。
     *     ⚠️ 捕获阶段只会「先于」页面处理，**不能**取消页面行为 —— 我们也不取消，只记录时间戳。
     *
     * 为什么记 timestamp 而不是直接调收键盘：JS 没有能力收掉原生软键盘（这是 App 侧的事），
     * 它能做的是「告诉 App 刚刚发生了一次发送」，跨进程的通道就是 [sendWatchHandler] 的轮询。
     */
    private fun buildSendWatcherJs(): String = "(function(){" +
        "if(window.__dshSendWatch)return 'exists';" +
        "window.__dshSendWatch=true;" +
        "window.__dshLastSend=0;" +
        "document.addEventListener('keydown',function(e){" +
        "var k=e.key;" +
        "if(k!=='Enter'&&k!=='NumpadEnter')return;" +
        "if(e.shiftKey||e.isComposing||e.keyCode===229)return;" +
        "var t=e.target;" +
        "var seat=t&&t.closest?t.closest('[data-composer-seat]'):null;" +
        "if(!seat&&window.console)console.warn('[dsh-sendwatch] composer anchor not found');" +
        "window.__dshLastSend=Date.now();" +
        "},true);" +
        "return 'installed';" +
        "})()"

    /**
     * v0.4.7：注入 [buildSendWatcherJs] 并（必要时）启动轮询。
     *
     * 幂等性**只靠 JS 侧**（window.__dshSendWatch），不像 perfCss 那样再有 Kotlin 侧字段：
     *   因为本函数**必须**每次 onPageFinished 都执行（SPA 内路由切换后 document 仍在、
     *   监听器也还在 → JS 返回 'exists'；整页重载 → document 换了 → 真的装一次）。
     *   用 Kotlin 字段挡住会把「重载后重新装」这条路也一并挡死。
     *
     * 轮询的启动不在这里判重（postDelayed 前先 removeCallbacks，见 [restartSendWatchPolling]）：
     *   SPA 里 onPageFinished 会因路由反复触发，若每次都 post 一个 Runnable，
     *   消息队列里会排队 N 个并发的轮询链 —— 越切路由越频繁，正确做法是先摘掉再挂。
     */
    private fun installSendWatcher(webView: WebView) {
        if (!sendWatchEnabled) {
            Log.d(TAG_SEND_WATCH, "send watcher skipped (?nosendwatch=1)")
            return
        }
        try {
            webView.evaluateJavascript(buildSendWatcherJs()) { result ->
                // result 是 JSON 形式：字符串会带引号，形如 "installed"。
                Log.d(TAG_SEND_WATCH, "send watcher inject: result=" + result)
            }
        } catch (t: Throwable) {
            Log.w(TAG_SEND_WATCH, "send watcher inject failed: " + t.message)
        }
        restartSendWatchPolling()
    }

    /**
     * v0.4.7：让轮询链**恰好有一条**在跑。
     *
     * 先 removeCallbacks 再 post：本函数在三个地方被调用（onPageFinished / onResume / 注入成功），
     * 若不先摘，同一个 Activity 里会并存多条链 —— 表现为每秒多次 evaluateJavascript，
     * 且 removeCallbacks 只摘得掉一条（其余仍在跑），onDestroy 后残留继续泄漏。
     */
    private fun restartSendWatchPolling() {
        if (!sendWatchEnabled) return
        if (isFinishing || isDestroyed) return
        sendWatchHandler.removeCallbacks(sendWatchRunnable)
        sendWatchHandler.postDelayed(sendWatchRunnable, SEND_WATCH_INTERVAL_MS)
    }

    /** v0.4.7：停掉轮询链（onPause / onDestroy 各一次，见对应方法里的说明）。 */
    private fun stopSendWatchPolling() {
        sendWatchHandler.removeCallbacks(sendWatchRunnable)
    }

    /**
     * v0.4.7：收起软键盘。
     *
     * 为什么用 windowToken 而不是焦点 View：
     *   这里可能被收的那个人并不是我们主动请求的焦点（键盘是页面里 input 唤起的，
     *   WebView 内部有自己的输入连接），用 binding.webView.windowToken 是**最接近**
     *   那个输入连接所属窗口的句柄；传焦点 View 的 token 在某些 ROM 上无效。
     *
     * 为什么还要 clearFocus：imm.hideSoftInputFromWindow 只隐藏键盘、**不**改变焦点。
     *   DSH 输入框仍保持 focus 时，某些 ROM（以及一些输入法）会在下一次布局变化时
     *   把键盘重新弹出来 —— 表现为「键盘收起后闪一下又出来」。清焦点让重弹无从触发。
     *   ⚠️ WebView 的 clearFocus 会连带把 DOM 的激活元素失焦（页面会收到 blur），
     *      这正是「发送后不再输入」的语义，不需要额外处理。
     *
     * 失败静默：拿不到 InputMethodManager 时直接返回 —— 收键盘是锦上添花，
     *   不能因为它失败影响发送本身（此时消息已经发出去了）。
     */
    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        if (imm == null) {
            Log.w(TAG_SEND_WATCH, "hideKeyboard: imm unavailable")
            return
        }
        imm.hideSoftInputFromWindow(binding.webView.windowToken, 0)
        // 清焦点，避免部分 ROM 在下次布局变化时把键盘重新弹出来（见上方说明）。
        binding.webView.clearFocus()
        Log.d(TAG_SEND_WATCH, "hideKeyboard: done")
    }

    /**
     * 逃生阀判定：launchUrl 里是否带了某个开关参数（值为 1/true/yes 视为开启）。
     *
     * 为什么解析的是 [launchUrl] 而不是 WebView 的当前 URL：
     *   优化策略在 onCreate 时就定死了（见 [perfEnabled]），当前 URL 会随导航变化，
     *   拿它判断会让"这页到底有没有优化"随时间漂移。
     *
     * 为什么用 Uri.getQueryParameter：launchUrl 形如 "https://host/?token=..."，
     *   手动切字符串容易在 fragment / 编码上出错；平台 API 已处理这些。
     */
    private fun hasFlagParam(url: String, name: String): Boolean {
        if (url.isEmpty()) return false
        val value = runCatching { Uri.parse(url).getQueryParameter(name) }.getOrNull() ?: return false
        return value.equals("1", ignoreCase = true) ||
            value.equals("true", ignoreCase = true) ||
            value.equals("yes", ignoreCase = true)
    }

    /** `?noperf=1`：彻底关掉本版的全部性能优化（对照测试用）。 */
    private fun isNoPerfRequested(url: String): Boolean = hasFlagParam(url, PARAM_NO_PERF)

    /** `?nohw=1`：只跳过硬件层（渲染白屏/花屏时的急救开关）。 */
    private fun isNoHardwareLayerRequested(url: String): Boolean = hasFlagParam(url, PARAM_NO_HW)

    /** `?nosendwatch=1`（v0.4.7）：完全不装 Enter 发送监听与轮询（对照测试用）。 */
    private fun isNoSendWatchRequested(url: String): Boolean = hasFlagParam(url, PARAM_NO_SEND_WATCH)

    override fun onPause() {
        super.onPause()
        // 让页面进入后台态，暂停计时器类动画（省电）。onResume 里恢复。
        binding.webView.onPause()
        // ===== v0.4.7：停掉发送轮询 =====
        // 为什么在 onPause 就停（而不是只在 onDestroy）：用户切到后台/锁屏时轮询毫无意义，
        //   却仍然每秒两次跨 JSBridge 唤醒页面 —— 既费电，也可能让 WebView 无法真正进入
        //   frozen 状态。onDestroy 再停一次是**兜底**：正常情况下 onPause 已经摘干净，
        //   但「pause 之后再也没 resume 就到 destroy」这条路径必须自己保证。
        stopSendWatchPolling()
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
        // ===== v0.4.7：恢复发送轮询 =====
        // 为什么不在这里重新注入 JS：onPause/onResume 前后 document 没有变化，
        //   监听器还在页面里（JS 侧有 __dshSendWatch 守卫，重复注入也只是返回 'exists'）。
        //   但**不能假设一定在** —— 从后台回来时页面可能被内核丢弃并重新加载过一次，
        //   那种情况下 onPageFinished 会重新跑一遍注入，这里只管轮询这一半。
        restartSendWatchPolling()
    }


    /**
     * 安装 WebView 的 chrome client。
     *
     * 为什么必须有：WebView 默认**不实现** input[type=file] 的选文件流程。
     * 没有 WebChromeClient.onShowFileChooser 时，用户点网页里的「上传/添加附件」
     * 什么都不会发生——没有任何报错，也没有任何界面，看起来就是「按钮坏了」。
     * 这是 WebView 的既定行为，不是 DSH 前端的问题。
     */
    private fun installWebChromeClient() {
        binding.webView.webChromeClient = object : WebChromeClient() {

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                // ===== 防重复 =====
                // 上一回调还没回传就来了新请求（用户连点、页面重渲染 input）：
                // 必须先给它一个 null 让它结束，否则 WebView 会一直认为"上一次选择还没完"，
                // 之后所有点击都不再触发本方法，且旧回调对象会泄漏。
                pendingFileCallback?.let { previous ->
                    Log.w(TAG, "onShowFileChooser: previous callback still pending, cancelling it")
                    runCatching { previous.onReceiveValue(null) }
                }
                pendingFileCallback = filePathCallback
                pendingCaptureFile = null

                if (filePathCallback == null) return false

                // 网页要求直接调相机（capture 属性）时优先走相机；失败则退回文件选择。
                // ⚠️ 不能"相机失败就什么都不做" —— 那等于又回到修复前的死状态。
                val wantsCapture = fileChooserParams?.isCaptureEnabled == true
                if (wantsCapture && launchCameraForImage()) return true
                if (wantsCapture) {
                    Log.w(TAG, "capture unavailable, falling back to chooser")
                    Toast.makeText(this@WebViewActivity, R.string.webview_capture_failed, Toast.LENGTH_SHORT).show()
                }

                // launchFileChooser 失败时会自己 finishFileChooser(null) 并返回 false，
                // 而我们的返回值只决定"WebView 要不要自己处理"，两者互不冲突。
                return launchFileChooser(fileChooserParams)
            }

            /**
             * 网页申请摄像头/麦克风权限（DSH 的语音输入会用到）。
             *
             * 保守做法：不做无条件 grant，只对已由本 App 持有运行时权限的资源放行；
             * 其余拒绝。这样即使网页被 XSS 注入，也拿不到 App 没被授予的能力。
             */
            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                try {
                    val granted = request.resources.filter { res ->
                        when (res) {
                            PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                                hasPermission(android.Manifest.permission.CAMERA)
                            PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                                hasPermission(android.Manifest.permission.RECORD_AUDIO)
                            else -> false
                        }
                    }
                    if (granted.isEmpty()) {
                        request.deny()
                    } else {
                        request.grant(granted.toTypedArray())
                    }
                } catch (t: Throwable) {
                    // 网页已被销毁等竞态：忽略即可，不要让异常冒到 UI 线程。
                    runCatching { request.deny() }
                }
            }
        }
    }

    private fun hasPermission(permission: String): Boolean =
        checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * 结束一次文件选择：把结果回传并清空持有状态。
     *
     * ⚠️ 必须传 null 结束也是**成功路径**的一部分：用户点「取消」时如果不回传，
     *    WebView 会一直等这一次结果，之后**再也点不开**文件选择器。
     *    这是本功能最常见、也最难自查的坑（现象是「第一次能用，之后点了没反应」）。
     */
    private fun finishFileChooser(uris: Array<Uri>?) {
        val cb = pendingFileCallback ?: return
        pendingFileCallback = null
        runCatching { cb.onReceiveValue(uris) }
    }

    /** 清理拍照临时文件（取消/失败/已消费）。 */
    private fun discardPendingCapture() {
        val f = pendingCaptureFile ?: return
        pendingCaptureFile = null
        runCatching { if (f.exists()) f.delete() }
    }

    /**
     * 拉起系统文件选择器（SAF）。
     *
     * 为什么用 ACTION_GET_CONTENT 而不是自己申请 READ_MEDIA_* 权限：
     *   SAF 由系统文件选择器代读，App 只拿到一次性可读的 content Uri，
     *   **不需要任何运行时权限**，也不扩大对相册/存储的访问面。
     *
     * @return 是否成功拉起（false 时会由调用方报错提示）。
     */
    private fun launchFileChooser(params: WebChromeClient.FileChooserParams?): Boolean {
        val intent = runCatching { params?.createIntent() }.getOrNull()
            ?: Intent(Intent.ACTION_GET_CONTENT).apply { addCategory(Intent.CATEGORY_OPENABLE) }
        intent.addCategory(Intent.CATEGORY_OPENABLE)

        // 多选：mode == MODE_OPEN_MULTIPLE
        val multiple = params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
        if (multiple) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)

        // acceptTypes → MIME。注意：putExtra 的 EXTRA_MIME_TYPES 只接受 Array<String>，
        // 传 List 会编译失败（Kotlin 会选不到重载）。
        val accept = params?.acceptTypes?.filter { it.isNotBlank() } ?: emptyList()
        if (accept.isNotEmpty()) {
            intent.putExtra(Intent.EXTRA_MIME_TYPES, accept.toTypedArray())
            if (intent.type == null) intent.type = buildWildcardMime(accept)
        } else if (intent.type == null) {
            intent.type = "*/*"
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        return try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent.createChooser(intent, getString(R.string.webview_file_chooser_title)), REQ_FILE_CHOOSER)
            true
        } catch (e: ActivityNotFoundException) {
            // 极少数精简 ROM 没有系统文件选择器：必须明确告知，
            // 否则现象与「没实现」完全一样（点了没反应），用户无从区分。
            finishFileChooser(null)
            Toast.makeText(this, R.string.webview_file_chooser_unavailable, Toast.LENGTH_LONG).show()
            false
        }
    }

    /**
     * 由 acceptTypes 推出一个选择器可接受的顶层 MIME。
     *
     * 例：网页只要求图片时给出 image 通配，系统选择器会直接筛出图片。
     * 全部不是同一顶层类型时退回任意类型（EXTRA_MIME_TYPES 仍会做精确过滤）。
     */
    private fun buildWildcardMime(accept: List<String>): String {
        val tops = accept.map { it.substringBefore('/') }.filter { it.isNotBlank() }.distinct()
        if (tops.size != 1) return MIME_ANY
        if (!accept.all { it.contains('/') }) return MIME_ANY
        return tops.first() + "/" + MIME_WILDCARD
    }

    /**
     * 拉起系统相机拍一张照片。
     *
     * ⚠️ 相机不能接收 file Uri：minSdk 24 起会抛 FileUriExposedException。
     *    因此必须经 FileProvider 转成 content Uri，且该路径必须已在
     *    res/xml/file_paths.xml 中声明（filechooser/）。
     *
     * @return 是否成功拉起相机（失败时返回 false，调用方退回文件选择）。
     */
    private fun launchCameraForImage(): Boolean {
        val dir = File(cacheDir, CAPTURE_DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "capture dir unavailable: " + dir.absolutePath)
            return false
        }

        // 清理旧临时照片：cacheDir 的回收时机由系统决定，用户可能几个月不清理，
        // 不主动删就是每张 1~5MB 一路堆积。只在发起拍照时清一次。
        removeStaleCaptures(dir)

        val target = File(dir, "capture_" + System.currentTimeMillis() + ".jpg")
        val uri = try {
            if (!target.createNewFile()) {
                Log.e(TAG, "cannot create capture file: " + target.absolutePath)
                return false
            }
            FileProvider.getUriForFile(this, packageName + ".fileprovider", target)
        } catch (e: Exception) {
            // 最常见原因：file_paths.xml 里的 path 与 CAPTURE_DIR_NAME 不一致
            // （getUriForFile 抛 IllegalArgumentException）。这是配置问题，不是运行时错误，
            // 但必须 catch 住并退回文件选择，不能让用户看到崩溃。
            Log.e(TAG, "capture target unavailable: " + e.message)
            // 刚创建的空文件要删掉，否则每失败一次就留一个 0 字节的垃圾。
            runCatching { target.delete() }
            return false
        }

        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            // 相机通常是另一个进程：少发一个 flag 就会出现"能拍但保存失败 / 存出 0 字节"。
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        return try {
            startActivityForResult(intent, REQ_FILE_CAPTURE)
            pendingCaptureFile = target
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no camera activity: " + e.message)
            runCatching { target.delete() }
            false
        } catch (e: Exception) {
            Log.e(TAG, "launch camera failed: " + e.message)
            runCatching { target.delete() }
            false
        }
    }

    /**
     * 删除拍照目录里的旧临时文件（保留 [pendingCaptureFile] 指向的那个）。
     *
     * 用 "1 小时前" 而不是"全删"：全删会误伤**刚创建、正在被相机写入**的那个文件
     * （虽然本方法在创建新文件之前调用，但顺序一旦被后来的改动调换就是致命 bug）。
     * 时间窗同时保证长期不清理的机型不会把缓存塞满。
     */
    private fun removeStaleCaptures(dir: File) {
        val keepName = pendingCaptureFile?.name
        try {
            val cutoff = System.currentTimeMillis() - CAPTURE_STALE_MS
            dir.listFiles()?.forEach { old ->
                if (old.isFile && old.name != keepName && old.lastModified() < cutoff) {
                    if (!old.delete()) Log.w(TAG, "stale capture delete failed: " + old.name)
                }
            }
        } catch (e: Exception) {
            // 清理失败无关紧要，绝不能因此让拍照流程失败。
            Log.w(TAG, "clean stale captures failed: " + e.message)
        }
    }

    /**
     * 处理文件选择 / 拍照的返回。
     *
     * 四种路径都必须让 pendingFileCallback 有个了断，否则文件选择器会「卡住」：
     *   ① 文件选择正常返回 → 回传 Uri 列表（单选可能只在 data.data，多选在 clipData）；
     *   ② 拍照成功 → 回传临时文件的 content Uri；
     *   ③ 用户取消 → **回传 null**（不回传就永远卡住）；
     *   ④ 拍照取消/失败 → 回传 null 并删掉临时文件。
     */
    // 说明：startActivityForResult 虽被标记 deprecated，但在本 Activity 里仍是**唯一**
    // 能可靠接住"系统选择器/相机"结果的方式（ActivityResultLauncher 必须在 STARTED 之前
    // 注册，而 onShowFileChooser 可能在生命周期任意时刻被调用，会多出一份状态）。
    // 因此这里对 deprecation 只在调用点做局部 @Suppress，不改用契约式 API。
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQ_FILE_CHOOSER -> {
                // ⚠️ 取消（resultCode != RESULT_OK）**必须**回传 null。
                //    漏掉这一步 = WebView 认为"上一次选择还没结束"，
                //    之后**再点上传完全没反应**。这是本功能最隐蔽、也最常见的坑。
                if (resultCode != Activity.RESULT_OK || data == null) {
                    Log.i(TAG, "file chooser returned empty (resultCode=" + resultCode + ")")
                    discardPendingCapture()
                    finishFileChooser(null)
                    return
                }
                val clip: ClipData? = data.clipData
                val uris: Array<Uri>? = if (clip != null && clip.itemCount > 0) {
                    Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                } else {
                    data.data?.let { arrayOf(it) }
                }
                finishFileChooser(uris)
            }

            REQ_FILE_CAPTURE -> {
                val file = pendingCaptureFile
                pendingCaptureFile = null
                if (resultCode != Activity.RESULT_OK || file == null || !file.exists() || file.length() == 0L) {
                    runCatching { if (file != null && file.exists()) file.delete() }
                    finishFileChooser(null)
                    return
                }
                val uri = runCatching {
                    FileProvider.getUriForFile(this, packageName + ".fileprovider", file)
                }.getOrNull()
                if (uri == null) {
                    runCatching { file.delete() }
                    finishFileChooser(null)
                } else {
                    finishFileChooser(arrayOf(uri))
                }
            }

            else -> {
                // 不是本功能发起的请求码。
                // 本 Activity 目前没有其它 startActivityForResult 调用（更新流程用
                // AlertDialog + 显式 Intent，不走结果回调），所以这里只需记一条日志备查，
                // 不要凭空调用一个不存在的"继续处理"方法。
                Log.i(TAG, "onActivityResult ignored: requestCode=" + requestCode)
            }
        }
    }

    override fun onDestroy() {
        // 取消在途的更新下载：否则回调会在 Activity 销毁后尝试操作对话框。
        // 只取消下载，不动 UpdateSession（会话上下文属于整个登录周期）。
        Updates.onActivityDestroyed()
        // 结束可能还挂着的文件选择回调（用户在选择器里时 Activity 被销毁的场景），
        // 并清掉拍照临时文件。不清理会让 WebView 侧的 input 永久停在"选择中"状态。
        runCatching { finishFileChooser(null) }
        runCatching { discardPendingCapture() }
        // ===== v0.4.7：销毁前必须摘掉发送轮询 =====
        // ⚠️ 这是本版**唯一**的泄漏风险点：sendWatchRunnable 隐式持有 WebViewActivity，
        //    消息队列里 pending 着下一次 postDelayed ⇒ Activity + WebView 整页无法回收。
        //    为什么这里还要摘一次（onPause 已摘）：onPause 之后到 onDestroy 之间可能又
        //    post 过一次（例如 onResume → onPageFinished 的乱序），且「onCreate 早退（finish()）」
        //    这条路径也会走到这里 —— 本行保证**任何**销毁路径都摘干净。
        stopSendWatchPolling()
        // ===== v0.4.8：诊断模式的就绪轮询同理 =====
        // 它同样持有 Activity（Runnable 里引用了 this），且最长会排 3 秒；
        // 用户拨完开关立刻退出页面时，残留的回调会在已销毁的 WebView 上 evaluateJavascript。
        stopDiagRetry()
        // WebView 持有 Activity 的 Context，不主动销毁会泄漏整个 Activity
        // （表现：退出后内存不释放，反复进出后 OOM）。
        // 只在真正要结束时销毁：配置变更走 destroy → 重建会丢页面状态，
        // 而本 Activity 已在 manifest 里声明 configChanges 接管常见配置变化，
        // 因此 isFinishing 的判断就够用了。
        if (isFinishing) {
            binding.webView.stopLoading()
            binding.webView.loadUrl("about:blank")
            binding.webView.clearHistory()
            binding.webView.removeAllViews()
            binding.webView.destroy()
        }
        super.onDestroy()
    }
}
