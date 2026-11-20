package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val BumbleDarkColorScheme = darkColorScheme(
    primary = BumbleGold,
    onPrimary = BumbleVoidBlack,
    primaryContainer = BumbleGoldMuted.copy(alpha = 0.35f),
    onPrimaryContainer = BumbleGoldBright,
    secondary = BumbleAmber,
    onSecondary = BumbleVoidBlack,
    secondaryContainer = BumbleGraphiteElevated,
    onSecondaryContainer = BumbleChampagne,
    tertiary = BumbleCyanAccent,
    onTertiary = BumbleVoidBlack,
    background = BumbleVoidBlack,
    onBackground = BumbleTextPrimary,
    surface = BumbleObsidian,
    onSurface = BumbleTextPrimary,
    surfaceVariant = BumbleGraphite,
    onSurfaceVariant = BumbleTextSecondary,
    outline = BumbleHudBorder,
    outlineVariant = BumbleGold.copy(alpha = 0.28f),
    error = BumbleCrimson,
    onError = BumbleChampagne,
    errorContainer = BumbleCrimsonSoft,
    onErrorContainer = BumbleCrimson
)

private val BumbleShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun BumbleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BumbleDarkColorScheme,
        typography = BumbleTypography,
        shapes = BumbleShapes,
        content = content
    )
}
