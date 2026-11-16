package cd.ptrel.dshpostapi.ui.markdown

import cd.ptrel.dshpostapi.R
import cd.ptrel.dshpostapi.data.Res

/**
 * 把正文里的 HTML 折算成 **Markdown 等价物** —— 让内容不丢、不露标签。
 *
 * ### 为什么需要它
 *
 * 模型常输出 HTML：`<br>`、`<b>`、`<div>`、`<details>`、`<a href>`。
 * 而当前渲染链路（`org.jetbrains:markdown` 解析 + `mikepenz` 渲染）里，
 * **`HTML_BLOCK` / `HTML_TAG` 没有任何对应组件**：块级 HTML 整块丢失，
 * 行内 HTML 的标签原样留在正文里。表现为"内容少了"或"一堆尖括号"。
 *
 * ### 做法
 *
 * 在进解析器**之前**把这些标签折算成 markdown 已支持的写法：
 * 标题、加粗、斜体、删除线、行内码、引用、列表、分隔线、链接、图片。
 * 认不出的标签一律**只去掉标签、保留里面的内容** —— 宁可少一点格式，
 * 也不能把用户要读的字吞掉。
 *
 * ### 三类地方不能动
 *
 * 1. 围栏代码块内部（里面就是 HTML 源码，动了代码就废了）
 * 2. 行内代码反引号里（`` `<div>` `` 是要显示的东西）
 * 3. 自动链接 `<https://…>`、邮箱 `<a@b.com>`（长得像标签，其实是链接）
 *
 * 抽成纯函数是为了能单测：标签种类多、边界情况杂，肉眼看长文对不出来。
 *
 * HTML 实体（`&amp;` `&nbsp;` 等）**刻意不动** —— 渲染器自带 `EntityConverter`
 * 会做还原，这里再还原一遍会重复。
 *
 * @param imageAlt 图片没 alt 也没 src 时的占位文字。默认随界面语言取（`md_image`），
 *   单测把中文字面量塞进来 —— 纯函数不碰 Android Resources。
 */
internal fun normalizeHtml(
    markdown: String,
    imageAlt: () -> String = { Res.get(R.string.md_image) },
): String =
    collapseBlankLines(
        transformOutsideFences(fixEmphasisFlanking(resolveReferenceLinks(markdown)), imageAlt),
    )

/**
 * 把**引用式链接**内联成行内链接。
 *
 * ### 为什么必须在这里做
 *
 * 渲染走的是分块路径（[splitMarkdownBlocks]）：`[文字][id]` 与它的定义
 * `[id]: https://… "标题"` 常常被切进**不同的块**，各自单独解析时定义查不到，
 * 于是整段退回成字面 `[文字][id]`。
 *
 * 所以在进解析器之前先收集全文定义、就地改写成 `[文字](url)`，再删掉定义行。
 * 顺带解决"标题不显示"：移动端没有悬停，标题本来就无处展示；
 * 只有当**文字为空**时才拿标题当文字（`[][id]`）。
 *
 * 与 [normalizeHtml] 同样跳过围栏代码与行内代码。
 */
internal fun resolveReferenceLinks(markdown: String): String {
    val defs = LinkedHashMap<String, String>()          // id -> url
    val titles = HashMap<String, String>()              // id -> 标题（仅用于空文字）
    for (line in markdown.lines()) {
        val m = REF_DEF.find(line) ?: continue
        defs[m.groupValues[1].lowercase()] = m.groupValues[2]
        val title = m.groupValues[3].ifEmpty { m.groupValues[4] }.ifEmpty { m.groupValues[5] }
        if (title.isNotEmpty()) titles[m.groupValues[1].lowercase()] = title
    }
    if (defs.isEmpty()) return markdown

    val out = ArrayList<String>(markdown.lines().size)
    var fenceMarker: String? = null
    for (line in markdown.lines()) {
        val marker = fenceMarkerOf(line.trimStart())
        if (marker != null) {
            when {
                fenceMarker == null -> fenceMarker = marker
                marker == fenceMarker -> fenceMarker = null
            }
            out += line
            continue
        }
        if (fenceMarker == null && REF_DEF.matches(line)) continue        // 定义行删掉
        out += if (fenceMarker == null) transformOutsideCode(line) { s ->
            REF_USE.replace(s) { m -> refUseToInline(m, defs, titles) }
        } else line
    }
    return out.joinToString("\n")
}

/** `[id]: url "标题"`（标题可有可无，引号 / 单引号 / 圆括号三种写法）。 */
private val REF_DEF = Regex("""^\s*\[([^\]]+)\]:\s*(\S+?)\s*(?:"([^"]*)"|'([^']*)'|\(([^)]*)\))?\s*$""")

