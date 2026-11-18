package cd.ptrel.dshpostapi.ui.chat

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.Image
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxSize
import cd.ptrel.dshpostapi.ui.common.DeepDivingLabel
import cd.ptrel.dshpostapi.ui.common.SpinRing
import cd.ptrel.dshpostapi.ui.common.BusyDots
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeoutOrNull
import cd.ptrel.dshpostapi.R
import cd.ptrel.dshpostapi.ui.common.ContainerShape
import cd.ptrel.dshpostapi.ui.common.AppIcons
import cd.ptrel.dshpostapi.ui.common.AppShadow
import cd.ptrel.dshpostapi.ui.common.ContainerShape
import cd.ptrel.dshpostapi.data.TokenEstimate
import cd.ptrel.dshpostapi.data.Res
import cd.ptrel.dshpostapi.ui.common.IconCircleButton
import cd.ptrel.dshpostapi.ui.common.MotionEasing
import cd.ptrel.dshpostapi.ui.common.PillShape
import cd.ptrel.dshpostapi.ui.common.ProgressRing
import cd.ptrel.dshpostapi.ui.theme.LocalChatColors

/**
 * 输入区。
 *
 * 第二行布局（对齐 DeepSeek，但做了本项目自己的扩展）：
 *
 * ```
 * [ ⚙ ] [ 深度思考 ⌄ ]  ………………  [ ⊕ ] [ ↑/■ ]
 *   对话设置   思考档位+模型            附件   发送/停止
 * ```
 *
 * 两个入口都会在输入栏下方展开面板（与选图面板同一机制），输入栏随之上移。
 */
