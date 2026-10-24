package cd.ptrel.dshpostapi

import android.app.Activity
import android.content.DialogInterface
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File

/**
 * 「检查更新」的 UI 编排：静默检查 → 弹窗 → 下载（带进度）→ 校验 → 安装。
 *
 * 三条贯穿全文件的约束（改代码前先读）：
 *   1. **检查阶段失败一律静默**：只写 Log，不 Toast、不弹窗。用户没有主动要求检查更新，
 *      打扰是负收益（尤其在内网/离线场景，每次登录都弹一次"检查更新失败"很烦）。
 *   2. **不强制更新**：弹窗只有「稍后」与「立即更新」。没有"退出 App"这种选项。
 *   3. **cookie 不落日志**：进入这里的 cookie 只用于发请求，任何日志都不打印它的值。
 *
 * 生命周期：所有入口都持有 Activity 引用，回调前检查 `isFinishing || isDestroyed`，
 * 避免"用户退出后回调仍去操作已销毁的 Activity"。
 */
object Updates {

    private const val TAG = "Updates"

    /**
     * 上次自动检查的时间（SystemClock.uptimeMillis；不持久化，见下）。
     *
     * v0.4.0 之前这里是 5 秒防抖，用途是"登录后 + WebView 首次加载后"两个入口互相去重。
     * 现在入口增加到三处（App 启动 / 登录成功 / WebView 首次加载），且用户反馈
     * 「频繁启动反复请求」—— 改成 [AUTO_CHECK_MIN_INTERVAL_MS] 级节流。
     *
     * 为什么用 uptimeMillis 而不是墙钟：用户改系统时间不应该让我们多查或少查一次。
     * 为什么不持久化：进程结束它自然归零，正好符合"每次冷启动至少检查一次"，
     * 又不会在用户频繁杀进程重开时把服务端刷爆 —— 次数由用户的真实操作决定。
     */
    @Volatile
    private var lastAutoCheckAt: Long = 0L

    /** 自动检查的最小间隔：30 分钟（用户指定的防抖值）。 */
    private const val AUTO_CHECK_MIN_INTERVAL_MS = 30L * 60L * 1000L

    /**
     * "会话还没读出来"时的重试延迟表（见 [scheduleRetryForPendingSession]）。
     *
     * v0.4.4：从"单次 800ms"改成"两次（800ms / 1500ms）"。
     * 原因：冷启动时加密存储的首次解密实测 200~500ms，但弱机（低端 SoC + 冷 Keystore）
     * 可能在 1s 以上；单次重试一旦错过，本进程就再没有"会话一就绪就查"的机会，
     * 只剩 WebView onPageFinished 那条兜底（而它在离线 / 错误页场景根本不会触发）。
     * 间隔取**递增**而不是等间隔：漏掉第一次的典型原因是"还差一点没读完"，
     * 递增能覆盖更宽的分布，又不会在"确实没有账号"时多刷日志。
     */
    private val PENDING_SESSION_RETRY_DELAYS_MS = longArrayOf(800L, 1500L)

    /**
     * 本进程内是否**已经拿到过一次有效结果**（"同一次启动只查一次"的保证）。
     *
     * ⚠️ v0.4.4 语义修正 —— 这正是 0.4.3「自动更新坏了」的根因：
     *   0.4.3 及以前是「**发起过**就置 true」，于是一次冷启动的 401（那一刻用户还没登录、
     *   服务端会话 cookie 还没种好）就把整个进程的检查名额**永久占掉**；之后登录成功、
     *   WebView 加载完成，三处入口全被 `if (autoCheckedThisProcess) return` 挡住，
     *   用户感受就是"自动更新坏了"。
     *   现在改为「**只有确实拿到服务端清单**才算用掉名额」，失败按 [shouldKeepQuotaConsumed]
     *   决定是否回滚。
     *
     * 为什么用 @Volatile：写只发生在主线程（回调经 Handler 投递回主线程），读可能来自任意线程，
     * 单写场景下 volatile 足够。
     */
    @Volatile
    private var autoCheckedThisProcess = false

    /** 正在进行的检查/下载；避免用户连点「立即更新」产生两条下载。 */
    @Volatile
    private var active: Cancelable? = null

    private interface Cancelable {
        fun cancel()
    }