/** `![alt][id]` / `[text][id]` / `[text][]`（折叠式）—— 空 id 取文字当 id。 */
private val REF_USE = Regex("""(!?)\[([^\]]*)\]\[([^\]]*)\]""")

private fun refUseToInline(m: MatchResult, defs: Map<String, String>, titles: Map<String, String>): String {
    val bang = m.groupValues[1]
    val text = m.groupValues[2]
    val rawId = m.groupValues[3].ifEmpty { text }
    val url = defs[rawId.lowercase()] ?: return m.value            // 认不出就原样留着
    val label = text.ifEmpty { titles[rawId.lowercase()] ?: url }
    return if (bang.isEmpty()) "[$label]($url)" else label         // 图片一律只留文字
}

// ── 围栏与行内代码：跳过 ─────────────────────────────────────

/** 非代码区交给 [transformOutsideCode]，围栏整行原样过。 */
private fun transformOutsideFences(markdown: String, imageAlt: () -> String): String {
    val out = ArrayList<String>(markdown.lines().size)
    var fenceMarker: String? = null
    for (line in markdown.lines()) {
        val marker = fenceMarkerOf(line.trimStart())
        if (marker != null) {
            when {
                fenceMarker == null -> fenceMarker = marker
                marker == fenceMarker -> fenceMarker = null
            }
            out += line
            continue
        }
        out += if (fenceMarker == null) {
            transformOutsideCode(line) { s -> transformTags(s, imageAlt) }
        } else {
            line
        }
    }
    return out.joinToString("\n")
}

/** 反引号代码段原样保留，其余交给 [transform]。 */
private fun transformOutsideCode(text: String, transform: (String) -> String): String {
    val sb = StringBuilder(text.length)
    var last = 0
    for (m in INLINE_CODE.findAll(text)) {
        if (m.range.first > last) sb.append(transform(text.substring(last, m.range.first)))
        sb.append(m.value)
        last = m.range.last + 1
    }
    if (last < text.length) sb.append(transform(text.substring(last)))
    return sb.toString()
}

/** 反引号代码段：成对反引号围起来的最短串。 */
private val INLINE_CODE = Regex("`[^`\\n]*`")

// ── 标签折算 ────────────────────────────────────────────────

