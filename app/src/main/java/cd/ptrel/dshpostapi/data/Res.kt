package cd.ptrel.dshpostapi.data

import android.app.Application
import androidx.annotation.BoolRes
import androidx.annotation.StringRes

/**
 * 非 composable 侧的取词入口（移植自 SimpleChat 的 data/AppText.kt，已简化）。
 *
 * composable 里一律用 `stringResource(...)`；ViewModel、数据层要拼**用户看得见**
 * 的文字时才用这里。
 *
 * ## 与原版的差别
 *
 * 原版经 LocaleHelper 现算 context（支持应用内切换语言）。本项目只有中文，
 * 直接用 Application 的 resources —— 少一层依赖，也少一个「忘了 init」的失败点。
 *
 * ## 必须在 Application.onCreate 里 init 一次
 *
 * ⚠️ **纯 JVM 单测不要走到这里**（没有 Android Resources，会直接抛）。
 * 被单测覆盖的纯函数应把文案当参数收进来，而不是在里面 getString。
 */
object Res {

    @Volatile
    private var app: Application? = null

    fun init(application: Application) {
        app = application
    }

    fun get(@StringRes resId: Int, vararg args: Any): String {
        val a = checkNotNull(app) { "Res 还没 init —— Application.onCreate 里漏了 Res.init(this)" }
        return if (args.isEmpty()) a.getString(resId) else a.getString(resId, *args)
    }

    /** 读布尔资源。 */
    fun bool(@BoolRes resId: Int): Boolean {
        val a = checkNotNull(app) { "Res 还没 init —— Application.onCreate 里漏了 Res.init(this)" }
        return a.resources.getBoolean(resId)
    }
}
