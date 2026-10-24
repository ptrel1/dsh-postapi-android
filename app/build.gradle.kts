// app 模块构建脚本
//
// 几条刻意的取舍（改动前先读这里，别把"看起来更现代"当成升级理由）：
//   1. 视图层用 View + ViewBinding，不用 Compose。理由：MVP 只有两个页面，
//      Compose 会额外引入 compose-bom + 编译器插件 + 2 个 runtime 依赖，
//      而 WebView 容器本来就是 View 体系；View 版本能让 APK 更小、启动更快，
//      出错面也更窄。
//   2. 网络只用 OkHttp + Android 自带的 org.json。不引 Retrofit/Moshi/kotlinx-serialization：
//      登录请求只有一个、响应字段只有 5 个，序列化框架带来的收益为负。
//   3. minifyEnabled 在 release 打开，但 proguard-rules.pro 只做占位 + 保 OkHttp 反射面。
//      本项目没有反射扫描业务类，所以不需要大段 keep 规则。
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cd.ptrel.dshpostapi"
    compileSdk = 35

    defaultConfig {
        applicationId = "cd.ptrel.dshpostapi"
        // minSdk 24：WebView 的 CookieManager.flush() 在 API 21+ 可用，
        // 但 setAcceptThirdPartyCookies 与 WebView 现代 API 的稳定线在 24。
        // 24 覆盖 Android 7.0 及以上（2026 年仍在线的主流机型全在内）。
        minSdk = 24
        targetSdk = 35
        // v0.4.0：修 4 个交互问题（用户实测反馈）。
        //   ① 冷启动也检查更新（之前只在登录 / WebView 首次加载时查）；
        //   ② 「更新后又提示需要更新」—— 记录 lastKnownLatestCode，本机已最新时清 skipped_code，
        //      并区分"自动检查（已最新静默）"与"主动检查（Toast 已是最新）"；
        //   ③ 退出登录只清会话、保留地址/用户名/密码（之前 clearAll 把账号一起清了）；
        //   ④ 「已是最新」用 Toast，自动消失。
        // 为什么是 Minor 而不是 Patch：退出登录的语义变了（不再清账号），
        // 且新增了用户可见的菜单入口；升级后行为与 v0.3.x 有明显差别，不是纯修复。
        // v0.4.3（Patch）：改 windowSoftInputMode 为 adjustPan + 关掉 WebViewActivity 的 ime padding。
        // 为什么是 Patch：只修 bug（打字重排），没有新增用户可见功能、没有语义变化。
        // 顺带附了一条 BuildConfig.DEBUG 下的临时布局诊断日志（真机排查用，见 WebViewActivity）。
        // v0.4.4（Patch）：修「冷启动 401 后整个进程内不再检查更新」。
        //   ① Updates.runAutoCheck 改为「拿到有效结果才占用本进程名额」；失败按错误类型区分——
        //     401/网络/超时/5xx 等一律回滚，只有 503 未配置（稳定状态）占用名额；
        //   ② 登录成功后改调 Updates.checkAfterLogin（清空名额后静默查一次）；
        //   ③ 冷启动「会话还没读出来」的重试从 1 次(800ms) 改为 2 次(800ms/1500ms)。
        // 为什么是 Patch：纯 bug 修复，无新增用户可见功能、无契约/存储格式变化。
        // v0.4.6（Patch）：WebView 流式返回卡顿优化（真机确认：同机 Edge 不卡、App WebView 卡）。
        //   ① onPageFinished 注入 containment CSS（滚动容器/内容区/输入区/排队区隔离布局范围）；
        //   ② setLayerType(LAYER_TYPE_HARDWARE) 强制硬件合成；
        //   ③ databaseEnabled=true → false（WebSQL 已废弃，DSH 只用 localStorage）。
        //   逃生阀：URL 加 ?noperf=1 全关、?nohw=1 只跳过硬件层（白屏/花屏时急救）。
        // 为什么是 Patch：纯渲染层优化与一处废弃开关，无新增用户可见功能、
        //   无契约/存储格式变化；逃生阀是 URL 参数，不影响既有行为。
        // v0.4.7（Patch）：按 Enter 发送后自动收起键盘（用户反馈「发送之后键盘没有自动收起」）。
        //   ① onPageFinished 注入 window.__dshSendWatch：capture 阶段监听 keydown，
        //      只认输入区（[data-composer-seat]）里的 Enter，排除 Shift+Enter 与输入法组词中的 Enter，
        //      命中时记 Date.now()；
        //   ② App 侧用 500ms 轮询读 __dshLastSend，发现新时间戳就 hideKeyboard()。
        //   为什么不用 DOM observer / 每帧轮询：流式返回时消息列表每秒新增几十个节点，
        //      高频回调会加重正在修的卡顿；500ms 对「收键盘」这个动作而言延迟无感。
        //   代价（用户已接受）：只覆盖键盘 Enter，覆盖不到「点发送按钮」。
        //   逃生阀：URL 加 ?nosendwatch=1 完全不装（对照测试用）。
        // v0.4.8（Patch）：右侧抽屉加「诊断模式」开关（排查「切换会话后侧栏自动收起失效」）。
        //   ① 抽屉里加 MaterialSwitch（默认关、SharedPreferences 明文持久化、跨启动保持）；
        //   ② 开启后调 window.__postapiSidebarDiag.enable()，关闭调 disable()
        //      （接口由插件 dsh-postapi-bridge v1.7.1 提供）；
        //   ③ onPageFinished 后**轮询**确认接口就绪（10 次 × 300ms），就绪即 enable 一次 ——
        //      整页重载会让插件重新 apply，旧状态不落地，必须每次重来；
        //   ④ 开启时抽屉里显示一行提示（页面右上角有诊断浮层、排查完请关闭）。
        //   没做 URL 参数方案（?sidebardiag=1）：launchUrl 带一次性 token，
        //   官方门禁要求"恰好一个参数"，追加参数有把主流程登录打回去的风险 ——
        //   详见 WebViewActivity 的「v0.4.8 诊断模式」小节。
        // 为什么是 Patch：新增的是一个排查开关（默认关闭、普通用户无感），
        //   不改存储契约/不改变既有交互路径；开关状态是新增的独立 key。
        // v0.4.11（Patch）：修 Android 10 键盘完全不上移 + 抽屉去掉「更多」标题 + 补「切换桌面版」。
        //   ① 键盘（真因）：v0.4.3/0.4.5/0.4.10 三种让位方式在 Huawei VRD-AL10 / Android 10 上
        //      全部无效 ⇒ 三条路的唯一共同依赖是 ime.bottom，该值在 API 29- 上恒为 0。
        //      根因是 WindowInsetsHelper 无条件 setDecorFitsSystemWindows(false)：
        //      API 29- 上它只关掉了系统避让，并不保证 ime inset 被上报，系统与我们两头落空。
        //      ⇒ 按 API 分级：30+ 保持接管（translationY，v0.4.5 成果不动）；
        //                     29- 不接管，交还系统（fitsSystemWindows + adjustResize）。
        //      同时把布局诊断重写为「一次打印 ime/缩放后高度/屏幕高度/容器尺寸 + verdict」，
        //      因为只看 ime.bottom 分不清「insets 拿不到」与「系统已缩窗口」这两种相反情况。
        //   ② 抽屉去掉「更多」标题（只有 4 项，标题无信息量）；字符串保留给悬浮按钮的
        //      contentDescription（删了读屏用户会失去入口）。
        //   ③ 补「切换桌面版/移动版」（v0.4.5 派过未交付）：DSH 是纯 CSS 响应式、不看 UA，
        //      切换 = 改 <meta viewport> 的 width（980 ↔ device-width）+ loadWithOverviewMode，
        //      状态存 AppPrefs.desktop_mode（显示偏好，退出登录**不清**）。
        // 为什么是 Patch：两项是 bug 修复/UI 精简；桌面版切换虽是新功能，但只是一个显示开关，
        //   不改存储契约（新增独立 key）、不改变既有交互路径、无 API 变化。
        versionCode = 17
        versionName = "0.4.11"
    }

    buildFeatures {
        viewBinding = true
        // BuildConfig 在 AGP 8 起**默认关闭**。本版「检查更新」需要
        // BuildConfig.VERSION_CODE / VERSION_NAME 与服务端清单的 code / versionName 比对，
        // 因此显式打开。生成的字段来自上面的 defaultConfig，不需要额外配置。
        buildConfig = true
    }

    buildTypes {
        debug {
            // debug 允许明文 HTTP：真机联调时常用 http://<内网IP>:53080 直连 frp 端口。
            // 这一行**只影响 debug**，release 的对应开关在 manifest 里固定为 false。
            isMinifyEnabled = false
            manifestPlaceholders["usesCleartextTraffic"] = "true"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // release 门禁：明文 HTTP 一律禁止（公网入口必须 https）。
            manifestPlaceholders["usesCleartextTraffic"] = "false"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        // 与 compileOptions 对齐，避免 Kotlin/Java 混编时出现不同目标字节码版本。
        jvmTarget = "17"
    }
}

