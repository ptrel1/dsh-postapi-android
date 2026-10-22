package cd.ptrel.dshpostapi

/**
 * 更新流程的失败分类。
 *
 * 为什么要分类：每一种失败给用户的下一步动作不同 ——
 *   - 检查阶段：一律静默（用户没主动要求，打扰是负收益）；
 *   - 下载阶段：必须说清"能不能重试"，以及重试前要不要先解决别的问题（磁盘/权限）。
 */
sealed class UpdateError {

    /** 网络层失败（DNS / 连接被拒 / TLS / 断网）。[detail] 供日志与排查。 */
    data class Network(val detail: String) : UpdateError()

    /** 超时（检查 5s / 读数据期间 readTimeout）。可重试。 */
    data object Timeout : UpdateError()

    /** 503 或 ok=false：服务端没配置分发。这是**正常状态**，不是故障。 */
    data object NotConfigured : UpdateError()

    /** 其它非 2xx。[body] 只留片段。 */
    data class Http(val code: Int, val body: String) : UpdateError()

    /** 响应不是预期的 JSON 结构。 */
    data class BadResponse(val detail: String) : UpdateError()

    /** 服务端说明 apkReady=false（清单在、包还没上传）。 */
    data object ApkNotReady : UpdateError()

    /** sha256 与清单不符 —— 下载内容被篡改或传输损坏，文件已删除。 */
    data class ChecksumMismatch(val expected: String, val actual: String) : UpdateError()

    /** 磁盘空间不足。[needBytes] / [freeBytes] 用于给出具体数字。 */
    data class NoSpace(val needBytes: Long, val freeBytes: Long) : UpdateError()

    /** 写文件失败（目录不可写、FileProvider 路径不可用等）。 */
    data class Storage(val detail: String) : UpdateError()

    /** 用户取消。 */
    data object Cancelled : UpdateError()
}

/** 给日志用的单行描述。**不包含 cookie / URL 查询串等敏感信息。** */
fun UpdateError.debugText(): String = when (this) {
    is UpdateError.Network -> "network: " + detail
    UpdateError.Timeout -> "timeout"
    UpdateError.NotConfigured -> "HTTP 503 / not configured"
    is UpdateError.Http -> "HTTP " + code + (if (body.isEmpty()) "" else ": " + body)
    is UpdateError.BadResponse -> "bad response: " + detail
    UpdateError.ApkNotReady -> "apk not ready on server"
    is UpdateError.ChecksumMismatch -> "sha256 mismatch expected=" + expected + " actual=" + actual
    is UpdateError.NoSpace -> "no space need=" + needBytes + " free=" + freeBytes
    is UpdateError.Storage -> "storage: " + detail
    UpdateError.Cancelled -> "cancelled"
}