@Composable
fun InputBar(
    text: String,
    onTextChange: (String) -> Unit,
    thinkingEnabled: Boolean,
    thinkingPanelOpen: Boolean,
    /**
     * 智能搜索是否开启。
     *
     * ✦ 是「这一轮怎么生成」的唯一入口（思考档位与联网搜索都在面板里），
     * 所以两者**任一开启**都算激活 —— 否则开了搜索、星星还是黑的，用户会以为没生效。
     */
    webSearchEnabled: Boolean = false,
    onOpenThinkingPanel: () -> Unit,
    settingsPanelOpen: Boolean,
    onOpenSettingsPanel: () -> Unit,
    streaming: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    /** 附件入口是否显示。关掉是为了不给"点了没反应"的控件留位置。 */
    canAttach: Boolean = true,
    /**
     * 是否显示「对话设置」与「思考/搜索」入口（v0.10.1）。
     *
     * 这两个入口打开的是 SimpleChat 自己的面板（模型选择、思考档位、联网搜索）。
     * DSH 侧这些都由服务端决定，客户端没有对应概念 ——
     * 显示出来只会是「点了没反应」。
     *
     * 教训：控件不可用就不要渲染。空 onClick 比隐藏更糟 ——
     * 用户会反复点、怀疑 App 坏了，而隐藏是明确的「这里没有这个功能」。
     */
    showSettingsEntries: Boolean = true,
    /** 点 ⊕：由调用方展开附件面板（图片 / 纯文本文件）。 */
    onAttachClick: () -> Unit,
    /** 附件面板是否展开 —— 决定 ⊕ 要不要转成 ✕。 */
    attachPanelOpen: Boolean = false,
    /** 已挂上的附件数量。>0 时即使没打字也允许发送。 */
    pendingCount: Int = 0,
    /** 这一轮开始的时刻（SystemClock.elapsedRealtime，v0.15.1）。0 = 没在跑。 */
    busyStartedAt: Long = 0L,
    /**
     * 已选图片的缩略图（v0.13.2）。
     *
     * ## 为什么必须显示
     *
     * 之前只有 pendingCount —— 它只用来判断「发送按钮能不能点」。
     * 用户选完图后界面上**什么变化都没有**（只有按钮从灰变亮，很容易忽略），
     * 于是以为图没选上（这是用户实际反馈的「图片选择了之后没进框」）。
     *
     * 每一项是 (base64, 显示名)。base64 直接用来解码缩略图 ——
     * 图片本来就要以 base64 发给服务端，不必为了预览再存一份文件。
     */
    pendingImages: List<Pair<String, String>> = emptyList(),
    /**
     * 亚克力背景（v0.21.0）。null = 不用模糊，退回渐变+半透明。
     *
     * 由 ChatScreen 用 AcrylicBackdrop.build() 生成：把壁纸在输入栏位置的那块
     * 裁出来、降采样、模糊。因为输入栏位置固定，这张图**只在壁纸或尺寸变化时重算**。
     */
    acrylic: androidx.compose.ui.graphics.ImageBitmap? = null,
    /** 点缩略图右上角的 ✕ 移除这张。 */
    onRemovePendingImage: (Int) -> Unit = {},
    onTextFieldFocused: () -> Unit = {},
    /** 非空即处于「修改输入」态：顶部显示该标题 + 取消按钮。 */
    editingLabel: String? = null,
    onCancelEdit: () -> Unit = {},
    /** 下方有面板展开时，输入栏的投影淡出（两块合看成一体）。 */
    panelExpanded: Boolean = false,
    /** 上下文占用。圆环 + 长度，点开是统计面板。 */
    contextStats: ContextStats,
    statsPanelOpen: Boolean,
    onOpenStatsPanel: () -> Unit,
    /** 设备有没有可用的语音识别服务。没有就不露麦克风。 */
    showVoice: Boolean = false,
    /** 是否正在录音 —— 按钮换成"结束"状态。 */
    voiceListening: Boolean = false,
    onToggleVoice: () -> Unit = {},
    /**
     * 是否开启「长按输入框说话」。
     *
     * 由调用方决定：**设备有识别服务 + 已授权 + 扩展通道可用** 时才为 true。
     * 关掉时输入框一个手势都不多挂。
     */
    holdToTalkEnabled: Boolean = false,
    /** 长按开始（手指按住不放）。 */
    onVoiceHoldStart: () -> Unit = {},
    /** 松手结束。 */
    onVoiceHoldEnd: () -> Unit = {},
    /** 按住后上滑取消：不发送、丢弃识别结果。 */
    onVoiceHoldCancel: () -> Unit = {},
    /** 外部请求「现在发送一次」。值一变就触发，复用同一条发送路径。 */
    sendSignal: Int = 0,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current
    // v0.17.0：玻璃底色。OFF 档时保持原样（不透明），避免「关了玻璃还是一样」的困惑。
    val glassQuality = cd.ptrel.dshpostapi.ui.common.rememberGlassQuality()
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val glassTint = if (glassQuality == cd.ptrel.dshpostapi.ui.common.GlassQuality.OFF) {
        colors.card
    } else if (isDark) {
        // v0.17.3：0.86 → 0.62。
        //
        // 0.86 等于做成了实心块 —— 用户反馈「底部框背景不是透明的，
        // 上滑文字应该能放在背景上」。玻璃要能透出背景才有意义。
        //
        // 可读性改由**输入框自身的不透明底**保证（见下方 textField 的底色），
        // 而不是靠把整块玻璃做厚 —— 那会让它变成一堵墙。
        colors.card.copy(alpha = 0.62f)
    } else {
        colors.card.copy(alpha = 0.70f)
    }
    val shape = ContainerShape
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    /**
     * 「空框 + 有语音能力」才挂长按热区。
     *
     * 有字时长按要让位给**选词** —— 那是文本框自己的默认语义，抢掉它编辑会变得很难用。
     */
    val holdToTalkActive = holdToTalkEnabled && text.isEmpty()

    // 语音说完自动发送：外部把 sendSignal 改一下，这里复用同一条发送路径
    LaunchedEffect(sendSignal) {
        if (sendSignal > 0) onSend()
    }

    // 面板展开 → 投影淡出；收起 → 淡入。否则接缝处会同时压着两条阴影。
    val shadowElevation by animateDpAsState(
        targetValue = if (panelExpanded) 0.dp else AppShadow.PanelElevation,
        animationSpec = tween(durationMillis = 180),
        label = "inputBarShadow",
    )

    /*
     * 进入编辑态时自动聚焦并**拉起输入法**。
     *
     * 只调 requestFocus() 是不够的 —— 它只给焦点，系统输入法不会自己出来，
     * 表现为「点了消息、光标在闪、但键盘没弹」。必须显式 show()，
     * 且要等一帧让焦点真正落到输入框上，否则 show() 会被忽略。
     */
    LaunchedEffect(editingLabel) {
        if (editingLabel == null) return@LaunchedEffect
        runCatching {
            focusRequester.requestFocus()
            withFrameNanos { }
            keyboard?.show()
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 与菜单同一个投影族：先 shadow 再 clip，投影才不会被自家圆角裁掉
                .shadow(
                    elevation = shadowElevation,
                    shape = ContainerShape,
                    clip = false,
                    ambientColor = AppShadow.PanelAmbient,
                    spotColor = AppShadow.PanelSpot,
                )
                .clip(shape)
                // v0.17.0：输入栏改玻璃底。
                //
                // 原来是 colors.card（不透明卡片色），叠在壁纸上像一块贴纸。
                // 玻璃底让壁纸透出来，同时保留原有的圆角与投影 ——
                // 投影在外层 shadow 上，这里只管填充。
                //
                // 注意：输入栏在滚动时**位置不变**（固定在底部），
                // 所以它的模糊结果同样可以长期缓存 —— 与顶栏一样是静态受益者。
                // v0.18.6：垂直渐变替代纯色。
                //
                // 只给输入栏加 —— 它是全屏唯一「应该显得有实体厚度」的元素：
                //   · 顶栏已经用上深下透（v0.18.3）表示「淡出到内容」
                //   · 消息区是内容本身，叠渐变只会显脏
                //
                // 方向遵循全局光照（光从上方来）：顶部略浅、底部略深，
                // 与已有的 1px 顶边高光形成一致的光照声明。
                // 幅度刻意压得很小（±6% 亮度），多了就会变成「塑料感」。
                // v0.21.0：三档底色。
                //
                //   OFF      → 纯色（用户明确要省电）
                //   有亚克力  → 模糊的壁纸局部 + 色罩（真正的 Acrylic：模糊+色调）
                //   无亚克力  → 垂直渐变（壁纸没设 / 生成失败时的降级）
                //
                // 亚克力图由上层传入，尺寸与输入栏一致 —— 它已经按屏幕坐标裁好了，
                // 这里直接 fillMaxSize 铺满即可。
                .background(
                    if (glassQuality == cd.ptrel.dshpostapi.ui.common.GlassQuality.OFF) {
                        androidx.compose.ui.graphics.SolidColor(glassTint)
                    } else {
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(lighten(glassTint, 0.06f), glassTint, darken(glassTint, 0.05f)),
                        )
                    },
                )
                /*
                 * 按住说话：挂在**整条输入栏**上，而不是输入框上。
                 *
                 * 挂在输入框上会跟文本框自己的长按（选词 / 光标）打架 ——
                 * 那正是"打字和说话互相抢"的来源。这里只在**空输入框**时响应，
                 * 且**不消费**短按：点一下照常聚焦、光标照常落位。
                 */
                .pointerInput(holdToTalkActive) {
                    if (!holdToTalkActive) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        // 先松手就是普通点击，交给文本框
                        val releasedEarly = withTimeoutOrNull(
                            viewConfiguration.longPressTimeoutMillis,
                        ) { waitForUpOrCancellation() }
                        if (releasedEarly != null) return@awaitEachGesture
                        onVoiceHoldStart()
                        waitForUpOrCancellation()
                        onVoiceHoldEnd()
                    }
                }
                .border(1.dp, colors.outline, shape)
                // v0.11.1：vertical 12dp → 8dp。
                // 实测一行文字时卡片高 187px（≈71dp），比微信那类「一行胶囊」厚不少 ——
                // 用户反馈「输入框太高」。水平 16dp 保持不动：它决定卡片到屏幕左右的距离，
                // 与 ChatScreen 的 bottom padding 一起构成视觉等距。
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // ── 亚克力底层（v0.21.0）───────────────────────
            //
            // 画在最前面、后面所有内容之上有各自的底 ——
            // 这一层只负责「让壁纸以模糊的形式透出来」。
            //
            // 用 Box fillMaxSize 而不是 Modifier.background(ImageBitmap)：
            // background 不支持位图缩放策略，fillMaxSize 的 Image 才能让
            // 这张（可能是不同像素尺寸的）图正好铺满输入栏。
            if (acrylic != null) {
                androidx.compose.foundation.Image(
                    bitmap = acrylic,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .alpha(0.85f),
                )
                // ⚠️ 色罩必须叠在亚克力图**之上**。
                //
                // Column 的 background() 是最底层，Image 会盖住它 ——
                // 不补这一层的话，`acrylic` 就是一张未加色调的模糊壁纸，
                // 文字直接压上去，对比度全看壁纸本身。
                //
                // 用同一个 glassTint，保证「有壁纸」与「没壁纸」两档的色调一致。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .background(glassTint),
                )
            }

            // ── 已选图片缩略图（v0.13.2）───────────────────
            //
            // 之前完全没有预览，用户选完图看不到任何变化，以为没选上。
            // 放在输入框**上方**而不是里面：输入框是可滚动的文本区，
            // 塞进去会随文本一起滚走，图片就跑到视野外了。
            if (pendingImages.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pendingImages.forEachIndexed { index, item ->
                        Box {
                            PendingThumb(b64 = item.first, contentDescription = item.second)
                            // 移除按钮压在右上角：选错图要能去掉，
                            // 否则只能清空整条输入重新选。
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.55f))
                                    .clickable { onRemovePendingImage(index) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("×", color = Color.White, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // ── 「修改输入」标题行 ─────────────────────────
            if (editingLabel != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = editingLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.placeholder,
                        modifier = Modifier.weight(1f),
                    )
                    IconCircleButton(
                        icon = AppIcons.Close,
                        contentDescription = stringResource(R.string.input_cancel_edit),
                        onClick = onCancelEdit,
                        size = 26.dp,
                        iconSize = 14.dp,
                        tint = colors.processText,
                    )
                }
            }

            /*
             * 光标：**外部换文本**（恢复草稿 / 切会话）时放到末尾 —— 那是"接着往下写"；
             * 用户自己打字时光标不动。
             *
             * `BasicTextField` 的 `String` 重载不保证换文本后光标落在哪，这里自己钉住。
             */
            var fieldValue by remember { mutableStateOf(TextFieldValue(text = text)) }
            LaunchedEffect(text) {
                if (fieldValue.text != text) {
                    fieldValue = TextFieldValue(text = text, selection = TextRange(text.length))
                }
            }
            BasicTextField(
                value = fieldValue,
                onValueChange = {
                    fieldValue = it
                    if (it.text != text) onTextChange(it.text)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 26.dp, max = 160.dp)
                    .focusRequester(focusRequester)
                    /*
                     * 按住说话：只挂在**空输入框**上，且不消费短按 ——
                     * 点一下照常聚焦、光标照常落位，打字与说话不会互相抢。
                     */
                    .pointerInput(holdToTalkActive) {
                        if (!holdToTalkActive) return@pointerInput
                        val cancelSlop = VoiceCancelSlop.toPx()
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val releasedEarly = withTimeoutOrNull(
                                viewConfiguration.longPressTimeoutMillis,
                            ) { waitForUpOrCancellation() }
                            if (releasedEarly != null) return@awaitEachGesture

                            onVoiceHoldStart()
                            /*
                             * 按住期间向上滑超过 [VoiceCancelSlop] 就算取消 ——
                             * 松手时不发送、也不保留识别结果。这是"说错了想撤回"
                             * 唯一的即时出口：不给它，用户只能等发出去再删。
                             */
                            var cancelled = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                if (down.position.y - change.position.y > cancelSlop) {
                                    cancelled = true
                                }
                                change.consume()
                            }
                            if (cancelled) onVoiceHoldCancel() else onVoiceHoldEnd()
                        }
                    }
                    // 用户点回输入框时收起面板，避免键盘与面板同时占屏
                    .onFocusChanged { if (it.isFocused) onTextFieldFocused() },
                textStyle = LocalTextStyle.current.merge(
                    MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                maxLines = 6,
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) {
                            Text(
                                text = when {
                                    voiceListening -> stringResource(R.string.voice_listening)
                                    holdToTalkActive -> stringResource(R.string.input_hint_hold_to_talk)
                                    else -> stringResource(R.string.input_hint)
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (voiceListening) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    colors.placeholder
                                },
                            )
                        }
                        inner()
                    }
                },
            )

            Spacer(Modifier.size(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 第二行全部按钮**同高**；纯图标一律正圆，只有「图标 + 箭头」用胶囊。
                // 之前 ⚙ 是 padding 撑出来的椭圆，和 ⊕ / ↑ 的圆并排就很毛糙。

                // ── 对话设置 ─────────────────────────────
                // 与右侧思考入口同一套样式：灰图标 + 描边；展开时换品牌色底
                if (showSettingsEntries) IconCircleButton(
                    icon = AppIcons.Settings,
                    contentDescription = stringResource(R.string.input_settings),
                    onClick = onOpenSettingsPanel,
                    size = RowButtonSize,
                    iconSize = 18.dp,
                    background = if (settingsPanelOpen) colors.capsuleSelected else null,
                    tint = if (settingsPanelOpen) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        // 未激活也画**黑**（原来是灰的）—— 与 ⊕ / 发送键统一，对齐官方
                        MaterialTheme.colorScheme.onSurface
                    },
                    /*
                     * 描边**常驻**，激活时换成品牌色。
                     *
                     * 官方那套激活样式是「淡底 + 同色描边 + 同色内容」；
                     * 原来激活态把描边去掉只铺底，在并排的几个按钮里会显得"矮一层"。
                     */
                    outlined = true,
                    outlineColor = if (settingsPanelOpen) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        null
                    },
                )

                // ── 思考档位 + 模型 ───────────────────────
                // 同设置入口：DSH 没有思考档位/模型选择概念，隐藏而不是留个死按钮。
                if (showSettingsEntries) ThinkingToggle(
                    enabled = thinkingEnabled || webSearchEnabled,
                    expanded = thinkingPanelOpen,
                    onClick = onOpenThinkingPanel,
                )
                // ── 上下文占用 ────────────────────────────
                // 同设置入口：DSH 不暴露上下文占用统计，传零值只会画出一个永远空的圆环，
                // 点了也打不开面板（onOpenStatsPanel 是空实现）。
                // 与 showSettingsEntries 共用一个开关 —— 它们都是「SimpleChat 有、DSH 没有」的概念。
                if (showSettingsEntries) ContextMeterPill(
                    stats = contextStats,
                    expanded = statsPanelOpen,
                    onClick = onOpenStatsPanel,
                )

                /*
                 * 兜底式运行提示（v0.15.3）：只有一只小鲸鱼，没有说话。
                 *
                 * 主提示在**消息流末尾**（「深度求索中，用时 Ns ···」）——
                 * 那符合接近法则：状态紧邻它所描述的那一轮。
                 * 但用户滚上去看历史时，末尾就出了视野。这时输入栏这只小鲸鱼
                 * 充当「还在跑」的一眼确认，不必滚回去。
                 *
                 * 这里刻意**不带文字**：
                 *   · 带文字会与消息流末尾那行重复，且挤占输入宽度
                 *   · 兜底要克制，不能喧宾夺主
                 */
                if (streaming) {
                    // v0.18.7：尾巴 → 转圈。
                    //
                    // 用户截图指出同一屏出现两个鲸鱼尾巴（消息流末尾 + 这里），重复。
                    //
                    // 这两处的**角色本来就不同**：
                    //   · 消息流末尾 —— 主要指示器（近邻原则：紧挨它所描述的那一轮）
                    //   · 输入栏左下 —— 兜底，用户把末尾滚出视野时才注意到
                    //
                    // 兜底用「进程正在运行」的通用符号（转圈）比复用品牌符号更合适：
                    // 两者一眼能区分，也符合「兜底要克制、不能喧宾夺主」的原则。
                    SpinRing(size = 16.dp, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Spacer(Modifier.weight(1f))

                /*
                 * 附件入口。图标是**圆圈加号**（⊕），与顶栏的「新建对话」区分开 ——
                 * 后者是气泡加号。同一个符号不要承担两种动作。
                 *
                 * 展开时整体转 45°：加号变成叉，"再点一下是收起"就不用另外说明。
                 * 转的是**整个按钮** —— 圆形是对称的，看起来就是里头的加号在转，
                 * 比"两帧之间换一个图标"顺得多。
                 */
                /*
                 * 运行指示（v0.15.0）：鲸鱼摆动 + 三点依次出现。
                 *
                 * 为什么放这里（输入栏左侧、⊕ 之前）：
                 *   · 用户视线在打字时本来就落在输入栏附近，比顶栏更容易注意到
                 *   · 不占消息区，不挡最后一条内容
                 *   · 与 ⊕ 相邻但语义不同：左边是「它在跑」，右边是「我要发东西」
                 *
                 * 只在 streaming 时出现 —— 空闲时这块完全不占位（不是透明占位），
                 * 否则会留下一条莫名其妙的空白。
                 */


                if (canAttach) {
                    val spin by animateFloatAsState(
                        targetValue = if (attachPanelOpen) 45f else 0f,
                        animationSpec = tween(200, easing = MotionEasing),
                        label = "attachSpin",
                    )
                    IconCircleButton(
                        icon = AppIcons.PlusCircle,
                        contentDescription = if (attachPanelOpen) {
                            stringResource(R.string.input_attach_collapse)
                        } else {
                            stringResource(R.string.input_attach_add)
                        },
                        onClick = onAttachClick,
                        size = RowButtonSize,
                        iconSize = 24.dp,
                        modifier = Modifier.graphicsLayer { rotationZ = spin },
                    )
                }

                SendButton(
                    streaming = streaming,
                    enabled = streaming || text.isNotBlank() || pendingCount > 0,
                    onClick = { if (streaming) onStop() else onSend() },
                )
            }
        }
    }
}

