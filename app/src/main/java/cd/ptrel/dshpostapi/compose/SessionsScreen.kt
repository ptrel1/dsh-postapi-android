package cd.ptrel.dshpostapi.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cd.ptrel.dshpostapi.CompatApi
import cd.ptrel.dshpostapi.groupByWorkspace
import cd.ptrel.dshpostapi.R
import cd.ptrel.dshpostapi.data.Res
import cd.ptrel.dshpostapi.ui.common.AppIcons
import cd.ptrel.dshpostapi.ui.common.AppMenu
import cd.ptrel.dshpostapi.ui.common.AppMenuDivider
import cd.ptrel.dshpostapi.ui.common.AppMenuItem
import cd.ptrel.dshpostapi.ui.common.CapsuleButton
import cd.ptrel.dshpostapi.ui.common.IconCircleButton
import cd.ptrel.dshpostapi.ui.common.MenuWidth
import cd.ptrel.dshpostapi.ui.common.SoftShape
import cd.ptrel.dshpostapi.ui.common.Space
import cd.ptrel.dshpostapi.ui.common.rememberPressPosition
import cd.ptrel.dshpostapi.ui.theme.LocalChatColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * 会话列表页（v0.11.0）。
 *
 * ## 为什么换掉 XML + RecyclerView 版
 *
 * 旧版（CompatSessionsActivity）是移植前的实现，只为了「能看见列表」。
 * 现在聊天页已经是 SimpleChat 的 Compose 组件，两页的视觉语言不一致
 * （列表是 Material 默认风格、聊天页是 SimpleChat 风格）——
 * 同一次操作里切两页会明显感到"这不是一个 App"。
 *
 * 数据来源仍然是 CompatApi.listSessions()（DSH 后端），
 * 不是 SimpleChat 的 Room —— 只借它的**外观**，不借数据层。
 *
 * ## 与 SimpleChat 的 HistoryDrawerContent 的差别
 *
 * 它是抽屉、带置顶/多选/搜索/长按改名删除 —— 这些在 DSH 侧后端都没有对应接口。
 * 这里只保留：标题 + 时间、点击进入、顶部刷新、右上「更多」、底部「新建会话」。
 * 不做「看起来能点但点了没反应」的入口（与聊天页 InputBar 的处理原则一致）。
 */

/** 时间列的格式。全局复用一个：SimpleDateFormat 不是线程安全的，但只在这里、只在主线程用。 */
private val SessionTimeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

@Composable
fun SessionsScreen(
    state: SessionsViewModel.UiState,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onOpenSession: (CompatApi.SessionRow) -> Unit,
    onNewSession: () -> Unit,
    onSwitchMode: () -> Unit,
    onCheckUpdate: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.pageBackground),
    ) {
        // ── 顶栏 ────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Space.Screen, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = Res.get(R.string.compat_sessions_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )

            // 刷新：不用文字按钮（顶栏放不下中文），用图标 + 读屏描述。
            IconCircleButton(
                icon = AppIcons.Refresh,
                contentDescription = Res.get(R.string.compat_refresh),
                onClick = onRefresh,
            )

            // 「更多」：切换使用方式 / 检查更新 / 退出登录。
            // 和完整模式右侧三点按钮对齐 —— 这三件事两种模式下都该可达。
            Box {
                IconCircleButton(
                    icon = AppIcons.More,
                    contentDescription = Res.get(R.string.compat_menu_more),
                    onClick = { menuOpen = true },
                )
                AppMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    AppMenuItem(Res.get(R.string.compat_menu_switch_mode), AppIcons.Contrast) {
                        menuOpen = false
                        onSwitchMode()
                    }
                    AppMenuItem(Res.get(R.string.compat_menu_check_update), AppIcons.Refresh) {
                        menuOpen = false
                        onCheckUpdate()
                    }
                    // 分隔线固定接在危险操作之前 —— 结构不随数据变化。
                    AppMenuDivider()
                    AppMenuItem(Res.get(R.string.compat_menu_logout), AppIcons.Close, destructive = true) {
                        menuOpen = false
                        onLogout()
                    }
                }
            }
        }

        // ── 错误行：可点重试 ─────────────────────────────────────
        state.error?.let { msg ->
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = colors.danger,
                modifier = Modifier
                    .fillMaxWidth()
                    // 点一下即重试：这一页没有下拉刷新，错误行是唯一的重试入口。
                    .clickable(onClick = onRetry)
                    .padding(horizontal = Space.Screen, vertical = 8.dp),
            )
        }

        // ── 列表 / 空态 / 加载 ──────────────────────────────────
        when {
            state.loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp,
                    )
                }
            }

            state.sessions.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = Res.get(R.string.compat_sessions_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.placeholder,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }

            else -> {
                // 分组在外面算：LazyColumn 的 content lambda 不是 @Composable
                // 作用域（它是 LazyListScope），remember 放进去会报
                // 「@Composable invocations can only happen from a @Composable function」。
                val groups = remember(state.sessions) { state.sessions.groupByWorkspace() }
                // 折叠状态集中管在一个 map 里，而不是每组一个 remember ——
                // LazyListScope 内不是 @Composable 作用域，remember 放进去编译不过
                // （这一处踩了两次，记下来）。
                // 默认空 map ⇒ 全部收起。
                val expandedPaths = remember { mutableStateMapOf<String, Boolean>() }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(
                        start = Space.Screen,
                        end = Space.Screen,
                        top = 2.dp,
                        bottom = 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // v0.10.2：按工作区分组渲染。
                    //
                    // 早先是扁平一列。DSH 的会话天然属于某个工作区（cwd），
                    // 30 个会话平铺时用户找「上次在某个项目里干到哪了」只能靠标题猜。
                    // 分组后：组标题是项目名，组内按时间倒序，组间按最近活动倒序。
                    groups.forEach { group ->
                        // v0.10.3：分组默认**收起**。
                        //
                        // 为什么要收起：64 个会话全展开时，用户要滑很久才能看到
                        // 第二个工作区。列表页的第一职责是「让我找到那个项目」，
                        // 不是「把所有会话摊开」。
                        //
                        // 为什么记在 remember 里而不是持久化：折叠状态是**浏览时的临时意图**，
                        // 下次打开时「都是收起的」才是可预期的起点。
                        // 用 path 作 key：同名项目（不同目录）各自独立折叠。
                        val expanded = expandedPaths[group.path] == true
                        item(key = "hdr-" + group.path) {
                            WorkspaceHeader(
                                group = group,
                                expanded = expanded,
                                onToggle = {
                                    expandedPaths[group.path] = !expanded
                                },
                            )
                        }
                        if (expanded) {
                            items(items = group.sessions, key = { it.sessionId }) { row ->
                                SessionRow(row = row, onClick = { onOpenSession(row) })
                            }
                        }
                    }
                }
            }
        }

        // ── 底部：新建会话 ──────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.Screen)
                .padding(bottom = Space.Screen),
        ) {
            CapsuleButton(
                text = Res.get(R.string.compat_new_session),
                leadingIcon = AppIcons.PlusCircle,
                onClick = onNewSession,
                modifier = Modifier.fillMaxWidth(),
                selected = true,
            )
        }
    }
}

