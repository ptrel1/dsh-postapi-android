package cd.ptrel.dshpostapi.ui.theme

import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import cd.ptrel.dshpostapi.R
import cd.ptrel.dshpostapi.data.Res

/** 主题模式，对应设置页的「外观」三项。 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * 主题色。设置页「个性化 → 主题色」。
 *
 * 只影响**品牌色那一族**：主色（按钮 / 链接 / 选中态 / 发送键 / 进度环）、
 * 选中态的浅底、以及用户气泡。页面底色、正文颜色、功能色（危险 / 警告）都不动 ——
 * 换主题色不该把"有错误"这类语义色也换掉。
 *
 * 取值存的是 `name`；认不出来就回落到 [BLUE]（见 `MainActivity`）。
 */
enum class AccentColor(@StringRes private val labelRes: Int) {
    BLUE(R.string.theme_color_blue),
    GREEN(R.string.theme_color_green),
    RED(R.string.theme_color_red),
    PINK(R.string.theme_color_pink),
    PURPLE(R.string.theme_color_purple),
    BROWN(R.string.theme_color_brown),
    GRAY(R.string.theme_color_gray),
    ;

    /** 颜色名，随界面语言取（`theme_color_*`）。 */
    val label: String get() = Res.get(labelRes)
}

/** 该主题色在指定明暗下的**主色**。 */
fun AccentColor.primary(dark: Boolean): Color = when (this) {
    AccentColor.BLUE -> if (dark) BrandBlueDark else BrandBlue
    AccentColor.GREEN -> if (dark) AccentGreenDark else AccentGreenLight
    AccentColor.RED -> if (dark) AccentRedDark else AccentRedLight
    AccentColor.PINK -> if (dark) AccentPinkDark else AccentPinkLight
    AccentColor.PURPLE -> if (dark) AccentPurpleDark else AccentPurpleLight
    AccentColor.BROWN -> if (dark) AccentBrownDark else AccentBrownLight
    AccentColor.GRAY -> if (dark) AccentGrayDark else AccentGrayLight
}

/**
 * 由主色**派生**出来的浅色底：选中态填充 + 用户气泡。
 *
 * 刻意不逐个手写色值 —— 7 个主题色 × 2 档明暗 = 28 个手调颜色，
 * 既难保证它们跟主色"是同一个色相"，改一个还要跟着改一片。
 * 这里按固定比例把主色掺进页面底色，整套自动跟着主色走。
 *
 * 比例（浅 0.16 / 深 0.22）是照原来那版蓝色反推的：算出来与
 * `#E8EDFF` / `#243056` 基本重合，所以**默认的蓝色观感不变**。
 */
private fun tintOf(primary: Color, dark: Boolean): Color =
    lerp(if (dark) PageBgDark else Color.White, primary, if (dark) 0.22f else 0.16f)

/**
 * Material3 ColorScheme 之外的扩展语义色。
 * 这些是 DeepSeek 观感的关键，Material 没有对应槽位。
 */
@Immutable
data class ChatColors(
    val userBubble: Color,
    val onUserBubble: Color,
    val processText: Color,
    val processBar: Color,
    val placeholder: Color,
    val pageBackground: Color,
    val settingsBackground: Color,
    val card: Color,
    val outline: Color,
    val divider: Color,
    val danger: Color,
    /** 上下文接近阈值这类「注意」型状态，不是错误。 */
    val warning: Color,
    val capsuleSelected: Color,
    val fieldBackground: Color,
    /** 长按菜单等浮层表面。 */
    val menuBackground: Color,
    /**
     * 抽屉 / 弹层背后的遮罩。
     *
     * 放在这里而不是用 Material 的 `colorScheme.scrim`：那个值在两套主题下
     * 都是同一个黑，**深色模式下盖在近黑底上等于没有**。这里按主题分别取值。
     */
    val scrim: Color,
)

private val LightChatColors = ChatColors(
    userBubble = UserBubbleLight,
    onUserBubble = TextPrimaryLight,
    processText = ProcessTextLight,
    processBar = ProcessBarLight,
    placeholder = PlaceholderLight,
    pageBackground = PageBgLight,
    settingsBackground = SettingsBgLight,
    card = CardLight,
    outline = OutlineLight,
    divider = DividerLight,
    danger = DangerRed,
    warning = WarningAmber,
    capsuleSelected = CapsuleSelectedLight,
    fieldBackground = FieldBgLight,
    menuBackground = MenuBgLight,
    scrim = Color.Black.copy(alpha = 0.45f),
)

