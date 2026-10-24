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

    private fun switchMode(mode: String) {
        AppPrefs.setUiMode(this, mode)
        val target = if (mode == AppPrefs.UI_MODE_COMPAT) {
            CompatActivity::class.java
        } else {
            WebViewActivity::class.java
        }
        startActivity(Intent(this, target).apply {
            putExtra(WebViewActivity.EXTRA_RESUME_MODE, true)
        })
        finish()
    }
}
