package cd.ptrel.dshpostapi

import android.app.Application
import java.util.concurrent.Executors

/**
 * 进程入口：只为「账号本地记忆」预热加密存储。
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
 *   - 只做一件事，且这件事全程被 try/catch 包住（AccountStore.warmUp 内部已兜底，
 *     这里再包一层是防"预读之外"的意外，例如线程池拒绝）；
 *   - 用单线程池而不是 Thread：单线程池的线程默认是守护语义之外的普通线程，
 *     但线程池可以复用（若将来还有别的启动任务，不必再开线程）；
 *   - 不复用 ApiClient 的线程 —— 那是 OkHttp 的内部线程池，不该被非网络任务占用。
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
    }

    private companion object {
        /** 单线程即可：预热是一个串行的小任务，不需要并发。 */
        val IO = Executors.newSingleThreadExecutor { r ->
            Thread(r, "dsh-account-warmup").apply { isDaemon = true }
        }
    }
}
