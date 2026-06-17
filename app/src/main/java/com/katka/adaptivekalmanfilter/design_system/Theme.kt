package com.katka.adaptivekalmanfilter.design_system

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Background   = Color(0xFF0A0D0F)
val Surface      = Color(0xFF111518)
val SurfaceHigh  = Color(0xFF1A1F24)
val Divider      = Color(0xFF242B31)

val SignalGreen  = Color(0xFF00E676)
val SignalDim    = Color(0xFF1B4332)
val RawAmber     = Color(0xFFFFC107)
val ErrorRed     = Color(0xFFFF5252)
val PrecisionCyan= Color(0xFF40C4FF)

val TextPrimary  = Color(0xFFE8EDF0)
val TextSecondary= Color(0xFF7A8994)
val TextMono     = Color(0xFFB0BEC5)

private val DarkColors = darkColorScheme(
    primary        = SignalGreen,
    onPrimary      = Background,
    secondary      = RawAmber,
    onSecondary    = Background,
    tertiary       = PrecisionCyan,
    background     = Background,
    surface        = Surface,
    onBackground   = TextPrimary,
    onSurface      = TextPrimary,
    error          = ErrorRed,
    outline        = Divider
)

/** Monospace family for numeric readouts. */
val MonoFamily = FontFamily.Monospace

val ReadoutStyle = TextStyle(
    fontFamily = MonoFamily,
    fontWeight = FontWeight.Normal,
    fontSize   = 13.sp,
    color      = TextMono,
    letterSpacing = 0.5.sp
)

val ReadoutLargeStyle = TextStyle(
    fontFamily = MonoFamily,
    fontWeight = FontWeight.Bold,
    fontSize   = 22.sp,
    color      = TextPrimary,
    letterSpacing = (-0.5).sp
)

/** App dark theme with an oscilloscope-style palette. */
@Composable
fun KalmanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content     = content
    )
}
