package cd.ptrel.dshpostapi.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cd.ptrel.dshpostapi.CompatApi
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
                    items(items = state.sessions, key = { it.sessionId }) { row ->
                        SessionRow(row = row, onClick = { onOpenSession(row) })
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
