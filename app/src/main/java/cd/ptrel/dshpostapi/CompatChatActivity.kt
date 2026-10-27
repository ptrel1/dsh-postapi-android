package cd.ptrel.dshpostapi

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import cd.ptrel.dshpostapi.databinding.ActivityCompatChatBinding
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * v0.6.0：兼容模式的聊天页。
 *
 * ==================== 三条不做什么 ====================
 * 1. **不渲染 Markdown**：纯文本 + 保留换行。代价是代码块没有高亮，
 *    但没有高亮的代码块**比**渲染错误的富文本更有用 —— 后者会把缩进吃掉、
 *    把 `*` 变成列表、把反引号配对错。原生客户端的第一价值是「能读、能发」。
 * 2. **不显示推理过程**：插件会把 reasoning 增量单独推（type=reasoning），
 *    本页忽略它。思考过程与正文交替出现会让用户分不清哪句是回答。
 * 3. **不做消息编辑/重发**：完整模式里有，这里保持最小。
 *
 * ==================== 流式渲染怎么做到不卡 ====================
 * 每收到一个 text 增量只更新**最后一条** TextView 的文本，不重建整个列表。
 * RecyclerView 只在「一轮结束」时收到一次 notifyItemInserted。
 * 这是原生界面相对 WebView 的核心优势所在：DOM 全量 diff → 一次 setText。
 */
class CompatChatActivity : AppCompatActivity(), ChatViewModel.Listener {

    companion object {
        const val EXTRA_SESSION_ID = "cd.ptrel.dshpostapi.EXTRA_CHAT_SESSION_ID"
        const val EXTRA_TITLE = "cd.ptrel.dshpostapi.EXTRA_CHAT_TITLE"

        /**
         * 消息判等规则。
         *
         * 必须放在**外层**的 companion 里：inner class 不允许带 companion（Kotlin 限制），
         * 而它又要在构造 ListAdapter 时就用上（构造发生在实例初始化之前，
         * 写成普通属性会「先使用后声明」）。
         */
        val MESSAGE_DIFF = object : DiffUtil.ItemCallback<CompatApi.Message>() {
            override fun areItemsTheSame(a: CompatApi.Message, b: CompatApi.Message): Boolean {
                // 新会话里本地回显的消息 seq 都是 -1，只能靠「同一角色 + 同一文本」判等，
                // 否则每次 submitList 都会把整表重建（闪一下）。
                if (a.seq >= 0 || b.seq >= 0) return a.seq == b.seq
                return a.role == b.role && a.text == b.text
            }

            override fun areContentsTheSame(a: CompatApi.Message, b: CompatApi.Message) = a == b
        }
    }

    private lateinit var binding: ActivityCompatChatBinding
    private lateinit var adapter: MessageAdapter
    private var vm: ChatViewModel? = null

    /** 流式中的那条消息还没进列表，单独存一份给最后一行用。 */
    private var streamingText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCompatChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowInsetsHelper.applySystemBarPadding(binding.root)

        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        val title = intent.getStringExtra(EXTRA_TITLE)
        binding.chatTitle.text = title?.takeIf { it.isNotEmpty() }
            ?: getString(if (sessionId == null) R.string.compat_new_chat_title else R.string.compat_untitled)

        adapter = MessageAdapter()
        binding.messagesList.layoutManager = LinearLayoutManager(this).apply {
            // 从底部开始堆：聊天内容的自然阅读方向是「最新的在下方」。
            stackFromEnd = true
        }
        binding.messagesList.adapter = adapter

        binding.chatBack.setOnClickListener { finish() }
        binding.chatSend.setOnClickListener { onSendClicked() }

