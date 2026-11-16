package cd.ptrel.dshpostapi.ui.chat

import cd.ptrel.dshpostapi.net.ReplyBlock
import cd.ptrel.dshpostapi.net.WebSource

/**
 * 把一条回复的**内容块序列**推导成界面要的东西：过程时间线 + 带引用角标的正文 + 来源表。
 *
 * 这是官方 Chat Demo `services/chat/view.ts` 的等价物，纯计算、不改动内容块
 * （块要原样回传，见 `AnthropicRequestBuilder`）。抽出成纯函数是为了能单测：
 * 这里的错法都很隐蔽 —— 角标编号错位、搜索步骤顺序颠倒、正文被吞进过程区。
 *
 * ### 为什么"正文"是**最后**那批文本块
 * 服务端把"工具调用之前的说明"也发成文本块。只有**最后一个非文本块之后**的文本
 * 才是真正的回答；之前的那些是过程性说明（"我先查一下"），要放进折叠区。
 */

/** 工具步骤的状态。 */
enum class ToolStatus { WIP, DONE, FAILED }

/** 一个可引用的来源。[index] 从 1 开始，与正文角标、来源列表的编号一致。 */
data class SourceRef(
    val index: Int,
    val url: String,
    val title: String,
    val siteName: String,
    val snippet: String?,
    val siteIcon: String?,
    val pageAge: String?,
)

/** 打开 / 阅读过的页面。 */
data class PageRef(
    val url: String,
    val title: String,
    val failed: Boolean,
)

/** 页内查找命中的关键词。 */
data class PatternRef(
    val pattern: String,
    val failed: Boolean,
)

sealed interface TimelineStep {
    /** 思考片段。相邻思考块合并成一步（共用同一个圆点与竖线）。 */
    data class Thinking(val key: String, val text: String, val active: Boolean) : TimelineStep

    /** 工具调用之前的说明文字。 */
    data class Preamble(val key: String, val markdown: String) : TimelineStep

    /** 一次搜索（普通搜索按种类聚合，边想边搜只合并相邻调用）。 */
    data class Search(
        val key: String,
        val kind: String,
        val queries: List<String>,
        val sources: List<SourceRef>,
        val status: ToolStatus,
    ) : TimelineStep

    /** 边想边搜打开的网页。 */
    data class Open(val pages: List<PageRef>) : TimelineStep

    /** 边想边搜的页内查找。 */
    data class Find(val patterns: List<PatternRef>) : TimelineStep

    /** 阅读用户消息里的链接（只展示进行态）。 */
    data class ReadLink(val links: List<PageRef>) : TimelineStep
}

data class TimelineView(
    val steps: List<TimelineStep>,
    /** 正文 markdown，引用处已插入 `[ⁿ](url)` 角标。 */
    val answer: String,
    val sources: List<SourceRef>,
    /** 思考总用时（毫秒）；有块缺起止时刻时为 null（那就不显示秒数，不编数）。 */
    val thinkingMillis: Long? = null,
) {
    val hasAnswer: Boolean get() = answer.isNotBlank()

    /** 有没有"过程"可展示（思考或工具）。 */
    val hasSteps: Boolean get() = steps.isNotEmpty()

    /**
     * 思考用时（秒），至少 1 秒。
     *
     * **四舍五入**，与 `ChatViewModel` 里 `thinkingSeconds(ms)` 的取整规则一致 ——
     * 两条通道显示的是同一件事，取整方式不同会让人以为是两个统计口径。
     */
    fun thinkingSeconds(): Long? = thinkingMillis?.let { ((it + 500) / 1000).coerceAtLeast(1) }

    /** 搜索/打开/查找这一轮到底做了什么工具调用。 */
    val hasToolUse: Boolean
        get() = steps.any { it is TimelineStep.Search || it is TimelineStep.Open || it is TimelineStep.Find }

    /** 搜索到的网页（去重），底部汇总用。 */
    val searchResults: List<SourceRef>
        get() = steps.filterIsInstance<TimelineStep.Search>()
            .flatMap { it.sources }
            .distinctBy { it.url }
}

