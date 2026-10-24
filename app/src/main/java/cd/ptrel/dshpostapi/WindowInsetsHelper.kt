package cd.ptrel.dshpostapi

import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 系统栏（状态栏 / 导航栏 / 键盘）留白处理。
 *
 * ================== 为什么需要这个文件 ==================
 * targetSdk = 35 ⇒ Android 15 (API 35) 起强制 edge-to-edge：
 * 系统把窗口绘制边界推到状态栏与导航栏下方，应用内容从 y=0 开始画，
 * 于是顶部被状态栏盖住、底部被导航栏盖住。
 *
 * 这是平台行为变更，不是某个 ROM 的怪癖：
 *   - API 35+：即使主题写了 statusBarColor，也不给内容让位；
 *   - API < 35：默认仍是 fitsSystemWindows 语义（内容自动避让）。
 *
 * 所以这里显式接管：分出 systemBars 与 ime 的 insets，把根容器 padding 撑开。
 * 两个好处：
 *   ① 行为在所有 API 上一致，不依赖平台是否自动避让；
 *   ② 键盘弹出（ime）由同一个监听器处理 —— 这是它相对
 *      android:fitsSystemWindows=true 的关键优势（后者不处理 ime）。
 *
 * ================== v0.4.11：为什么必须按 API 分级 ==================
 * v0.4.10 及之前，本文件**无条件**调用 WindowCompat.setDecorFitsSystemWindows(window, false)。
 * 实测（华为 VRD-AL10 / Android 10 / EMUI）：键盘弹出时 ime.bottom 读到的是 0，
 * 于是 translationY = -0、padding = 导航栏高度 —— 两种让位方式**同时**失效。
 *
 * 根因：setDecorFitsSystemWindows(false) 在 API 30 之前不是「完整语义」。
 *   API 30+（WindowInsetsController / 强制 edge-to-edge 体系）才真正把
 *   insets 的分发责任交给应用；API 29- 只把这个标志翻译成
 *   `decorView.fitsSystemWindows = false`，并不保证 ime inset 被上报 ——
 *   部分 ROM（EMUI 是典型）此时仍由系统按 adjustResize 的老规矩自己处理，
 *   应用侧拿到的 ime inset 恒为 0。
 *
 * 所以本版按 [defaultHandlesInsetsItself] 分级：
 *   · API 30+：接管 insets（setDecorFitsSystemWindows(false) + padding + translationY）；
 *   · API 29-：**不接管**，标志显式传 false，让系统按老规矩自行避让
 *     （manifest 侧对应 adjustResize，见 AndroidManifest 的说明）。
 *   Android 10 及以下本来就不强制 edge-to-edge，系统默认会正确避让状态栏与键盘；
 *   那套接管逻辑只为 targetSdk=35 在 Android 15+ 上的强制 edge-to-edge 而生。
 *
 * ================== 为什么用 padding 而不是 margin ==================
 * padding 让容器的背景继续铺到状态栏区域（背景色可见、内容不侵入），
 * 正是「状态栏区域显示 App 主题色，不要透出网页」的要求。
 * 用 margin 则容器背景不铺到状态栏，会透出 windowBackground；
 * 两层背景色一旦不同就能看到一条色带。
 */
object WindowInsetsHelper {

    /** Logcat 过滤：`adb logcat -s WebViewInsets`（与 WebViewLayout 的布局诊断分开）。 */
    private const val TAG = "WebViewInsets"

    /**
     * 「窗口 insets 是否由本应用接管」的 API 分水岭（v0.4.11）。
     *
     * 为什么是 30（Android 11）：WindowInsetsController 体系自 API 30 起才完整 ——
     * 从这一版开始 `setDecorFitsSystemWindows(window, false)` 才真正意味着
     * 「系统不再自行避让，ime inset 由应用读取并处理」。
     *
     * 29- 的实测结论（华为 VRD-AL10 / Android 10 / EMUI，v0.4.3/0.4.5/0.4.10 三次验证）：
     * 不管让位方式用 translationY 还是 padding，页面**完全不动**；
     * 两种方式唯一的共同依赖是 ime.bottom ⇒ 该值在 API 29- 上恒为 0。
     * 详见文件头「为什么必须按 API 分级」。
     */
    const val SDK_TAKEOVER_INSETS = Build.VERSION_CODES.R // = 30

