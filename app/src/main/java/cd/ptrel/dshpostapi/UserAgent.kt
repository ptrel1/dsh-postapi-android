package cd.ptrel.dshpostapi

/**
 * User-Agent 的唯一来源（v0.9.1，ISSUE-20261116-01 P2）。
 *
 * ## 为什么必须集中到一个常量
 *
 * 插件把会话指纹绑在 UA 的 **sha256 前 16 位**上（lib/index.js 的 verifyFingerprint）。
 * 登录、读历史、发消息、下载、检查更新 —— 任何一处 UA 字面量不同，
 * 那次请求的哈希就对不上，落进 ua-hash-drift 分支。
 *
 * 20261116 实测：本文件出现前，三处各写各的：
 *   CompatApi.MOBILE_UA      ... DSH-PostAPI-Android
 *   ApiClient 登录           ... DSH-PostAPI-Android/0.1
 *   UpdateClient             ... DSH-PostAPI-Android/<versionName>
 * 三个不同哈希，等于每次登录、每次请求都在换指纹。
 * 虽然 v1.9.4 已修掉「伪回环一律 deny」使其不再直接踢人，
 * 但一直在漂移分支上走，等于把指纹校验架空。
 *
 * ## 为什么带版本号
 *
 * 日志里能直接看出是哪个 App 版本发的。排查「陌生会话是谁建的」时，
 * 光看 `127.0.0.1` + 通用 Android UA 根本分不清是 App、浏览器还是脚本。
 *
 * ⚠️ 改这个常量会改变指纹哈希。改完后**必须重新登录** ——
 *   旧会话绑的是旧哈希，新版本发出新哈希会走漂移分支。
 *   这是可接受的：升级后重新登录一次是常态。
 */
object UserAgent {

    /**
     * 所有请求统一使用的 UA。
     *
     * 形态刻意保持与 Android WebView 同族（含 `Android` + `Mobile`），
     * 这样 uaDeviceClass() 判成 `mobile`，与完整模式 WebView 的类别一致 ——
     * 两种模式共用同一会话 cookie 时不会因类别不符被拒。
     *
     * 结尾的 `DSHApp/<版本>` 是给日志用的标识，不影响类别判定。
     */
    val VALUE: String =
        "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 " +
            "DSHApp/" + BuildConfig.VERSION_NAME
}
