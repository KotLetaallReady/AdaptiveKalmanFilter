package com.katka.adaptivekalmanfilter.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.katka.adaptivekalmanfilter.design_system.*
import com.katka.adaptivekalmanfilter.model.FilterUiState
import kotlin.math.sin

// Дополнительный цвет для нейросетевого режима — если нет в design_system, добавь туда
private val NeuralCyan = Color(0xFF00E5FF)

@Composable
fun MenuScreen(
    uiState: FilterUiState,
    onStartClassicalSession: () -> Unit,
    onStartNeuralSession: () -> Unit,
    onPermissionGranted: () -> Unit,
    onPermissionDenied: () -> Unit
) {
    val permLauncher = rememberPermissionLauncher(onPermissionGranted, onPermissionDenied)
    val needsPerm    = uiState is FilterUiState.NeedsPermission

    Box(
        modifier = Modifier.fillMaxSize().background(Background),
        contentAlignment = Alignment.Center
    ) {
        OscilloscopeBackground()

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            // ── Заголовок ─────────────────────────────────────────────────────
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text  = "ADAPTIVE",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = SignalGreen, letterSpacing = 8.sp, fontSize = 11.sp
                    )
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text  = "Kalman Filter",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        color = TextPrimary, fontWeight = FontWeight.Light,
                        fontSize = 38.sp, letterSpacing = (-1).sp
                    )
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text  = "Выберите режим работы фильтра",
                    style = ReadoutStyle.copy(color = TextSecondary)
                )
            }

            // ── Плитки характеристик ──────────────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                InfoTile("СОСТОЯНИЕ", "x y vx vy", Modifier.weight(1f))
                InfoTile("СЕНСОРЫ",   "GPS + IMU",  Modifier.weight(1f))
                InfoTile("РЕЖИМЫ",    "KF + NN",    Modifier.weight(1f))
            }

            // ── Баннер разрешения ──────────────────────────────────────────────
            if (needsPerm) PermissionBanner()

            // ── Кнопка: Классический фильтр ───────────────────────────────────
            FilterModeButton(
                label       = if (needsPerm) "ВЫДАТЬ РАЗРЕШЕНИЕ" else "КЛАССИЧЕСКИЙ ФИЛЬТР",
                description = "Уравнения Риккати · Sage-Husa R",
                accentColor = SignalGreen,
                isPrimary   = true,
                onClick = {
                    if (needsPerm) {
                        permLauncher.launch(locationPermissions)
                    } else {
                        onStartClassicalSession()
                    }
                }
            )

            // ── Кнопка: Нейросетевой фильтр ───────────────────────────────────
            FilterModeButton(
                label       = "НЕЙРОСЕТЕВОЙ ФИЛЬТР",
                description = "MLP · обучение на маршруте · offline",
                accentColor = NeuralCyan,
                isPrimary   = false,
                onClick = {
                    if (needsPerm) {
                        permLauncher.launch(locationPermissions)
                    } else {
                        onStartNeuralSession()
                    }
                },
                enabled = !needsPerm
            )

            // ── Карточка алгоритмов ────────────────────────────────────────────
            AlgorithmCard()
        }
    }
}

// ── Компоненты ────────────────────────────────────────────────────────────────

