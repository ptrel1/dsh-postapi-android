package cd.ptrel.dshpostapi.ui.markdown

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cd.ptrel.dshpostapi.R
import cd.ptrel.dshpostapi.data.Res
import cd.ptrel.dshpostapi.ui.common.SoftShape
import cd.ptrel.dshpostapi.ui.theme.LocalChatColors
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive

/**
 * Mermaid 图表。
 *
 * 与官方 Chat Demo 一样用 **WebView + mermaid.js**，而不是手写一个流程图渲染器：
 * mermaid 的语法族太多（流程图 / 时序图 / 甘特 / 状态 / 类图 / 饼图…），
 * 自己实现等于重写一个项目。
 *
 * ### 三条工程约束
 * 1. **脚本本地打包**（`assets/mermaid/view.html` + `mermaid.min.js`），不连 CDN ——
 *    这是个离线优先的客户端，而 CDN 在部分地区还会被挡。
 * 2. **只在真的出现 ```mermaid 时才会走到这里**（由 `collapsibleCodeFence` 分派）。
 * 3. **`securityLevel: 'strict'`**：图形代码来自模型输出（不可信），
 *    必须禁止其中的脚本与点击事件。
 *
 * ### ⚠️ 慢是常态，慢不是坏
 *
 * 每张图都是一个**独立的 WebView**，各自要解析一遍 3.4MB 的 mermaid.js。
 * 一条消息里放四张图，就是四份解析 —— 首屏几秒钟才出图是正常的（真机实测如此）。
 *
 * 所以这里**只用"加载中"表示还没好，绝不用超时判定失败**：
 * 曾经加过 1.8 秒看门狗，慢一点的图会先闪一段源码、再变回图，比白等更糟。
 * **只有页面真的报错**（语法错、脚本没加载）才退回源码，并且把原因写在上面 ——
 * 一块没有解释的白板是最坏的失败。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MermaidDiagram(
    code: String,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var heightPx by remember { mutableIntStateOf(0) }
    var failure by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf(false) }
    var lastPushed by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    /** 页面已经回报过高度 = 图已经画出来了。它决定"加载中"那一层要不要撤掉。 */
    var drawn by remember { mutableStateOf(false) }

    // mermaid 的主题是**初始化参数**，深色模式下要重画，不能只靠 CSS 覆盖
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // 用内容 + 主题做去重键：同一段代码不重复下发，换主题 / 换内容就重画
    val renderKey = "$dark|$code"

    fun push(view: WebView?, force: Boolean = false) {
        if (!ready || failure != null) return
        if (!force && renderKey == lastPushed) return
        lastPushed = renderKey
        // 用 JSON 生成字符串字面量：图形里的引号 / 换行会把手写的脚本打断
        val id = JsonPrimitive(((code.hashCode().toLong() shl 1) or (if (dark) 1 else 0)).toString())
        val script = "render(${JsonPrimitive(code)}, $dark, $id);"
        runCatching { view?.evaluateJavascript(script, null) }
            .onFailure { failure = it.message ?: Res.get(R.string.mermaid_render_failed) }
    }

    if (failure != null) {
        MermaidFallback(code = code, reason = failure, modifier = modifier)
        return
    }

    Box(modifier = modifier.fillMaxWidth()) {
        /*
         * 延迟一拍再建 WebView。
         *
         * LazyColumn 打开会话时会**先组合一批、随即丢弃视口外的那几条**：
         * 那些条目如果当场建 WebView，就是"建完立刻销毁"，既慢又正好撞在
         * 渲染线程读它的窗口上。等这一拍过去还活着，才值得建。
         */
        var attached by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(WebViewCreateDelayMs)
            attached = true
        }

        if (attached) {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (heightPx > 0) {
                            Modifier.height(with(density) { heightPx.toDp() })
                        } else {
                            Modifier.heightIn(min = LoadingHeight)
                        },
                    ),
                factory = { context ->
                    WebView(context).apply {
                        /*
                         * ⚠️ **软件图层。**
                         *
                         * 硬件加速下 WebView 在显示列表里挂一个 GL functor，渲染线程
                         * 画它时要取 surface —— 真机/模拟器上都实测到过
                         * `GLFunctorDrawable::onDraw → SkSurface::getCanvas()` 空指针直接
                         * SIGSEGV（模拟器是软件模拟 GPU，更容易中）。
                         *
                         * 图表是**静态 SVG**，不需要硬件合成；走软件图层彻底绕开这条
                         * 崩溃路径，代价只是滚动时多一次软件重绘。
                         */
                        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                        setBackgroundColor(AndroidColor.TRANSPARENT)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = false
                        settings.allowFileAccess = false
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        isVerticalScrollBarEnabled = false
                        isHorizontalScrollBarEnabled = false
                        overScrollMode = View.OVER_SCROLL_NEVER
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean = true // 图形里不放链接；万一有，也不许跳走

                            override fun onPageFinished(view: WebView, url: String?) {
                                super.onPageFinished(view, url)
                                ready = true
                                push(view, force = true)
                            }
                        }
                        // JS 的 console 与未捕获异常都进 logcat：真机排查只有这一个入口
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                                Log.d(MermaidLogTag, "${message.messageLevel()} ${message.message()}")
                                return true
                            }
                        }
                        // JS 回调在别的线程：一律 post 回主线程再改 Compose 状态
                        addJavascriptInterface(
                            MermaidBridge(
                                // 显式包一层 Runnable：函数类型的值直接传给 Java 的 SAM 形参不可靠
                                post = { block -> post(Runnable { block() }) },
                                applyHeight = { px ->
                                    if (px > 0) {
                                        heightPx = px
                                        drawn = true
                                    }
                                },
                                reportError = { reason -> failure = reason },
                                reportReady = {
                                    ready = true
                                    push(this, force = true)
                                },
                            ),
                            BridgeName,
                        )
                        loadUrl(AssetUrl)
                        webView = this
                    }
                },
                update = { view ->
                    webView = view
                    push(view)
                },
            )
        }

        /*
         * 加载中：图还没画出来时盖一句提示。
         *
         * ⚠️ **刻意是静态文字，没有呼吸/转圈动画。**
         * 动画会让父节点每帧重绘，正好压在 WebView 那一层上，把"渲染线程正在画它"
         * 的窗口撑得更大。
         */
        if (!drawn) {
            MermaidLoading(modifier = Modifier.matchParentSize())
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val view = webView ?: return@onDispose
            webView = null
            /*
             * ⚠️ **不能立刻 destroy()。**
             *
             * WebView 的显示列表里挂着它自己的 GL functor；同一帧里 destroy() 会先把
             * 渲染器释放掉，而渲染线程随后仍按那份显示列表去画 ——
             * 栈上就是 `GLFunctorDrawable::onDraw → SkSurface::getCanvas()` 空指针。
             *
             * LazyColumn 的初始滚动会**先组合一批、随即丢弃视口外的那几条**，
             * 于是"创建即销毁"在打开会话的头两秒里成批发生 —— 正好撞在崩溃窗口上。
             *
             * 所以：先摘掉父子关系让这一帧不再包含它，再等一会儿销毁。
             * 代价是几百毫秒的内存滞留，换的是不闪退。
             */
            runCatching {
                view.stopLoading()
                view.webChromeClient = null
                (view.parent as? ViewGroup)?.removeView(view)
            }
            Handler(Looper.getMainLooper()).postDelayed(
                { runCatching { view.destroy() } },
                WebViewDestroyDelayMs,
            )
        }
    }
}

