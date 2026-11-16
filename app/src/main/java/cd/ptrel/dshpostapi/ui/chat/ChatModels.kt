package cd.ptrel.dshpostapi.ui.chat

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import cd.ptrel.dshpostapi.R
import cd.ptrel.dshpostapi.data.Res
import cd.ptrel.dshpostapi.net.ModelInfo

/**
 * 会话模式。由「创作模式 / 扮演模式」的差异驱动系统提示词模板与空态文案。
 *
 * - [CREATIVE] 创作模式：AI 作为写作助手产出台词与正文
 * - [ROLEPLAY] 扮演模式：AI 扮演角色，以对话与动作推进剧情
 *
 * 文案（名称 / 空态标题 / 说明）随界面语言取（`mode_*`）。
 */
enum class ConversationMode(
    @StringRes private val titleRes: Int,
    @StringRes private val emptyTitleRes: Int,
    @StringRes private val captionRes: Int,
) {
    CREATIVE(
        titleRes = R.string.mode_creative_title,
        emptyTitleRes = R.string.mode_creative_empty,
        captionRes = R.string.mode_creative_caption,
    ),
    ROLEPLAY(
        titleRes = R.string.mode_roleplay_title,
        emptyTitleRes = R.string.mode_roleplay_empty,
        captionRes = R.string.mode_roleplay_caption,
    ),
    ;

    val title: String get() = Res.get(titleRes)

    val emptyTitle: String get() = Res.get(emptyTitleRes)

    val caption: String get() = Res.get(captionRes)
}

enum class MessageRole { USER, ASSISTANT }

/** 消息状态。真实流式需要区分「正常结束 / 用户中断 / 出错」三种终态。 */
enum class MessageStatus { DONE, STREAMING, STOPPED, ERROR }

/**
 * 思考档位。**开关与强度合并为一个滑块**，3 档：
 *
 * ```
 * 关闭  ────  high  ────  max
 *  ↑ 最左即关闭
 * ```
 *
 * 滑到最左即关闭思考（发 `thinking.type = disabled`，不带 `reasoning_effort`）；
 * 其余档位发送对应的 `reasoning_effort`。
 */
enum class ReasoningLevel(val apiValue: String?, @StringRes private val labelRes: Int) {
    OFF(null, R.string.reasoning_off),
    HIGH("high", R.string.reasoning_high),
    MAX("max", R.string.reasoning_max),
    ;

    /** 档位名。OFF 随界面语言，high / max 是发给 API 的字面值本身。 */
    val label: String get() = Res.get(labelRes)

    val enabled: Boolean get() = this != OFF

    companion object {
        /** 滑块档位顺序，索引即滑块位置。 */
        val sliderOrder: List<ReasoningLevel> = listOf(OFF, HIGH, MAX)

        fun fromSliderPosition(position: Int): ReasoningLevel =
            sliderOrder[position.coerceIn(0, sliderOrder.lastIndex)]

        fun sliderPositionOf(level: ReasoningLevel): Int =
            sliderOrder.indexOf(level).coerceAtLeast(0)
    }
}

/**
 * 预设：**一段可命名的系统提示词**。
 *
 * 刻意不做结构化字段、不兼容外部角色卡格式 —— 保持泛用性（设计文档 §9.1.1①）。
 * M4 会迁移到 Room 的 `presets` 表。
 */
@Immutable
data class Preset(
    val id: String,
    val name: String,
    val content: String,
)

/**
 * 会话级设置。驱动输入栏两个面板与请求构造。
 *
 * 参数条件化规则（设计文档 §5.4）：思考开启时 `temperature` 不生效、`top_p` 才生效。
 */
@Immutable
data class ConversationSettings(
    val mode: ConversationMode = ConversationMode.CREATIVE,
    /** 会话级补充提示词，与预设拼接后构成最终系统提示词。 */
    val systemPrompt: String = "",
    val enabledPresetIds: List<String> = emptyList(),
    val model: ModelInfo,
    /** 思考档位；[ReasoningLevel.OFF] 即关闭思考。 */
    val reasoningLevel: ReasoningLevel = ReasoningLevel.OFF,
    /** 思考模式下不生效，UI 需置灰（官方：静默忽略，不报错）。 */
    val temperature: Float = 1.0f,
    /** 仅思考模式生效，有效范围 0.95–1.0。 */
    val topP: Float = 1.0f,
    val maxTokens: Int? = null,
) {
    /** 实际是否开启思考：模型支持且档位非 OFF。 */
    val thinkingEnabled: Boolean get() = model.supportsThinking && reasoningLevel.enabled

    val reasoningEffort: String?
        get() = if (thinkingEnabled) reasoningLevel.apiValue else null

    /** 最终系统提示词：预设按序拼接 + 会话补充。用于预览，避免黑盒。 */
    fun composedSystemPrompt(presets: List<Preset>): String {
        val parts = buildList {
            presets.filter { it.id in enabledPresetIds && it.content.isNotBlank() }
                .forEach { add(it.content.trim()) }
            if (systemPrompt.isNotBlank()) add(systemPrompt.trim())
        }
        return parts.joinToString("\n\n")
    }
}