    /**
     * 当前设备是否应当由本应用接管窗口 insets。
     *
     * true（API 30+）：接管 —— padding 处理系统栏、translationY 处理键盘。
     * false（API 29-）：**不接管** —— 不设 setDecorFitsSystemWindows(false)，
     *   由 Window 按 fitsSystemWindows 语义自行避让状态栏/导航栏，
     *   由 manifest 的 adjustResize 自行避让键盘（内容区被缩短）。
     *
     * ⚠️ 这条分支与 AndroidManifest 的 softInputMode 设置**成对**：
     *    WebViewActivity 在 API 29- 上跑的就是 adjustResize。
     */
    val defaultHandlesInsetsItself: Boolean
        get() = Build.VERSION.SDK_INT >= SDK_TAKEOVER_INSETS

    /**
     * 给 [rootView] 施加系统栏 + 键盘的 inset padding。
     *
     * @param rootView 根容器。它的背景色会成为状态栏/导航栏区域的底色，
     *   调用方应确保该容器的背景是期望的 App 主题色（本项目是 colorBackground）。
     * @param additionalTopDp 额外顶部留白（dp）。
     * @param additionalBottomDp 额外底部留白（dp）。
     * @param handleIme 是否由本函数负责给键盘让位（默认 true）。
     *   - true：底部取 max(systemBars.bottom, ime.bottom)，键盘弹出时 padding 长高。
     *     适用于**没有 WebView** 的页面（登录页）—— 那里没有 100vh 的排版依赖，
     *     用 padding 让位最稳，且不需要改窗口行为。
     *   - false：底部只取 systemBars.bottom，**键盘完全不参与 padding**。
     *     适用于 WebViewActivity（v0.4.5 起配 translateForIme = true）。
     *
     *   ⚠️ 为什么必须能关掉：padding 变化本身就是一次布局重算
     *   （根容器 padding 改变 ⇒ 子 View 重新 measure/layout ⇒ WebView 有效高度变化）。
     *   在华为 ROM 上实测会导致打字重排（v0.4.2 的失败点）。
     *   ⇒ 键盘让位只能有**一个**施力点（padding 或 translationY，二选一）。
     * @param translateForIme 键盘弹出时用 translationY 把 [rootView] 整体上移（默认 false）。
     *   见 [applyImeTranslation] 的说明。true 时应同时传 handleIme = false，
     *   否则 padding 与 translationY 会各让一次位（键盘高度算两遍）。
     * @param handlesInsetsItself 是否由本函数接管窗口 insets（默认 = [defaultHandlesInsetsItself]）。
     *   - true：setDecorFitsSystemWindows(false) + 自己 padding/平移（API 30+ 的路径）；
     *   - false：**不碰**窗口标志，只挂监听器记录数值（诊断用），布局完全交给系统。
     *     用于 API 29-（见文件头说明）—— 那里由 manifest 的 adjustResize 负责避让。
     *   ⚠️ 传 false 时 handleIme / translateForIme 一律无效（键盘由系统缩窗口处理）。
     */
    fun applySystemBarPadding(
        rootView: View,
        additionalTopDp: Int = 0,
        additionalBottomDp: Int = 0,
        handleIme: Boolean = true,
        translateForIme: Boolean = false,
        handlesInsetsItself: Boolean = defaultHandlesInsetsItself,
    ) {
        // ===== 必须先关掉「窗口自动避让」，否则所有 API 上都会双重留白 =====
        //   API 35+：平台强制 edge-to-edge，语义等价于 false（显式设也无害，行为一致）；
        //   API 30~34：默认 true ⇒ Window 自己已经把内容让开了系统栏，
        //             我们再叠一层 padding 就变成「顶部空出一大块」。
        // 这一行是**跨版本正确**的前提：不写它，代码在 Android 15 上对、在 7~14 上错。
        //
        // ⚠️ v0.4.11：API 29- 传 false —— **不要**在这里设标志（见 [defaultHandlesInsetsItself]）。
        val activity = rootView.context.findActivity()
        if (activity != null && handlesInsetsItself) {
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        } else if (activity != null) {
            Log.d(
                TAG,
                "insets takeover skipped on sdk=" + Build.VERSION.SDK_INT +
                    " (system handles bars & ime)",
            )
        }

        val extraTopPx = dp(rootView, additionalTopDp)
        val extraBottomPx = dp(rootView, additionalBottomDp)

        // 记录基础 padding：避免每次 inset 变化都在上一次结果上再叠加。
        // 漏了这一层会表现为「切换深色模式 / 转屏后留白越来越大」。
        val baseLeft = rootView.paddingLeft
        val baseTop = rootView.paddingTop
        val baseRight = rootView.paddingRight
        val baseBottom = rootView.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            // ===== v0.4.11：API 29- 不接管 insets =====
            // 这里必须**什么都不做**：既不加 padding 也不设 translationY。
            // 一旦在这条分支上做任何一项，都会与系统的 adjustResize 叠加成双重让位
            // （顶部多空一块 / 页面被推上去两遍）。
            // 只记一行日志：真机上要靠它确认「这条分支确实走到了」。
            if (!handlesInsetsItself) {
                val dbgBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                val dbgIme = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
                Log.d(
                    TAG,
                    "insets passthrough sdk=" + Build.VERSION.SDK_INT +
                        " bars=" + dbgBars.top + "/" + dbgBars.bottom +
                        " imeBottom=" + dbgIme.bottom,
                )
                return@setOnApplyWindowInsetsListener windowInsets
            }

            // 分别取 systemBars 与 ime，而不是用 `or` 合并成一个类型位掩码。
            //
            // 为什么分开有两个理由：
            //   ① 语义清楚：状态栏走 systemBars.top，键盘走 ime.bottom；
            //   ② 避免重复计算：部分 ROM 的 ime inset 已经包含导航栏高度，
            //      若把两者合并成一个 mask 再取 inset，结果取决于实现细节，
            //      无法断言它取的是 max 还是相加。分开取、显式 max 才是可推理的。
            val bars: Insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            // handleIme = false 时**不读** ime inset：既不让它参与 padding，
            // 也保证 ime 变化时本监听器算出的结果与之前完全一致
            // （读一下再丢掉虽然结果相同，却会让「为什么这里要读 ime」变成一个疑问）。
            val ime: Insets = if (handleIme) {
                windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            } else {
                Insets.NONE
            }

            // ===== v0.4.5：键盘让位改用 translationY（必须与 padding 在**同一个** listener 里）=====
            // 为什么合并进来而不是另挂一个 listener：
            //   ViewCompat.setOnApplyWindowInsetsListener 对同一个 View 是**覆盖**语义，
            //   在 rootFrame 上再调一次 applyImeTranslation 会把这个 listener 挤掉
            //   ⇒ 状态栏 padding 全部失效（顶部内容直接钻到状态栏下面）。
            //   所以「padding」与「translationY」只能在这个回调里同时设置。
            //
            // 为什么 translationY 而不是 padding/height：
            //   改尺寸 ⇒ WebView 有效高度变 ⇒ 前端 100vh 变 ⇒ DSH 每敲一个字整页重排
            //   （v0.4.2 实测 CLS 单次 1.0、FPS 1~5）。
            //   translationY 只改绘制位置：getBoundingClientRect / innerHeight / 100vh 全不变。
            //
            // 为什么只在 translateForIme = true 时碰 ime：
            //   LoginActivity 用 padding 让位（handleIme = true），那里绝不能同时平移，
            //   否则键盘高度被让两遍。默认 false ⇒ 对既有调用方**零行为变化**。
            val imeForTranslation = if (translateForIme) {
                windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            } else {
                Insets.NONE
            }
            // 直接赋值而非累加：translationY 是绝对位移量，键盘收起时 ime.bottom = 0
            // ⇒ 归零，不需要额外复位逻辑（漏了这一点会表现为「收起键盘后页面仍悬空」）。
            view.translationY = -imeForTranslation.bottom.toFloat()

            // bottom 取两者较大者：键盘弹出时让位给键盘，未弹出时让位给导航栏。
            // 相加是错的 —— 会把导航栏高度算两遍，底部多出一段空白。
            // 键盘让位走 translationY 时 ime 恒为 Insets.NONE ⇒ bottomInset 就是导航栏高度。
            val bottomInset = maxOf(bars.bottom, ime.bottom)

            view.setPadding(
                baseLeft + bars.left,
                baseTop + bars.top + extraTopPx,
                baseRight + bars.right,
                baseBottom + bottomInset + extraBottomPx,
            )
            // 返回原 insets 而不是 CONSUMED：
            //   子 View 若也需要 insets 信息，消费掉会让它们拿到 0。
            //   本项目子 View 不读 insets，但保留传播是更安全的默认。
            windowInsets
        }