/** 加载态的一层：与图同区域居中，出图后自然消失（不占额外高度）。 */
@Composable
private fun MermaidLoading(modifier: Modifier = Modifier) {
    val colors = LocalChatColors.current
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.mermaid_loading),
            style = MaterialTheme.typography.labelMedium,
            color = colors.placeholder,
        )
    }
}

/** 真失败时的退路：把源码显示出来，并说清楚**为什么**没画出来。 */
@Composable
private fun MermaidFallback(code: String, reason: String?, modifier: Modifier = Modifier) {
    val colors = LocalChatColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(SoftShape)
            .background(colors.fieldBackground)
            .padding(12.dp),
    ) {
        Text(
            text = stringResource(R.string.mermaid_render_failed),
            style = MaterialTheme.typography.labelMedium,
            color = colors.danger,
        )
        reason?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it.take(200),
                style = MaterialTheme.typography.labelSmall,
                color = colors.placeholder,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Text(
            text = code,
            style = MaterialTheme.typography.bodySmall,
            color = colors.processText,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * 一段 ```mermaid 围栏的完整卡片：可切换「图表 / 代码」。
 *
 * 默认显示图表；只有页面真的报错才自动退回源码（见 [MermaidDiagram]）。
 * 保留「代码」这一档是刻意的：用户想把图改一改、或者把它复制到别处时，
 * 源码必须够得着。
 */
@Composable
fun MermaidBlock(
    code: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current
    var showDiagram by remember(code) { mutableStateOf(true) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(SoftShape)
            .background(colors.fieldBackground),
    ) {
        MermaidHeader(showDiagram = showDiagram, onToggle = { showDiagram = !showDiagram })
        if (showDiagram) {
            MermaidDiagram(
                code = code,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .padding(bottom = 10.dp),
            )
        } else {
            Text(
                text = code,
                style = MaterialTheme.typography.bodySmall,
                color = colors.processText,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** 图表 / 代码 两种形态的切换头（对齐官方的「图表 · 代码」）。 */
@Composable
fun MermaidHeader(
    showDiagram: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.mermaid_label),
            style = MaterialTheme.typography.labelMedium,
            color = colors.processText,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(
                if (showDiagram) R.string.mermaid_show_code else R.string.mermaid_show_diagram,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onToggle)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/**
 * JS ↔ Kotlin 的桥。
 *
 * 只暴露四个**只接收字符串 / 整数**的回调 —— 页面里跑的是模型产出的图表代码，
 * 桥暴露得越少，被利用的面越小。[post] 把它切回主线程再改状态。
 *
 * ⚠️ 属性名与方法名**必须不同**：同名时方法体里的 `onHeight(...)` 解析到的是
 * 方法自己，编译器只会报一句"类型检查出现递归问题"，很难看出是自调用。
 */
private class MermaidBridge(
    private val post: (() -> Unit) -> Unit,
    private val applyHeight: (Int) -> Unit,
    private val reportError: (String) -> Unit,
    private val reportReady: () -> Unit,
) {
    @JavascriptInterface
    fun onHeight(px: Int) = post { applyHeight(px) }

    @JavascriptInterface
    fun onError(message: String) = post { reportError(message) }

    @JavascriptInterface
    fun onReady() = post { reportReady() }

    @JavascriptInterface
    fun onLog(message: String) = Log.d(MermaidLogTag, message)
}

private const val MermaidLogTag = "Mermaid"

private const val BridgeName = "Bridge"

private const val AssetUrl = "file:///android_asset/mermaid/view.html"

/** 还没有高度时的占位高度：别让首屏只有一厘米，出图那一刻整段跳动。 */
private val LoadingHeight = 120.dp

/**
 * 销毁 WebView 前等多久（毫秒）。
 *
 * 只求"这一帧的显示列表已经画完"，不是精确值；800ms 足够跨过几次 vsync，
 * 又短到不会让一屏图的 WebView 同时压在内存里。
 */
private const val WebViewDestroyDelayMs = 800L

/**
 * 组合之后等多久才真正建 WebView（毫秒）。
 *
 * LazyColumn 打开会话时会先组合一批、随即丢弃视口外那几条；等这一拍过去还活着，
 * 才值得为它起一个 WebView（每个 WebView 都是一个独立的渲染进程 + 3.4MB 脚本解析）。
 */
private const val WebViewCreateDelayMs = 160L

/** 感知亮度，用来决定 mermaid 用亮色还是深色主题。 */
private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue
