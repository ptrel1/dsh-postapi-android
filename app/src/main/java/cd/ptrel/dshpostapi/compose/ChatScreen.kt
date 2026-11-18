package cd.ptrel.dshpostapi.compose

import androidx.compose.ui.draw.clip
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Row
import cd.ptrel.dshpostapi.ui.common.AppIcons
import cd.ptrel.dshpostapi.data.Res

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
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
import cd.ptrel.dshpostapi.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cd.ptrel.dshpostapi.ui.chat.followBottom
import cd.ptrel.dshpostapi.ui.chat.rememberIsAtBottom
import cd.ptrel.dshpostapi.ui.chat.scrollToBottom
import cd.ptrel.dshpostapi.net.ModelInfo
import cd.ptrel.dshpostapi.ui.chat.ContextStats
import cd.ptrel.dshpostapi.ui.chat.InputBar
import cd.ptrel.dshpostapi.ui.common.AppBackground
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
    /** 自定义背景图路径（v0.12.0），null = 用主题色。与会话列表共享同一个设置。 */
    backgroundPath: String? = null,
    /** 提交对外部提问的回答（v0.13.0）。 */
    onAnswerQuestion: (callId: String, answers: List<cd.ptrel.dshpostapi.CompatApi.AnswerItem>) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }

    /** 输入栏实测高度（px→dp）。用于给消息列表留底部空间 —— 见 contentPadding。 */
    var inputBarHeight by remember { mutableStateOf(0.dp) }
    val density = androidx.compose.ui.platform.LocalDensity.current

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
    /*
     * ═══ 滚动跟随：锁定底部（v0.16.4 重构）═══
     *
     * ## 前面的方案为什么一直在打补丁
     *
     * v0.10 ~ v0.16.3 的写法是「**枚举触发原因**」：
     *   lastMessageLen 变了 → 滚
     *   busy 变了          → 滚
     *   messages.size 变了 → 滚
     *   layoutInfo 变了    → 滚
     *
     * 每漏一种信号就多一个 bug。实测漏过两次：
     *   · 流式结束（文本不再增长，但多出操作栏）
     *   · 命令执行完（工具条目展开，文本没变）
     *
     * ## 现在：只维护一个状态
     *
     *   locked == true  → **任何**布局变化都滚到底（不判断原因）
     *   locked == false → 完全不动
     *
     * 「什么变了」不再重要 —— 只要内容比视口高、且用户锁在底部，就跟。
     * 这样新增任何会改变高度的 UI（新的工具卡片、折叠块）都自动被覆盖，
     * 不需要再记得回来加一条触发条件。
     *
     * 锁的解开**只由手指决定**（位置回退），不由布局状态决定 ——
     * 那正是 v0.15.10 踩过的坑（canScrollForward 会把「内容变长」误判成「用户上拉」）。
     */
    var locked by remember(state.sessionId) { mutableStateOf(true) }

    // ① 用户意图：手指往上拖 → 解锁；拖回底部 → 重新锁定。
    //
    // ⚠️ v0.18.1：这一版是**第三版**，前两版都错在同一个地方 ——
    // 试图用 isScrollInProgress 判断「是不是用户在滚」。
    //
    // 但它的语义是「列表正在滚动」，包括：
    //   · 手指拖动        ← 只有这个该改意图
    //   · 惯性滑动
    //   · **程序自己调 scrollBy（followBottom）**  ← 致命
    //
    // followBottom 滚到底时 offset 会快速变化，某些帧因重新布局短暂回退，
    // 于是 `pos < lastPos - 8f` 成立 → locked 被置 false → 跟随永久停掉。
    // 用户看到的正是「聊着聊着就不跟了」。
    //
    // 现在改用 **nestedScroll**：只有手指拖动才会走 onPreScroll / onPostScroll，
    // 程序滚动（scrollBy）不经过 nestedScroll 链路。这是 Compose 里唯一
    // 能可靠区分「谁引起的滚动」的地方。
    var fingerDelta = 0f
    val scrollWatcher = androidx.compose.runtime.remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                // 手指往上推（available.y < 0）说明用户在看更早的内容 → 解锁
                if (source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput && available.y < 0f) {
                    fingerDelta += available.y
                    if (fingerDelta < -8f) locked = false
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(listState) {
        // 滑回底部就重新锁定。「到底」是布局事实，不需要手指参与 ——
        // 但只在**用户刚动过**之后才检查，否则程序滚到底也会把它锁上，
        // 而程序滚到底不代表用户改主意了。
        androidx.compose.runtime.snapshotFlow {
            listState.isScrollInProgress to !listState.canScrollForward
        }.collect { (scrolling, atBottom) ->
            if (!scrolling && atBottom) {
                fingerDelta = 0f
                locked = true
            }
        }
    }

    // ② 跟随：只看「锁着没」+「布局变了没」，不看变化的原因。
    LaunchedEffect(listState, locked) {
        if (!locked) return@LaunchedEffect
        snapshotFlow {
            // 布局指纹：总项数 + 最后一个可见项的下标与偏移。
            // 任何会让内容变高的变化（新 token、操作栏、工具条目展开）都会改动它。
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            Triple(info.totalItemsCount, last?.index ?: -1, last?.offset ?: 0)
        }.collect {
            if (listState.isScrollInProgress) return@collect
            if (!locked) return@collect
            // 内容没超出视口时列表根本不滚动，补了也没用。
            // 这同时是**防止死循环**的闸：followBottom 一次滚到底后
            // canScrollForward 变 false，下一次 collect 就跳过。
            if (listState.canScrollForward) listState.followBottom(density)
        }
    }

    // 网络层 UiMessage → 渲染层 UiMessage。转换是 O(n)，跟着消息列表走。
    val uiMessages = remember(state.messages) { state.messages.map { it.toChatUi() } }

    // v0.12.0：与会话列表同一套背景（用户设一次，两页都生效）。
    // 包在 Scaffold 外面：Scaffold 会自己铺底色，包里面等于被盖住。
    AppBackground(path = backgroundPath) {
    Scaffold(
        // 透明底：Scaffold 默认用 colorScheme.background 铺满整块，
        // 会把外层的 AppBackground 完全盖住（会话列表那边没 Scaffold，
        // 所以只在聊天页出现「背景没生效」）。
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
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
        // v0.13.0：外部提问的回答面板。
        // 叠在 Scaffold 内容之上而不是塞进列表里：它是**阻塞式交互**，
        // 用户必须先答完才能看到后续内容，塞进列表会被滚走。
        val pending = state.pendingQuestion
        if (pending != null) {
            PendingQuestionDialog(
                pending = pending,
                onAnswer = { callId, answers -> onAnswerQuestion(callId, answers) },
                onDismiss = { onAnswerQuestion(pending.callId, emptyList()) },
            )
        }
        // v0.17.3：Column → Box。
        //
        // 原来 Column 里 MessageList 用 weight(1f)，消息区到输入栏上沿就截止 ——
        // 文字永远不会出现在输入栏「下方」，用户看到的是一堵墙把内容切断。
        //
        // 改成 Box 叠放：列表铺满整页（含输入栏区域），输入栏浮在底部。
        // 于是上滑时文字会从输入栏底下经过 —— 那才是「玻璃浮在内容之上」的样子。
        androidx.compose.foundation.layout.Box(
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
                // ⚠️ v0.18.2：这里原本**连着两行** .navigationBarsPadding()，
                // 底部又被下面那个 padding(bottom = ...) 再加一次 ——
                // 三层 inset 叠加，输入栏被抬到离屏幕底边很远的位置，
                // 下方留出一条谁也说不清是什么的空白（用户截图反馈）。
                //
                // 只保留**下面那个 padding**：它已经取了「手势条高度与 16dp 的较大值」，
                // 语义更精确（贴齐手势区上沿，且三键机上也与左右留白一致）。
                //
                // 早先是 navigationBarsPadding() 后面再加 padding(bottom = 16.dp)，两者相加 ——
                // 在 gesture inset ≈ 24dp 的机型上变成 40dp，而左右是 16dp，
                // 视觉上底部明显偏空（用户反馈「底部距离在这个手机上不一样」）。
                //
                // 改成「取大」：手势条比 16dp 高就用手势条高度（恰好贴齐手势区上沿），
                // 手势条为 0（三键关闭 / 平板）就用 16dp（与左右一致）。
                // 两种设备上「输入框到屏幕底边的距离」都等于左右留白，观感统一。
                .padding(
                    bottom = WindowInsets.navigationBars.asPaddingValues()
                        .calculateBottomPadding()
                        .coerceAtLeast(ChatSidePadding),
                ),
        ) {
            MessageList(
                messages = uiMessages,
                modifier = Modifier
                    .fillMaxSize()
                    // v0.18.1：nestedScroll 是唯一能区分「手指拖动」与「程序滚动」的入口。
                    // 程序调 scrollBy（followBottom）不走这条链路，所以不会再误判成用户上拉。
                    .nestedScroll(scrollWatcher),
                listState = listState,
                // v0.15.3：运行状态作为消息流最后一条（接近法则：紧邻它所描述的那一轮）。
                busy = state.busy,
                busyStartedAt = state.busyStartedAt,
                // v0.12.1：只保留「复制」。重新生成/编辑/删除依赖服务端能力，
                // 而 DSH 的会话日志是追加式的，改删在语义上不成立。
                showMutatingActions = false,
                // v0.12.2：接入复制 —— 之前只隐藏了另外三个，复制本身也没接，
                // 表现为「点了没反应」（用户反馈）。
                onCopy = { msg ->
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    clipboard.setPrimaryClip(
                        android.content.ClipData.newPlainText("消息", msg.content),
                    )
                    // Android 13+ 会在系统层显示「已复制」气泡，无需自己弹 Toast；
                    // 低版本系统没有那个提示，统一给一个短 Toast 保证有反馈。
                    if (android.os.Build.VERSION.SDK_INT < 33) {
                        android.widget.Toast.makeText(
                            context, R.string.action_copied, android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                // 底部留给浮层输入栏，否则最后一条永远被压在下面滚不出来。
                // v0.17.3：底部 padding 要留出**输入栏高度**。
                //
                // 列表现在铺满整页（含输入栏区域），如果只留 12dp，
                // 滚到底时最后一行会被输入栏压在下面 —— 用户看不到最新内容。
                // 220dp 是输入栏在最坏情况（多行文字 + 图片缩略图 + 一排按钮）下的高度上限。
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    // v0.18.4：不再写死 220dp。
                    //
                    // 220dp 是按「多行文字 + 图片缩略图 + 按钮排」的最坏情况估计的，
                    // 单行时输入栏只有 ~120dp，多出来的 100dp 就是用户看到的
                    // 「拉到底下面还空一块」（截图反馈）。
                    //
                    // 改成读取输入栏的实测高度；还没量出来时给个保守下限，
                    // 避免首帧内容被压在输入栏底下。
                    bottom = if (inputBarHeight > 0.dp) inputBarHeight + 12.dp else 140.dp,
                ),
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
                modifier = androidx.compose.ui.Modifier
                    .align(androidx.compose.ui.Alignment.BottomCenter)
                    // v0.18.4：量出输入栏真实高度，给消息列表留出**恰好**的底部空间。
                    //
                    // 写死 220dp 时单行输入栏只占 ~120dp，多出的 100dp 表现为
                    // 「拉到底下面还空一块」。
                    .onGloballyPositioned { coords ->
                        val h = with(density) { coords.size.height.toDp() }
                        // 只在变化超过 1dp 时写状态，避免每帧触发重组。
                        if (kotlin.math.abs((h - inputBarHeight).value) > 1f) {
                            inputBarHeight = h
                        }
                    },
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
                // v0.13.2：把图片内容交给输入栏显示缩略图。
                // 之前只传数量，界面上看不到任何东西 —— 用户以为没选上。
                pendingImages = pendingImages.map { it.base64 to it.name },
                onRemovePendingImage = { idx ->
                    pendingImages = pendingImages.filterIndexed { i, _ -> i != idx }
                },
                // 对话设置 / 思考档位 / 模型选择都是 SimpleChat 的概念，
                // DSH 侧由服务端决定，客户端没有对应面板。
                // 早先传空 lambda 导致按钮渲染出来但点了没反应（用户反馈)。
                showSettingsEntries = false,
                // DSH 不暴露上下文占用统计，给零值占位（圆环画成空的，不误导）。
                contextStats = EMPTY_CONTEXT_STATS,
                statsPanelOpen = false,
                onOpenStatsPanel = {},
            )
            // ── v0.18.5：两个跳转按钮 ────────────────────────────────
            //
            // 只在**不在底部**时出现。理由：在底部时这两个按钮都是「我已经在的位置」，
            // 常驻会让右下角一直有两块浮层，遮挡阅读。
            //
            // 位置在输入栏正上方：那是视线自然停留的地方（用户刚打完字），
            // 也避免与输入栏的发送键重叠。
            if (!isAtBottom) {
                Row(
                    modifier = Modifier
                        .align(androidx.compose.ui.Alignment.BottomEnd)
                        // 输入栏高度 + 一点余量：贴着输入栏上沿，不压住它
                        .padding(
                            end = 16.dp,
                            bottom = (if (inputBarHeight > 0.dp) inputBarHeight else 140.dp) + 12.dp,
                        ),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    // 跳到本题开头：最后一条**用户**消息的位置。
                    //
                    // 日常聊天里读长回答时最常用的动作 —— 想重看这道题是怎么问的。
                    // 写代码时反而少用（那边更关心最新输出），所以放在次要位置（左侧）。
                    JumpChip(
                        text = "跳到本题",
                        icon = AppIcons.ArrowUp,
                        onClick = {
                            val idx = jumpToQuestionIndex(uiMessages)
                            if (idx >= 0) {
                                // v0.18.8：**先解锁、再滚动**，都放进同一个协程。
                                //
                                // 这里用 animateScrollToItem 是对的 —— 我们要的正是
                                // 「让那条用户消息对齐视口顶部」，与本轮回答从第一行开始读的意图一致。
                                // （回到底部用的是另一个语义，见那个按钮的注释。）
                                locked = false
                                scope.launch { listState.animateScrollToItem(idx) }
                            }
                        },
                    )
                    JumpChip(
                        text = Res.get(R.string.chat_scroll_bottom),
                        icon = AppIcons.ArrowDown,
                        onClick = {
                            // v0.18.8：**不能用 animateScrollToItem(lastIndex)**。
                            //
                            // 那个 API 是把「该 item 的**顶边**」对齐到视口顶部 ——
                            // 最后一条如果比屏幕矮，滚完它仍在顶部，下面空一大块，
                            // 表现为「点了没回到底部」。
                            //
                            // 正确做法是滚到「不能再往后滚」为止：scrollToBottom() 用一个
                            // 超大 delta，ScrollableState 会自动夹到内容末尾。
                            //
                            // 顺序也有讲究：**先滚动、后上锁**。反过来的话，
                            // 动画期间 follow 的 effect 看到 locked 已经为 true 但
                            // 布局还没变，不会做事；等它真滚动时又可能被用户手势打断。
                            scope.launch {
                                listState.scrollToBottom()
                                locked = true
                            }
                        },
                    )
                }
            }
        }
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
        // v0.14.0：执行过程透传给渲染层。
        execSteps = src.steps,
    )
}

private typealias ChatViewModelMsg = NetMessage

/**
 * 外部提问的回答面板（v0.13.0）。
 *
 * ## 为什么用 Dialog 而不是内联卡片
 *
 * ask_user_question 是**阻塞式**的：agent 停在工具调用里等回答，不答就不会有后续输出。
 * 内联卡片会被消息列表滚走，用户看不到「有人在等」。Dialog 保证它始终在视线内。
 *
 * ## 多选与开放输入
 *
 * · options 为空 → 开放式输入（自由文本），走 custom 字段
 * · multiSelect = true → 可多选
 * · 其余 → 单选
 *
 * ## 为什么 onDismiss 也提交空答案
 *
 * 用户点「关闭」= 拒绝回答。这时必须给服务端一个结果，否则 agent 永远挂着。
 * 提交空 answers 让服务端按「未作答」处理。
 */
@androidx.compose.runtime.Composable
fun PendingQuestionDialog(
    pending: cd.ptrel.dshpostapi.CompatApi.PendingQuestion,
    onAnswer: (String, List<cd.ptrel.dshpostapi.CompatApi.AnswerItem>) -> Unit,
    onDismiss: () -> Unit,
) {
    // 每个问题各自的选择状态：questionId → 选中的标签集合
    val selections = androidx.compose.runtime.remember(pending.callId) {
        androidx.compose.runtime.mutableStateMapOf<String, MutableSet<String>>()
    }
    // 开放式输入：questionId → 文本
    val customs = androidx.compose.runtime.remember(pending.callId) {
        androidx.compose.runtime.mutableStateMapOf<String, String>()
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            androidx.compose.material3.Text(
                text = pending.questions.firstOrNull()?.header?.takeIf { it.isNotBlank() } ?: "需要你的回答",
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            androidx.compose.foundation.layout.Column(
                modifier = androidx.compose.ui.Modifier
                    .fillMaxWidth()
                    .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            ) {
                pending.questions.forEach { q ->
                    androidx.compose.material3.Text(
                        text = q.question,
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    )
                    q.detail?.takeIf { it.isNotBlank() }?.let {
                        androidx.compose.material3.Text(
                            text = it,
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (q.options.isEmpty()) {
                        // 开放输入
                        androidx.compose.material3.OutlinedTextField(
                            value = customs[q.id] ?: "",
                            onValueChange = { customs[q.id] = it },
                            label = { androidx.compose.material3.Text("输入你的回答") },
                            modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                        )
                    } else {
                        q.options.forEach { opt ->
                            val sel = selections.getOrPut(q.id) { mutableSetOf() }
                            val checked = sel.contains(opt.label)
                            androidx.compose.foundation.layout.Row(
                                modifier = androidx.compose.ui.Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (q.multiSelect) {
                                            if (checked) sel.remove(opt.label) else sel.add(opt.label)
                                        } else {
                                            sel.clear()
                                            sel.add(opt.label)
                                        }
                                        // 触发重组：mutableStateMapOf 的值是普通 Set，需重新赋值
                                        selections[q.id] = sel.toMutableSet()
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = null)
                                androidx.compose.material3.Text(
                                    text = opt.label,
                                    modifier = androidx.compose.ui.Modifier.padding(start = 8.dp),
                                )
                            }
                            opt.description?.takeIf { it.isNotBlank() }?.let {
                                androidx.compose.material3.Text(
                                    text = it,
                                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = androidx.compose.ui.Modifier.padding(start = 36.dp, bottom = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    val answers = pending.questions.map { q ->
                        val sel = selections[q.id]?.toList() ?: emptyList()
                        val custom = customs[q.id]?.takeIf { it.isNotBlank() }
                        cd.ptrel.dshpostapi.CompatApi.AnswerItem(q.id, sel, custom)
                    }
                    onAnswer(pending.callId, answers)
                },
            ) { androidx.compose.material3.Text("提交") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                androidx.compose.material3.Text("关闭")
            }
        },
    )
}

/**
 * 找到「本次提问」在列表中的下标（v0.18.5）。
 *
 * 判据：**最后一条 role == USER 的消息**。
 *
 * 为什么这样就够 —— 一轮对话的结构是「用户提问 → 助手回答」，
 * 所以最后一条用户消息必然是本轮提问的开头。不需要额外的 turn 编号，
 * 也不依赖服务端给任何东西。
 *
 * 边界：
 *   · 没有用户消息（空会话）→ 返回 -1，调用方不动作
 *   · 最后一条用户消息已经是第一条 → 跳过去等于回到顶部，语义上正确
 */
private fun jumpToQuestionIndex(messages: List<cd.ptrel.dshpostapi.ui.chat.UiMessage>): Int {
    for (i in messages.indices.reversed()) {
        if (messages[i].role == cd.ptrel.dshpostapi.ui.chat.MessageRole.USER) return i
    }
    return -1
}

/**
 * 浮动跳转胶囊（v0.18.5）。
 *
 * 半透明底 + 描边，与玻璃表面同一套语言 —— 但**不做模糊**：
 * 它浮在内容之上且会移动，每帧重算模糊不值得（同 GlassSurface 的判断）。
 */
@Composable
private fun JumpChip(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    val colors = cd.ptrel.dshpostapi.ui.theme.LocalChatColors.current
    Row(
        modifier = Modifier
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(colors.card.copy(alpha = 0.86f))
            .border(
                1.dp,
                colors.outline.copy(alpha = 0.5f),
                androidx.compose.foundation.shape.CircleShape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        androidx.compose.foundation.layout.Spacer(Modifier.size(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
