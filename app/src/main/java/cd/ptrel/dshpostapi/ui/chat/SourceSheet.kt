package cd.ptrel.dshpostapi.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import cd.ptrel.dshpostapi.R
import cd.ptrel.dshpostapi.ui.common.ContainerShape
import cd.ptrel.dshpostapi.ui.theme.LocalChatColors

/**
 * 来源列表弹窗。
 *
 * 点「已阅读 N 个网页」或正文下方的来源条都会打开它 —— 一处列表、两种入口，
 * 不再各写一套。每行给出**编号 + 站点名 + 标题 + 摘要**：编号与正文角标对齐，
 * 用户能立刻把「正文里那个 ³」对上「第 3 条」。
 *
 * 点一行直接交给系统浏览器（`LocalUriHandler`）。刻意**不做内置 WebView**：
 * 那等于把一个浏览器塞进 APK，与本项目「轻量」的第一要义冲突。
 */
@Composable
fun SourceSheet(
    sources: List<SourceRef>,
    onDismiss: () -> Unit,
) {
    val colors = LocalChatColors.current
    val uri = LocalUriHandler.current

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ContainerShape)
                .background(colors.card)
                .padding(vertical = 18.dp),
        ) {
            Text(
                text = stringResource(R.string.search_results),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = stringResource(R.string.search_read_many, sources.size),
                style = MaterialTheme.typography.labelMedium,
                color = colors.placeholder,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.size(12.dp))

            if (sources.isEmpty()) {
                Text(
                    text = stringResource(R.string.search_panel_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.placeholder,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            } else {
                Column(
                    modifier = Modifier
                        // 再多也别把整屏占满：给"关掉"留一条退路
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    sources.forEach { source ->
                        SourceRow(source) {
                            if (source.url.isNotBlank()) runCatching { uri.openUri(source.url) }
                        }
                    }
                }
            }

            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.action_close),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun SourceRow(source: SourceRef, onClick: () -> Unit) {
    val colors = LocalChatColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 编号：与正文角标一一对应
        Text(
            text = source.index.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = colors.processText.copy(alpha = 0.7f),
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(colors.fieldBackground)
                .padding(top = 3.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = source.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = source.siteName,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.placeholder,
                )
                source.pageAge?.let { age ->
                    Text(
                        text = age.take(10),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.placeholder,
                    )
                }
            }
            source.snippet?.let { snippet ->
                Spacer(Modifier.size(4.dp))
                Text(
                    text = snippet,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.processText,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