/**
 * 工作区分组标题（v0.10.2）。
 *
 * 三段信息各有用途：
 *   · 项目名（最后一段路径）—— 用户认的是这个，一眼定位；
 *   · 完整路径 —— 同名项目（不同父目录）靠它区分，小字灰显不抢焦点；
 *   · 会话数 —— 让用户知道这组里有多少，免得逐个点。
 *
 * cwd 为空的会话归到「其他」：宿主没给工作目录（老会话/非 workdir 场景），
 * 单独成组比混进某个项目更诚实。
 */
@Composable
private fun WorkspaceHeader(
    group: CompatApi.WorkspaceGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val colors = LocalChatColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 整块可点：只让箭头可点的话，手指要精确落在 16dp 的图标上。
            .clickable(onClick = onToggle)
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = group.name.ifBlank { Res.get(R.string.compat_workspace_other) },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = group.sessions.size.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = colors.placeholder,
            )
            // 展开指示：旋转 90° 的 V 形。用旋转而不是换图标 ——
            // 换图标是两帧之间的突变，旋转是连续的，读起来更顺。
            Icon(
                imageVector = AppIcons.ChevronLeft,
                contentDescription = null,
                tint = colors.placeholder,
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer { rotationZ = if (expanded) 90f else 0f },
            )
        }
        // 完整路径只在有值且有父目录时显示 —— 顶层目录（如 /main）本身已经
        // 和组名一样了，再显示一遍是噪音。
        if (group.path.isNotBlank() && group.path.trimEnd('/').contains('/')) {
            Text(
                text = group.path,
                style = MaterialTheme.typography.labelSmall,
                color = colors.placeholder,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 一行会话。
 *
 * 用 SimpleChat 抽屉里那套「整行一块浅底、44~46dp 行高、标题单行省略」，
 * 但**不做**长按菜单 —— DSH 后端没有改名 / 删除 / 置顶接口。
 */
@Composable
private fun SessionRow(
    row: CompatApi.SessionRow,
    onClick: () -> Unit,
) {
    val colors = LocalChatColors.current
    val (pressPosition, pressListener) = rememberPressPosition()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(pressListener)
            .clip(SoftShape)
            .clickable(onClick = onClick)
            .heightIn(min = 46.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = AppIcons.MessageSquare,
            contentDescription = null,
            tint = colors.placeholder,
            modifier = Modifier.size(18.dp),
        )
        // v0.10.2：正在运行的会话加一个状态点。
        //
        // 服务端 /sessions 一直有 running 字段，客户端却没解析 —— 用户看不出
        // 哪个会话在后台跑，只能挨个点进去确认。列表页最该一眼看出的就是这件事。
        if (row.running) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title.ifBlank { Res.get(R.string.compat_untitled) },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.updatedAt > 0) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = SessionTimeFormat.format(Date(row.updatedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.placeholder,
                    maxLines = 1,
                )
            }
        }
    }
    // pressPosition 目前不用（没有长按菜单），保留监听是为了将来接长按时不改动布局。
    @Suppress("UNUSED_EXPRESSION")
    pressPosition
}
