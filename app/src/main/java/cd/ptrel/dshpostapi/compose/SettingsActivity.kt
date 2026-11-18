@file:Suppress("DEPRECATION")

package cd.ptrel.dshpostapi.compose

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import cd.ptrel.dshpostapi.AppPrefs
import cd.ptrel.dshpostapi.data.Res
import cd.ptrel.dshpostapi.ui.chat.ChatTopBar
import cd.ptrel.dshpostapi.ui.common.AppBackground
import cd.ptrel.dshpostapi.ui.common.AppIcons
import cd.ptrel.dshpostapi.ui.common.AppSwitch
import cd.ptrel.dshpostapi.ui.theme.LocalChatColors
import cd.ptrel.dshpostapi.ui.theme.SimpleChatTheme

/*
 * 设置页（v0.19.1）。
 *
 * ## 为什么单独开一页
 *
 * 早先设置项散在会话列表的 ⋮ 菜单里（切换模式 / 检查更新 / 背景图 / 退出登录）。
 * 问题：
 *   · 只有首页能进 —— 聊天时想改背景得先返回
 *   · 「背景图」埋在菜单第二层，用户找不到
 *   · **没地方放新设置**（遮罩强度、玻璃档位、诊断模式都无处可去）
 *
 * ## 分组依据
 *
 * 按**用户想解决什么**分，而不是按代码模块分：
 *   · 外观 —— 我看到的东西不对
 *   · 连接 —— 我连的方式不对
 *   · 诊断 —— 出问题了要排查
 *   · 关于 —— 版本与更新
 *
 * 「退出登录」单独放在最下面并用危险色：它是**不可逆**操作，
 * 不该混在普通设置里让人误触。
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialBackground = AppPrefs.getBackgroundPath(this)
        setContent {
            SimpleChatTheme {
                SettingsScreen(
                    initialBackground = initialBackground,
                    onBack = { finish() },
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    initialBackground: String?,
    onBack: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val colors = LocalChatColors.current
    val backgroundPath by remember { mutableStateOf(initialBackground) }
    // 诊断模式：控制聊天页是否显示帧统计浮层。
    var diagnostics by remember { mutableStateOf(AppPrefs.isDiagnosticsEnabled(ctx)) }

    AppBackground(path = backgroundPath) {
        Scaffold(containerColor = androidx.compose.ui.graphics.Color.Transparent) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState()),
            ) {
                ChatTopBar(
                    title = "设置",
                    onMenu = onBack,
                    onSettings = {},
                )

                Spacer(Modifier.height(8.dp))

                // ── 设置项 ──────────────────────────────
                SettingsRow(
                    title = "诊断模式",
                    subtitle = "在聊天页显示流式帧统计，用于排查同步问题",
                    trailing = {
                        AppSwitch(
                            checked = diagnostics,
                            onCheckedChange = {
                                diagnostics = it
                                AppPrefs.setDiagnosticsEnabled(ctx, it)
                            },
                        )
                    },
                )

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * 一行设置项。
 *
 * 视觉上用**玻璃卡片** —— 与聊天页的输入栏同一套语言，
 * 让人一眼看出「这是一个可操作的面板」，而不是普通列表行。
 */
@Composable
private fun SettingsRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = LocalChatColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.card.copy(alpha = 0.72f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.placeholder,
                )
            }
        }
        trailing?.invoke()
    }
}