/** 输入栏第二行的统一控件高度。 */
private val RowButtonSize = 32.dp

/** 按住说话时，上滑超过这个距离即视为取消。 */
private val VoiceCancelSlop = 56.dp

/**
 * 思考档位入口。
 *
 * 只留**图标 + 箭头**，不带文字 —— 输入栏第二行本来就窄，
 * 图标（Bubble/Brain）已足够表意，箭头单独承担"可展开"的提示。
 *
 * [enabled] 表示思考已开启；[expanded] 表示面板正在展开 ——
 * 两者独立，因为「面板打开」和「思考已开启」是两件事。
 */
@Composable
private fun ThinkingToggle(
    enabled: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalChatColors.current
    // 未开启也画**黑**（原来是灰的），与 ⊕ / 发送键统一
    val content = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .height(RowButtonSize)
            .clip(PillShape)
            .background(if (enabled) colors.capsuleSelected else Color.Transparent)
            /*
             * 开启态**保留描边**，只换成品牌色 —— 官方那套激活样式是
             * 「淡底 + 同色描边 + 同色内容」。原来激活时把描边设成透明，
             * 并排看会比旁边几个"矮一层"。
             */
            .border(
                width = 1.dp,
                color = if (enabled) MaterialTheme.colorScheme.primary else colors.outline,
                shape = PillShape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = AppIcons.Sparkles,
            contentDescription = stringResource(R.string.input_thinking),
            tint = content,
            modifier = Modifier.size(17.dp),
        )
        Icon(
            imageVector = AppIcons.ChevronDown,
            contentDescription = null,
            tint = content,
            // 展开时箭头翻转，给出明确的方向反馈
            modifier = Modifier
                .size(14.dp)
                .rotate(if (expanded) 180f else 0f),
        )
    }
}

