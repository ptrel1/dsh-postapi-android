package cd.ptrel.dshpostapi.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import cd.ptrel.dshpostapi.ui.common.GlassSurface
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
import androidx.compose.ui.graphics.graphicsLayer
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
    /**
     * 右侧按钮：打开设置（v0.19.1）。
     *
     * 早先是「新建对话」。用户指出：「新建会话不是常用操作，只能在首页新建就行了」。
     * 而且会话里点「新建」语义含糊（另起一个？还是当前这个？），
     * 而设置是**随时可能想改**的（背景、玻璃、诊断），才该占这个位置。
     */
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // v0.17.0：顶栏变成玻璃表面。
    //
    // 顶栏背后是壁纸的**固定区域**（不随滚动变化），所以它的模糊结果可以长期缓存 ——
    // 这是 GlassSurface 里「静态区域不重算」策略最典型的受益者。
    //
    // cornerRadius = 0：顶栏贴边，圆角会把屏幕边角露出来。
    GlassSurface(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 0.dp,
        // v0.17.3：0.80 → 0.68。0.80 压得住字但玻璃感全无（看起来就是一块灰板）。
        // 0.68 配合标题文字的阴影，兼顾两者。
        tintAlpha = 0.68f,
        // 贴边的顶栏紧挨状态栏，顶部内高光会与状态栏区域糊成一片，关掉。
        topHighlight = false,
        // v0.18.3：上深下透。
        //
        // 均匀色罩在顶栏底边切出一条硬边，看着像盖了块板子（用户反馈「顶部也需要透明渐变」）。
        // 改成渐变后：状态栏区域仍有 68% 压住壁纸保证标题可读，
        // 往下逐渐透明，与内容自然衔接。
        fadeBottom = true,
    ) {
    Row(
        modifier = Modifier
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
                .padding(start = 8.dp)
                // v0.17.3：文字阴影。
                //
                // 玻璃调到 0.68 之后，照片的亮部会透上来，深色标题在浅色区域
                // 仍然发虚。阴影是**局部的**可读性保障 —— 比把整块玻璃做厚好得多，
                // 后者会让玻璃彻底看不出透。
                .graphicsLayer {
                    shadowElevation = 1.dp.toPx()
                    ambientShadowColor = androidx.compose.ui.graphics.Color.Black
                    spotShadowColor = androidx.compose.ui.graphics.Color.Black
                },
            maxLines = 1,
        )
        // 与长按菜单的「从这里新建对话」同一个图标 —— 同一个动作，同一个符号。
        // 圆圈加号留给输入栏的附件按钮（⊕ 加图片/文件），两者别混用。
        TopBarAction(AppIcons.MenuDots, stringResource(R.string.topbar_settings), onSettings)
    }
    }  // GlassSurface
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
