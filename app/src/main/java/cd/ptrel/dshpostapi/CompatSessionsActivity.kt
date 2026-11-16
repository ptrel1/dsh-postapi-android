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

    /** v0.8.2：防止重复跳转（load 可能被 onResume 多次触发）。 */

    private var redirecting = false

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
            // v0.8.2：凭据不可用（没有 / 已过期）时**必须把用户送回登录页**，
            // 不能只红字提示就停在这里。
            //
            // 早先只 showError 然后 return —— 用户看到「未登录或凭据已失效」，
            // 但界面上没有任何可点的东西，只能自己去翻菜单，体验上等于卡死。
            // 兼容模式没有地址栏、没有下拉刷新，用户唯一的出路是返回键试运气。
            showError(getString(R.string.compat_not_logged_in))
            redirectToLogin()
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

    /**
     * 回登录页重新认证。
     *
     * 延迟一小会儿再跳：让用户**看见**那行「凭据已失效」的提示，
     * 否则界面一闪就换页，用户不知道发生了什么（以为是自己点错了）。
     *
     * 用 LoginActivity 而不是 CompatActivity 作为目标：
     * 后者是分发器，会再次读 UI 模式并可能又绕回本页，形成循环。
     */
    private fun redirectToLogin() {
        if (redirecting) return
        redirecting = true
        binding.root.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            startActivity(
                android.content.Intent(this, LoginActivity::class.java).apply {
                    // 清掉返回栈：重新登录后不该还能按返回键回到失效的列表页
                    addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK,
                    )
                },
            )
            finish()
        }, 1200L)
    }

    private fun showError(msg: String) {
        binding.sessionsError.text = msg
        binding.sessionsError.visibility = View.VISIBLE
    }

    private fun openChat(sessionId: String?, title: String?) {
        // v0.9.0：聊天页切到 Compose 实现。
        //
        // 旧的 CompatChatActivity（XML + RecyclerView）暂时保留在仓库里：
        // 它验证过的那些坑（流式占位消息、交付物卡片、上翻加载）在 Compose 版
        // 重新实现并验证之前，不能删 —— 删了就没有可回退的参照。
        //
        // title 不传：Compose 版顶部标题还没做，传了也用不上。
        startActivity(
            android.content.Intent(this, cd.ptrel.dshpostapi.compose.ComposeChatActivity::class.java).apply {
                if (sessionId != null) {
                    putExtra(cd.ptrel.dshpostapi.compose.ComposeChatActivity.EXTRA_SESSION_ID, sessionId)
                }
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