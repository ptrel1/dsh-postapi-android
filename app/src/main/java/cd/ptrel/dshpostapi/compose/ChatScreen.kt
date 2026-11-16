package cd.ptrel.dshpostapi.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Compose 聊天页（v0.9.0）。
 *
 * ## 滚动锚点：顶部为原点
 *
 * LazyColumn 默认就是顶部为原点（不用 reverseLayout）。这是**刻意的**：
 * 流式消息永远是最后一条，它在**底边**长高 = 长在视口下方，
 * 屏上已显示的内容纹丝不动，一行业务代码都不用写。
 *
 * 反过来用 reverseLayout（锚点钉底边）会把已显示内容整体往上顶 ——
 * SimpleChat 实测 0.5 秒滑走 861px，手指拦不住。
 *
 * 「进来就在底部」靠初始滚动位置解决，不是靠一次滚动动画：
 * 首帧就在底部，不需要重试、不需要等高度量出来。
 */
@Composable
fun ChatScreen(
    viewModel: ChatScreenViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }

    // 新消息到达时贴底。用 messages.size 而不是每条消息的内容变化：
    // 流式增量会疯狂触发，每次都滚会跟用户的主动上滑打架。
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            // imePadding：键盘弹出时把输入区顶上去。
            // 配合 manifest 的 adjustResize —— 两者缺一个键盘都会盖住输入框。
            .imePadding(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 12.dp, vertical = 12.dp,
            ),
        ) {
            items(state.messages, key = { it.id }) { msg ->
                MessageRow(msg)
            }
        }

        state.error?.let { err ->
            Text(
                text = err,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入消息…") },
                maxLines = 4,
            )
            Button(
                onClick = {
                    if (state.busy) {
                        viewModel.stop()
                    } else {
                        viewModel.send(input)
                        input = ""
                    }
                },
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text(if (state.busy) "停止" else "发送")
            }
        }
    }
}

@Composable
private fun MessageRow(msg: UiMessage) {
    val isUser = msg.role == UiMessage.Role.User
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(
                    color = if (isUser) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                )
                .padding(12.dp),
        ) {
            // 工具提示：有工具在跑时显示一行，让用户知道模型没卡住。
            msg.tool?.takeIf { it.isNotEmpty() }?.let { tool ->
                Text(
                    text = "正在使用 " + tool + "…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            if (msg.reasoning.isNotEmpty()) {
                Text(
                    text = msg.reasoning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Text(
                text = msg.text.ifEmpty { if (msg.status == UiMessage.Status.Streaming) "…" else "" },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (msg.status == UiMessage.Status.Stopped) {
                Text("已停止", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
