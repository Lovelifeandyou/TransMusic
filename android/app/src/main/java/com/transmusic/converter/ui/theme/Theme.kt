package com.transmusic.converter.ui.theme

import android.os.Build
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------- 品牌色（与 Windows 桌面版同一套） ----------------
val BrandBlue = Color(0xFF4C8DFF)
val AccentOk = Color(0xFF37D39A)
val AccentWarn = Color(0xFFFFB454)
val AccentError = Color(0xFFFF5F6D)
val InkMuted = Color(0xFF98A1B3)

/**
 * MD3E 深色配色：以品牌蓝为主色，容器色层层叠加（surfaceContainer*），
 * 这是 Expressive 风格「大色块 + 高圆角」的基础。
 */
private val TransMusicDark = darkColorScheme(
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
    error = AccentError,
    onError = Color(0xFF2A0206),
    errorContainer = Color(0xFF4A1119),
    onErrorContainer = Color(0xFFFFDADD),
    inverseSurface = Color(0xFFE8ECF4),
    inverseOnSurface = Color(0xFF171A20),
    scrim = Color(0xFF000000),
)

/**
 * Expressive 字体：标题更重、字距更紧，标签更大更粗（MD3E 的 emphasized 风格）。
 */
private val ExpressiveTypography = Typography(
    displaySmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.8).sp,
    ),
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.5.sp,
    ),
)

/** MD3E 形状：比标准 M3 更圆更「软」。 */
private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

/**
 * MD3E 动效：低阻尼弹簧，把「有弹性」这件事写进代码里。
 */
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

/**
 * 入口主题。Android 12+ 默认跟随壁纸取色（Material You / MD3E 的招牌能力），
 * 老系统回落到品牌深色方案。想要固定品牌色时传 useDynamicColor = false。
 */
@Composable
fun TransMusicTheme(
    useDynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = if (useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context)
    } else {
        TransMusicDark
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = ExpressiveTypography,
        shapes = ExpressiveShapes,
        content = content,
    )
}
