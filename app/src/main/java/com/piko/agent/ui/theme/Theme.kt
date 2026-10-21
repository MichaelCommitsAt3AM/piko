package com.piko.agent.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

// Status colors retain readable contrast in either theme.
val PikoViolet: Color
    @Composable get() = MaterialTheme.colorScheme.secondary
val PikoMint: Color
    @Composable get() = if (MaterialTheme.colorScheme.background.luminance() < 0.5f)
        Color(0xFF80C8A8) else Color(0xFF24694F)
val PikoAmber: Color
    @Composable get() = if (MaterialTheme.colorScheme.background.luminance() < 0.5f)
        Color(0xFFE3BD76) else Color(0xFF805400)
val PikoRed: Color
    @Composable get() = MaterialTheme.colorScheme.error
val PikoMuted: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE5E5E5),
    onPrimary = Color(0xFF181818),
    primaryContainer = Color(0xFF303030),
    onPrimaryContainer = Color(0xFFE5E5E5),
    secondary = Color(0xFFB8B8B8),
    onSecondary = Color(0xFF181818),
    secondaryContainer = Color(0xFF292929),
    onSecondaryContainer = Color(0xFFE3E2DA),
    tertiary = Color(0xFF80C8A8),
    background = Color(0xFF111210),
    onBackground = Color(0xFFF0F0E9),
    surface = Color(0xFF1A1C19),
    onSurface = Color(0xFFF0F0E9),
    surfaceVariant = Color(0xFF232520),
    onSurfaceVariant = Color(0xFFAAAAAA),
    outline = Color(0xFF707070),
    outlineVariant = Color(0xFF363636),
    surfaceContainerLowest = Color(0xFF111210),
    surfaceContainerLow = Color(0xFF1A1C19),
    surfaceContainer = Color(0xFF20221E),
    surfaceContainerHigh = Color(0xFF282B25),
    surfaceContainerHighest = Color(0xFF30332C),
    surfaceTint = Color.Transparent,
    error = Color(0xFFF2A1A1),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF252823),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEAEAEA),
    onPrimaryContainer = Color(0xFF252823),
    secondary = Color(0xFF5E5E5E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0EFE9),
    onSecondaryContainer = Color(0xFF333333),
    tertiary = Color(0xFF26765C),
    background = Color(0xFFF8F7F3),
    onBackground = Color(0xFF252823),
    surface = Color(0xFFF0EFE9),
    onSurface = Color(0xFF252823),
    surfaceVariant = Color(0xFFF0EFE9),
    onSurfaceVariant = Color(0xFF666666),
    outline = Color(0xFF858585),
    outlineVariant = Color(0xFFE3E2DA),
    surfaceContainerLowest = Color(0xFFF8F7F3),
    surfaceContainerLow = Color(0xFFF3F2EC),
    surfaceContainer = Color(0xFFF0EFE9),
    surfaceContainerHigh = Color(0xFFEAE9E2),
    surfaceContainerHighest = Color(0xFFE3E2DA),
    surfaceTint = Color.Transparent,
    error = Color(0xFFB3261E),
)

private val PikoTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 40.sp,
        lineHeight = 48.sp,
        letterSpacing = (-1.2).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 30.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.8).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 24.sp,
        lineHeight = 29.sp,
        letterSpacing = (-0.4).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 25.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 21.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp,
    ),
).let { base ->
    // Keep Material's less-used styles while applying Piko's display styles.
    base.copy(
        bodySmall = base.bodyMedium.copy(fontSize = 12.sp, lineHeight = 17.sp),
        labelSmall = base.labelMedium.copy(fontSize = 11.sp, lineHeight = 14.sp),
    )
}

private val PikoShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun PikoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = PikoTypography,
        shapes = PikoShapes,
        content = content,
    )
}