/**
 * 智能搜索入口。
 *
 * 与 [ThinkingToggle] 同一套视觉（同高、同圆角、同激活样式），
 * 但**没有箭头**：它是个开关，不是面板入口 —— 点了就开/关，没有下一层。
 *
 * 开启态用「淡底 + 品牌色描边 + 品牌色图标」，与旁边的思考档位一致；
 * 关闭态用描边 + 黑色图标，与 ⊕ / 发送键一致。
 */
@Composable
private fun WebSearchToggle(enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalChatColors.current
    val content = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    val label = stringResource(R.string.web_search)

    Row(
        modifier = Modifier
            .height(RowButtonSize)
            .clip(PillShape)
            .background(if (enabled) colors.capsuleSelected else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (enabled) MaterialTheme.colorScheme.primary else colors.outline,
                shape = PillShape,
            )
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = label
                role = Role.Button
                stateDescription = if (enabled) {
                    Res.get(R.string.web_search_on)
                } else {
                    Res.get(R.string.web_search_off)
                }
            }
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = AppIcons.Globe,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(17.dp),
        )
    }
}

/**
 * 上下文占用入口：圆环 + 长度（如 `12k`）。
 *
 * 颜色按占用分三档，让"该压缩了"在**不点开面板**的情况下也看得见：
 * 常规 / 接近阈值（≥70%）/ 逼近上限（≥90%）。
 *
 * 压缩生效时环上会出现一个品牌色的小标，提示"发出去的和本地看到的不一样"。
 */
