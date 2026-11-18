package cd.ptrel.dshpostapi.compose

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 客户端诊断日志环形缓冲（v0.19.5）。
 *
 * ## 为什么需要它
 *
 * 真机上没法 `adb logcat`。用户报「流式重复 / 漏字 / 多端不同步」时，
 * 我只能拿到一句描述或一张浮层截图 —— 浮层只有四个计数，看不到**时间线**。
 *
 * ## 只记元数据
 *
 * 每行是一条被处理的帧：时间、类型、clientId、判定结果。
 * **不含任何对话内容** —— 那是用户隐私，而定位归属问题不需要它。
 *
 * ## 为什么是环形的
 *
 * 流式时每秒几十帧，无限累积会把内存吃光。
 * 保留最近 [CAPACITY] 条（约几分钟的量），够覆盖「复现一次问题」的窗口。
 */
object DiagLog {

    /** 保留条数。1000 条约覆盖 30~60 秒的流式输出。 */
    private const val CAPACITY = 1000

    private val buffer = ArrayDeque<String>(CAPACITY)
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /**
     * 记一行。
     *
     * ⚠️ 由调用方保证 [msg] 里**没有对话正文**。
     * 这个方法不做过滤 —— 逐个字符串检查内容的成本比省下的隐私风险高，
     * 且容易漏。约定写在调用点。
     */
    @Synchronized
    fun add(tag: String, msg: String) {
        if (buffer.size >= CAPACITY) buffer.removeFirst()
        buffer.addLast(fmt.format(Date()) + " " + tag + " " + msg)
    }

    /** 取出全部（副本，调用方可安全遍历）。 */
    @Synchronized
    fun snapshot(): List<String> = buffer.toList()

    @Synchronized
    fun clear() = buffer.clear()

    @Synchronized
    fun size(): Int = buffer.size
}