/** 工具的 kind 分类。 */
internal fun isDeepSearchKind(kind: String): Boolean =
    kind == "deep_search" || kind == "deep_image_search"

internal fun isImageSearchKind(kind: String): Boolean =
    kind == "image_search" || kind == "deep_image_search"

/**
 * 推导整个视图。
 *
 * [streaming] 只影响"进行态"的判定：流还没结束时，缺少结果的工具块算 `WIP`，
 * 结束后一律算 `FAILED`（服务端没给结果就是失败了，不能永远转圈）。
 */
internal fun buildTimelineView(blocks: List<ReplyBlock>, streaming: Boolean): TimelineView {
    val results = blocks.mapNotNull { block ->
        when (block) {
            is ReplyBlock.SearchResult -> block.toolUseId to block
            is ReplyBlock.FetchResult -> block.toolUseId to block
            else -> null
        }
    }.toMap()

    val registry = SourceRegistry()
    // 第一遍：按"搜索结果 → 打开的网页"的**出现顺序**编号，与正文引用无关
    blocks.forEach { block ->
        if (block !is ReplyBlock.ToolUse) return@forEach
        when (val result = results[block.id]) {
            is ReplyBlock.SearchResult -> result.results.forEach { registry.add(it) }
            is ReplyBlock.FetchResult -> if (!result.failed && result.url.isNotBlank()) {
                registry.add(WebSource(url = result.url, title = result.title, pageAge = result.publishedAt))
            }
            else -> Unit
        }
    }

    val steps = mutableListOf<TimelineStep>()
    var pending = mutableListOf<ReplyBlock.Text>()

    // 用**下标**而不是对象去定位可合并的步骤：data class 的相等性会误合并同文步骤
    var thinkingIndex: Int? = null
    val searchIndexByKind = mutableMapOf<String, Int>()

    fun flushPreamble() {
        if (pending.isEmpty()) return
        val markdown = joinText(pending, registry, withMarkers = false)
        if (markdown.isNotBlank()) {
            steps += TimelineStep.Preamble(key = "text-${steps.size}", markdown = markdown)
        }
        pending = mutableListOf()
    }

    blocks.forEach { block ->
        when (block) {
            is ReplyBlock.Text -> {
                thinkingIndex = null
                pending += block
            }

            is ReplyBlock.Thinking -> {
                val active = streaming && block.endedAt == null
                val at = thinkingIndex
                if (at != null) {
                    val current = steps[at] as TimelineStep.Thinking
                    steps[at] = current.copy(text = current.text + block.thinking, active = active)
                } else if (block.thinking.isNotBlank()) {
                    flushPreamble()
                    steps += TimelineStep.Thinking(
                        key = "thinking-${steps.size}",
                        text = block.thinking,
                        active = active,
                    )
                    thinkingIndex = steps.lastIndex
                }
            }

            is ReplyBlock.ToolUse -> {
                thinkingIndex = null
                val result = results[block.id]
                val status = statusOf(result, streaming)
                when (block.action) {
                    "search" -> {
                        flushPreamble()
                        val sources = (result as? ReplyBlock.SearchResult)
                            ?.results
                            ?.map(registry::refFor)
                            .orEmpty()
                        val deep = isDeepSearchKind(block.kind)
                        // 普通搜索按种类聚合；边想边搜只合并相邻的同种调用
                        val at = if (deep) {
                            steps.lastOrNull()
                                ?.takeIf { it is TimelineStep.Search && it.kind == block.kind }
                                ?.let { steps.lastIndex }
                        } else {
                            searchIndexByKind[block.kind]
                        }
                        if (at != null) {
                            val current = steps[at] as TimelineStep.Search
                            steps[at] = current.copy(
                                queries = current.queries + block.queries,
                                sources = (current.sources + sources).distinctBy { it.url },
                                status = mergeStatus(current.status, status),
                            )
                        } else {
                            steps += TimelineStep.Search(
                                key = if (deep) block.id else block.kind,
                                kind = block.kind,
                                queries = block.queries,
                                sources = sources,
                                status = status,
                            )
                            if (!deep) searchIndexByKind[block.kind] = steps.lastIndex
                        }
                    }

                    "open_page", "read_link" -> {
                        val fetched = result as? ReplyBlock.FetchResult
                        val url = block.url.ifBlank { fetched?.url.orEmpty() }
                        val page = PageRef(
                            url = url,
                            title = fetched?.title?.takeIf { it.isNotBlank() } ?: url,
                            failed = status == ToolStatus.FAILED,
                        )
                        if (block.action == "read_link") {
                            // 读链接只展示进行态：结果仍留着回传，但成功后界面上不占位
                            if (status == ToolStatus.WIP) {
                                val last = steps.lastOrNull() as? TimelineStep.ReadLink
                                if (last != null) {
                                    steps[steps.lastIndex] = last.copy(links = last.links + page)
                                } else {
                                    steps += TimelineStep.ReadLink(listOf(page))
                                }
                            }
                        } else {
                            flushPreamble()
                            val last = steps.lastOrNull() as? TimelineStep.Open
                            if (last != null) {
                                steps[steps.lastIndex] = last.copy(pages = last.pages + page)
                            } else {
                                steps += TimelineStep.Open(listOf(page))
                            }
                        }
                    }

                    "find_in_page" -> {
                        flushPreamble()
                        val item = PatternRef(block.pattern, status == ToolStatus.FAILED)
                        val last = steps.lastOrNull() as? TimelineStep.Find
                        if (last != null) {
                            steps[steps.lastIndex] = last.copy(patterns = last.patterns + item)
                        } else {
                            steps += TimelineStep.Find(listOf(item))
                        }
                    }
                }
            }

            // 结果块本身不产生步骤：它挂在对应的调用块上
            is ReplyBlock.SearchResult, is ReplyBlock.FetchResult -> Unit
        }
    }

    return TimelineView(
        steps = steps,
        answer = joinText(pending, registry, withMarkers = true),
        sources = registry.list,
        thinkingMillis = totalThinkingMillis(blocks, streaming),
    )
}

