package cd.ptrel.dshpostapi

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * v0.5.0：兼容模式入口（当前为占位）。
 *
 * 存在的意义：让「模式切换」这条链路**完整可走** —— 用户能选兼容模式、
 * 能进来、能随时切回完整模式。原生聊天界面后续替换掉本页内容即可，
 * 外面的路由与存储不用再动。
 *
 * 退出登录复用 LoginActivity 的既有入口（它已经处理了"只清 cookie、保留账号密码"），
 * 不在这里另写一套，避免两处行为漂移。
 */
class CompatActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v0.6.0：CompatActivity 不再是一个"占位页"，而是一个**分发点** ——
        // 它只做一件事：把用户送进会话列表，然后自己退出。
        //
        // 为什么保留这个空壳而不是让 LoginActivity 直接起 CompatSessionsActivity：
        //   · 兼容模式的入口在 App 里有四五处（登录页、模式对话框、抽屉、CompatActivity 自身），
        //     它们都指向 CompatActivity；把这层留着，改模式内部结构时不用动所有入口。
        //   · 将来若要加"兼容模式的引导页/协议页"，插在这里最自然。
        startActivity(android.content.Intent(this, CompatSessionsActivity::class.java))
        finish()
        return

        @Suppress("UNREACHABLE_CODE")
        setContentView(R.layout.activity_compat)

        findViewById<android.view.View>(R.id.switchToFull).setOnClickListener {
            switchMode(AppPrefs.UI_MODE_FULL)
        }
        findViewById<android.view.View>(R.id.compatLogout).setOnClickListener {
            // 与 WebViewActivity 的退出登录走同一条路：清 cookie 后回登录页。
            AccountStore.clearSession(this)
            startActivity(
                // 与 WebViewActivity 的退出登录完全同一条路（EXTRA_AUTO_LOGIN=false）：
                // 只清 cookie、保留账号密码并预填，但不自动登录 —— 用户刚才明确要退出。
                Intent(this, LoginActivity::class.java)
                    .putExtra(LoginActivity.EXTRA_AUTO_LOGIN, false),
            )
            finish()
        }
    }

    // v0.5.2：切模式必须给 WebViewActivity 一个非空的 launchUrl。
    // 它 onCreate 里有 if (launchUrl.isEmpty()) { finish(); return } —— 那是防
    // 被外部应用直接拉起的护栏。只传 EXTRA_RESUME_MODE 会被它当成
    // 参数不全立刻 finish；而本页也 finish ⇒ 栈空 ⇒ 直接回系统桌面（实测踩过）。
    // resume 语义下用服务端根路径即可：cookie 已在 CookieManager 里。
    private fun switchMode(mode: String) {
        AppPrefs.setUiMode(this, mode)
        if (mode == AppPrefs.UI_MODE_COMPAT) {
            startActivity(Intent(this, CompatActivity::class.java))
            finish()
            return
        }
        val account = AccountStore.load(this)
        val rootUrl = account?.host?.let { host -> ApiClient.parseHost(host)?.let { it.baseUrl + "/" } }
        if (rootUrl == null) {
            android.util.Log.w("CompatActivity", "no stored host, back to login")
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
