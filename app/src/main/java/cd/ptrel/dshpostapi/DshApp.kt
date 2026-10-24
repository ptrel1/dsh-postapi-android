package cd.ptrel.dshpostapi

import android.app.Application.ActivityLifecycleCallbacks
import android.app.Application
import java.util.concurrent.Executors

/**
 * 进程入口：预热加密存储 + **冷启动检查一次更新**（v0.4.0，问题 ①）。
 *
 * ==================== 为什么需要 Application 这个类 ====================
 * EncryptedSharedPreferences 的**首次** create 要在 Android Keystore 里生成
 * AES-256-GCM 主密钥（Tink keyset），实测 200~500ms（低端机更久）。
 * 如果放在 LoginActivity.onCreate 里同步做，用户看到的是"点了图标，白屏半秒"。
 *
 * 所以这里在进程启动时扔到一个后台线程去预热；LoginActivity 随后调用
 * AccountStore.load() 时通常已经读完（命中内存缓存，零等待）。
 *
 * ==================== 为什么在 Application 里这么小心 ====================
 * Application.onCreate 抛异常 = **整个 App 一起崩，且没有任何 UI 可以自救**。
 * 因此：
 *   - 只做两件事（预热存储 + 触发一次更新检查），每件都全程被 try/catch 包住
 *     （AccountStore.warmUp 内部已兜底，
 *     这里再包一层是防"预读之外"的意外，例如线程池拒绝）；
 *   - 用单线程池而不是 Thread：单线程池的线程默认是守护语义之外的普通线程，
 *     但线程池可以复用（若将来还有别的启动任务，不必再开线程）；
 *   - 不复用 ApiClient 的线程 —— 那是 OkHttp 的内部线程池，不该被非网络任务占用；
 *   - 更新检查也由 try/catch 包住，且只注册一个"第一个 Activity 创建时触发一次"的回调。
 *
 * ⚠️ 不要在 manifest 里漏掉 android:name=".DshApp"，否则本类根本不会被实例化，
 *   表现是"预热没生效（启动变慢半秒）"，不会有任何报错 —— 属于静默失效。
 */
class DshApp : Application() {

    override fun onCreate() {
        super.onCreate()
        try {
            IO.execute { AccountStore.warmUp(this) }
        } catch (e: Exception) {
            // 极端情形（进程刚起就 OOM / 线程池被关）：忽略。
            // 预热失败只影响启动速度，不影响功能 —— AccountStore 会在首次 load 时重试。
        }
        startUpdateCheck()
    }

    /**
     * 冷启动的更新检查（v0.4.0，问题 ①）。
     *
     * ==================== 为什么要在 Application 里做 ====================
     * 用户原话：「怎么只有登录的时候才检查更新，重新打开 app 的时候也需要检测更新吧」。
     * 之前只有三个检查点（手动登录成功 / 自动重登成功 / WebView 首次加载），
     * 免密重开（cookie 有效）时**可能一次都不触发** —— 比如 LoginActivity 直接跳 WebView，
     * 而 WebView 那次又恰好因为某种原因没走到 onPageFinished。
     *
     * 放在 Application.onCreate 才能保证"不管走登录页、自动重登、还是直接进 WebView"，
     * 每一次冷启动都检查一次。三条路径的检查共用一个节流（见 Updates.runAutoCheck）：
     *   · 本进程只查一次；
     *   · 距上次自动检查 < 30 分钟则跳过。
     * 所以这里多调用一次**不会**多打一次请求。
     *
     * ==================== 为什么用 ActivityLifecycleCallbacks ====================
     * Updates.checkQuietly 的宿主参数是 Activity，而本方法在 Application 还**没有**任何
     * Activity 创建，所以不直接调它 —— 注册 ActivityLifecycleCallbacks，等第一个
     * Activity 创建出来再触发（那正是"App 真正启动起来"的时刻）。
     *
     * 已知取舍（诚实记录）：
     *   · 冷启动这一刻加密存储可能还没读出来（Keystore 首次解密 200~500ms），
     *     此时 checkQuietly 不会放弃，而是 800ms 后重试一次（见 Updates 里的
     *     scheduleRetryForPendingSession）；
     *   · 检查是异步的（OkHttp + 5s 超时），返回时页面通常已经渲染完，
     *     弹出的更新对话框不会与首帧启动竞争。
     */
    private fun startUpdateCheck() {
        try {
            registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {
                    // 只在本进程的**第一次**界面创建时触发一次，然后立刻注销自己 ——
                    // 不注销的话，用户退出登录再进（同进程新 Activity）会重复走一遍，
                    // 虽然会被节流挡住，但白白多一次日志与一次存储读取。
                    unregisterActivityLifecycleCallbacks(this)
                    try {
                        Updates.checkQuietly(activity)
                    } catch (e: Exception) {
                        // 启动期的检查失败绝不能影响 App：它只是"顺手做"的事。
                    }
                }

                // 其余五个回调本类不需要；空实现即可（接口无默认实现）。
                override fun onActivityStarted(activity: android.app.Activity) = Unit
                override fun onActivityResumed(activity: android.app.Activity) = Unit
                override fun onActivityPaused(activity: android.app.Activity) = Unit
                override fun onActivityStopped(activity: android.app.Activity) = Unit
                override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
                override fun onActivityDestroyed(activity: android.app.Activity) = Unit
            })
        } catch (e: Exception) {
            // Application 里抛异常 = 整个 App 崩且无 UI 可自救。注册失败就不检查，
            // 登录成功 / WebView 首次加载那两处仍会检查。
        }
    }

    private companion object {
        /** 单线程即可：预热是一个串行的小任务，不需要并发。 */
        val IO = Executors.newSingleThreadExecutor { r ->
            Thread(r, "dsh-account-warmup").apply { isDaemon = true }
        }
    }
}