    /**
     * 静默检查更新（自动路径）。
     *
     * 调用时机（v0.4.0，问题 ① 的用户要求：**冷启动一定要检查**，不管走哪条路）：
     *   ① DshApp.onCreate —— 进程启动的最早时机；
     *   ② LoginActivity 登录成功 / 自动重登成功之后；
     *   ③ WebViewActivity 首次 onPageFinished（页面确认能打开，cookie 一定有效）。
     *
     * 三个入口都走本方法的节流（30 分钟 + 本进程只查一次），所以叠加调用只会真正发一次请求。
     *
     * ⚠️ 与 v0.3.x 的关键差别：**可以**在 App 启动时调用。
     *    启动那一刻会话可能还没从加密存储读出来，此时不会静默放弃，
     *    而是等 [PENDING_SESSION_RETRY_MS] 后再试一次（见 scheduleRetryForPendingSession）。
     */
    fun checkQuietly(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        // v0.3.0：会话上下文改为从加密存储现读（UpdateSession 是 AccountStore 的只读代理）。
        // 仍然是"没有会话就静默返回"——首装 / 已退出登录时它是常态，不是错误。
        val session = UpdateSession.get(activity)
        if (session == null) {
            // ★ v0.4.0（问题 ①）：会话**尚未落盘**时不再直接放弃，而是稍后重试一次。
            // 典型场景 = 冷启动：DshApp.onCreate 的自动检查与 LoginFlow 的启动决策同时发生，
            // 后者紧接着读存储并（cookie 有效时）直接进 WebView —— 那一刻 AccountStore
            // 可能还没从 Keystore 里读出来，于是本次检查被跳过，
            // 用户看到的就是"重开 App 不检查更新"。
            scheduleRetryForPendingSession(activity)
            return
        }
        runAutoCheck(activity, session)
    }

    /**
     * 自动检查（App 启动 / 登录成功 / WebView 首次加载）：**节流 + 静默**。
     *
     * 三条并存的条件（任一不满足就跳过，且**不消耗**任何额度）：
     *   ① 本进程还没拿到过有效结果（[autoCheckedThisProcess]）；
     *   ② 距上次**成功**检查 >= 30 分钟（[lastAutoCheckAt]，失败会被回滚成 0）；
     *   ③ 有可用的会话 cookie（由调用方保证）。
     *
     * ==================== v0.4.4：名额不再被失败占用 ====================
     * 0.4.3 的病：本方法一进来就 `autoCheckedThisProcess = true`，然后才发请求。
     * 冷启动第一次检查发生在**用户还没登录**（会话 cookie 还没种好）的时刻，
     * 服务端回 401 → 静默返回 → 名额已被占死 → 之后登录成功 / WebView 加载完成
     * 全被挡住 → 用户看到的就是「自动更新坏了」。
     *
     * 0.4.4 的做法（不选"先置位再回滚"，而是干脆"成功才置位"）：
     *   · 置位与时间戳都**只**在拿到 [UpdateInfo] 的那条路径上写；
     *   · 唯一的例外是 503 未配置（[UpdateError.NotConfigured]）—— 那是**稳定状态**，
     *     见 [shouldKeepQuotaConsumed]。
     * 为什么用"成功才置位"而不是"先置位、失败再回滚"：
     *   回滚要记住"我进来时它是什么值"，而 checkQuietly 有三个入口可能在 5 秒超时窗口内
     *   并发进入（DshApp / LoginActivity / WebView onPageFinished 都跑在主线程，但回调时机
     *   分散），"置位→回滚"中间存在一段其他调用方看得见的假占位窗口，
     *   回滚时还可能把**别人**刚写进来的成功状态抹掉。成功路径置位则没有这个竞态。
     *
     * 结果处理按用户确认的方案（v0.4.0，问题 ②/④）：有新版本 → 弹更新框；
     * 已是最新 → **完全静默**，只写日志。用户没主动问，就不要告诉他"已是最新"。
     */
    private fun runAutoCheck(activity: Activity, session: UpdateSession.Current) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (autoCheckedThisProcess) {
            Log.i(TAG, "auto check: quota already used in this process, skip")
            return
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (lastAutoCheckAt != 0L && now - lastAutoCheckAt < AUTO_CHECK_MIN_INTERVAL_MS) {
            Log.i(TAG, "auto check: within 30min cooldown (last success), skip")
            return
        }
        // ⚠️ 这里**故意不置位** autoCheckedThisProcess / lastAutoCheckAt：
        //    它们代表"本次自动检查已经花掉了"，只有真正拿到结果时才该花掉。
        Log.i(TAG, "auto check: start (quota not yet consumed)")

        fetch(
            activity = activity,
            session = session,
            onUnauthorized = { runCatching { AccountStore.clearCookie(activity) } },
        ) { owner, info, error ->
            if (error != null || info == null) {
                onAutoCheckFailed(owner, error)
                return@fetch
            }
            // ---- 成功路径（无论有无新版本）：这才算用掉名额 ----
            autoCheckedThisProcess = true
            lastAutoCheckAt = android.os.SystemClock.uptimeMillis()
            UpdatePrefs.reconcile(owner, info.code)
            if (info.code <= BuildConfig.VERSION_CODE) {
                // 静默：自动检查发现"已是最新"时不打扰用户（问题 ②/④ 的确认方案）。
                Log.i(
                    TAG,
                    "update check: up to date (remote=" + info.code + " local=" +
                        BuildConfig.VERSION_CODE + "), quota consumed for this process",
                )
                return@fetch
            }
            if (!UpdatePrefs.shouldPrompt(owner, info.code)) {
                Log.i(TAG, "update check: remote=" + info.code + " skipped by user")
                return@fetch
            }
            if (!info.apkReady) {
                // 清单在、包没上传：静默（这是发布流程的中间态，用户无能为力）。
                Log.i(TAG, "update check: remote=" + info.code + " apkReady=false, not prompting")
                return@fetch
            }
            showPrompt(owner, info)
        }
    }

