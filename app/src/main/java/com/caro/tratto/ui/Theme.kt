package com.caro.tratto.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Accento dell'app: blu inchiostro. */
val InkBlue = Color(0xFF1F3A5F)

// Palette chiara e neutra: niente tinte viola del baseline Material, superfici bianche.
private val TrattoColors = lightColorScheme(
    primary = InkBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6F3),
    onPrimaryContainer = Color(0xFF0E2340),
    inversePrimary = Color(0xFFA9C7EE),
    secondary = Color(0xFF4F6075),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE6F3),
    onSecondaryContainer = Color(0xFF0E2340),
    tertiary = Color(0xFF7A5A1E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE6B8),
    onTertiaryContainer = Color(0xFF2A1B00),
    background = Color(0xFFF4F6F9),
    onBackground = Color(0xFF191C20),
    surface = Color.White,
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE6EAF0),
    onSurfaceVariant = Color(0xFF4B5563),
    surfaceTint = InkBlue,
    inverseSurface = Color(0xFF2B3038),
    inverseOnSurface = Color(0xFFEFF2F6),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF8A94A3),
    outlineVariant = Color(0xFFD7DDE5),
    scrim = Color.Black,
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFDDE2E9),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainer = Color.White,          // menu
    surfaceContainerHigh = Color.White,      // dialog
    surfaceContainerHighest = Color(0xFFE9EDF2),
)

private val BaseType = Typography()

private val TrattoTypography = BaseType.copy(
    headlineMedium = BaseType.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
    titleLarge = BaseType.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = BaseType.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = BaseType.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = BaseType.labelLarge.copy(fontWeight = FontWeight.Medium),
)

private val TrattoShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun TrattoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TrattoColors,
        typography = TrattoTypography,
        shapes = TrattoShapes,
        content = content,
    )
}
