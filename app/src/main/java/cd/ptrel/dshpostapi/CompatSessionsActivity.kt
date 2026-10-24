package cd.ptrel.dshpostapi

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import cd.ptrel.dshpostapi.databinding.ActivityCompatSessionsBinding
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * v0.6.0：兼容模式的会话列表。
 *
 * ==================== 为什么必须有这一页 ====================
 * 原生界面的价值是「打开就能继续聊」。如果每次进来都得重新发起一轮对话，
 * 用户会直接退回完整模式 —— 会话列表是这条路径的最低门槛。
 *
 * ==================== 与完整模式的关系 ====================
 * 两者读的是同一批会话（同一个 DSH 后端、同一个账号）。
 * 所以在任何一端继续的对话，另一端都能看到。
 */
class CompatSessionsActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "CompatSessions"
    }

    private lateinit var binding: ActivityCompatSessionsBinding
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "compat-sessions-io").apply { isDaemon = true }
    }
    private lateinit var adapter: SessionAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCompatSessionsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowInsetsHelper.applySystemBarPadding(binding.root)

        adapter = SessionAdapter { row -> openChat(row.sessionId, row.title) }
        binding.sessionsList.layoutManager = LinearLayoutManager(this)
        binding.sessionsList.adapter = adapter

        binding.refreshSessions.setOnClickListener { load() }
        binding.newSession.setOnClickListener { openChat(null, null) }

        load()
    }

    override fun onResume() {
        super.onResume()
        // 从聊天页返回时刷新一次：刚聊过的会话应该出现在最上面。
        // 不做节流 —— 列表接口很轻（只读摘要），且 onResume 不会高频触发。
        if (::adapter.isInitialized) load()
    }

    private fun load() {
        val api = CompatApi.fromStore(this)
        if (api == null) {
            showError(getString(R.string.compat_not_logged_in))
            return
        }
        binding.sessionsError.visibility = View.GONE
        io.execute {
            val result = api.listSessions()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (result) {
                    is CompatApi.Result.Ok -> {
                        adapter.submitList(result.value)
                        binding.sessionsEmpty.visibility =
                            if (result.value.isEmpty()) View.VISIBLE else View.GONE
                    }
                    is CompatApi.Result.Err -> showError(result.reason)
                }
            }
        }
    }

    private fun showError(msg: String) {
        binding.sessionsError.text = msg
        binding.sessionsError.visibility = View.VISIBLE
    }

    private fun openChat(sessionId: String?, title: String?) {
        startActivity(
            Intent(this, CompatChatActivity::class.java).apply {
                if (sessionId != null) putExtra(CompatChatActivity.EXTRA_SESSION_ID, sessionId)
                if (title != null) putExtra(CompatChatActivity.EXTRA_TITLE, title)
            },
        )
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    // ==========================================================================

    /**
     * 列表适配器。
     *
     * 用 ListAdapter + DiffUtil 而不是 notifyDataSetChanged：
     * 后者会让整个列表重建，滚动位置与点击动画都会抖。
     * 这里只有「标题变了 / 时间变了 / 新增 / 删除」四种变化，DiffUtil 足够快。
     */
    private class SessionAdapter(private val onClick: (CompatApi.SessionRow) -> Unit) :
        ListAdapter<CompatApi.SessionRow, SessionAdapter.VH>(DIFF) {

        companion object {
            private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

            val DIFF = object : DiffUtil.ItemCallback<CompatApi.SessionRow>() {
                override fun areItemsTheSame(a: CompatApi.SessionRow, b: CompatApi.SessionRow) =
                    a.sessionId == b.sessionId

                override fun areContentsTheSame(a: CompatApi.SessionRow, b: CompatApi.SessionRow) =
                    a == b
            }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: android.widget.TextView = v.findViewById(R.id.sessionTitle)
            val time: android.widget.TextView = v.findViewById(R.id.sessionTime)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(LayoutInflater.from(parent.context).inflate(R.layout.item_session, parent, false))
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = getItem(position)
            holder.title.text = row.title.ifEmpty {
                holder.itemView.context.getString(R.string.compat_untitled)
            }
            holder.time.text = if (row.updatedAt > 0) {
                timeFmt.format(Date(row.updatedAt))
            } else {
                ""
            }
            holder.itemView.setOnClickListener { onClick(row) }
        }
    }
}