        // 主动拉一次：避免某些 ROM 上「首帧已画完才收到回调」导致闪一下。
        ViewCompat.requestApplyInsets(rootView)
    }

    /**
     * 键盘弹出时把根视图整体上移，而不是改变它的尺寸。
     *
     * 为什么用 translationY 而不是 padding/height：
     *   改尺寸会让 WebView 内部的视口高度变化 ⇒ 前端（DSH 用 100vh + 不监听 visualViewport）
     *   每敲一个字就整页重排一次（实测 CLS 单次 1.0、FPS 掉到 1-5）。
     *   translationY 只改绘制位置，getBoundingClientRect / innerHeight / 100vh 全不变。
     *
     * 为什么不靠 adjustPan：
     *   本文件中调用了 setDecorFitsSystemWindows(false)，等于声明「insets 我自己管」；
     *   此时 adjustPan（要求系统平移窗口）语义矛盾，系统两边都不做 —— 实测页面完全不动。
     *
     * ⚠️ 这是 [applySystemBarPadding] 的**语法糖**，内部转调它（translateForIme = true）。
     *   不要对同一个 View 同时调用本函数和 applySystemBarPadding：
     *   setOnApplyWindowInsetsListener 是覆盖语义，后调用的会挤掉先前的 listener。
     *   需要 padding 时直接调 applySystemBarPadding(..., translateForIme = true)。
     *
     * @param rootView 要平移的根视图（应是铺满屏幕的 FrameLayout）
     */
    fun applyImeTranslation(rootView: View) {
        applySystemBarPadding(rootView, handleIme = false, translateForIme = true)
    }

    /**
     * 让系统栏图标颜色跟随背景明暗。
     *
     * 为什么需要：targetSdk 35 下我们接管了留白，状态栏区域显示的是 App 背景色。
     * 图标颜色不跟着变，浅色主题下会出现「白色图标画在白色背景上」（看不见）
     * ——「背景与前景是一对」的典型：改了背景必须同时改前景。
     *
     * @param lightBackground true = 背景是浅色，需要**深色**图标。
     */
    fun applySystemBarIconAppearance(activity: Activity, lightBackground: Boolean) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        // 注意命名：isAppearanceLightStatusBars = true 表示「状态栏用深色（黑）图标」，
        // 与「状态栏背景是浅色」同义，读的时候容易反过来。
        controller.isAppearanceLightStatusBars = lightBackground
        controller.isAppearanceLightNavigationBars = lightBackground
    }

    /** 是否运行在强制 edge-to-edge 的 Android 15+（仅供排查，逻辑不依赖它）。 */
    val isMandatoryEdgeToEdge: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

    /**
     * ============ 键盘让位诊断：一次回调决定「根因是哪一类」（v0.4.11）============
     *
     * 为什么要重写（v0.4.3 的 debugInsets 有**两个**致命缺陷）：
     *   ① 用的是 ViewCompat.getRootWindowInsets()，它在 insets **已分发完之后**
     *      返回的是「剩余量」而不是原始量 —— 我们自己消费/平移过的值会相互污染，
     *      拿它做判断等于用放大镜看已经改过的图像；
     *   ② 它没有把「软键盘到底占了多少屏幕」独立量一次。
     *      ime.bottom = 0 有两种完全不同的原因：
     *        a) insets 方案在我们这里没接通（换方案能修）；
     *        b) 系统做了 adjustResize、窗口本身已经缩短（不需要让位，换了也白换）。
     *      只看 ime.bottom 分不清这两者 —— 而它们的修复方向**相反**。
     *
     * 因此这里在回调里一次性打印**四个量**，判读方式写在返回值末尾的 verdict 里：
     *   · imeBottom     : InsetsType.ime 的高度（API 30+ 应有值；API 29- 实测为 0）
     *   · frameH        : decorView 当前可见高度（adjustResize 生效时会变矮）
     *   · displayH      : 屏幕真实高度（含状态栏/导航栏）
     *   · hiddenPx      : displayH - frameH = 「被系统吃掉的像素」（键盘占的就体现在这里）
     *   · rootH / transY: 我们的容器高度与平移量（验证 padding / translationY 是否真落下）
     *
     * ⚠️ 用的是 WindowInsets 回调里的原始 inset（不是 getRootWindowInsets），
     *   所以数值不受我们自己的处理影响。
     *
     * @return 形如 "ime=? bars=?... verdict=..." 的字符串；同时已写 Logcat（TAG=WebViewLayout）。
     */
    fun describeImeSituation(
        activity: Activity,
        webView: View,
        root: View,
        imeBottom: Int,
        barsTop: Int,
        barsBottom: Int,
    ): String {
        val decor = activity.window.decorView
        // getWindowVisibleDisplayFrame：框架级 API，从 API 1 就有，不依赖 insets 体系。
        // 它给的是「窗口当前真正可见的矩形」，adjustResize 缩窗口时这里会跟着变。
        val frame = android.graphics.Rect()
        decor.getWindowVisibleDisplayFrame(frame)
        val metrics = activity.resources.displayMetrics
        val displayH = metrics.heightPixels
        val frameH = frame.height()
        val hidden = (displayH - frameH).coerceAtLeast(0)

        // 键盘高度按「可见区域之外」估算：状态栏不在可见区域里，但要扣掉；
        // 导航栏在 visible display frame**之内**（它只是盖住内容），所以不扣。
        // ⚠️ 这个值只是**估算**，用于日志判读，不参与任何布局计算。
        val imeEstimate = (hidden - barsTop).coerceAtLeast(0)

        val verdict = when {
            imeBottom > 0 -> "insets-ok"
            hidden <= 80 -> "no-keyboard"       // 键盘没弹（或 <80px，不可能）
            imeEstimate > 100 -> "system-resized" // 系统已缩短窗口 ⇒ 不需要我们自己让位
            else -> "insets-broken"              // 键盘弹了、窗口没缩、inset 也没有 ⇒ 拿不到
        }

        val line = "DIAG sdk=" + Build.VERSION.SDK_INT +
            " takeover=" + defaultHandlesInsetsItself +
            " imeBottom=" + imeBottom +
            " imeEstimate=" + imeEstimate +
            " frameH=" + frameH + " frameTop=" + frame.top +
            " displayH=" + displayH + " hiddenPx=" + hidden +
            " barsTop=" + barsTop + " barsBottom=" + barsBottom +
            " webViewH=" + webView.height +
            " rootH=" + root.height +
            " rootPadT=" + root.paddingTop + " rootPadB=" + root.paddingBottom +
            " translationY=" + root.translationY +
            " softInput=0x" + Integer.toHexString(activity.window.attributes.softInputMode) +
            " verdict=" + verdict
        Log.d(TAG, line)
        return line
    }

    private fun dp(view: View, value: Int): Int {
        if (value == 0) return 0
        return (value * view.resources.displayMetrics.density).toInt()
    }

    /**
     * 从 View 的 Context 向上找 Activity。
     *
     * 为什么要循环：View 的 Context 可能是 ContextThemeWrapper / ContextWrapper
     * 套了好几层，直接 `context as? Activity` 在主题化布局里经常拿到 null。
     */
    private fun android.content.Context.findActivity(): Activity? {
        var current: android.content.Context? = this
        while (current is android.content.ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }
}
