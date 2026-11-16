package cd.ptrel.dshpostapi.ui.chat

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import cd.ptrel.dshpostapi.R
import cd.ptrel.dshpostapi.data.Attachment
import cd.ptrel.dshpostapi.data.AttachmentFiles
import cd.ptrel.dshpostapi.data.Attachments
import cd.ptrel.dshpostapi.ui.common.AppIcons
import cd.ptrel.dshpostapi.ui.common.ContainerShape
import cd.ptrel.dshpostapi.ui.common.PillShape
import cd.ptrel.dshpostapi.ui.common.SoftShape
import cd.ptrel.dshpostapi.ui.theme.LocalChatColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * 附件在界面上的三种样子。
 *
 * 全都**不引图片库**：附件就在数据库里躺着 base64，解码两步就完事，
 * 为一个"显示一张本地图"的需求拖进 Coil 不值得（设计文档 §8）。
 * 关键是**按显示尺寸采样解码** —— 直接解一张 1600×1600 的位图是 10MB 内存，
 * 在一屏聊天里滚两下就 OOM 了。
 */

/** 缩略图边长。 */
private val ThumbSize = 64.dp

/** 消息里图片的最大宽度 / 高度。 */
private val BubbleImageWidth = 220.dp
private val BubbleImageMaxHeight = 300.dp

// ══════════════════════════════════════════════════════════
//  解码
// ══════════════════════════════════════════════════════════

/**
 * 按需解码，**按显示尺寸采样**。
 *
 * 在 IO 线程做；解码期间返回 null，调用方显示占位。
 */
@Composable
fun rememberAttachmentImage(attachment: Attachment, targetPx: Int): ImageBitmap? {
    val context = LocalContext.current
    /*
     * key 里带上 `path`：落盘附件的 `data` 是空串，只用 data 当 key 会让
     * "换了一张图但 key 没变"的情况（同一消息里两张落盘图）命中旧结果。
     */
    val state by produceState<ImageBitmap?>(
        initialValue = null,
        attachment.data,
        attachment.path,
        targetPx,
    ) {
        value = withContext(Dispatchers.IO) {
            // 落盘的从文件读，内联的走 base64 —— 渲染层只认字节，不关心存哪
            val stored = if (attachment.isStored) AttachmentFiles.read(context, attachment.path) else null
            decodeSampled(stored ?: Base64.decode(attachment.data, Base64.NO_WRAP), targetPx)
        }
    }
    return state
}

private fun decodeSampled(bytes: ByteArray, targetPx: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

    var sample = 1
    var longest = max(bounds.outWidth, bounds.outHeight)
    while (longest / 2 >= targetPx) {
        sample *= 2
        longest /= 2
    }

    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}.getOrNull()

// ══════════════════════════════════════════════════════════
//  待发送区（输入栏上方）
// ══════════════════════════════════════════════════════════

/** 待发送附件。图片是缩略图，文本文件是一条小卡片。 */
@Composable
fun PendingAttachment(spec: Attachment, onRemove: () -> Unit, onOpen: () -> Unit) {
    Box {
        AttachmentSurface(inUserBubble = false) {
            when (spec.kind) {
                Attachment.Kind.IMAGE -> ImageThumb(spec, ThumbSize, onClick = onOpen)
                Attachment.Kind.TEXT -> FileChip(spec, onClick = onOpen)
            }
        }
        RemoveBadge(onRemove, Modifier.align(Alignment.TopEnd))
    }
}

/** 输入栏上方那一排：横向可滚，附件多了也不会把输入栏撑高。 */
@Composable
fun PendingAttachmentsRow(
    attachments: List<Attachment>,
    onRemove: (Attachment) -> Unit,
    onOpen: (Attachment) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { spec ->
            PendingAttachment(
                spec = spec,
                onRemove = { onRemove(spec) },
                onOpen = { onOpen(spec) },
            )
        }
    }
}

/** 右上角的小 ✕。深色圆底 + 白叉，压在任何底图上都看得清。 */
@Composable
private fun RemoveBadge(onRemove: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(2.dp)
            .size(18.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.66f))
            .clickable(onClick = onRemove),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = AppIcons.Close,
            contentDescription = stringResource(R.string.attach_remove),
            tint = androidx.compose.ui.graphics.Color.White,
            modifier = Modifier.size(11.dp),
        )
    }
}