    /**
     * 自动检查失败时的收尾：决定这一趟**是否占用名额**，并把原因与结论写进日志。
     *
     * 为什么失败要分三六九等（v0.4.4）：把"这次查不到"和"以后也不用查"混为一谈，
     * 就是 0.4.3 那个 bug 的成因。判据见 [shouldKeepQuotaConsumed]。
     *
     * 全程静默：只写日志，不 Toast、不弹窗 —— 用户没有主动要求检查更新。
     */
    private fun onAutoCheckFailed(activity: Activity, error: UpdateError?) {
        val reason = error?.debugText() ?: "empty response (no UpdateInfo, no error)"
        // 没有 error 也没有 info：UpdateClient 的契约上不该发生，但真发生了就不能假装成功，
        // 按"可重试"处理（不占名额）—— 宁可下次再查一次，也不要静默失效。
        val keepQuota = error != null && shouldKeepQuotaConsumed(error)
        if (keepQuota) {
            // 只有 503 未配置会走到这里：稳定状态，反复查没有意义，本次把名额用掉。
            autoCheckedThisProcess = true
            lastAutoCheckAt = android.os.SystemClock.uptimeMillis()
            Log.i(
                TAG,
                "update check: " + reason + " (normal when distribution is off)" +
                    " -> quota consumed, no further auto check in this process",
            )
            return
        }
        // ★ 核心修复：不占名额。下一次入口（登录成功 / WebView 首次加载 / 会话重试）
        //   仍会真的发请求。lastAutoCheckAt 不动 —— 它只记录"上次成功"。
        Log.w(
            TAG,
            "update check failed: " + reason +
                " -> quota NOT consumed (will retry on next entry), lastAutoCheckAt=" +
                lastAutoCheckAt,
        )
        if (activity.isFinishing || activity.isDestroyed) return
        // 失败后如果会话仍然为空（典型 = 冷启动时 401 顺带清了 cookie，或本来就还没读出来），
        // 再走一次"等会话就绪"的重试：这是把"用户还没登录完"的情形彻底捞回来的兜底。
        if (UpdateSession.get(activity) == null) {
            scheduleRetryForPendingSession(activity)
        }
    }

