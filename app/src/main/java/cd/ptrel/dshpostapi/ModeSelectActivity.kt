package cd.ptrel.dshpostapi

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity

/**
 * v0.5.0：使用方式选择页 —— 让用户在「完整模式（WebView）」与「兼容模式（原生）」之间选一次。
 *
 * ==================== 什么时候会走到这里 ====================
 * [AppPrefs.getUiMode] 返回 null（= 从没选过）时，[LoginActivity] 登录成功后拉起本页。
 * 选过之后直接进对应界面，不再打扰；要改去设置里的「切换使用方式」。
 *
 * 为什么必须让用户**显式选一次**而不是给个默认值：
 *   默认任何一种都会有用户不满意。更糟的是 —— 如果默认完整模式，
 *   新用户永远看不到另一条路的存在，遇到卡顿只会以为「这 App 就是卡」，
 *   不会想到还有兼容模式。**让选择可见，比替用户省一次点击重要。**
 *
 * ==================== 为什么检测 WebView 版本 ====================
 * 旧内核（实测华为 VRD-AL10 停在 Chrome 114）会让 DSH 网页明显卡顿。
 * 检测到旧内核就在兼容模式卡片上加一句提示 —— 但不是强制、不是禁用，
 * 因为「旧内核」与「用户实际体验」不是严格等价的（有人就愿意为功能忍受卡顿）。
 * 提示是**信息**，不是**限制**。
 */
class ModeSelectActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "ModeSelect"
        /** 低于这个 Chrome 主版本号时提示"可能更适合兼容模式"。 */
        const val OLD_WEBVIEW_MAJOR = 120
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mode_select)

        findViewById<View>(R.id.cardCompat).setOnClickListener {
            choose(AppPrefs.UI_MODE_COMPAT)
        }
        findViewById<View>(R.id.cardFull).setOnClickListener {
            choose(AppPrefs.UI_MODE_FULL)
        }

        maybeShowOldWebViewHint()
    }

    /**
     * 检测系统 WebView 的 Chrome 主版本，旧内核时在兼容模式卡片上加一行提示。
     *
     * 为什么用 WebView 的 UA 而不是 PackageManager 查版本号：
     *   UA 里的 Chrome/xxx 就是**实际运行时**用的内核版本，而包版本号还得自己映射
     *   （WebView 包版本 ≠ Chrome 内核版本，两者数字完全不同）。
     *   取 UA 是一次性字符串操作，代价可忽略；取不到就当作"不知道"，不显示提示。
     */
    private fun maybeShowOldWebViewHint() {
        // ⚠️ 必须用 Throwable 而不是 Exception：
        //    WebView provider 不可用时可能抛 Error（UnsatisfiedLinkError 等），
        //    catch (e: Exception) 接不住，会直接崩到桌面。
        //
        // ⚠️ 这个检测纯粹是「锦上添花」—— 检测失败只是少一行提示，
        //    绝不能让它把选择页带崩。所以范围放到最大、且立刻销毁临时 WebView。
        var probe: WebView? = null
        try {
            probe = WebView(applicationContext)
            val ua = probe.settings.userAgentString
            if (ua != null) {
                val m = Regex("Chrome/([0-9]+)").find(ua)
                val major = m?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (major != null) {
                    Log.i(TAG, "webview chrome major=" + major)
                    if (major < OLD_WEBVIEW_MAJOR) {
                        findViewById<View>(R.id.compatHint).visibility = View.VISIBLE
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "detect webview version failed: " + t.javaClass.simpleName)
        } finally {
            try { probe?.destroy() } catch (t: Throwable) { /* 销毁失败无所谓 */ }
        }
    }

    /**
     * 选完模式后进目标界面。
     *
     * ⚠️ 必须给 WebViewActivity 一个非空 launchUrl：它把 launchUrl 为空视为
     * "被外部应用直接拉起"并立刻 finish（见 WebViewActivity.onCreate）。
     * 只传 EXTRA_RESUME_MODE 会导致目标页秒退、本页也已 finish ⇒ 栈空 ⇒ 回桌面。
     * resume 语义下用服务端根路径即可（cookie 已在 CookieManager 里）。
     */
    private fun choose(mode: String) {
        if (!AppPrefs.setUiMode(this, mode)) {
            Log.w(TAG, "persist ui mode failed, continuing for this session")
        }
        if (mode == AppPrefs.UI_MODE_COMPAT) {
            startActivity(Intent(this, CompatActivity::class.java))
            finish()
            return
        }
        val account = AccountStore.load(this)
        val rootUrl = account?.host?.let { host -> ApiClient.parseHost(host)?.let { it.baseUrl + "/" } }
        if (rootUrl == null) {
            Log.w(TAG, "no stored host, back to login")
            startActivity(Intent(this, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_AUTO_LOGIN, false))
            finish()
            return
        }
        startActivity(Intent(this, WebViewActivity::class.java).apply {
            putExtra(WebViewActivity.EXTRA_LAUNCH_URL, rootUrl)
            putExtra(WebViewActivity.EXTRA_RESUME_MODE, true)
        })
        finish()
    }
}