private fun transformTags(text: String, imageAlt: () -> String): String {
    var s = text
    if (s.contains("![")) {
        s = MD_IMAGE.replace(s) { m -> m.groupValues[1].ifEmpty { imageAlt() } }
    }
    if (s.contains('<')) {
        s = ANCHOR.replace(s) { m -> anchorToMarkdown(m) }
        s = IMAGE.replace(s) { m -> imageToMarkdown(m, imageAlt) }
        s = TAG.replace(s) { m ->
            // 自动链接 / 邮箱：长得像标签，留着
            if (AUTOLINK.matches(m.value)) m.value
            else tagToMarkdown(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        }
    }
    return if (s.contains('&')) decodeEntities(s) else s
}

/**
 * 折算 markdown 图片 —— 与 HTML `<img>` 同一个理由：**本应用不加载远程图**
 * （`ImageTransformer` 是 NoOp），造出图片节点只会在正文里留一大块空白。
 * 保留 alt 文字，内容不丢、也不多占位。
 */
private val MD_IMAGE = Regex("""!\[([^\]]*)\]\([^)]*\)""")

/** 命名实体 + 数字实体。`&amp;` 的还原放最后一步，避免 `&amp;lt;` 被二次还原。 */
private val ENTITY = Regex("""&(?:[a-zA-Z][a-zA-Z0-9]{1,10}|#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6});""")

private fun decodeEntities(text: String): String =
    ENTITY.replace(text) { m -> entityToChar(m.value) }

private fun entityToChar(entity: String): String {
    val body = entity.substring(1, entity.length - 1)
    val ch: Char? = when {
        body.startsWith("#x", ignoreCase = true) -> body.drop(2).toIntOrNull(16)?.toChar()
        body.startsWith("#") -> body.drop(1).toIntOrNull()?.toChar()
        else -> when (body.lowercase()) {
            "amp" -> '&'
            "lt" -> '<'
            "gt" -> '>'
            "quot" -> '"'
            "apos" -> '\''
            "nbsp" -> ' '
            else -> null
        }
    }
    return when (ch) {
        null -> entity
        // `<` `>` 还原后会被解析器当成 HTML 标签（而标签没有渲染组件），用反斜杠保住字面量
        '<' -> "\\<"
        '>' -> "\\>"
        ' ' -> " "
        else -> ch.toString()
    }
}

/** `<a href="…">文字</a>`（属性里可含引号，`href` 位置不固定）。 */
private val ANCHOR = Regex("""<\s*a\b((?:[^<>"']|"[^"]*"|'[^']*')*)>(.*?)<\s*/\s*a\s*>""", RegexOption.IGNORE_CASE)

/** `<img …>`，单标签。 */
private val IMAGE = Regex("""<\s*img\b((?:[^<>"']|"[^"]*"|'[^']*')*)/?>""", RegexOption.IGNORE_CASE)

/** 通用标签。第 1 组 = 斜杠，第 2 组 = 名字，第 3 组 = 属性（含末尾 `/`）。 */
private val TAG = Regex("""<\s*(/?)([a-zA-Z][a-zA-Z0-9-]*)((?:[^<>"']|"[^"]*"|'[^']*')*)>""")

/** `<https://a.b>` 与 `<a@b.com>`：像标签，实际是链接，不动。 */
private val AUTOLINK = Regex("""^<(?:[a-zA-Z][a-zA-Z0-9+.\-]*:[^<>\s]*|[^<>\s@]+@[^<>\s@]+\.[^<>\s@]+)>$""")

private fun anchorToMarkdown(m: MatchResult): String {
    val href = attr(m.groupValues[1], "href") ?: return m.groupValues[2]
    val label = m.groupValues[2].trim().ifEmpty { href }
    return "[$label]($href)"
}

private fun imageToMarkdown(m: MatchResult, imageAlt: () -> String): String {
    // 不折算成 markdown 图片：本应用**不加载远程图**（`ImageTransformer` 是 NoOp），
    // 造出图片节点只会留一块空白。保留 alt 文字，内容不丢、也不多占位。
    val attrs = m.groupValues[1]
    return attr(attrs, "alt")?.takeIf { it.isNotBlank() } ?: attr(attrs, "src") ?: imageAlt()
}

/** 取属性值；无值属性（`disabled`）返回空串。 */
private fun attr(attrs: String, name: String): String? {
    val m = Regex("""(?i)\b""" + name + """\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""").find(attrs) ?: return null
    return m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { m.groupValues[3] }
}

private fun tagToMarkdown(slash: String, name: String, attrs: String): String {
    val closing = slash.isNotEmpty()
    val tag = name.lowercase()
    return when (tag) {
        // 换行
        "br" -> "  \n"
        "hr" -> "\n\n---\n\n"
        // 块级：给段落边界
        "p" -> "\n\n"
        "div", "section", "article", "header", "footer", "nav", "main", "aside",
        "figure", "figcaption", "ul", "ol", "table", "thead", "tbody", "tr", "tfoot",
        "pre", "details", "summary" -> "\n"
        "li" -> if (closing) "\n" else "- "
        "blockquote" -> if (closing) "\n\n" else "\n\n> "
        "h1", "h2", "h3", "h4", "h5", "h6" -> {
            val level = tag[1] - '0'
            if (closing) "\n\n" else "\n\n" + "#".repeat(level) + " "
        }
        // 行内
        "strong", "b" -> "**"
        "em", "i", "cite", "var" -> "*"
        "del", "s", "strike" -> "~~"
        "code", "kbd", "samp", "tt" -> "`"
        // 只去标签、保留内容
        else -> ""
    }.let { result ->
        // 自闭合（`<div/>`）不该留下"关闭"那一半的语义；上面都是简单成对，
        // 自闭合时按"开 + 关"补偿，避免半个段落边界。
        if (!closing && attrs.trim().endsWith("/") && result.isNotEmpty() && result != "- ") {
            when (tag) {
                "p", "blockquote", "h1", "h2", "h3", "h4", "h5", "h6" -> result + "\n\n"
                "div", "section", "article", "header", "footer", "nav", "main", "aside",
                "figure", "figcaption", "ul", "ol", "table", "thead", "tbody", "tr", "tfoot",
                "pre", "details", "summary", "li" -> result + "\n"
                else -> result
            }
        } else result
    }
}

// ── 收尾 ────────────────────────────────────────────────────

/** 围栏之外把连续空行压成一个（标签折算会留下 `\n\n\n`）。 */
private fun collapseBlankLines(text: String): String {
    val out = ArrayList<String>(text.lines().size)
    var fenceMarker: String? = null
    var blank = false
    for (line in text.lines()) {
        val marker = fenceMarkerOf(line.trimStart())
        if (marker != null) {
            when {
                fenceMarker == null -> fenceMarker = marker
                marker == fenceMarker -> fenceMarker = null
            }
            out += line
            blank = false
            continue
        }
        if (fenceMarker == null && line.isBlank()) {
            if (blank) continue
            blank = true
            out += ""
        } else {
            blank = false
            out += line
        }
    }
    return out.joinToString("\n")
}

// ══════════════════════════════════════════════════════════
//  强调符号（** / __）的定界修补
// ══════════════════════════════════════════════════════════

/**
 * 修补 CommonMark「定界符左右侧」规则在中文 + 引号上的误伤。
 *
 * ### 现象
 *
 * `确实**"爆"**。` 会原样显示成一串星号。原因是 CommonMark 的
 * **left-flanking** 判定：`**` 左边是汉字（不是空白也不是标点）、右边是引号（标点），
 * 两个条件同时成立时它**不能当开定界符**，于是整对 `**` 退回字面量。
 * 中文写作里「**"爆"**」这种"紧贴文字 + 内容是引号开头"的写法极常见，
 * 官方客户端也是加粗的，所以这里在**送进解析器之前**补一刀。
 *
 * ### 怎么补
 *
 * 在开定界符后面、收定界符前面各插一个**零宽空格**（U+200B）：
 * - 开：`**` 后面不再是标点 → 满足 left-flanking；
 * - 收：`**` 前面不再是标点 → 满足 right-flanking。
 *
 * 零宽空格不可见、不占宽度，而且**只改显示用的文本**：
 * 落库与发给模型的内容仍是原文（复制走的是消息本体，不是这份渲染文本）。
 *
 * ### 不动的地方
 *
 * 围栏代码块、行内代码里的 `**` 是**要给人看的内容**，一律不碰。
 */
internal fun fixEmphasisFlanking(markdown: String): String {
    if (!markdown.contains("**") && !markdown.contains("__")) return markdown

    val out = ArrayList<String>(markdown.lines().size)
    var fence: String? = null
    for (line in markdown.lines()) {
        val marker = fenceMarkerOf(line.trimStart())
        if (marker != null) {
            when {
                fence == null -> fence = marker
                marker == fence -> fence = null
            }
            out += line
            continue
        }
        out += if (fence == null) transformOutsideCode(line) { patchEmphasis(it) } else line
    }
    return out.joinToString("\n")
}

/** 一段**不含行内代码**的文本：给不满足定界规则的 `**x**` 补零宽空格。 */
private fun patchEmphasis(segment: String): String {
    if (segment.indexOf('*') < 0 && segment.indexOf('_') < 0) return segment
    return EMPHASIS_PAIR.replace(segment) { m ->
        val inner = m.groupValues[2]
        if (inner.isBlank()) return@replace m.value

        val start = m.range.first
        val end = m.range.last
        val before = segment.getOrNull(start - 1)
        val first = inner.first()
        val last = inner.last()
        val after = segment.getOrNull(end + 1)

        // 开定界符：左边是"普通字符"、右边是标点 → 不满足 left-flanking
        val needsOpen = before != null && !before.isMdSpace() && !before.isMdPunct() &&
            first.isMdPunct()
        // 收定界符：左边是标点、右边是"普通字符" → 不满足 right-flanking
        val needsClose = after != null && !after.isMdSpace() && !after.isMdPunct() &&
            last.isMdPunct()
        if (!needsOpen && !needsClose) return@replace m.value

        buildString {
            append(m.groupValues[1])
            if (needsOpen) append(Zwsp)
            append(inner)
            if (needsClose) append(Zwsp)
            append(m.groupValues[1])
        }
    }
}

/** 成对的 `**x**` 或 `__x__`（同一种定界符配对，不跨行）。 */
private val EMPHASIS_PAIR = Regex("""(\*\*|__)([^\n]*?)\1""")

/** 零宽空格：不占宽度、不可见，只用来让定界符满足 flanking 规则。 */
private const val Zwsp = "\u200B"

/** CommonMark 的空白：空格、制表、换行，以及 Unicode `Zs` 类。 */
private fun Char.isMdSpace(): Boolean =
    this == ' ' || this == '\t' || this == '\n' || Character.getType(this) == Character.SPACE_SEPARATOR.toInt()

/** CommonMark 的标点：Unicode `P*` 与 `S*`。中文引号属于 `Pi` / `Pf`。 */
private fun Char.isMdPunct(): Boolean = when (Character.getType(this)) {
    Character.CONNECTOR_PUNCTUATION.toInt(),
    Character.DASH_PUNCTUATION.toInt(),
    Character.START_PUNCTUATION.toInt(),
    Character.END_PUNCTUATION.toInt(),
    Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
    Character.FINAL_QUOTE_PUNCTUATION.toInt(),
    Character.OTHER_PUNCTUATION.toInt(),
    Character.MATH_SYMBOL.toInt(),
    Character.CURRENCY_SYMBOL.toInt(),
    Character.MODIFIER_SYMBOL.toInt(),
    Character.OTHER_SYMBOL.toInt(),
    -> true

    else -> false
}