dependencies {
    // 只保留 MVP 真正用到的四个：appcompat / material / constraintlayout 负责 UI，
    // okhttp 负责登录请求。JSON 用 Android 平台自带的 org.json（零依赖）。
    // 注意：不引 androidx.webkit —— MVP 只用到平台 WebView 的公开 API；
    // 若第二版需要可靠地等待 cookie 落地，再引 androidx.webkit 并改用 WebViewCompat。
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // androidx.core 提供 WindowInsetsCompat / ViewCompat / WindowCompat，
    // 是系统栏留白（WindowInsetsHelper）的直接依赖。
    // 为什么显式声明：appcompat 会**传递**带入 core-ktx，那属于"运气"；
    //   · 传递版本会随 appcompat 升级而漂移（1.13.0 之前没有某些 API）；
    //   · 我们直接 import 该库的类型，就应当直接声明依赖 ——
    //     否则上游某天调整依赖树，编译会在我们毫无改动时突然失败。
    implementation("androidx.core:core-ktx:1.13.0")
    // androidx.security:security-crypto —— 账号本地记忆的加密存储。
    //
    // 为什么是 1.1.0 而不是 1.0.0 / 1.1.0-alpha06：
    //   1.1.0 是 **2025-07 发布的稳定版**（不是 alpha），也是当前最后稳定版。
    //   ⚠️ 该类在该版本被标记 @Deprecated（官方计划迁移到新库，但承诺继续安全维护），
    //   编译会有 deprecation 警告 —— AccountStore.kt 顶部有 @file:Suppress 与完整理由，
    //   看到警告不要随手换成 alpha 版新库或手写 Keystore 加密。
    //
    // 传递依赖：com.google.crypto.tink:tink-android:1.8.0（+ gson）。
    //   这些都是必要的，不要 exclude —— tink 负责密钥与 AEAD，去掉它库就不工作。
    implementation("androidx.security:security-crypto:1.1.0")
}
