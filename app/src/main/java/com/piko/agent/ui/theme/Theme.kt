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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

val PikoInk = Color(0xFF080B12)
val PikoInkElevated = Color(0xFF111623)
val PikoLime = Color(0xFFD4FF4F)
val PikoLimeDark = Color(0xFF647D00)
val PikoViolet = Color(0xFF8B7CFF)
val PikoMint = Color(0xFF65E6B4)
val PikoAmber = Color(0xFFFFC857)
val PikoRed = Color(0xFFF35B6B)
val PikoMuted = Color(0xFFAEB8CA)

private val DarkColors = darkColorScheme(
    primary = PikoLime,
    onPrimary = Color(0xFF141A00),
    primaryContainer = Color(0xFF2A3500),
    onPrimaryContainer = PikoLime,
    secondary = PikoViolet,
    onSecondary = Color(0xFF100B2A),
    secondaryContainer = Color(0xFF2B2450),
    onSecondaryContainer = Color(0xFFDAD4FF),
    tertiary = PikoMint,
    onTertiary = Color(0xFF002116),
    background = PikoInk,
    onBackground = Color(0xFFF4F7EE),
    surface = PikoInkElevated,
    onSurface = Color(0xFFF4F7EE),
    surfaceVariant = Color(0xFF1A2130),
    onSurfaceVariant = PikoMuted,
    outline = Color(0xFF3B4558),
    outlineVariant = Color(0xFF252D3C),
    error = PikoRed,
    onError = Color(0xFF2A0007),
    errorContainer = Color(0xFF4A0F19),
    onErrorContainer = Color(0xFFFFD9DE),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF3F4E00),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9F88A),
    onPrimaryContainer = Color(0xFF111A00),
    secondary = Color(0xFF5B4FC4),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E1FF),
    onSecondaryContainer = Color(0xFF1A1148),
    tertiary = Color(0xFF006B4F),
    onTertiary = Color.White,
    background = Color(0xFFF7F8F1),
    onBackground = Color(0xFF151A10),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF151A10),
    surfaceVariant = Color(0xFFE9ECDD),
    onSurfaceVariant = Color(0xFF4A5144),
    outline = Color(0xFF777F70),
    outlineVariant = Color(0xFFD5DAC8),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val PikoTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 38.sp,
        lineHeight = 42.sp,
        letterSpacing = (-1.2).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.8).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 29.sp,
        letterSpacing = (-0.4).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 25.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
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
        fontWeight = FontWeight.SemiBold,
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
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

@Composable
fun PikoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
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
