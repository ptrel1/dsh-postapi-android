package cd.ptrel.dshpostapi.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 一条消息携带的附件。
 *
 * 只有两种：
 * - [Kind.IMAGE]：图片。`data` 是 **base64**（不含 `data:` 前缀），发送时拼成
 *   官方要求的 data URL；本地存的是**已压过的副本**（见 `ImageImport`）。
 * - [Kind.TEXT]：纯文本文件（md / txt 等）。`data` 就是文件原文。
 *
 * ### 图片字节存在哪里：两种形态
 *
 * - **内联**（`path == null`）：`data` 是 base64。这是老数据与用户自己粘的图 ——
 *   压到最长边 1600px 之后单图 100~400KB，内联可以接受。
 * - **落盘**（`path != null`）：`data` 为空，字节在 `filesDir/attachments/<path>`。
 *   **导入的历史必须走这种**：一条消息的附件和正文在**同一行**，
 *   而 Android 的 `CursorWindow` 只有 2MB —— 一行装不下，那条会话就读不出来
 *   （`SQLiteBlobTooBigException`，实测 7.4MB 一行直接闪退）。
 *
 * 早期所有图片都内联，于是不得不给导入加配额（单张 6MB / 单条 800KB / 单次 24MB）。
 * 字节搬出数据库之后这些配额全部取消 —— 这就是「全部导入」成立的前提。
 *
 * 代价：`filesDir` 被清掉时图片会缺失（数据库自愈：界面显示"原件缺失"，不崩）；
 * 导出时按需把文件读回 base64 内联，备份格式不变。
 */
@Serializable
data class Attachment(
    val kind: Kind,
    val name: String,
    /** 图片的原始 MIME；文本文件可为空。 */
    val mime: String = "",
    /** 图片：base64（落盘时为空串）；文本：原文。 */
    val data: String,
    /** 仅图片：压缩后的像素尺寸，用于按比例占位（避免加载时高度跳变）。 */
    val width: Int = 0,
    val height: Int = 0,
    /**
     * 图片字节在 `filesDir/attachments/` 下的文件名；null 表示字节内联在 [data]。
     *
     * 新字段带默认值，老数据读进来是 null —— JSON 列自描述，**不需要数据库迁移**。
     */
    val path: String? = null,
) {
    enum class Kind { IMAGE, TEXT }

    val isImage: Boolean get() = kind == Kind.IMAGE

    /** 字节在哪：落盘看 [path]，否则内联在 [data]。 */
    val isStored: Boolean get() = !path.isNullOrBlank()

    /**
     * 官方要求的形式：`data:image/jpeg;base64,...`。
     *
     * ⚠️ 只对**内联**的附件成立。落盘的附件要先用
     * `AttachmentFiles.materialize(...)` 把字节读回 [data]，再调这里 ——
     * 请求与导出都在边界处统一做这一步，渲染层不直接碰文件。
     */
    fun toDataUrl(): String = "data:${mime.ifBlank { "image/jpeg" }};base64,$data"

    /**
     * 拼进**请求文本**的形式（仅文本附件用）。
     *
     * 用显式的定界行而不是直接把内容粘进用户的话里：
     * 模型能分清"这是附件内容、那是我的要求"，否则长文会把它自己的
     * 指令淹没掉。也方便用户事后核对发出去的是什么。
     */
    fun toPromptBlock(): String = buildString {
        appendLine("<<<附件开始：$name>>>")
        append(data)
        if (!data.endsWith("\n")) appendLine()
        appendLine("<<<附件结束：$name>>>")
    }

    /**
     * 图片字节数，用于"这张图多大"的说明与 token 估算。
     *
     * 落盘时 `data` 是空的，用不上——改由调用方传实际大小（见 `AttachmentFiles`）。
     */
    val sizeBytes: Int get() = if (isImage) data.length * 3 / 4 else data.toByteArray().size
}

/**
 * 附件列表 ↔ 数据库里那一列 JSON 文本。
 *
 * 存 JSON 而不是再开一张表：附件的生命周期**完全跟随消息**，
 * 没有独立查询需求，单独一张表只会多一次 join、多一处级联要维护。
 */
object Attachments {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(list: List<Attachment>): String? =
        if (list.isEmpty()) null else json.encodeToString(list)

    /** 解析失败一律当"没有附件" —— 一条坏数据不该让整条消息打不开。 */
    fun decode(raw: String?): List<Attachment> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<Attachment>>(raw) }.getOrDefault(emptyList())
    }

    /** 人类可读的大小，用于附件 chip 上的副标题。 */
    fun formatSize(bytes: Int): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    }
}
