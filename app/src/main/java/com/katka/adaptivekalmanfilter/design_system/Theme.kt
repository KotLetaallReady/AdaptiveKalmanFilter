package com.katka.adaptivekalmanfilter.design_system

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ── Palette ────────────────────────────────────────────────────────────────
// Промышленный / осциллографный стиль: почти чёрный фон, зелёный сигнал,
// янтарный акцент, холодно-белый текст.

val Background   = Color(0xFF0A0D0F)
val Surface      = Color(0xFF111518)
val SurfaceHigh  = Color(0xFF1A1F24)
val Divider      = Color(0xFF242B31)

val SignalGreen  = Color(0xFF00E676)   // filtered track / primary accent
val SignalDim    = Color(0xFF1B4332)   // dim green for backgrounds
val RawAmber     = Color(0xFFFFC107)   // raw GPS track
val ErrorRed     = Color(0xFFFF5252)   // high error indicator
val PrecisionCyan= Color(0xFF40C4FF)   // K-gain / secondary metric

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

// ── Typography ─────────────────────────────────────────────────────────────
// SpaceMono для числовых readout'ов (моноширинный, технический),
// Fallback на system monospace если шрифт не добавлен в проект.
val MonoFamily = FontFamily.Monospace   // замени на Font(R.font.space_mono_regular) если подключишь

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

// ── Theme composable ───────────────────────────────────────────────────────

@Composable
fun KalmanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content     = content
    )
}