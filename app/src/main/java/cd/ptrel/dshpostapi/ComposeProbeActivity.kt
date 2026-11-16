package cd.ptrel.dshpostapi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * v0.9.0 Compose 骨架验证页。
 *
 * 存在的唯一目的：确认 Compose 在**当前工具链**（AGP 8.5.2 / Kotlin 2.0.21 /
 * compileSdk 35 / Compose BOM 2024.09.03）下能编译、能渲染、能跑 LazyColumn。
 *
 * 为什么单独一个 Activity 而不是直接改 CompatChatActivity：
 *   改造是渐进的，旧页面必须保持可用直到新页面验证通过。
 */
class ComposeProbeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Scaffold { padding ->
                    ProbeScreen(Modifier.padding(padding))
                }
            }
        }
    }
}

@Composable
private fun ProbeScreen(modifier: Modifier = Modifier) {
    // 故意用 LazyColumn + 长列表：真正要验证的是「大量条目滚动」这条路径，
    // 静态 Text 跑通不代表 RecyclerView 的替代品可用。
    val data = remember { (1..200).map { i -> "Compose 条目 #" + i + " — 验证 LazyColumn 复用与滚动" } }
    val listState = rememberLazyListState()

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = "Compose 骨架验证（" + data.size + " 条）",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(data) { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }
}
