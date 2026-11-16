package cd.ptrel.dshpostapi.data

import kotlin.math.roundToInt

/**
 * Token 数的**本地估算**。
 *
 * 刻意不引 tiktoken 之类的分词器：
 * - 它是按目标模型精确编码的，换个服务商就不准；
 * - 体积与耗时都不小，而这里只要"够用的量级感"（12k 还是 120k）。
 *
 * 分 CJK 与非 CJK 加权，比"字符数 ÷ 常数"稳：
 * 中文 ≈ 1 token / 1.5 字符，英文 ≈ 1 token / 4 字符 —— 两者差 2.7 倍，
 * 用同一个常数会把纯中文或纯英文的场景估偏一倍以上。
 *
 * 用途：上下文占用圆环、统计面板、压缩阈值提醒。
 * **不用于**计费或任何需要精确值的场合。
 */
object TokenEstimate {

    /** 非 CJK 的每 token 字符数。 */
    private const val CHARS_PER_TOKEN_LATIN = 4.0

    /** CJK 的每 token 字符数。 */
    private const val CHARS_PER_TOKEN_CJK = 1.5

    fun of(text: String): Int {
        if (text.isEmpty()) return 0

        var cjk = 0
        var other = 0
        for (ch in text) {
            if (isCjk(ch)) cjk++ else other++
        }

        val tokens = cjk / CHARS_PER_TOKEN_CJK + other / CHARS_PER_TOKEN_LATIN
        // 至少 1 —— 一个非空字符串不可能对应 0 个 token
        return tokens.roundToInt().coerceAtLeast(1)
    }

    /** 多段文本的合计，避免逐段取整带来的累计误差。 */
    fun ofAll(texts: Iterable<String>): Int {
        var cjk = 0
        var other = 0
        for (text in texts) {
            for (ch in text) {
                if (isCjk(ch)) cjk++ else other++
            }
        }
        if (cjk == 0 && other == 0) return 0
        return (cjk / CHARS_PER_TOKEN_CJK + other / CHARS_PER_TOKEN_LATIN)
            .roundToInt()
            .coerceAtLeast(1)
    }

    fun ofAll(vararg texts: String): Int = ofAll(texts.asIterable())

    /**
     * 人类可读的紧凑写法：`12k` / `1.2M` / `980`。
     *
     * 圆环旁边只有 ~4 个字符的位置，写不下 `1,048,576`。
     */
    fun format(count: Int): String = when {
        count < 1_000 -> count.toString()
        count < 1_000_000 -> {
            val k = count / 1000.0
            if (k < 10) trimZero(k) + "k" else k.roundToInt().toString() + "k"
        }
        else -> {
            val m = count / 1_000_000.0
            trimZero(m) + "M"
        }
    }

    private fun trimZero(value: Double): String {
        val rounded = (value * 10).roundToInt() / 10.0
        return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
    }

    /**
     * CJK 判定：汉字、平假名、片假名、谚文。
     *
     * 只作为加权依据，不求完备 —— 判错的代价是估算偏差几十个百分点，
     * 而本来就是个估算。
     */
    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return code in 0x4E00..0x9FFF ||   // 中日韩统一表意
            code in 0x3400..0x4DBF ||      // 扩展 A
            code in 0x3040..0x30FF ||      // 平假名 / 片假名
            code in 0xAC00..0xD7AF        // 谚文
    }
}
