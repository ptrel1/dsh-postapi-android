// 根构建脚本：只声明插件版本，具体配置在各模块里。
//
// ⚠️ AGP 9 起 Kotlin 支持**内建**，不再需要 org.jetbrains.kotlin.android 插件
//    （用了会直接报错：The 'org.jetbrains.kotlin.android' plugin is no longer
//    required for Kotlin support since AGP 9.0）。
//
//    但 AGP 9 默认绑定的是较旧的 KGP（2.2.x）。Compose BOM 2026.09 的若干
//    artifact 是用 Kotlin 2.4 编译的，低版本编译器读不了高版本元数据。
//    所以按官方推荐做法，用 buildscript classpath 把 KGP 抬到 2.4.10。
//
// 版本组合约束（改任何一个都要三者一起核对）：
//   AGP 9.4.1 要求 Gradle >= 9.0；Kotlin 2.4.10 与 Compose 插件必须同号。
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
    // 不要加 org.jetbrains.kotlin.android —— 见上方注释。
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    // 移植 SimpleChat 的 UI 需要序列化（它的模型层用 @Serializable）。
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10" apply false
}
