package com.silab.smartcount.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Sistema visual de SmartCount, inspirado en Trade Republic:
 * fondo plano sin tarjetas ni sombras, tipografía grande y apretada,
 * separadores finísimos, y color solo para el signo del dinero.
 * Sigue el modo claro/oscuro del sistema.
 */

private val Green = Color(0xFF00C46A)
private val Red = Color(0xFFFF4D4D)

/**
 * Azul de marca, el mismo del icono. Regla de color de la app:
 * verde y rojo quedan reservados EXCLUSIVAMENTE para dinero (saldos, importes);
 * cualquier otro estado afirmativo o elemento interactivo usa el azul de marca.
 * En claro se oscurece un punto para mantener el contraste sobre blanco.
 */
private val BrandDark = Color(0xFF4A6CFF)
private val BrandLight = Color(0xFF3355E6)

data class SmartColors(
    val background: Color,
    val surface: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val divider: Color,
    val chipBackground: Color,
    val chipSelected: Color,
    val chipSelectedText: Color,
    val brand: Color,
    val positive: Color = Green,
    val negative: Color = Red,
    val isDark: Boolean
)

private val DarkColors = SmartColors(
    background = Color(0xFF000000),
    surface = Color(0xFF0E0E10),
    primaryText = Color(0xFFFFFFFF),
    secondaryText = Color(0xFF8A8A8E),
    divider = Color(0xFF1C1C1E),
    chipBackground = Color(0xFF161618),
    chipSelected = Color(0xFFFFFFFF),
    chipSelectedText = Color(0xFF000000),
    brand = BrandDark,
    isDark = true
)

private val LightColors = SmartColors(
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFF7F7F8),
    primaryText = Color(0xFF07070A),
    secondaryText = Color(0xFF74747A),
    divider = Color(0xFFEDEDF0),
    chipBackground = Color(0xFFF2F2F4),
    chipSelected = Color(0xFF07070A),
    chipSelectedText = Color(0xFFFFFFFF),
    brand = BrandLight,
    isDark = false
)

val LocalSmartColors = staticCompositionLocalOf { DarkColors }

/** Atajo: SmartTheme.colors.secondaryText */
object SmartTheme {
    val colors: SmartColors
        @Composable get() = LocalSmartColors.current
}

/** Cifras grandes con tracking negativo, la firma tipográfica de TR. */
val DisplayNumber = TextStyle(
    fontSize = 44.sp,
    lineHeight = 48.sp,
    fontWeight = FontWeight.SemiBold,
    letterSpacing = (-1.4).sp
)

val SectionTitle = TextStyle(
    fontSize = 13.sp,
    lineHeight = 16.sp,
    fontWeight = FontWeight.Medium,
    letterSpacing = 0.6.sp
)

private val SmartTypography = Typography(
    headlineLarge = TextStyle(
        fontSize = 32.sp, lineHeight = 36.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp
    ),
    titleLarge = TextStyle(
        fontSize = 22.sp, lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp
    ),
    titleMedium = TextStyle(
        fontSize = 17.sp, lineHeight = 22.sp,
        fontWeight = FontWeight.Medium, letterSpacing = (-0.2).sp
    ),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(
        fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium
    )
)

/** Márgenes constantes: TR respira mucho por los lados. */
val ScreenPadding = 20.dp
val RowVerticalPadding = 16.dp

@Composable
fun SmartCountTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val smart = if (darkTheme) DarkColors else LightColors
    val scheme = if (darkTheme) {
        darkColorScheme(
            background = smart.background,
            surface = smart.background,
            onBackground = smart.primaryText,
            onSurface = smart.primaryText,
            primary = smart.primaryText,
            onPrimary = smart.background,
            outline = smart.divider
        )
    } else {
        lightColorScheme(
            background = smart.background,
            surface = smart.background,
            onBackground = smart.primaryText,
            onSurface = smart.primaryText,
            primary = smart.primaryText,
            onPrimary = smart.background,
            outline = smart.divider
        )
    }

    CompositionLocalProvider(LocalSmartColors provides smart) {
        MaterialTheme(colorScheme = scheme, typography = SmartTypography, content = content)
    }
}
