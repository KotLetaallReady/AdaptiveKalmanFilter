package com.katka.adaptivekalmanfilter.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.katka.adaptivekalmanfilter.design_system.Background
import com.katka.adaptivekalmanfilter.design_system.Divider
import com.katka.adaptivekalmanfilter.design_system.ErrorRed
import com.katka.adaptivekalmanfilter.design_system.PrecisionCyan
import com.katka.adaptivekalmanfilter.design_system.RawAmber
import com.katka.adaptivekalmanfilter.design_system.ReadoutStyle
import com.katka.adaptivekalmanfilter.design_system.SignalDim
import com.katka.adaptivekalmanfilter.design_system.SignalGreen
import com.katka.adaptivekalmanfilter.design_system.SurfaceHigh
import com.katka.adaptivekalmanfilter.design_system.TextMono
import com.katka.adaptivekalmanfilter.design_system.TextPrimary
import com.katka.adaptivekalmanfilter.design_system.TextSecondary
import com.katka.adaptivekalmanfilter.model.FilterUiState

import kotlin.math.sin

@Composable
fun MenuScreen(
    uiState: FilterUiState,
    onStartSession: () -> Unit,
    onPermissionGranted: () -> Unit,
    onPermissionDenied: () -> Unit
) {
    // ── Permission launcher ─────────────────────────────────────────────────
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) onPermissionGranted() else onPermissionDenied()
    }

    val needsPerm = uiState is FilterUiState.NeedsPermission

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background),
        contentAlignment = Alignment.Center
    ) {
        // ── Фоновая анимация — осциллограф ──────────────────────────────────
        OscilloscopeBackground()

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(32.dp),
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            // ── Заголовок ────────────────────────────────────────────────────
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text  = "ADAPTIVE",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color         = SignalGreen,
                        letterSpacing = 8.sp,
                        fontSize      = 11.sp
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text  = "Kalman Filter",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        color      = TextPrimary,
                        fontWeight = FontWeight.Light,
                        fontSize   = 38.sp,
                        letterSpacing = (-1).sp
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text  = "Classical · Mathematical R",
                    style = ReadoutStyle.copy(color = TextSecondary)
                )
            }

            // ── Инфо-плитки ──────────────────────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                InfoTile(label = "СОСТОЯНИЕ", value = "x y vx vy", modifier = Modifier.weight(1f))
                InfoTile(label = "СЕНСОРЫ",   value = "GPS + IMU",  modifier = Modifier.weight(1f))
                InfoTile(label = "РЕЖИМ",     value = "CLASS.",     modifier = Modifier.weight(1f))
            }

            // ── Permission warning ────────────────────────────────────────────
            if (needsPerm) {
                PermissionBanner()
            }

            // ── Кнопка старта ─────────────────────────────────────────────────
            StartButton(
                label = if (needsPerm) "ВЫДАТЬ РАЗРЕШЕНИЕ" else "НАЧАТЬ СЕССИЮ",
                onClick = {
                    if (needsPerm) {
                        permLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    } else {
                        permLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                        onStartSession()
                    }
                }
            )

            // ── Описание алгоритма ────────────────────────────────────────────
            AlgorithmCard()
        }
    }
}

// ── Компоненты ────────────────────────────────────────────────────────────────

@Composable
private fun OscilloscopeBackground() {
    val infiniteTransition = rememberInfiniteTransition(label = "osc")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue  = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing)
        ),
        label = "phase"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        // Сетка
        val gridColor = Color(0xFF0F1A1F)
        val step = 48.dp.toPx()
        var x = 0f
        while (x < w) {
            drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 0.5f)
            x += step
        }
        var y = 0f
        while (y < h) {
            drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 0.5f)
            y += step
        }
        // Синусоида — два слоя (фильтрованный + сырой)
        val amplitude = h * 0.05f
        val baseY1 = h * 0.25f
        val baseY2 = h * 0.75f

        fun sinPath(baseY: Float, freqMult: Float, phaseOff: Float): Path {
            val path = Path()
            var first = true
            for (px in 0..w.toInt() step 4) {
                val t = px / w
                val py = baseY + amplitude * sin((t * 6.28f * freqMult + phaseOff + phase).toDouble()).toFloat()
                if (first) { path.moveTo(px.toFloat(), py); first = false }
                else path.lineTo(px.toFloat(), py)
            }
            return path
        }

        drawPath(sinPath(baseY1, 1.5f, 0f), color = SignalGreen.copy(alpha = 0.15f),
            style = Stroke(width = 1.5f, cap = StrokeCap.Round))
        drawPath(sinPath(baseY2, 1f, 1f), color = RawAmber.copy(alpha = 0.1f),
            style = Stroke(width = 1.5f, cap = StrokeCap.Round))
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
        Text(text = label, style = ReadoutStyle.copy(fontSize = 9.sp, color = TextSecondary))
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text  = value,
            style = ReadoutStyle.copy(color = SignalGreen, fontWeight = FontWeight.Bold)
        )
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
            text  = "Требуется разрешение на геолокацию для работы фильтра",
            style = ReadoutStyle.copy(color = ErrorRed.copy(alpha = 0.9f), fontSize = 12.sp)
        )
    }
}

@Composable
private fun StartButton(label: String, onClick: () -> Unit) {
    // Пульсирующий контур
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.5f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "pulseAlpha"
    )

    Box(contentAlignment = Alignment.Center) {
        // Внешнее свечение
        Box(
            modifier = Modifier
                .size(220.dp, 68.dp)
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, SignalGreen.copy(alpha = pulse * 0.3f), RoundedCornerShape(4.dp))
        )
        Button(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape  = RoundedCornerShape(4.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = SignalDim,
                contentColor   = SignalGreen
            )
        ) {
            Text(
                text  = label,
                style = ReadoutStyle.copy(
                    fontSize      = 14.sp,
                    fontWeight    = FontWeight.Bold,
                    letterSpacing = 3.sp,
                    color         = SignalGreen
                )
            )
        }
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
        Text(
            text  = "АЛГОРИТМ",
            style = ReadoutStyle.copy(fontSize = 9.sp, color = TextSecondary, letterSpacing = 4.sp)
        )
        FormulaRow("R",  "diag(σ_gps², σ_gps²)")
        FormulaRow("K",  "P·Hᵀ·(H·P·Hᵀ + R)⁻¹")
        FormulaRow("x̂",  "x̂_pred + K·(z − H·x̂_pred)")
        FormulaRow("P",  "(I−KH)·P·(I−KH)ᵀ + K·R·Kᵀ")
    }
}

@Composable
private fun FormulaRow(lhs: String, rhs: String) {
    Row(
        modifier            = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text     = lhs,
            style    = ReadoutStyle.copy(color = PrecisionCyan, fontWeight = FontWeight.Bold),
            modifier = Modifier.width(24.dp)
        )
        Text(text = "=", style = ReadoutStyle.copy(color = TextSecondary))
        Text(text = rhs, style = ReadoutStyle.copy(color = TextMono))
    }
}