@Composable
private fun ContextMeterPill(
    stats: ContextStats,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalChatColors.current
    val accent = when {
        stats.ratio >= 0.9f -> colors.danger
        stats.ratio >= 0.7f -> colors.warning
        else -> MaterialTheme.colorScheme.primary
    }
    val content = when {
        expanded -> MaterialTheme.colorScheme.primary
        // 接近阈值 / 逼近上限：数字跟着变色。这个信号只留在环上太容易被忽略
        stats.ratio >= 0.7f -> accent
        // 常规态也画**黑**（原来是灰的），与旁边几个按钮统一
        else -> MaterialTheme.colorScheme.onSurface
    }
    val contextUsageLabel = stringResource(R.string.stats_context_usage)

    Row(
        modifier = Modifier
            .height(RowButtonSize)
            .clip(PillShape)
            .background(if (expanded) colors.capsuleSelected else Color.Transparent)
            // 展开时描边换成品牌色（与 ⚙ / ✧ 同一套激活样式）
            .border(
                width = 1.dp,
                color = if (expanded) MaterialTheme.colorScheme.primary else colors.outline,
                shape = PillShape,
            )
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = contextUsageLabel
                role = Role.Button
            }
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        ProgressRing(
            progress = stats.ratio,
            color = accent,
            trackColor = colors.outline,
            size = 15.dp,
            strokeWidth = 2.dp,
        )
        Text(
            text = TokenEstimate.format(stats.usedTokens),
            style = MaterialTheme.typography.labelMedium,
            color = content,
        )
        if (stats.hasCompaction) {
            // 一个极小的小圆点：说明"上下文已被压缩过"
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/**
 * 发送按钮。三态：
 * - 空闲且无内容：浅灰，不可点
 * - 有内容：品牌蓝实心，向上箭头
 * - 流式中：品牌蓝实心，停止方块
 */
@Composable
private fun SendButton(
    streaming: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalChatColors.current
    val background = if (enabled) MaterialTheme.colorScheme.primary else colors.fieldBackground
    val content = if (enabled) Color.White else colors.placeholder

    Box(
        modifier = Modifier
            .size(RowButtonSize)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (streaming) AppIcons.Stop else AppIcons.ArrowUp,
            contentDescription = if (streaming) stringResource(R.string.input_stop) else stringResource(R.string.input_send),
            tint = content,
            modifier = Modifier.size(if (streaming) 16.dp else 19.dp),
        )
    }
}

/** 无背景的圆形图标按钮，48dp 触控目标。等价于 [IconCircleButton] 的大尺寸预设。 */
@Composable
fun IconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onBackground,
    modifier: Modifier = Modifier,
) {
    IconCircleButton(
        icon = icon,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        size = 48.dp,
        iconSize = 24.dp,
        tint = tint,
    )
}

