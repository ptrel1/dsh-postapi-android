package cd.ptrel.dshpostapi.ui.theme

import androidx.compose.ui.graphics.Color

// ── 品牌色 ────────────────────────────────────────────────
/** DeepSeek 蓝，浅色模式主色。 */
val BrandBlue = Color(0xFF4D6BFE)

/** 深色模式下略微提亮的品牌蓝，保证对比度。 */
val BrandBlueDark = Color(0xFF6B84FF)

// ── 主题色候选（设置页「个性化 → 主题色」）──────────────────
/*
 * 每个色相两档：浅色一档、深色一档。
 *
 * 深色那一档**不是**把浅色随手调亮 —— 同一个色相在近黑底上要够亮才顶得住
 * 对比度（原来的 BrandBlue → BrandBlueDark 就是这么来的，这里沿用同一套处理）。
 *
 * 蓝色两个档刻意就取原来的品牌色：**默认观感一个像素都不变**。
 */
val AccentGreenLight = Color(0xFF12A150)
val AccentGreenDark = Color(0xFF3DBF74)
val AccentRedLight = Color(0xFFB01E28)
val AccentRedDark = Color(0xFFE05C63)
val AccentPinkLight = Color(0xFFE35183)
val AccentPinkDark = Color(0xFFFF77A9)
val AccentPurpleLight = Color(0xFF7C4DFF)
val AccentPurpleDark = Color(0xFF9E7BFF)
val AccentBrownLight = Color(0xFF9A6B4F)
val AccentBrownDark = Color(0xFFB98A6B)
val AccentGrayLight = Color(0xFF6B7280)
val AccentGrayDark = Color(0xFF9AA1AC)

// ── 用户气泡 ──────────────────────────────────────────────
val UserBubbleLight = Color(0xFFDCE6FF)
val UserBubbleDark = Color(0xFF2A3556)

// ── 过程面板（思考区）────────────────────────────────────
val ProcessTextLight = Color(0xFF8A8F99)
val ProcessTextDark = Color(0xFF9BA1AC)
val ProcessBarLight = Color(0xFFD8DADE)
val ProcessBarDark = Color(0xFF3A3D42)

// ── 文本 ─────────────────────────────────────────────────
val TextPrimaryLight = Color(0xFF1A1A1A)
val TextPrimaryDark = Color(0xFFE8E8EA)
val TextSecondaryLight = Color(0xFF9AA0A6)
val TextSecondaryDark = Color(0xFF8B9099)
val PlaceholderLight = Color(0xFFB8BCC4)
val PlaceholderDark = Color(0xFF6B7079)

// ── 表面 ─────────────────────────────────────────────────
val PageBgLight = Color(0xFFFFFFFF)
val PageBgDark = Color(0xFF0F0F10)
val SettingsBgLight = Color(0xFFF5F6F8)
val SettingsBgDark = Color(0xFF151517)
val CardLight = Color(0xFFFFFFFF)
val CardDark = Color(0xFF1A1A1C)
val OutlineLight = Color(0xFFEAEBEF)
val OutlineDark = Color(0xFF2E2F33)
val DividerLight = Color(0xFFF0F0F2)
val DividerDark = Color(0xFF26272A)

// ── 浮层（长按菜单等）────────────────────────────────────
/**
 * 菜单浮层比卡片再"抬高"一档：
 * 深色下必须比 CardDark 亮，否则菜单和背景糊在一起（见参考图）。
 */
val MenuBgLight = Color(0xFFFFFFFF)
val MenuBgDark = Color(0xFF2B2C30)

// ── 功能色 ───────────────────────────────────────────────
val DangerRed = Color(0xFFF5222D)
val DangerRedDark = Color(0xFFFF5A5F)
val CapsuleSelectedLight = Color(0xFFE8EDFF)
val CapsuleSelectedDark = Color(0xFF243056)

// ── 状态色 ───────────────────────────────────────────────
/** 上下文接近阈值时的提醒色。不用红色 —— 那是"出错了"，这里是"注意一下"。 */
val WarningAmber = Color(0xFFD98A1F)
val WarningAmberDark = Color(0xFFE8A33D)
val FieldBgLight = Color(0xFFF2F3F5)
val FieldBgDark = Color(0xFF232427)
