# DSH PostAPI R8/ProGuard 规则（release 生效）
#
# 原则：能靠代码不写反射就不写 keep 规则。本工程的业务类没有反射入口，
# R8 的默认优化（含类名混淆）是安全的；只有第三方库的反射面需要显式保留。

# OkHttp 4.x + Okio：官方推荐的最小规则集。
# 为什么需要：OkHttp 内部按平台探测 TLS/HTTP2 实现（Platform 类用反射挑选），
# 完整关闭会导致 release 包在部分 ROM 上 TLS 握手失败——这类问题只在真机出现，
# 在 debug（不混淆）里永远复现不了。
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.platform.** { *; }
-keepnames class okio.AsyncTimeout
-keepclassmembers class okhttp3.internal.platform.Platform {
    static <methods>;
}

# org.json 是 Android 平台类（bootclasspath），R8 自带规则，无需 keep。

# 保留行号用于崩溃栈定位（混淆后仍能还原到源文件行）。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
