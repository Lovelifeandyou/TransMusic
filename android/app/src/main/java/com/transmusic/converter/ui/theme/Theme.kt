package com.transmusic.converter.ui.theme

import android.os.Build
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 当前是否深色主题（供各处取状态色/图标时判断）。 */
val LocalIsDarkTheme = staticCompositionLocalOf { true }

// ---------------- 品牌色（与 Windows 桌面版同一套） ----------------
val BrandBlue = Color(0xFF4C8DFF)
val AccentOk = Color(0xFF12A16A)
val AccentWarn = Color(0xFFB26A00)
val AccentError = Color(0xFFD3384B)
val InkMuted = Color(0xFF6B7385)

/** 深色主题下状态色需要更亮，浅色主题下用更深的版本保证对比度。 */
val AccentOkDark = Color(0xFF37D39A)
val AccentWarnDark = Color(0xFFFFB454)
val AccentErrorDark = Color(0xFFFF5F6D)
val InkMutedDark = Color(0xFF98A1B3)

/** 主题模式：跟随系统 / 始终浅色 / 始终深色。 */
enum class ThemeMode(val label: String) {
    System("跟随系统"),
    Light("浅色"),
    Dark("深色"),
    ;

    companion object {
        fun fromInt(value: Int): ThemeMode = entries.getOrElse(value) { System }
    }
}

// ---------------- 深色方案 ----------------
internal val TransMusicDark = darkColorScheme(
    primary = BrandBlue,
    onPrimary = Color(0xFF04101F),
    primaryContainer = Color(0xFF1B2A4C),
    onPrimaryContainer = Color(0xFFD6E3FF),
    inversePrimary = Color(0xFF2F5FA8),
    secondary = Color(0xFF8FB4FF),
    onSecondary = Color(0xFF08131F),
    secondaryContainer = Color(0xFF22304A),
    onSecondaryContainer = Color(0xFFDCE5FF),
    tertiary = Color(0xFF9BD8BF),
    onTertiary = Color(0xFF06231A),
    tertiaryContainer = Color(0xFF1B3329),
    onTertiaryContainer = Color(0xFFB8EFDA),
    background = Color(0xFF0D0F14),
    onBackground = Color(0xFFE8ECF4),
    surface = Color(0xFF0D0F14),
    onSurface = Color(0xFFE8ECF4),
    surfaceVariant = Color(0xFF232733),
    onSurfaceVariant = Color(0xFFA8B0C0),
    surfaceTint = BrandBlue,
    surfaceBright = Color(0xFF2A303C),
    surfaceDim = Color(0xFF0A0C11),
    surfaceContainerLowest = Color(0xFF090B10),
    surfaceContainerLow = Color(0xFF12151C),
    surfaceContainer = Color(0xFF161A22),
    surfaceContainerHigh = Color(0xFF1C212B),
    surfaceContainerHighest = Color(0xFF232935),
    outline = Color(0xFF3A4050),
    outlineVariant = Color(0xFF262B36),
    error = AccentErrorDark,
    onError = Color(0xFF2A0206),
    errorContainer = Color(0xFF4A1119),
    onErrorContainer = Color(0xFFFFDADD),
    inverseSurface = Color(0xFFE8ECF4),
    inverseOnSurface = Color(0xFF171A20),
    scrim = Color(0xFF000000),
)

// ---------------- 浅色方案 ----------------
internal val TransMusicLight = lightColorScheme(
    primary = Color(0xFF2F6BD8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD8E4FF),
    onPrimaryContainer = Color(0xFF00163A),
    inversePrimary = Color(0xFFA8C6FF),
    secondary = Color(0xFF3F5D8F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD9E3F8),
    onSecondaryContainer = Color(0xFF001B3C),
    tertiary = Color(0xFF2C6B5A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFB4F0DC),
    onTertiaryContainer = Color(0xFF00201A),
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF1A1D24),
    surface = Color(0xFFF6F7FB),
    onSurface = Color(0xFF1A1D24),
    surfaceVariant = Color(0xFFE1E4EC),
    onSurfaceVariant = Color(0xFF4A5162),
    surfaceTint = Color(0xFF2F6BD8),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE7E9F0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F2F8),
    surfaceContainer = Color(0xFFEBEEF5),
    surfaceContainerHigh = Color(0xFFE5E9F1),
    surfaceContainerHighest = Color(0xFFDFE3EC),
    outline = Color(0xFF767D8E),
    outlineVariant = Color(0xFFC6CBD8),
    error = AccentError,
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDADD),
    onErrorContainer = Color(0xFF410007),
    inverseSurface = Color(0xFF2C3038),
    inverseOnSurface = Color(0xFFF0F1F6),
    scrim = Color(0xFF000000),
)

/**
 * Expressive 字体：标题更重、字距更紧，标签更大更粗。
 */
private val ExpressiveTypography = Typography(
    displaySmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 42.sp, letterSpacing = (-0.8).sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.6).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 21.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.5.sp),
)

/** MD3E 形状：比标准 M3 更圆。 */
private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

/** MD3E 动效：低阻尼弹簧。 */
object Motion {
    const val Fast = 160
    const val Medium = 300

    val bouncy: FiniteAnimationSpec<Float> =
        spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)

    val snappy: FiniteAnimationSpec<Float> =
        spring(dampingRatio = 0.62f, stiffness = 720f)

    val gentle: FiniteAnimationSpec<Float> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow)
}

/** 当前主题下应该用的状态色（浅色主题用深一档，深色主题用亮一档）。 */
@Composable
fun statusOk(): Color = if (isDarkTheme()) AccentOkDark else AccentOk

@Composable
fun statusWarn(): Color = if (isDarkTheme()) AccentWarnDark else AccentWarn

@Composable
fun statusError(): Color = if (isDarkTheme()) AccentErrorDark else AccentError

@Composable
fun statusMuted(): Color = if (isDarkTheme()) InkMutedDark else InkMuted

@Composable
fun isDarkTheme(): Boolean = LocalIsDarkTheme.current

/**
 * 主题入口。darkTheme 由调用方决定（跟随系统 / 用户手动选择），
 * useDynamicColor 在 Android 12+ 上跟随壁纸取色。
 */
@Composable
fun TransMusicTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    useDynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> TransMusicDark
        else -> TransMusicLight
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = ExpressiveTypography,
            shapes = ExpressiveShapes,
            content = content,
        )
    }
}