/**
 * 一张待发图片的缩略图（v0.13.2）。
 *
 * 直接用 base64 解码而不是先落盘再读：图片本来就要以 base64 发给服务端，
 * 为了预览再存一份文件等于双倍占用，且要处理清理。
 *
 * 解码在 IO 线程；失败时显示一个灰块占位 —— 缩略图出不来不影响发送。
 */
@Composable
private fun PendingThumb(b64: String, contentDescription: String) {
    var bmp by remember(b64) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(b64) {
        bmp = withContext(Dispatchers.IO) {
            try {
                val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
    }
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        bmp?.let {
            androidx.compose.foundation.Image(
                bitmap = it,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue

/** 提亮（模拟玻璃上沿受光）。amount 是往白色靠的比例。 */
private fun lighten(c: androidx.compose.ui.graphics.Color, amount: Float) = androidx.compose.ui.graphics.Color(
    red = c.red + (1f - c.red) * amount,
    green = c.green + (1f - c.green) * amount,
    blue = c.blue + (1f - c.blue) * amount,
    alpha = c.alpha,
)

/** 压暗（模拟玻璃下沿的自阴影）。 */
private fun darken(c: androidx.compose.ui.graphics.Color, amount: Float) = androidx.compose.ui.graphics.Color(
    red = c.red * (1f - amount),
    green = c.green * (1f - amount),
    blue = c.blue * (1f - amount),
    alpha = c.alpha,
)