/**
 * 思考总用时（毫秒）。各思考块用时之和；有块缺少起止时刻时返回 null
 * —— 那样只能显示「已深度思考」而不能给秒数，给一个编的数是骗人。
 */
internal fun totalThinkingMillis(blocks: List<ReplyBlock>, streaming: Boolean): Long? {
    val thinking = blocks.filterIsInstance<ReplyBlock.Thinking>()
    if (thinking.isEmpty()) return null
    var total = 0L
    thinking.forEach { block ->
        val start = block.startedAt ?: return null
        total += (block.endedAt ?: if (streaming) System.currentTimeMillis() else start) - start
    }
    return total.coerceAtLeast(0)
}

/** 把若干文本块拼成一段 markdown，引用处插入角标。 */
private fun joinText(
    blocks: List<ReplyBlock.Text>,
    registry: SourceRegistry,
    withMarkers: Boolean,
): String {
    val out = StringBuilder()
    blocks.forEach { block ->
        val indexes = block.citations.map { registry.add(it.url, it.title) }.distinct()
        if (!withMarkers || indexes.isEmpty()) {
            out.append(block.text)
        } else {
            // 引用挂在**块尾**，但块尾常带换行 —— 角标要插在换行之前
            val trimmed = block.text.trimEnd()
            out.append(trimmed)
            indexes.forEach { out.append(citationMark(it)) }
            out.append(block.text.substring(trimmed.length))
        }
    }
    return out.toString()
}

/**
 * 正文里的引用角标：`[ⁿ](#cite-N)`。
 *
 * 先写成**页内锚点**而不是真实 URL，因为 markdown 的链接目标遇到 `)`、空格、中文
 * 会被截断，一次搜索就能把整段正文的排版搞乱。真正的 URL 在渲染前由
 * [resolveCitationLinks] 换进去 —— 那一步只做字符串替换，不参与解析。
 */
internal fun citationMark(index: Int): String = "[${superscript(index)}](#cite-$index)"

/** 角标链接在正文里的占位形态，见 [citationMark]。 */
private val CitationHref = Regex("""\(#cite-(\d+)\)""")