/**
 * 当前会话的上下文占用与性能统计。
 *
 * 所有 token 数都是**本地估算**（见 `TokenEstimate`），用于给出量级感，
 * 不用于计费。
 */
@Immutable
data class ContextStats(
    /** 下一次请求实际会占用的 token（含系统提示词、摘要、未被排除的消息）。 */
    val usedTokens: Int,
    /** 模型的上下文窗口。 */
    val windowTokens: Int,
    /** 本地消息条数。 */
    val messageCount: Int,
    /** 本地全部内容的 token（含被排除、被压缩掉的）。 */
    val totalTokens: Int,
    /** 压缩省下的 token 估算；未压缩为 0。 */
    val compactedTokenSaving: Int,
    /** 平均首字延迟。 */
    val avgTtftMs: Long?,
    /** 平均生成速度（token/秒，估算）。 */
    val avgTps: Float?,
    /** 累计思考时长（秒）。 */
    val totalThinkingSeconds: Long?,
    val hasCompaction: Boolean,
) {
    /** 占用比例，0f..1f。 */
    val ratio: Float
        get() = if (windowTokens <= 0) 0f else (usedTokens.toFloat() / windowTokens).coerceIn(0f, 1f)

    /** 占用百分比，整数。 */
    val percent: Int get() = (ratio * 100).toInt()
}

/** 展示模型；M4 接入 Room 后由实体映射而来。 */
@Immutable
data class UiMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    /** 附件（图片 / 纯文本文件）。见 [cd.ptrel.dshpostapi.data.Attachment]。 */
    val attachments: List<cd.ptrel.dshpostapi.data.Attachment> = emptyList(),
    val reasoning: String? = null,
    val reasoningSeconds: Long? = null,
    /**
     * 思维链是否已经写完（正文已经开始）。
     *
     * 只在流式期间有意义，**不落库** —— 流式中的消息不会被重新读回来
     * （进程被杀时 `resolveDanglingStreams` 会把它们改成中断态）。
     */
    val reasoningDone: Boolean = false,
    val status: MessageStatus = MessageStatus.DONE,
    /** 失败原因，仅 [MessageStatus.ERROR] 时非空。 */
    val errorMessage: String? = null,
    /**
     * 这条由哪个模型产生（`ModelInfo.id`）。
     *
     * 只有 AI 回复有值；用户消息与 v3 以前的老数据为 null ——
     * 那时没记，界面上就**不显示**，而不是拿当前模型猜一个。
     */
    val model: String? = null,
    /** 父亲消息 id；空串表示这是当前分支的根（§17）。 */
    val parentId: String = "",
    /** 同父兄弟间的版本号，0 起。 */
    val variantIndex: Int = 0,
    /** 同父兄弟总数，用于渲染 `‹ n/m ›`。 */
    val variantCount: Int = 1,
    /** 首字延迟，用于性能仪表（设计文档 §9.1 P1）。 */
    val ttftMs: Long? = null,
    /** 每秒输出 token 数估算。 */
    val tps: Float? = null,
    /**
     * 内容块时间线（仅 Anthropic 兼容通道有值）。
     *
     * 空 = 这条回复没有结构（OpenAI 兼容通道、或老数据），界面走原来的
     * 「思考栏 + 正文」渲染。非空 = 按块渲染：思考 / 搜索 / 浏览网页 / 正文 + 引用角标。
     */
    val timeline: List<cd.ptrel.dshpostapi.net.ReplyBlock> = emptyList(),
    /**
     * 结束原因（`end_turn` / `max_tokens` / `tool_use` / `refusal`）。
     *
     * 只有 Anthropic 通道会写，**仅作为记录**：不据此删改任何已经收到的内容，
     * 也不据此否决续写。客户端只做透传 —— 服务端发来什么就显示什么、存什么。
     * （唯一用到它的是 `max_tokens`：表示"被上限截断"，可以续写。）
     */
    val stopReason: String? = null,
) {
    val streaming: Boolean get() = status == MessageStatus.STREAMING

    /**
     * 思维链是否**还在**生成。
     *
     * 刻意不等于 [streaming] —— 后者是"整条回复还在生成"（思维链 + 正文），
     * 而思维链结束得早得多。用错的后果：正文都快写完了，思考栏还挂在
     * 「正在思考」的尾巴模式上，看起来模型还在想。
     */
    val reasoningStreaming: Boolean get() = streaming && !reasoningDone
}