    /**
     * 这次失败是否应当**仍然占用**本进程的自动检查名额。
     *
     * 判据只有一条：**这个失败是不是一个不会自己变好的稳定状态？**
     *   稳定 → 占名额（反复查没好处的请求体量，换不来任何东西）；
     *   不稳定 → 不占（等条件变好再查一次，这才是用户要的"自动更新"）。
     *
     * | 失败 | 占用? | 理由 |
     * | :--- | :---- | :--- |
     * | Http 401 / 403 | 否 | 最常见的时序问题：冷启动那一刻会话 cookie 还没种好 / 已过期。
     * |                 |     | 用户登录后 cookie 就有了，同一个进程内必须还能再查。 |
     * | Network / Timeout | 否 | 断网、地铁、服务端一时连不上 —— 下次可能就好了。 |
     * | Http 5xx | 否 | 服务端临时故障，等它恢复。 |
     * | Http 其它 4xx | 否 | 路由/反代配置类问题（404/502 之外的），保守按可重试处理：
     * |               |     | 占用名额的代价是"用户这次启动永远看不到更新"，比多打一次请求贵得多。 |
     * | BadResponse | 否 | 响应不是预期 JSON —— 多为版本不匹配或反代改写，可能随部署变化。 |
     * | NotConfigured(503) | **是** | 服务端**明确**说"没配置分发"：这是稳定状态，
     * |                    |        | 不是故障。反复查既不会变好，又会在每次入口白打一次请求。 |
     * | 其它（下载类错误不该出现在检查阶段）| 否 | 保守不占，宁可多查一次。 |
     *
     * 为什么 503 不回滚而 401 回滚，一句话：
     *   **401 是"时机不对"（等下就会对），503 是"现在就是这样"（等多久都一样）。**
     */
    private fun shouldKeepQuotaConsumed(error: UpdateError): Boolean =
        error is UpdateError.NotConfigured

    /**
     * 用户**主动**点菜单里的「检查更新」（v0.4.0，问题 ②）。
     *
     * 与 [checkQuietly] 的差别只有一个，但很关键：**结果一定有可见反馈**。
     *   - 有新版本 → 更新对话框；
     *   - 已是最新 → Toast「已是最新版本」，约 3 秒自动消失（Toast.LENGTH_SHORT）；
     *   - 检查失败 → 明确告知失败原因（用户主动点了，静默等于"点了没反应"）。
     *
     * 不受 30 分钟节流限制：用户明确要求查，就必须真的发一次请求。
     */
    fun checkManually(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val session = UpdateSession.get(activity)
        if (session == null) {
            // 没有 cookie 就没法带鉴权查（也下不了包）：直接说清要重新登录。
            Toast.makeText(activity, R.string.update_need_relogin, Toast.LENGTH_LONG).show()
            return
        }
        Log.i(TAG, "manual check: start")

        fetch(
            activity = activity,
            session = session,
            onUnauthorized = { runCatching { AccountStore.clearCookie(activity) } },
        ) { owner, info, error ->
            if (error != null) {
                Log.w(TAG, "manual check failed: " + error.debugText())
                Toast.makeText(
                    owner,
                    owner.getString(R.string.update_check_failed, error.debugText()),
                    Toast.LENGTH_LONG,
                ).show()
                return@fetch
            }
            if (info == null) {
                Toast.makeText(owner, R.string.update_check_failed_empty, Toast.LENGTH_LONG).show()
                return@fetch
            }
            UpdatePrefs.reconcile(owner, info.code)
            if (info.code <= BuildConfig.VERSION_CODE) {
                Log.i(TAG, "manual check: up to date (remote=" + info.code + " local=" + BuildConfig.VERSION_CODE + ")")
                // 用户主动点检查 ⇒ 一定有可见反馈。用 Toast 而不是对话框：
                // 3 秒自动消失、不打断正在进行的操作（问题 ④ 的用户要求）。
                Toast.makeText(
                    owner,
                    owner.getString(R.string.update_up_to_date, BuildConfig.VERSION_NAME),
                    Toast.LENGTH_SHORT,
                ).show()
                return@fetch
            }
            if (!info.apkReady) {
                Toast.makeText(owner, R.string.update_error_not_ready, Toast.LENGTH_LONG).show()
                return@fetch
            }
            // 用户主动查到了新版本：无视「稍后」记录，如实弹更新框
            // （否则点了菜单什么都不出现，比不提供入口更糟）。
            showPrompt(owner, info)
        }
    }

