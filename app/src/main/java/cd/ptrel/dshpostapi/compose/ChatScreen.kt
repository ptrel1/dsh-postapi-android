package cd.ptrel.dshpostapi.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cd.ptrel.dshpostapi.net.ModelInfo
import cd.ptrel.dshpostapi.ui.chat.ContextStats
import cd.ptrel.dshpostapi.ui.chat.InputBar
import cd.ptrel.dshpostapi.ui.chat.MessageList
import cd.ptrel.dshpostapi.ui.chat.MessageRole
import cd.ptrel.dshpostapi.ui.chat.MessageStatus
import cd.ptrel.dshpostapi.ui.chat.UiMessage

/**
 * 聊天页（v0.10.0）—— 用移植自 SimpleChat 的 [MessageList] 与 [InputBar] 渲染。
 *
 * ## 与 v0.9.0 简版的差别
 *
 * v0.9.0 是自己写的极简版（Row + Text + OutlinedTextField），只验证链路通不通。
 * 这一版换成 SimpleChat 的真实组件，带来：
 *   · 消息气泡与用户/助手左右分栏
 *   · 思考区折叠、长消息自动收起
 *   · 代码块横滚、Markdown 渲染
 *   · 输入栏的发送/停止切换、多行输入
 *
 * ## 为什么不用 SimpleChat 的 ChatScreen
 *
 * 它需要 42 个 ViewModel 成员，其中大半是 DSH 没有的概念
 * （上下文压缩、多模型切换、对话分叉、导出、草稿持久化）。
 * 硬造空壳比改接口更糟，所以这里只组装我们真正需要的部分。
 *
 * ## 滚动锚点
 *
 * 保持顶部为原点：流式消息永远是最后一条，它在底边长高 = 长在视口下方，
 * 屏上已显示的内容纹丝不动。反过来钉底边会把内容整体上顶
 * （SimpleChat 实测 0.5 秒滑走 861px，手指拦不住）。
 */
@Composable
fun ChatScreen(
    viewModel: ChatScreenViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }

    // 新消息到达时贴底。用 size 而不是内容变化：
    // 流式增量会疯狂触发，每次都滚会跟用户的主动上滑打架。
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    // 网络层 UiMessage → 渲染层 UiMessage。转换是 O(n)，跟着消息列表走。
    val uiMessages = remember(state.messages) { state.messages.map { it.toChatUi() } }

    Scaffold { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                // imePadding：键盘弹出时把输入区顶上去。
                // 配合 manifest 的 adjustResize —— 缺一个键盘都会盖住输入框。
                .imePadding(),
        ) {
            MessageList(
                messages = uiMessages,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                listState = listState,
                // 底部留给浮层输入栏，否则最后一条永远被压在下面滚不出来。
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                // 其余回调全部用默认值：本项目暂不支持重新生成/编辑/分叉/朗读。
                // 依赖默认值而不是传空 lambda —— 语义上「这个功能不存在」，
                // 而不是「存在但点了没反应」。
            )

            state.error?.let { err ->
                Text(
                    text = err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            InputBar(
                text = input,
                onTextChange = { input = it },
                // DSH 侧没有「思考档位」开关（模型由服务端决定）。
                thinkingEnabled = false,
                thinkingPanelOpen = false,
                onOpenThinkingPanel = {},
                settingsPanelOpen = false,
                onOpenSettingsPanel = {},
                streaming = state.busy,
                onSend = {
                    viewModel.send(input)
                    input = ""
                },
                onStop = { viewModel.stop() },
                // 附件是后续需求，先隐藏入口 ——
                // 显示一个点了没反应的按钮比不显示更糟。
                canAttach = false,
                onAttachClick = {},
                // DSH 不暴露上下文占用统计，给零值占位（圆环画成空的，不误导）。
                contextStats = EMPTY_CONTEXT_STATS,
                statsPanelOpen = false,
                onOpenStatsPanel = {},
            )
        }
    }
}

/** 没有上下文统计时的零值。 */
private val EMPTY_CONTEXT_STATS = ContextStats(
    usedTokens = 0,
    windowTokens = ModelInfo.DEFAULT_CONTEXT_WINDOW,
    messageCount = 0,
    totalTokens = 0,
    compactedTokenSaving = 0,
    avgTtftMs = null,
    avgTps = null,
    totalThinkingSeconds = null,
    hasCompaction = false,
)

/**
 * 网络层的 UiMessage → 渲染层的 UiMessage。
 *
 * 两者同名但不同包：`compose.ChatScreenViewModel.NetMessage` 是协议视图
 * （role/text/status/tool），`ui.chat.UiMessage` 是渲染视图
 * （含 parentId/variantIndex/timeline 等）。转换刻意集中在一处 ——
 * 散在 UI 里改一个字段要翻好几处。
 */
private fun ChatViewModelMsg.toChatUi(): UiMessage {
    // 显式用 this@toChatUi 访问接收者成员：
    // 参数名与成员名（id/role/content/status）大量重名，不加限定会被参数遮蔽。
    val src = this@toChatUi
    return UiMessage(
        id = src.id,
        role = when (src.role) {
            NetMessage.Role.User -> MessageRole.USER
            NetMessage.Role.Assistant -> MessageRole.ASSISTANT
        },
        content = src.text,
        // 空思考内容要转 null —— 否则 UI 会展开一个空的思考区。
        reasoning = src.reasoning.takeIf { it.isNotEmpty() },
        status = when (src.status) {
            NetMessage.Status.Streaming -> MessageStatus.STREAMING
            NetMessage.Status.Done -> MessageStatus.DONE
            NetMessage.Status.Stopped -> MessageStatus.STOPPED
            NetMessage.Status.Error -> MessageStatus.ERROR
        },
        // DSH 没有消息树：parentId 留空（UI 里「根消息」的约定），
        // variantIndex/Count 固定 0/1 —— 没有多版本可切。
        parentId = "",
        variantIndex = 0,
        variantCount = 1,
    )
}

private typealias ChatViewModelMsg = NetMessage