@Composable
private fun FilterModeButton(
    label:       String,
    description: String,
    accentColor: Color,
    isPrimary:   Boolean,
    onClick:     () -> Unit,
    enabled:     Boolean = true
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_$label")
    val pulse by infiniteTransition.animateFloat(
        initialValue = if (isPrimary) 0.5f else 0.2f,
        targetValue  = if (isPrimary) 1.0f else 0.5f,
        animationSpec = infiniteRepeatable(
            animation  = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "pulseAlpha"
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(contentAlignment = Alignment.Center) {
            // Свечение по контуру
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isPrimary) 64.dp else 56.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .border(1.dp, accentColor.copy(alpha = pulse * 0.4f), RoundedCornerShape(4.dp))
            )
            Button(
                onClick  = onClick,
                enabled  = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isPrimary) 56.dp else 50.dp),
                shape  = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isPrimary) SignalDim
                    else accentColor.copy(alpha = 0.08f),
                    contentColor   = accentColor,
                    disabledContainerColor = SurfaceHigh,
                    disabledContentColor   = TextSecondary
                )
            ) {
                Text(
                    text  = label,
                    style = ReadoutStyle.copy(
                        fontSize      = if (isPrimary) 14.sp else 12.sp,
                        fontWeight    = FontWeight.Bold,
                        letterSpacing = 3.sp,
                        color         = if (enabled) accentColor else TextSecondary
                    )
                )
            }
        }

        if (enabled) {
            Spacer(Modifier.height(4.dp))
            Text(
                text     = description,
                style    = ReadoutStyle.copy(
                    fontSize = 10.sp, color = accentColor.copy(alpha = 0.5f),
                    letterSpacing = 1.sp
                ),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
private fun rememberPermissionLauncher(
    onGranted: () -> Unit,
    onDenied:  () -> Unit
) = androidx.activity.compose.rememberLauncherForActivityResult(
    androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
) { results ->
    val granted = results[android.Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            results[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
    if (granted) onGranted() else onDenied()
}

private val locationPermissions = arrayOf(
    android.Manifest.permission.ACCESS_FINE_LOCATION,
    android.Manifest.permission.ACCESS_COARSE_LOCATION
)

@Composable
private fun OscilloscopeBackground() {
    val infiniteTransition = rememberInfiniteTransition(label = "osc")
    val phase by infiniteTransition.animateFloat(
        initialValue  = 0f,
        targetValue   = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing)),
        label         = "phase"
    )
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        val gridColor = Color(0xFF0F1A1F)
        val step = 48.dp.toPx()
        var x = 0f; while (x < w) { drawLine(gridColor, Offset(x, 0f), Offset(x, h), 0.5f); x += step }
        var y = 0f; while (y < h) { drawLine(gridColor, Offset(0f, y), Offset(w, y), 0.5f); y += step }

        fun sinPath(baseY: Float, freq: Float, phaseOff: Float): Path {
            val path = Path(); var first = true
            for (px in 0..w.toInt() step 4) {
                val t = px / w
                val py = baseY + h * 0.05f * sin((t * 6.28f * freq + phaseOff + phase).toDouble()).toFloat()
                if (first) { path.moveTo(px.toFloat(), py); first = false } else path.lineTo(px.toFloat(), py)
            }
            return path
        }
        drawPath(sinPath(h * 0.25f, 1.5f, 0f), SignalGreen.copy(alpha = 0.15f),
            style = Stroke(1.5f, cap = StrokeCap.Round))
        drawPath(sinPath(h * 0.75f, 1f, 1f), RawAmber.copy(alpha = 0.08f),
            style = Stroke(1.5f, cap = StrokeCap.Round))
        drawPath(sinPath(h * 0.5f, 0.8f, 2f), NeuralCyan.copy(alpha = 0.06f),
            style = Stroke(1.5f, cap = StrokeCap.Round))
    }
}

@Composable
private fun InfoTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceHigh)
            .border(1.dp, Divider, RoundedCornerShape(8.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, style = ReadoutStyle.copy(fontSize = 9.sp, color = TextSecondary))
        Spacer(Modifier.height(6.dp))
        Text(value, style = ReadoutStyle.copy(color = SignalGreen, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun PermissionBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(ErrorRed.copy(alpha = 0.12f))
            .border(1.dp, ErrorRed.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("⚠", color = ErrorRed, fontSize = 18.sp)
        Text(
            text  = "Требуется разрешение на геолокацию",
            style = ReadoutStyle.copy(color = ErrorRed.copy(alpha = 0.9f), fontSize = 12.sp)
        )
    }
}

@Composable
private fun AlgorithmCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceHigh)
            .border(1.dp, Divider, RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("АЛГОРИТМЫ", style = ReadoutStyle.copy(fontSize = 9.sp, color = TextSecondary, letterSpacing = 4.sp))
        FormulaRow("K",  "P·Hᵀ·(H·P·Hᵀ + R)⁻¹", SignalGreen)
        FormulaRow("K*", "MLP(инновации, acc, dt)", NeuralCyan)
        FormulaRow("x̂",  "x̂_pred + K·(z − H·x̂_pred)", TextMono)
    }
}

@Composable
private fun FormulaRow(lhs: String, rhs: String, lhsColor: Color = PrecisionCyan) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(lhs, style = ReadoutStyle.copy(color = lhsColor, fontWeight = FontWeight.Bold),
            modifier = Modifier.width(24.dp))
        Text("=", style = ReadoutStyle.copy(color = TextSecondary))
        Text(rhs, style = ReadoutStyle.copy(color = TextMono))
    }
}