package cd.ptrel.dshpostapi

import android.app.Activity
import android.os.Build
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
 * ================== 为什么用 padding 而不是 margin ==================
 * padding 让容器的背景继续铺到状态栏区域（背景色可见、内容不侵入），
 * 正是「状态栏区域显示 App 主题色，不要透出网页」的要求。
 * 用 margin 则容器背景不铺到状态栏，会透出 windowBackground；
 * 两层背景色一旦不同就能看到一条色带。
 */
object WindowInsetsHelper {

    /**
     * 给 [rootView] 施加系统栏 + 键盘的 inset padding。
     *
     * @param rootView 根容器。它的背景色会成为状态栏/导航栏区域的底色，
     *   调用方应确保该容器的背景是期望的 App 主题色（本项目是 colorBackground）。
     * @param additionalTopDp 额外顶部留白（dp）。
     * @param additionalBottomDp 额外底部留白（dp）。
     */
    fun applySystemBarPadding(
        rootView: View,
        additionalTopDp: Int = 0,
        additionalBottomDp: Int = 0,
    ) {
        // ===== 必须先关掉「窗口自动避让」，否则所有 API 上都会双重留白 =====
        //   API 35+：平台强制 edge-to-edge，语义等价于 false（显式设也无害，行为一致）；
        //   API 24~34：默认 true ⇒ Window 自己已经把内容让开了系统栏，
        //             我们再叠一层 padding 就变成「顶部空出一大块」。
        // 这一行是**跨版本正确**的前提：不写它，代码在 Android 15 上对、在 7~14 上错。
        val activity = rootView.context.findActivity()
        if (activity != null) {
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
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
            // 分别取 systemBars 与 ime，而不是用 `or` 合并成一个类型位掩码。
            //
            // 为什么分开有两个理由：
            //   ① 语义清楚：状态栏走 systemBars.top，键盘走 ime.bottom；
            //   ② 避免重复计算：部分 ROM 的 ime inset 已经包含导航栏高度，
            //      若把两者合并成一个 mask 再取 inset，结果取决于实现细节，
            //      无法断言它取的是 max 还是相加。分开取、显式 max 才是可推理的。
            val bars: Insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime: Insets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())

            // bottom 取两者较大者：键盘弹出时让位给键盘，未弹出时让位给导航栏。
            // 相加是错的 —— 会把导航栏高度算两遍，底部多出一段空白。
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

    /** 把当前 insets 打出来，供真机排查用。 */
    fun debugInsets(rootView: View): String {
        val actual = ViewCompat.getRootWindowInsets(rootView) ?: return "rootWindowInsets=null"
        val bars = actual.getInsets(WindowInsetsCompat.Type.systemBars())
        val ime = actual.getInsets(WindowInsetsCompat.Type.ime())
        return "systemBars=" + bars + " (top=" + bars.top + ") ime=" + ime + " (bottom=" + ime.bottom + ")"
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