@Composable
private fun ImageThumb(spec: Attachment, size: Dp, onClick: () -> Unit) {
    val colors = LocalChatColors.current
    val surface = LocalAttachmentSurface.current
    val density = LocalDensity.current
    val target = with(density) { size.roundToPx() }
    val bitmap = rememberAttachmentImage(spec, target)

    Box(
        modifier = Modifier
            .size(size)
            .clip(SoftShape)
            .background(surface.background)
            .border(1.dp, surface.border, SoftShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = spec.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = AppIcons.Image,
                contentDescription = null,
                tint = colors.placeholder,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun FileChip(spec: Attachment, onClick: () -> Unit) {
    val colors = LocalChatColors.current
    val surface = LocalAttachmentSurface.current
    Row(
        modifier = Modifier
            .heightIn(min = ThumbSize)
            .widthIn(max = 200.dp)
            .clip(SoftShape)
            .background(surface.background)
            .border(1.dp, surface.border, SoftShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = AppIcons.FileText,
            contentDescription = null,
            tint = colors.processText,
            modifier = Modifier.size(18.dp),
        )
        Column {
            Text(
                text = spec.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.attach_char_count, spec.data.length),
                style = MaterialTheme.typography.labelSmall,
                color = colors.placeholder,
                maxLines = 1,
            )
        }
    }
}

// ══════════════════════════════════════════════════════════
//  消息里
// ══════════════════════════════════════════════════════════

/**
 * 一条消息携带的附件（用户气泡里）。
 *
 * 图片按原始比例占位（用存下来的宽高先撑住），**避免解码完成后高度跳一下** ——
 * 聊天流里跳高度会连带把滚动位置顶掉。
 */
@Composable
fun MessageAttachments(
    attachments: List<Attachment>,
    onOpenImage: (Attachment) -> Unit,
    modifier: Modifier = Modifier,
    /** 在用户气泡里时换一套配色 —— 灰底灰框压在蓝气泡上很脏。 */
    inUserBubble: Boolean = false,
) {
    if (attachments.isEmpty()) return
    val images = attachments.filter { it.isImage }
    val files = attachments.filter { !it.isImage }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        images.forEach { spec ->
            AttachmentSurface(inUserBubble = inUserBubble) {
                BubbleImage(spec, onClick = { onOpenImage(spec) })
            }
        }
        files.forEach { spec ->
            AttachmentSurface(inUserBubble = inUserBubble) {
                FileChip(spec, onClick = {})
            }
        }
    }
}

/**
 * 附件的底板配色。
 *
 * 两种场合各一套：
 * - 常规（输入栏上方、AI 侧）：浅灰底 + 描边，跟页面其它卡片一致
 * - **用户气泡内**：用正文色的低透明度去压蓝底。灰底灰框在蓝气泡上像贴了张
 *   打了补丁的纸，而低透明度黑既保持了气泡的整体感，又仍然分得出"这是一块内容"。
 */
@Composable
private fun AttachmentSurface(
    inUserBubble: Boolean,
    content: @Composable () -> Unit,
) {
    val colors = LocalChatColors.current
    val background = if (inUserBubble) colors.onUserBubble.copy(alpha = 0.06f) else colors.fieldBackground
    val border = if (inUserBubble) colors.onUserBubble.copy(alpha = 0.14f) else colors.outline
    CompositionLocalProvider(LocalAttachmentSurface provides AttachmentSurfaceColors(background, border)) {
        content()
    }
}

private data class AttachmentSurfaceColors(val background: Color, val border: Color)

private val LocalAttachmentSurface = staticCompositionLocalOf {
    AttachmentSurfaceColors(Color.Unspecified, Color.Unspecified)
}

@Composable
private fun BubbleImage(spec: Attachment, onClick: () -> Unit) {
    val colors = LocalChatColors.current
    val surface = LocalAttachmentSurface.current
    val density = LocalDensity.current
    val target = with(density) { BubbleImageWidth.roundToPx() }
    val bitmap = rememberAttachmentImage(spec, target)

    val ratio = if (spec.width > 0 && spec.height > 0) {
        spec.width.toFloat() / spec.height.toFloat()
    } else {
        1f
    }

    Box(
        modifier = Modifier
            .widthIn(max = BubbleImageWidth)
            .heightIn(max = BubbleImageMaxHeight)
            .aspectRatio(ratio)
            .clip(SoftShape)
            .background(surface.background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = spec.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = AppIcons.Image,
                contentDescription = null,
                tint = colors.placeholder,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

// ══════════════════════════════════════════════════════════
//  附件面板
// ══════════════════════════════════════════════════════════

/**
 * 点 `⊕` 展开的附件面板内容。
 *
 * ⚠️ 它**不是** `ModalBottomSheet`，而是和思考档位 / 统计 / 对话设置
 * 完全同一个容器（`InputPanel`）：浮在输入栏上方的一块圆角卡片，
 * 左右留 16dp、和输入栏同宽、共用同一层阴影与点击外部关闭。
 *
 * 一开始用了 Material 的 `ModalBottomSheet` —— 它自带满宽、自带蒙版、自带
 * 拖拽把手，摆在应用里像个外来的东西：左右顶到屏幕边、和输入栏对不齐，
 * 与下面那几个面板是两套语言。**同一件事只该有一套容器。**
 */
@Composable
fun AttachmentPanelContent(
    onCapture: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
) {
    /*
     * 三个格子，和官方那套一致：拍照 / 相册 / 文件。
     *
     * 等宽而不是按内容自适应 —— 三个入口的地位是平等的，
     * 谁宽谁窄会让人觉得有一个"更主要"。
     */
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AttachmentTile(
            label = stringResource(R.string.attach_camera),
            icon = AppIcons.Camera,
            onClick = onCapture,
            modifier = Modifier.weight(1f),
        )
        AttachmentTile(
            label = stringResource(R.string.attach_gallery),
            icon = AppIcons.Image,
            onClick = onPickImage,
            modifier = Modifier.weight(1f),
        )
        AttachmentTile(
            label = stringResource(R.string.attach_file),
            icon = AppIcons.FileText,
            onClick = onPickFile,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AttachmentTile(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current
    Column(
        modifier = modifier
            .clip(SoftShape)
            .background(colors.fieldBackground)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.processText,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ══════════════════════════════════════════════════════════
//  全屏查看
// ══════════════════════════════════════════════════════════

/**
 * 点开看大图 —— **全屏**，纯查看，不做保存/分享（那是系统相册的事）。
 *
 * ### 为什么是整屏而不是对话框
 *
 * 先前是个居中的圆角卡片：图片被塞进固定高度的盒子里（min 160 / max 520），
 * 竖图两侧、横图上下都会留出一大块底色 —— 那块空白既不是图片也不是背景，
 * 看着就像"图没加载完"。看图这件事本来就是"尽量占满"，
 * 所以改成整屏铺满：图片按 `Fit` 缩放居中，点任意处关闭，
 * 多张时左右可翻。
 *
 * 背景用近黑而不是主题色：图片本身可能是浅底，深底才看得清边界。
 */
@Composable
fun AttachmentViewerDialog(
    images: List<Attachment>,
    initial: Attachment,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    // 全屏尺寸解码：屏幕最长边的像素数，够清楚又不至于把原图解进内存
    val target = with(density) { 1080.dp.roundToPx() }

    val pages = images.ifEmpty { listOf(initial) }
    val startIndex = pages.indexOfFirst { it.name == initial.name && it.data == initial.data }
        .coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = startIndex) { pages.size }
    val current = pages.getOrNull(pagerState.currentPage) ?: initial

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // 自己处理系统栏：整屏看图不该被默认的 insets 再缩一圈
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ViewerBackground)
                // 点任意处关闭（看图时最直觉的退出方式）
                .clickable(onClick = onDismiss),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val attachment = pages[page]
                // 每页各解各的：Pager 只组合当前页与左右邻页，不会把整组图读进内存
                val pageBitmap = rememberAttachmentImage(attachment, target)
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (pageBitmap != null) {
                        Image(
                            bitmap = pageBitmap,
                            contentDescription = attachment.name,
                            // Fit：整张图都在屏幕内，不裁切；空白由深底兜住，不显眼
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                        )
                    } else {
                        Icon(
                            imageVector = AppIcons.Image,
                            contentDescription = null,
                            tint = ViewerTextDim,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }

            // ── 顶栏：关闭 + 计数 ──────────────────────────
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .systemBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = AppIcons.Close,
                        contentDescription = stringResource(R.string.action_close),
                        tint = ViewerText,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (pages.size > 1) {
                    Text(
                        text = "${pagerState.currentPage + 1}/${pages.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = ViewerText,
                    )
                    Spacer(Modifier.size(12.dp))
                }
            }

            // ── 底栏：文件名 · 尺寸 · 大小 ─────────────────
            Text(
                text = buildString {
                    append(current.name)
                    if (current.width > 0) append(" · ${current.width}×${current.height}")
                    // 落盘的附件 `data` 是空的，字节数要问文件系统（否则显示成 0 B）
                    append(" · ${Attachments.formatSize(AttachmentFiles.sizeOf(context, current))}")
                },
                style = MaterialTheme.typography.labelSmall,
                color = ViewerTextDim,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .systemBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 全屏看图的底色：近黑。图片可能是浅底，深底才看得清边界。 */
private val ViewerBackground = Color(0xF2000000)

/** 顶栏 / 底栏文字。 */
private val ViewerText = Color(0xFFEDEDED)

/** 次要文字（尺寸、大小）。 */
private val ViewerTextDim = Color(0xFF9E9E9E)
