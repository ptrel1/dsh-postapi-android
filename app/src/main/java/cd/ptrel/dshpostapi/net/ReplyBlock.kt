package cd.ptrel.dshpostapi.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 一条 AI 回复的**内容块序列** —— Anthropic 兼容通道的原始形态（`§5.1` 双通道之一）。
 *
 * 官方 Chat Demo 的回复不是"一段正文 + 一段思考"，而是一串**按到达顺序**排列的块：
 *
 * ```
 * thinking → server_tool_use(search) → web_search_tool_result → text(带 citations) → …
 * ```
 *
 * 界面要渲染的"思考 / 搜索 / 浏览网页 / 正文"时间线正是由这个顺序推出来的
 * （见 `ui/chat/ReplyTimelineView.kt`）。所以顺序**必须原样保存**，
 * 不能用「思考 + 正文」两个字段拼出来的假顺序。
 *
 * ### 为什么要落库
 * 搜索结果与引用角标是历史消息的一部分：翻回三天前的对话，
 * 「已阅读 6 个网页」和正文里的角标都得还在。只活在内存里等于每次重开都丢。
 *
 * ### 为什么只回传一部分
 * 请求侧只回传 `read_link` 的调用与其结果（页面对服务端仍是可解密的上下文），
 * 搜索调用与搜索结果**不回传** —— 官方协议如此（见 `AnthropicRequest`）。
 *
 * 序列化用 kotlinx 的 sealed 多态，`type` 字段即判别器。
 */
@Serializable
sealed interface ReplyBlock {

    /** 正文块。挂过引用的块之后，后续正文另起新块；同一块内引用按 url 去重。 */
    @Serializable
    @SerialName("text")
    data class Text(
        val text: String,
        val citations: List<Citation> = emptyList(),
    ) : ReplyBlock

    /**
     * 思考块。
     *
     * [signature] 在块结束前经 `signature_delta` 下发，续写时要原样回传；
     * [startedAt] / [endedAt] 是本地时刻，只为算「已思考（用时 N 秒）」——
     * 服务端不提供思考计时。
     */
    @Serializable
    @SerialName("thinking")
    data class Thinking(
        val thinking: String,
        val signature: String? = null,
        val startedAt: Long? = null,
        val endedAt: Long? = null,
    ) : ReplyBlock

    /**
     * 服务端工具调用（原子下发：`content_block_start` 就带完整 input）。
     *
     * [kind] 区分文字搜索 / 边想边搜 / 图搜 / 读链接，决定界面用哪套文案；
     * [action] 决定 input 的形状，因此把三种动作的字段拍平成一个类
     * （[queries] / [url] / [pattern]），比三种子类少一层分支。
     */
    @Serializable
    @SerialName("tool_use")
    data class ToolUse(
        val id: String,
        val name: String,
        val kind: String,
        val action: String,
        val queries: List<String> = emptyList(),
        val url: String = "",
        val pattern: String = "",
    ) : ReplyBlock

    /** 搜索结果块。失败时 [failed] = true，[results] 为空。 */
    @Serializable
    @SerialName("search_result")
    data class SearchResult(
        val toolUseId: String,
        val kind: String,
        val results: List<WebSource> = emptyList(),
        val failed: Boolean = false,
    ) : ReplyBlock

    /**
     * 链接阅读结果块。
     *
     * [signature] 仅读链接成功时下发，多轮请求要原样回传 —— 服务端靠它解密恢复网页正文。
     * 因此这一块**不能因为界面上不展示就丢掉**。
     */
    @Serializable
    @SerialName("fetch_result")
    data class FetchResult(
        val toolUseId: String,
        val kind: String,
        val url: String = "",
        val title: String? = null,
        val publishedAt: String? = null,
        val signature: String? = null,
        val failed: Boolean = false,
    ) : ReplyBlock
}

/**
 * 正文引用：服务端把模型的引用标记还原成来源，挂在对应文本块上。
 *
 * [citedText] 是「截至该引用处的全部正文」，界面不用它渲染，但备份里留着才完整。
 */
@Serializable
data class Citation(
    val url: String,
    val title: String? = null,
    val citedText: String = "",
)

/** 一条网页来源。搜索结果的元数据比"打开过的网页"全，缺的字段一律可空。 */
@Serializable
data class WebSource(
    val url: String,
    val title: String? = null,
    val siteName: String? = null,
    val siteIcon: String? = null,
    val snippet: String? = null,
    /** RFC 3339（搜索）或网页解析出的原始日期串（阅读链接），没发布时间时为 null。 */
    val pageAge: String? = null,
    /** 图搜召回的原站图片地址，仅保存，当前不展示。 */
    val imageUrl: String? = null,
)

/**
 * 回复块的 JSON 编解码。
 *
 * 单独一个 [Json] 实例（不复用网络层的）：这里编的是**落库格式**，
 * 与请求编码的约束完全无关 —— 共用一个实例迟早会为了某一头改配置而弄坏另一头。
 */
object ReplyBlocks {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** 空列表编码成 null：省掉一整列里的 `[]`，也让"没有时间线"只有一种表示。 */
    fun encode(blocks: List<ReplyBlock>): String? =
        if (blocks.isEmpty()) null
        // ⚠️ Kotlin 2.4 + serialization 1.7：reified 的 encodeToString(value) 重载
        //    在这里解析不到（它把 List 当成了 SerializationStrategy 参数）。
        //    显式取序列化器是唯一稳定的写法。
        else runCatching {
            json.encodeToString(ListSerializer(ReplyBlock.serializer()), blocks)
        }.getOrNull()

    fun decode(raw: String?): List<ReplyBlock> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<ReplyBlock>>(raw) }.getOrDefault(emptyList())
    }
}
