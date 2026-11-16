package cd.ptrel.dshpostapi.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cd.ptrel.dshpostapi.CompatApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cd.ptrel.dshpostapi.ui.chat.followBottom
import cd.ptrel.dshpostapi.ui.chat.rememberIsAtBottom
import cd.ptrel.dshpostapi.ui.chat.scrollToBottom
import cd.ptrel.dshpostapi.net.ModelInfo
import cd.ptrel.dshpostapi.ui.chat.ContextStats
import cd.ptrel.dshpostapi.ui.chat.InputBar
import cd.ptrel.dshpostapi.ui.chat.ChatTopBar
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
    /**
     * 顶部标题 = 当前会话名称（v0.10.3）。
     *
     * 从会话列表传进来，而不是在这里再请求一次 —— 列表已经有标题了，
     * 多一次请求既慢又可能拿到不同的值（列表缓存 vs 服务端最新）。
     */
    title: String = "",
    /** 左上返回：回会话列表。 */
    onBack: () -> Unit = {},
    /** 右上新建会话。 */
    onNewChat: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }

    // ---- 待发送图片（v0.11.0）----
    // 读成 PendingImage 而不是留着 Uri：
    //   ① Uri 的临时授权可能过期（尤其从相册选完、切后台再回来）；
    //   ② 发送瞬间才读取会让「点发送」卡一下（几 MB 的 base64 编码）；
    //   ③ 提前读出来还能立刻按大小/格式拒掉，用户当场就知道，不用等发送失败。
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingImages by remember { mutableStateOf<List<CompatApi.PendingImage>>(emptyList()) }
    var imageError by remember { mutableStateOf<String?>(null) }

    // ⚠️ 用 GetContent（不是 PickVisualMedia）：后者需要 activity-result 库，
    //    本项目没引。GetContent 是标准 API，且允许用户从「最近/相册/文件」任选来源。
    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // 读盘 + base64 编码必须在 IO 线程 —— 一张 4MB 的图在主线程编码会明显卡顿
            val img = withContext(Dispatchers.IO) { CompatApi.readImage(context, uri) }
            if (img == null) {
                // 失败原因只有两种：格式不支持 / 文件太大。合并成一句，
                // 用户不需要区分（两者都是「换一张」）。
                imageError = "图片无法添加（仅支持 PNG/JPEG/WebP/GIF，且不超过 4MB）"
            } else if (pendingImages.size >= MAX_PENDING_IMAGES) {
                imageError = "最多一次发送 " + MAX_PENDING_IMAGES + " 张图片"
            } else {
                pendingImages = pendingImages + img
                imageError = null
            }
        }
    }

    // ---- 贴底跟随（v0.10.1 修正）----
    //
    // ⚠️ 早先只监听 messages.size —— 流式增量**不改变条数**（只改最后一条的
    //    text），所以永远不触发，用户看到内容在长但屏不动，得自己往下滑。
    //
    // 现在监听「最后一条的内容长度」：新消息、以及流式追加都会变。
    // 用长度而不是整个 text：字符串比较是 O(n)，长度是 O(1)，而截断/改写
    // 在流式场景不存在（只会追加），长度变化已足够触发。
    val lastMessageLen = state.messages.lastOrNull()?.text?.length ?: 0
    val isAtBottom by rememberIsAtBottom(listState)

    // ⚠️ v0.10.2：区分「首次定位」与「流式跟随」。
    //
    // 原来只有一个 LaunchedEffect 且都以 isAtBottom 为门槛 —— 首次进入时
    // 列表刚拿到数据、还没滚动过，canScrollForward 为 true ⇒ isAtBottom=false
    // ⇒ 直接 return，用户进来永远停在最上面。
    //
    // 首次定位必须**无条件**贴底：用户点进会话就是要看最新那句话，
    // 不存在「他已经在上面读历史」这种情况。
    var positioned by remember(state.sessionId) { mutableStateOf(false) }
    LaunchedEffect(state.messages.size) {
        if (state.messages.isEmpty()) return@LaunchedEffect
        if (!positioned) {
            // 等一帧让 LazyColumn 完成首次布局 —— 列表还没量出高度时
            // scrollToItem 会被忽略（表现为「没滚动」）。
            withFrameNanos { }
            listState.scrollToBottom()
            positioned = true
            return@LaunchedEffect
        }
    }

    // ---- 流式跟随（v0.11.1 修正）----
    //
    // ⚠️ 不能用 isAtBottom 作门槛。它的定义是 `!canScrollForward`，而那是
    //    Compose 的**布局状态**，在**下一帧**才更新 ——
    //    流式每追加一点内容，canScrollForward 就变 true，
    //    于是下一帧 isAtBottom=false ⇒ 跟随停掉。
    //    实测表现：只滚一两下就停，用户得不断手动往下拉。
    //
    // 正确的判据是「**用户有没有主动滚开**」，那是输入意图，不是布局状态。
    // 用 listState.isScrollInProgress：手指按着拖动时它为 true，此时不要抢。
    // ---- 跟随标志（v0.11.2，对齐官方 Web 的做法）----
    //
    // 官方的 dsh-client-ui-chat 用**独立于布局的 follow 布尔**：
    //   scrollToBottom(follow, behavior)  —— follow 由用户滚动位置维护
    //   [data-chat-following-tail] .scroll { overflow-anchor: none }
    // 而不是每次问「当前是不是在底部」。
    //
    // 为什么必须独立：`canScrollForward` 是布局状态，流式追加瞬间它就是 true，
    // 于是「刚才还在底部」这个**意图**被误判成「用户滚开了」，跟随中断。
    // 实测表现为只滚一两下就停。
    //
    // 维护方式：订阅滚动位置，用户主动向上滑（firstVisibleItemIndex 回退或
    // 向后滚动超过阈值）就关掉 follow；滑回底部就重新打开。
    var following by remember(state.sessionId) { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canForward) ->
                // 只在**用户手指参与**时改意图；程序滚动（我们自己调 followBottom）不算。
                if (scrolling) {
                    // 手指在动：他往上拖就停止跟随
                    following = !canForward
                }
            }
    }

    LaunchedEffect(lastMessageLen) {
        if (state.messages.isEmpty() || !positioned) return@LaunchedEffect
        // 用户正在拖动/惯性滑动：让给他，别抢
        if (listState.isScrollInProgress) return@LaunchedEffect
        // 用**意图标志**而不是布局状态判断要不要跟（见上方说明）
        if (!following) return@LaunchedEffect
        // followBottom 每帧吃掉剩余距离的 0.7（见 ScrollToBottom.kt 的长注释）：
        // 内容每 50ms 离散来一批，直接 scrollToItem 会变成「每 50ms 跳一个台阶」。
        listState.followBottom()
    }

    // 网络层 UiMessage → 渲染层 UiMessage。转换是 O(n)，跟着消息列表走。
    val uiMessages = remember(state.messages) { state.messages.map { it.toChatUi() } }

    Scaffold(
        topBar = {
            // 用移植来的 ChatTopBar：☰ 标题 ｜ 右侧新建。
            // 早先这一页**没有任何顶栏** —— 用户进了会话就出不去（只能按系统返回键），
            // 也看不到自己在哪个会话里。
            ChatTopBar(
                // 空标题给占位文案，而不是留一条空白顶栏 ——
                // 新会话在发出第一条消息前就是没有标题的，
                // 顶栏空着会让用户以为页面没加载出来。
                title = title.ifBlank { "新会话" },
                // onMenu 在这里等价于「返回会话列表」：DSH 没有抽屉，
                // 会话列表就是上一级。图标沿用它原来的 Menu（☰），语义也对得上。
                onMenu = onBack,
                onNewChat = onNewChat,
            )
        },
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                // imePadding：键盘弹出时把输入区顶上去。
                // 配合 manifest 的 adjustResize —— 缺一个键盘都会盖住输入框。
                .imePadding()
                // navigationBarsPadding：避开系统手势条 / 三键导航栏。
                //
                // ⚠️ 缺它时输入栏贴死在屏幕最底部，发送键压在手势条上
                // （实测发送键 bounds=[929,2175][979,2225]，屏幕 2400 高、
                //  手势条约 130~190px，两者重叠）。表现为「输入框紧贴底边、
                //  点发送偶尔被系统手势抢走」。
                //
                // 加在 Column 而不是 InputBar：配合 weight(1f) 的 MessageList，
                // 输入栏自然被抬到安全区之上，列表高度也同步缩短 —— 一处生效两处都对。
                // 只加在 InputBar 上则列表仍会延伸到手势条下面（滚到底被遮）。
                .navigationBarsPadding()
                // ⚠️ 再补一段固定底部间距（v0.11.1）。
                //
                // 为什么不能只靠 insets：insets 是**设备相关**的 ——
                //   · 手机手势条 ≈ 48~72dp，`navigationBarsPadding` 已经留够；
                //   · 平板/模拟器三键或全面屏手势关闭时 insets = 0，
                //     输入栏就直接压在屏幕物理底边上，视觉上「贴着底」。
                // 同一份代码在两种设备上得到两种观感，这是 insets 的固有属性，
                // 不是 bug。补一个**与设备无关**的最小底部留白即可对齐观感。
                //
                // 底部留白 = 左右留白（v0.11.1 用户要求「三边视觉等距」）。
                //
                // 关键在于**自动跟随**而不是硬编码：
                //   左右留白由 [ChatSidePadding] 决定，这里用同一个值，
                //   两处不会失同步（早先两处各写 16/12，就出现了「底下偏挤」）。
                //
                // 为什么不直接用 navigationBarsPadding 的下沿：
                //   那是**设备相关**的（手机手势条 48~72dp、平板三键 0），
                //   insets 为 0 时卡片会直接压到屏幕物理底边。
                //   左右留白是**设计常量**，用它与底部对齐才在任何设备上观感一致。
                .padding(bottom = ChatSidePadding),
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

            (imageError ?: state.error)?.let { err ->
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
                    viewModel.send(input, pendingImages)
                    input = ""
                    pendingImages = emptyList()
                    imageError = null
                },
                onStop = { viewModel.stop() },
                canAttach = true,
                // v0.11.0：⊕ 打开系统图片选择器。
                // （不做「图片/文件」二级面板 —— 目前只支持图片，多一层选择是多余的点击。
                //   将来支持任意文件时再加面板。）
                onAttachClick = { pickImage.launch("image/*") },
                pendingCount = pendingImages.size,
                // 对话设置 / 思考档位 / 模型选择都是 SimpleChat 的概念，
                // DSH 侧由服务端决定，客户端没有对应面板。
                // 早先传空 lambda 导致按钮渲染出来但点了没反应（用户反馈)。
                showSettingsEntries = false,
                // DSH 不暴露上下文占用统计，给零值占位（圆环画成空的，不误导）。
                contextStats = EMPTY_CONTEXT_STATS,
                statsPanelOpen = false,
                onOpenStatsPanel = {},
            )
        }
    }
}

/**
 * 聊天页的水平 / 底部留白（v0.11.1）。
 *
 * 一处定义、两处使用：InputBar 的水平内边距 + 本页的底部内边距。
 * 这样「卡片到屏幕左/右/底三边等距」由结构保证，而不是靠两个常量碰巧相等。
 *
 * ⚠️ 改这里之前先想清楚：它同时决定消息区的左右边距（MessageList 的 contentPadding
 *    另有一份，若要严格对齐需一并改）。
 */
private val ChatSidePadding = 16.dp

/** 一次最多带几张图。插件侧也是 4（超出会被丢弃并记日志）。 */
private const val MAX_PENDING_IMAGES = 4

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