    /**
     * 登录成功后的主动检查（v0.4.4，问题 ②）。
     *
     * 与 [checkQuietly] 的唯一差别：**先把本进程的自动检查名额清空**再查。
     *
     * 为什么必须强制清空（而不是指望 ① 的回滚就够）：
     *   冷启动那条链有一次我们自己制造的竞态 ——
     *     ① 启动检查带着"未生效的 cookie"发请求；
     *     ② 服务端回 401；
     *     ③ [fetch] 的 onUnauthorized 顺手 `AccountStore.clearCookie()`；
     *     ④ 与此同时用户手动登录成功（或自动重登返回）并把新 cookie 写进存储。
     *   顺序若是 ③ 晚于 ④，新 cookie 会被 ③ 抹掉 —— 下一次检查就变成"没有会话"，
     *   照样查不成。此时 ① 的回滚逻辑救不了（它连会话都拿不到）。
     *   登录成功这一刻是**唯一能确认"cookie 刚刚有效"的时刻**，把名额清空、
     *   直接查一次，比依赖任何间接推断都可靠。
     *
     * 清空名额会影响什么：只影响"本进程还要不要再自动查"。[lastAutoCheckAt] 不动 ——
     * 它是**上次成功**检查的时间，与登录无关。
     *
     * ⚠️ 残留的已知行为（有意保留，不改）：若本进程内**刚刚成功**查过一次（<30 分钟），
     *    这次登录触发的检查会被 30 分钟节流挡住。那是对的 —— 刚查过没必要再查；
     *    冷启动 401 那条故障链里 lastAutoCheckAt 不会是被写过的（成功才写），
     *    所以本修复覆盖的正是"这次根本没查成功"的情形。
     * 一次登录最多触发一次额外请求，量级可忽略。
     *
     * 静默：结果仍走 [checkQuietly] 那套（有新版本才弹，其它只写日志）。
     */
    fun checkAfterLogin(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        Log.i(TAG, "post-login check: resetting auto-check quota before checking")
        autoCheckedThisProcess = false
        checkQuietly(activity)
    }

    /**
     * 检查更新的公共网络段。
     *
     * 401/403 统一在**这里**处理（清掉存储里的 cookie）：与 WebViewActivity.showError
     * 保持同一口径，否则下次启动会命中"cookie 未过期 → 直接进 WebView"的错误分支。
     */
    private fun fetch(
        activity: Activity,
        session: UpdateSession.Current,
        onUnauthorized: () -> Unit,
        onDone: (Activity, UpdateInfo?, UpdateError?) -> Unit,
    ) {
        UpdateClient.checkUpdate(
            hostBaseUrl = session.hostBaseUrl,
            cookieName = session.cookieName,
            cookieValue = session.cookieValue,
        ) { info, error ->
            if (activity.isFinishing || activity.isDestroyed) {
                return@checkUpdate
            }
            if (error is UpdateError.Http && (error.code == 401 || error.code == 403)) {
                Log.w(TAG, "update check unauthorized, clearing stored cookie")
                onUnauthorized()
            }
            onDone(activity, info, error)
        }
    }

    /**
     * 冷启动时"会话还没读出来"的兜底（问题 ①）。
     *
     * 为什么用固定延迟几次，而不是 Handler 轮询：
     *   · 首次 Keystore 解密实测 200~500ms（弱机更久），轮询会把它变成"最多等几秒"的悬案；
     *   · [PENDING_SESSION_RETRY_DELAYS_MS] 的两次固定延迟覆盖实测分布就够，失败后**不再**重试 ——
     *     启动阶段的检查是"顺手做"，不值得为它引入一条常驻定时器；
     *   · 真正保证"每次都检查"的是另外两个入口（登录成功 / WebView 首次加载）：
     *     它们发生在会话确定可用之后。本重试只是让冷启动更快拿到结果。
     *
     * v0.4.4 的改动：
     *   · 单次 800ms → 两次 800ms / 1500ms（弱机首次解密可能超过 1s，单次容易错过）；
     *   · 入口处额外判断"本进程名额是否已被占"——顺带覆盖"检查失败后补一次"的调用
     *     （见 onAutoCheckFailed），此时名额一定是空的。
     */
    private fun scheduleRetryForPendingSession(activity: Activity) {
        if (autoCheckedThisProcess) return
        scheduleSessionRetry(activity, 0)
    }

