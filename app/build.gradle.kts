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
        // v0.3.0：账号本地记忆（加密）。versionCode 3 → 4。
        // 为什么是 Minor 而不是 Patch：新增了持久化能力（EncryptedSharedPreferences +
        // 自动重登 + 退出登录），旧版本升级上来后行为变化明显（不再每次都要登录）。
        versionCode = 4
        versionName = "0.3.0"
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
