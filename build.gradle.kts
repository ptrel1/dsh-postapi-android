// 根构建脚本：只声明插件版本，具体配置在各模块里。
//
// 版本组合是保守稳定档（不是实验版本）：
//   AGP 8.5.2 / Kotlin 2.0.21 / Gradle 8.7 / compileSdk 35
//   —— AGP 8.5 要求 Gradle >= 8.7，Kotlin 2.0.x 与 AGP 8.5 兼容。
// 升级其中任何一个都必须三者一起核对（见 README「工具链版本」一节）。
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Kotlin 2.0 起 Compose 编译器随 Kotlin 发布，版本与 kotlin 插件严格一致。
    // 漏写这一项的现象是编译期报 "This version (x) of the Compose Compiler requires
    // Kotlin version y" —— 两者必须同号，不可各自升级。
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
