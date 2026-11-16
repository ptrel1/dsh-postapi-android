package cd.ptrel.dshpostapi.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cd.ptrel.dshpostapi.R
import cd.ptrel.dshpostapi.ui.common.AppIcons

/** 顶栏：`☰` 标题 ｜ 右侧新建对话。设置入口在抽屉底部。 */
@Composable
fun ChatTopBar(
    title: String,
    onMenu: () -> Unit,
    onNewChat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopBarAction(AppIcons.Menu, stringResource(R.string.topbar_history), onMenu)
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
            maxLines = 1,
        )
        // 与长按菜单的「从这里新建对话」同一个图标 —— 同一个动作，同一个符号。
        // 圆圈加号留给输入栏的附件按钮（⊕ 加图片/文件），两者别混用。
        TopBarAction(AppIcons.MessageSquarePlus, stringResource(R.string.topbar_new_chat), onNewChat)
    }
}

/** 顶栏图标按钮，48dp 触控目标。 */
@Composable
private fun TopBarAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.size(24.dp),
        )
    }
}
