package cd.ptrel.dshpostapi.data

import android.content.Context

/**
 * 语言适配（占位实现）。
 *
 * SimpleChat 的版本会按用户设置包一层 Context 来切换界面语言。
 * 本项目只有中文，这里原样返回 —— 保留这个类是因为移植来的 UI 代码
 * 在多处调用了它（Markdown 排版按语种分档：中文两端对齐、西文左对齐）。
 *
 * ⚠️ 将来真要做多语言，改这里一个文件即可，调用点不用动。
 */
object LocaleHelper {
    fun localizedContext(base: Context): Context = base

    /**
     * 界面语言是否 CJK（中文/日文/韩文）。
     *
     * 用途：Markdown 排版按语种分档 —— 中文两端对齐（justify）读起来更整齐，
     * 西文左对齐（否则词间距被拉得很怪）。这是排版惯例，不是审美偏好。
     *
     * 本项目只有中文界面，固定返回 true。
     * ⚠️ 若将来加英文界面，改成读当前 locale 的 language。
     */
    fun isCjkUi(context: Context): Boolean = true
}