/**
 * 把正文里的角标占位换成真实 URL。
 *
 * URL 做最小转义：只处理会打断 markdown 链接语法的字符。不整体 `encode` ——
 * 那会把中文路径也编码掉，点开反而可能 404。
 */
internal fun resolveCitationLinks(markdown: String, sources: List<SourceRef>): String {
    if (sources.isEmpty() || !markdown.contains("#cite-")) return markdown
    val byIndex = sources.associateBy { it.index }
    return CitationHref.replace(markdown) { match ->
        val index = match.groupValues[1].toIntOrNull()
        val url = byIndex[index]?.url ?: return@replace match.value
        "(${escapeHref(url)})"
    }
}

private fun escapeHref(url: String): String = url
    .replace("(", "%28")
    .replace(")", "%29")
    .replace(" ", "%20")
    .replace("\n", "")

/** 上标数字，任意位。不用 `[1]` 是因为它会和 markdown 链接语法打架。 */
internal fun superscript(value: Int): String =
    value.toString().map { digit -> "⁰¹²³⁴⁵⁶⁷⁸⁹"[digit - '0'] }.joinToString("")

private fun statusOf(result: Any?, streaming: Boolean): ToolStatus = when {
    result == null -> if (streaming) ToolStatus.WIP else ToolStatus.FAILED
    result is ReplyBlock.SearchResult -> if (result.failed) ToolStatus.FAILED else ToolStatus.DONE
    result is ReplyBlock.FetchResult -> if (result.failed) ToolStatus.FAILED else ToolStatus.DONE
    else -> ToolStatus.DONE
}

private fun mergeStatus(a: ToolStatus, b: ToolStatus): ToolStatus = when {
    a == ToolStatus.WIP || b == ToolStatus.WIP -> ToolStatus.WIP
    a == ToolStatus.FAILED && b == ToolStatus.FAILED -> ToolStatus.FAILED
    else -> ToolStatus.DONE
}

/** 来源登记：同一 URL 只编号一次，编号按首次出现顺序。 */
private class SourceRegistry {

    val list = mutableListOf<SourceRef>()
    private val indexByUrl = mutableMapOf<String, Int>()

    fun add(source: WebSource): Int {
        indexByUrl[source.url]?.let { at ->
            // 同一网页可能先被打开、或先返回不完整元数据；后到的补齐，不覆盖已有信息
            val existing = list[at]
            list[at] = existing.copy(
                title = existing.title.takeIf { it != existing.url }
                    ?: source.title?.trim()?.takeIf { it.isNotEmpty() } ?: existing.title,
                siteName = existing.siteName.takeIf { it != siteNameOf(existing.url) }
                    ?: source.siteName?.trim()?.takeIf { it.isNotEmpty() }
                    ?: siteNameOf(source.url),
                snippet = existing.snippet ?: source.snippet?.trim()?.takeIf { it.isNotEmpty() },
                siteIcon = existing.siteIcon ?: source.siteIcon?.trim()?.takeIf { it.isNotEmpty() },
                pageAge = existing.pageAge ?: source.pageAge,
            )
            return existing.index
        }
        val ref = SourceRef(
            index = list.size + 1,
            url = source.url,
            title = source.title?.trim()?.takeIf { it.isNotEmpty() } ?: source.url,
            siteName = source.siteName?.trim().orEmpty().ifEmpty { siteNameOf(source.url) },
            snippet = source.snippet?.trim()?.takeIf { it.isNotEmpty() },
            siteIcon = source.siteIcon?.trim()?.takeIf { it.isNotEmpty() },
            pageAge = source.pageAge,
        )
        indexByUrl[source.url] = list.size
        list += ref
        return ref.index
    }

    /** 只带 url 与标题的引用（正文角标只要这两项）。 */
    fun add(url: String, title: String?): Int = add(WebSource(url = url, title = title))

    fun refFor(source: WebSource): SourceRef {
        add(source)
        return list[indexByUrl.getValue(source.url)]
    }
}

/** 站点名兜底：注册域名去掉 `www.`。 */
internal fun siteNameOf(url: String): String = runCatching {
    java.net.URI(url).host.orEmpty().removePrefix("www.")
}.getOrDefault("")