        val api = CompatApi.fromStore(this)
        if (api == null) {
            showError(getString(R.string.compat_not_logged_in))
            return
        }
        val model = ChatViewModel(api, this)
        vm = model
        model.openSession(sessionId)
    }

    private fun onSendClicked() {
        val model = vm ?: return
        // 忙时按钮变成「停止」—— 同一位置承担两个动作，省一次误触。
        if (model.isBusy()) {
            model.cancel()
            return
        }
        val text = binding.chatInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        binding.chatInput.setText("")
        hideError()
        model.send(text)
    }

    // ==========================================================================
    // ChatViewModel.Listener
    // ==========================================================================

    override fun onMessages(messages: List<CompatApi.Message>) {
        adapter.submitList(messages) {
            // 列表更新后滚到底：用户发完消息期待立刻看到自己的话与回答。
            scrollToBottom()
        }
    }

    override fun onStreamingChanged(text: String) {
        streamingText = text
        // 只刷新最后一行，不动整个列表 —— 这是不卡的关键。
        val idx = adapter.itemCount - 1
        if (idx >= 0) adapter.notifyItemChanged(idx) else adapter.notifyDataSetChanged()
        scrollToBottom()
    }

    override fun onTurnFinished(error: String?) {
        streamingText = ""
        if (error != null) showError(error) else hideError()
    }

    override fun onBusyChanged(busy: Boolean) {
        binding.chatSend.text = getString(if (busy) R.string.compat_stop else R.string.compat_send)
    }

    // ==========================================================================

    // ==========================================================================
    // v0.7.0：交付物下载
    // ==========================================================================

    /**
     * 下载一个交付物。
     *
     * 流程：POST /file-ticket 换一次性下载地址 → OkHttp 下载到 cache → 用系统打开。
     *
     * 为什么不直接用 DownloadManager：
     *   下载地址是插件签发的临时票据 URL（带 HMAC + 过期时间），
     *   而 DownloadManager 是独立进程，带不上我们的会话 cookie；
     *   把 URL 交给系统还会留下一条无法撤销的公开链接。
     *   自己下到 cache 再交给系统打开，链接只在内存里存在几秒。
     */
    private fun downloadDeliverable(sessionId: String, d: CompatApi.Deliverable) {
        val api = CompatApi.fromStore(this)
        if (api == null) {
            showError(getString(R.string.compat_not_logged_in))
            return
        }
        showError(getString(R.string.compat_downloading, d.name))
        Thread {
            val result = api.downloadDeliverable(sessionId, d)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (result) {
                    is CompatApi.Result.Ok -> {
                        hideError()
                        openDownloadedFile(result.value)
                    }
                    is CompatApi.Result.Err ->
                        showError(getString(R.string.compat_download_failed) + result.reason)
                }
            }
        }.start()
    }

    /** 用系统「打开方式」打开下载好的文件。 */
    private fun openDownloadedFile(file: java.io.File) {
        try {
            val mime = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, packageName + ".fileprovider", file,
            )
            startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )
        } catch (e: Exception) {
            // 没有能打开这种文件的应用、或 FileProvider 未覆盖该路径：
            // 给出确切路径让用户自己去找，而不是笼统地说失败了。
            showError(getString(R.string.compat_open_failed, file.absolutePath))
        }
    }

    private fun scrollToBottom() {
        val n = adapter.itemCount
        if (n > 0) binding.messagesList.smoothScrollToPosition(n - 1)
    }

    private fun showError(msg: String) {
        binding.chatError.text = msg
        binding.chatError.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.chatError.visibility = View.GONE
    }

    override fun onDestroy() {
        vm?.dispose()
        vm = null
        super.onDestroy()
    }

    // ==========================================================================

    /**
     * 消息列表。
     *
     * 最后一条特殊处理：如果正在流式输出，它的正文用 [streamingText] 覆盖。
     * 这样「已固化的消息」与「正在生成的消息」共用同一套渲染路径，
     * 少一次视图切换，也就少一次闪烁。
     */
    private inner class MessageAdapter :
        ListAdapter<CompatApi.Message, MessageAdapter.VH>(MESSAGE_DIFF) {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val text: android.widget.TextView = v.findViewById(R.id.msgText)
            val deliverablesBox: android.view.ViewGroup = v.findViewById(R.id.deliverablesBox)
        }

        /**
         * 把某条消息的交付物渲染成卡片。
         *
         * 为什么每次重建子 View 而不是复用：
         *   一条消息的交付物通常在 1~3 个，重建成本远小于写一套复用逻辑（还要处理
         *   ViewHolder 回收时残留的点击监听）。RecyclerView 的回收粒度是整条消息，
         *   这层的重建会被上层复用吃掉。
         */
        private fun bindDeliverables(holder: VH, msg: CompatApi.Message) {
            val box = holder.deliverablesBox
            if (msg.deliverables.isEmpty()) {
                box.visibility = View.GONE
                return
            }
            box.visibility = View.VISIBLE

            // ⚠️ 缓存键必须包含 sessionId：下载闭包要用它。
            //    只用 deliverables 当 tag 会在「同一个 VH 复用到另一条消息」时残留旧内容
            //    （View.tag 是 View 上的属性，VH 回收时不会自动清）。
            val sessionId = vm?.sessionId()
            val key = sessionId + "|" + msg.deliverables.joinToString(",") { it.seq.toString() + ":" + it.index }
            if (box.tag == key) return
            box.tag = key
            box.removeAllViews()

            if (sessionId == null) {
                // 没有会话 id 时（极早期 / 会话被清），至少把文件名显示出来 ——
                // 静默不显示会让用户以为"这条消息没有交付物"。
                for (d in msg.deliverables) {
                    val tv = android.widget.TextView(holder.itemView.context)
                    tv.text = d.name
                    tv.textSize = 13f
                    box.addView(tv)
                }
                return
            }

            for (d in msg.deliverables) {
                val card = LayoutInflater.from(holder.itemView.context)
                    .inflate(R.layout.item_deliverable, box, false)
                card.findViewById<android.widget.TextView>(R.id.delivName).text = d.name
                val desc = card.findViewById<android.widget.TextView>(R.id.delivDesc)
                if (d.description.isBlank()) desc.visibility = View.GONE else desc.text = d.description
                card.setOnClickListener { downloadDeliverable(sessionId, d) }
                box.addView(card)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false))
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            // 消息体直接读模型里的文本 —— 流式内容也是**一条真实消息**（见 ChatViewModel），
            // 不在这里做任何合成或覆盖。
            //
            // ⚠️ 曾经踩过两种错法：
            //   ① 「最后一条的正文换成 streamingText」→ 最后一条是用户刚发的消息，被回复覆盖掉；
            //   ② 「覆写 getItemCount 给流式单独加一行」→ ListAdapter 内部不一致，
            //      抛 IndexOutOfBoundsException: Inconsistency detected（RecyclerView 经典陷阱）。
            //   正解只有一条：让流式内容成为列表里的真实消息，用 submitList 更新。
            val msg = getItem(position)
            val isUser = msg.role == "user"
            holder.text.text = msg.text
            // 用户靠右、助手靠左。
            val lp = holder.text.layoutParams as android.widget.LinearLayout.LayoutParams
            lp.gravity = if (isUser) android.view.Gravity.END else android.view.Gravity.START
            holder.text.layoutParams = lp

            bindDeliverables(holder, msg)
        }
    }
}