private val DarkChatColors = ChatColors(
    userBubble = UserBubbleDark,
    onUserBubble = TextPrimaryDark,
    processText = ProcessTextDark,
    processBar = ProcessBarDark,
    placeholder = PlaceholderDark,
    pageBackground = PageBgDark,
    settingsBackground = SettingsBgDark,
    card = CardDark,
    outline = OutlineDark,
    divider = DividerDark,
    danger = DangerRedDark,
    warning = WarningAmberDark,
    capsuleSelected = CapsuleSelectedDark,
    fieldBackground = FieldBgDark,
    menuBackground = MenuBgDark,
    scrim = Color.Black.copy(alpha = 0.62f),
)

val LocalChatColors = staticCompositionLocalOf { LightChatColors }

/**
 * 把主题色套到基础色板上。
 *
 * 只换两处"品牌色表面"：**选中态填充**与**用户气泡**。
 * 它们原来是写死的淡蓝（`CapsuleSelectedLight` / `UserBubbleLight`），
 * 换成绿色主题后若还留着蓝，一眼就是"没换干净"。
 */
private fun chatColors(dark: Boolean, accent: AccentColor): ChatColors {
    val tint = tintOf(accent.primary(dark), dark)
    return (if (dark) DarkChatColors else LightChatColors).copy(
        userBubble = tint,
        capsuleSelected = tint,
    )
}

/**
 * 当前是不是深色。
 *
 * 存在的理由只有一个：**系统栏（状态栏图标 / 导航栏「小白条」）要跟 App 的主题走，
 * 而不是跟系统的**。设置页可以强制浅色或深色，系统的深浅完全可能是另一个值 ——
 * 不看着这个值，就会出现「深色 App 顶着一排看不见的状态栏图标 / 底下一条小白条」。
 *
 * 见 [ApplySystemBarAppearance]。
 */
val LocalIsDarkTheme = staticCompositionLocalOf { false }

/**
 * 品牌色色板。
 *
 * **刻意不做动态取色（Monet / 莫奈取色）。**
 *
 * 动态取色会把背景、表面、描边全部染上壁纸的颜色 —— 阅读类应用里
 * 正文底色的色偏直接影响长时间阅读的舒适度，而"跟随壁纸"并不带来
 * 任何功能价值，只是让界面看起来像别人的应用。
 *
 * 这里要的是**稳定的中性底 + 一个**（由用户在设置里选定的）**品牌色**：
 * 正文区永远是干净的近白 / 近黑，品牌色永远可辨认 —— 换的只是**色相**，
 * 而不是"跟着壁纸跑"。见 [AccentColor]。
 */
private fun staticScheme(dark: Boolean, accent: AccentColor): ColorScheme {
    val primary = accent.primary(dark)
    val tint = tintOf(primary, dark)
    return if (dark) {
        darkColorScheme(
            primary = primary,
            onPrimary = Color.White,
            primaryContainer = tint,
            onPrimaryContainer = primary,
            background = PageBgDark,
            onBackground = TextPrimaryDark,
            surface = CardDark,
            onSurface = TextPrimaryDark,
            surfaceVariant = FieldBgDark,
            onSurfaceVariant = TextSecondaryDark,
            outline = OutlineDark,
            outlineVariant = DividerDark,
            error = DangerRedDark,
            onError = Color.White,
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = Color.White,
            primaryContainer = tint,
            onPrimaryContainer = primary,
            background = PageBgLight,
            onBackground = TextPrimaryLight,
            surface = CardLight,
            onSurface = TextPrimaryLight,
            surfaceVariant = FieldBgLight,
            onSurfaceVariant = TextSecondaryLight,
            outline = OutlineLight,
            outlineVariant = DividerLight,
            error = DangerRed,
            onError = Color.White,
        )
    }
}

/**
 * @param accent 主题色（设置页「个性化 → 主题色」）。只影响品牌色那一族，
 *   页面底色与功能色不动 —— 见 [AccentColor]。
 * @param fontScale 全局字号倍率（设置页「字体大小」）。
 *
 * 用 `LocalDensity.fontScale` 实现，而不是逐个改 `TextStyle`：
 * `sp` 换算受影响、`dp` 布局不受影响 —— 正是"只调字号"的语义，
 * 且对 `MarkdownBodyStyle`、`CodeTextStyle` 这些散落的样式一并生效，
 * 不会漏改。
 */
@Composable
fun SimpleChatTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    accent: AccentColor = AccentColor.BLUE,
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val scheme = staticScheme(dark, accent)

    val baseDensity = LocalDensity.current

    CompositionLocalProvider(
        LocalChatColors provides chatColors(dark, accent),
        LocalIsDarkTheme provides dark,
        LocalDensity provides Density(
            density = baseDensity.density,
            fontScale = baseDensity.fontScale * fontScale,
        ),
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = SimpleChatTypography,
            content = content,
        )
    }
}