    /**
     * 按 [PENDING_SESSION_RETRY_DELAYS_MS] 的第 [index] 档排一次重试。
     *
     * 终止条件（任一满足即不再排下一次）：
     *   · 名额已被占用（本进程已经拿到过结果，或 503 已判定为稳定状态）；
     *   · Activity 已销毁 / 正在结束（不能拿它当回调宿主）；
     *   · 档位用完。
     *
     * ⚠️ Activity 引用只被 Handler 短时持有（最多 800ms + 1500ms），可接受；
     *    真正长命的定时器绝不这样写。
     */
    private fun scheduleSessionRetry(activity: Activity, index: Int) {
        if (index >= PENDING_SESSION_RETRY_DELAYS_MS.size) {
            Log.i(TAG, "auto check: no session after " + PENDING_SESSION_RETRY_DELAYS_MS.size +
                " retries, giving up for this launch (quota still free)")
            return
        }
        val delay = PENDING_SESSION_RETRY_DELAYS_MS[index]
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed({
            if (autoCheckedThisProcess) return@postDelayed
            if (activity.isFinishing || activity.isDestroyed) return@postDelayed
            val session = UpdateSession.get(activity)
            if (session == null) {
                // 这一档还没读出来：排下一档（末档会在上面打出"giving up"）。
                Log.i(TAG, "auto check: session still unavailable after " + delay + "ms, retrying")
                scheduleSessionRetry(activity, index + 1)
                return@postDelayed
            }
            runAutoCheck(activity, session)
        }, delay)
    }

