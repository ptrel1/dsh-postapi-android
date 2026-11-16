package cd.ptrel.dshpostapi.data

import android.content.Context
import android.util.Base64
import java.io.File
import java.util.UUID

/**
 * 图片字节的**落盘存储**。
 *
 * ### 为什么要有它
 *
 * 附件与正文在同一行。Android 的 `CursorWindow` 只有 2MB，一行装不下整张图，
 * 那条会话就再也读不出来（`SQLiteBlobTooBigException`）—— 实测导入的历史里
 * 出现过单行 7.4MB，一点就闪退。
 *
 * 把字节搬到 `filesDir/attachments/` 之后：
 *
 * - 行里只剩几百字节 → **无论多少张图都不会超限**；
 * - 数据库不再被 base64 撑大（228MB 的归档导进来，库从几百兆降到几十兆）；
 * - 导入时那些"单张 / 单条 / 单次"的图片配额可以**全部取消**。
 *
 * ### 约定
 *
 * - 只有 [Attachment.Kind.IMAGE] 落盘；文本附件本来就小，继续内联。
 * - 文件内容是**原始图片字节**（不是 base64），显示/发送时才编码。
 * - 备份导出时按需读回 base64 内联，所以备份格式与文件布局**都没有变**。
 * - 文件缺失（用户清了数据目录）不抛异常：调用方按"原件缺失"处理。
 */
object AttachmentFiles {

    /** 相对 `filesDir` 的目录名。 */
    private const val Dir = "attachments"

    /** 落盘时的分块大小：与导入侧一致，避免自己变成一次大分配。 */
    private const val Chunk = 64 * 1024

    /**
     * 存一份图片字节，返回可以塞进 [Attachment.path] 的文件名。
     *
     * 用 UUID 命名而不是原始文件名：同一张图可能被多条消息引用，
     * 也可能有重名文件；UUID 让"文件名"变成纯标识，不承担语义。
     */
    fun save(context: Context, bytes: ByteArray, mime: String): String? = runCatching {
        val dir = dirOf(context)
        if (!dir.exists() && !dir.mkdirs()) return null
        val name = UUID.randomUUID().toString() + extensionOf(mime)
        File(dir, name).outputStream().use { out -> out.write(bytes) }
        name
    }.getOrNull()

    /** 读回落盘的字节；文件不在返回 null。 */
    fun read(context: Context, name: String?): ByteArray? {
        if (name.isNullOrBlank()) return null
        val file = File(dirOf(context), name)
        if (!file.isFile) return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    /** 删除落盘文件（消息 / 会话被删时调用）。 */
    fun delete(context: Context, name: String?) {
        if (name.isNullOrBlank()) return
        runCatching { File(dirOf(context), name).delete() }
    }

    /**
     * 把落盘附件的字节读回 [Attachment.data]，返回**可以内联**的副本。
     *
     * 两个地方必须过这一步：
     * 1. 构造请求（`toDataUrl()` 只认内联）；
     * 2. 导出备份（备份里图片是 base64 内联的，保持格式不变）。
     *
     * 读不到字节时返回原样（`data` 仍为空）—— 调用方按"原件缺失"处理，
     * 而不是让整次请求或导出失败。
     */
    fun materialize(context: Context, attachment: Attachment): Attachment {
        if (!attachment.isStored) return attachment
        val bytes = read(context, attachment.path) ?: return attachment
        return attachment.copy(data = Base64.encodeToString(bytes, Base64.NO_WRAP), path = null)
    }

    /** 批量版本，见 [materialize]。 */
    fun materialize(context: Context, attachments: List<Attachment>): List<Attachment> {
        if (attachments.none { it.isStored }) return attachments
        return attachments.map { materialize(context, it) }
    }

    /**
     * 落盘附件的**字节数**（用于界面文案与 token 估算）。
     *
     * 内联的直接由 [Attachment.sizeBytes] 算；落盘的必须问文件系统，
     * 否则会算成 0 —— 那会让"这张图多大"显示错，也让 token 估算偏低。
     */
    fun sizeOf(context: Context, attachment: Attachment): Int {
        if (!attachment.isStored) return attachment.sizeBytes
        val size = File(dirOf(context), attachment.path.orEmpty()).length()
        return size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun dirOf(context: Context): File = File(context.filesDir, Dir)

    /** MIME → 扩展名。认不出就不带后缀（内容靠 MIME 字段说明，不靠文件名）。 */
    private fun extensionOf(mime: String): String = when (mime.lowercase()) {
        "image/png" -> ".png"
        "image/webp" -> ".webp"
        "image/gif" -> ".gif"
        else -> ".jpg"
    }
}