    /** 发现新版本弹窗。[info] 非空且 [UpdateInfo.apkReady] 已为 true。 */
    private fun showPrompt(activity: Activity, info: UpdateInfo) {
        val message = if (info.notes.isBlank()) {
            activity.getString(R.string.update_message_no_notes)
        } else {
            info.notes
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_title, info.version))
            .setMessage(message)
            // 不可取消（点外部不关闭）：误触关闭与「稍后」语义不同，
            // 而「稍后」会写"不再提示该版本"，误触代价太大。
            .setCancelable(false)
            .setPositiveButton(R.string.update_action_update) { _, _ ->
                startDownload(activity, info)
            }
            .setNegativeButton(R.string.update_action_later) { _, _ ->
                UpdatePrefs.markSkipped(activity, info.code)
                Log.i(TAG, "update " + info.code + " skipped by user")
            }
            .create()
        dialog.show()
    }

    /**
     * 下载 + 校验 + 安装。
     *
     * 进度用 "**不可取消的进度对话框 + 百分数文字**" 而不是通知：
     *   - 本流程从用户点「立即更新」开始，全程在前台（下载期间用户就在看着），
     *     通知反而多一道权限（API 33 需要 POST_NOTIFICATIONS）与多一个可见性状态；
     *   - 6MB 通常在数秒~数十秒内完成，进度对话框足够；
     *   - 对话框本身给出"取消"按钮，等于把"可以重试"变成了"用户可控"。
     */
    private fun startDownload(activity: Activity, info: UpdateInfo) {
        val session = UpdateSession.get(activity)
        if (session == null) {
            // 极端情形：登录会话上下文丢了（进程被系统回收后重建了界面）。
            // 这时无法带 cookie 下载，明确告诉用户而不是静默失败。
            Toast.makeText(activity, R.string.update_need_relogin, Toast.LENGTH_LONG).show()
            return
        }
        if (active != null) {
            Log.i(TAG, "download already in progress, ignoring duplicate request")
            return
        }

        val target = UpdateClient.apkTarget(activity, info.code)
        val dialogView: View = LayoutInflater.from(activity).inflate(R.layout.dialog_update_progress, null)
        val progressBar = dialogView.findViewById<LinearProgressIndicator>(R.id.updateProgress)
        val detailText = dialogView.findViewById<TextView>(R.id.updateProgressText)
        progressBar.isIndeterminate = true
        detailText.text = activity.getString(R.string.update_download_starting)

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_title, info.version))
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton(R.string.update_action_cancel, null)
            .create()

        // 先在点击监听里取消下载；对话框关闭由下载回调统一负责，避免"关两次"。
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener {
                active?.cancel()
            }
        }
        dialog.show()

        val call = UpdateClient.download(
            apkUrl = info.resolveApkUrl(session.hostBaseUrl),
            cookieName = session.cookieName,
            cookieValue = session.cookieValue,
            target = target,
            expectedSha256 = info.sha256,
            expectedSize = info.size,
            onProgress = { downloaded, total ->
                if (activity.isFinishing || activity.isDestroyed) return@download
                if (total > 0) {
                    progressBar.isIndeterminate = false
                    progressBar.max = 100
                    progressBar.setProgressCompat(((downloaded * 100) / total).toInt(), true)
                    detailText.text = activity.getString(
                        R.string.update_download_progress,
                        humanSize(downloaded),
                        humanSize(total),
                        ((downloaded * 100) / total).toInt(),
                    )
                } else {
                    progressBar.isIndeterminate = true
                    detailText.text = activity.getString(R.string.update_download_progress_unknown, humanSize(downloaded))
                }
            },
        ) { file, error ->
            active = null
            if (activity.isFinishing || activity.isDestroyed) return@download
            dialog.dismiss()
            if (file != null) {
                Log.i(TAG, "download ok: code=" + info.code + " bytes=" + file.length())
                installOrGuide(activity, file)
            } else if (error != null) {
                if (error == UpdateError.Cancelled) {
                    Log.i(TAG, "download cancelled by user")
                    return@download
                }
                Log.w(TAG, "download failed: " + error.debugText())
                showDownloadError(activity, info, error)
            }
        }
        active = object : Cancelable {
            override fun cancel() {
                call.cancel()
            }
        }
    }

    /** 安装；缺「安装未知应用」授权时先引导去设置页。 */
    private fun installOrGuide(activity: Activity, apkFile: File) {
        when (val result = ApkInstaller.install(activity, apkFile)) {
            ApkInstaller.Result.Started -> Unit
            ApkInstaller.Result.NeedsPermission -> {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.update_permission_title)
                    .setMessage(R.string.update_permission_message)
                    .setCancelable(false)
                    .setPositiveButton(R.string.update_permission_action) { _, _ ->
                        ApkInstaller.openInstallPermissionSettings(activity)
                    }
                    .setNegativeButton(R.string.update_action_later, null)
                    .show()
            }
            is ApkInstaller.Result.Failure -> {
                Log.e(TAG, "install failed: " + result.reason)
                Toast.makeText(
                    activity,
                    activity.getString(R.string.update_install_failed, result.reason),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /** 下载失败弹窗：说清原因 + 给「重试」。不把技术原因删掉（那是排查唯一线索）。 */
    private fun showDownloadError(activity: Activity, info: UpdateInfo, error: UpdateError) {
        val message = when (error) {
            is UpdateError.NoSpace -> activity.getString(
                R.string.update_error_no_space,
                humanSize(error.needBytes),
                humanSize(error.freeBytes),
            )
            is UpdateError.ChecksumMismatch -> activity.getString(R.string.update_error_checksum)
            is UpdateError.Timeout -> activity.getString(R.string.update_error_timeout)
            is UpdateError.Http -> activity.getString(R.string.update_error_http, error.code)
            is UpdateError.ApkNotReady -> activity.getString(R.string.update_error_not_ready)
            else -> activity.getString(R.string.update_error_generic)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_error_title)
            .setMessage(message + "\n\n" + error.debugText())
            .setCancelable(false)
            .setPositiveButton(R.string.update_action_retry) { _, _ ->
                startDownload(activity, info)
            }
            .setNegativeButton(R.string.update_action_close, null)
            .show()
    }

    /** 人类可读的字节数（6.2 MB）。只用于文案，不参与任何判断。 */
    private fun humanSize(bytes: Long): String {
        if (bytes < 0) return "?"
        if (bytes < 1024) return bytes.toString() + " B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(java.util.Locale.US, "%.0f KB", kb)
        val mb = kb / 1024.0
        return String.format(java.util.Locale.US, "%.1f MB", mb)
    }

    /** Activity 销毁时调用：取消在途下载，避免回调打到已销毁的界面。 */
    fun onActivityDestroyed() {
        active?.cancel()
        active = null
    }
}
