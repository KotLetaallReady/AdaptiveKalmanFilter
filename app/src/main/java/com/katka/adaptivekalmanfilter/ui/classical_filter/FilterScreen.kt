package com.katka.adaptivekalmanfilter.ui.classical_filter

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.katka.adaptivekalmanfilter.design_system.Background
import com.katka.adaptivekalmanfilter.design_system.Divider
import com.katka.adaptivekalmanfilter.design_system.ErrorRed
import com.katka.adaptivekalmanfilter.design_system.PrecisionCyan
import com.katka.adaptivekalmanfilter.design_system.RawAmber
import com.katka.adaptivekalmanfilter.design_system.ReadoutLargeStyle
import com.katka.adaptivekalmanfilter.design_system.ReadoutStyle
import com.katka.adaptivekalmanfilter.design_system.SignalDim
import com.katka.adaptivekalmanfilter.design_system.SignalGreen
import com.katka.adaptivekalmanfilter.design_system.Surface
import com.katka.adaptivekalmanfilter.design_system.SurfaceHigh
import com.katka.adaptivekalmanfilter.design_system.TextMono
import com.katka.adaptivekalmanfilter.design_system.TextSecondary
import com.katka.adaptivekalmanfilter.model.FilterUiState
import com.katka.adaptivekalmanfilter.model.KalmanReadout
import com.katka.adaptivekalmanfilter.model.MetricsUiModel
import com.katka.adaptivekalmanfilter.model.TrackPoint

@Composable
fun FilterScreen(
    uiState: FilterUiState,
    onStart: () -> Unit,
    onStop:  () -> Unit,
    onReset: () -> Unit
) {
    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(bottom = 100.dp)
        ) {
            FilterHeader(uiState)
            Spacer(modifier = Modifier.height(12.dp))

            when (uiState) {
                is FilterUiState.Idle -> {
                    IdleContent()
                }

                is FilterUiState.Running -> {
                    TrackCanvas(
                        filteredPoints = uiState.trackPoints,
                        rawPoints      = uiState.rawPoints,
                        modifier       = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    KalmanReadoutGrid(uiState.readout)
                    Spacer(modifier = Modifier.height(16.dp))
                    GainBar(label = "K_x", value = uiState.readout.kPosX)
                    Spacer(modifier = Modifier.height(8.dp))
                    GainBar(label = "K_y", value = uiState.readout.kPosY)
                    Spacer(modifier = Modifier.height(16.dp))
                    InnovationIndicator(uiState.readout.innovationMagnitude)
                }

                is FilterUiState.Finished -> {
                    TrackCanvas(
                        filteredPoints = uiState.trackPoints,
                        rawPoints      = uiState.rawPoints,
                        modifier       = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    MetricsPanel(uiState.metrics)
                    Spacer(modifier = Modifier.height(16.dp))
                    KalmanReadoutGrid(uiState.readout)
                }

                is FilterUiState.Error -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(ErrorRed.copy(0.1f))
                            .border(1.dp, ErrorRed.copy(0.4f), RoundedCornerShape(8.dp))
                            .padding(16.dp)
                    ) {
                        Text(uiState.message, style = ReadoutStyle.copy(color = ErrorRed))
                    }
                }

                else -> Unit
            }
        }

        BottomActionBar(
            uiState = uiState,
            onStart = onStart,
            onStop  = onStop,
            onReset = onReset,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}


@Composable
private fun FilterHeader(uiState: FilterUiState) {
    val isRunning = uiState is FilterUiState.Running
    val elapsed   = when (uiState) {
        is FilterUiState.Running  -> uiState.elapsedSeconds
        is FilterUiState.Finished -> uiState.elapsedSeconds
        else -> 0
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text  = "ФИЛЬТР КАЛМАНА",
                style = ReadoutStyle.copy(
                    color         = SignalGreen,
                    letterSpacing = 4.sp,
                    fontSize      = 10.sp
                )
            )
            Text(
                text  = "Classical · R = σ²_gps",
                style = ReadoutStyle.copy(color = TextSecondary, fontSize = 11.sp)
            )
        }

        Row(
            verticalAlignment      = Alignment.CenterVertically,
            horizontalArrangement  = Arrangement.spacedBy(10.dp)
        ) {
            if (isRunning) PulsingDot()

            Text(
                text  = formatElapsed(elapsed),
                style = ReadoutLargeStyle.copy(
                    fontSize = 20.sp,
                    color    = if (isRunning) SignalGreen else TextSecondary
                )
            )
        }
    }
}

@Composable
private fun PulsingDot() {
    val infiniteTransition = rememberInfiniteTransition(label = "dot")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "dotAlpha"
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(SignalGreen.copy(alpha = alpha))
    )
}


@Composable
private fun TrackCanvas(
    filteredPoints: List<TrackPoint>,
    rawPoints: List<TrackPoint>,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(240.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceHigh)
            .border(1.dp, Divider, RoundedCornerShape(8.dp))
    ) {
        if (filteredPoints.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text  = "Ожидание GPS...",
                    style = ReadoutStyle.copy(color = TextSecondary)
                )
            }
        } else {
            Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                val all = filteredPoints + rawPoints
                drawGrid()

                fun normalize(pts: List<TrackPoint>): List<Offset> {
                    if (pts.isEmpty()) return emptyList()
                    val minX = all.minOf { it.x }
                    val maxX = all.maxOf { it.x }
                    val minY = all.minOf { it.y }
                    val maxY = all.maxOf { it.y }
                    val spanX = (maxX - minX).coerceAtLeast(1f)
                    val spanY = (maxY - minY).coerceAtLeast(1f)
                    val scale = minOf(size.width / spanX, size.height / spanY) * 0.85f
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val mx = (minX + maxX) / 2f
                    val my = (minY + maxY) / 2f
                    return pts.map { p ->
                        Offset(cx + (p.x - mx) * scale, cy - (p.y - my) * scale)
                    }
                }

                val filteredOffsets = normalize(filteredPoints)
                val rawOffsets      = normalize(rawPoints)

                for (o in rawOffsets) {
                    drawCircle(RawAmber.copy(alpha = 0.45f), radius = 2.5f, center = o)
                }

                drawPolyline(filteredOffsets, SignalGreen, strokeWidth = 2f)

                filteredOffsets.lastOrNull()?.let { last ->
                    drawCircle(SignalGreen, radius = 6f, center = last)
                    drawCircle(Background,   radius = 3f, center = last)
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            LegendItem(color = SignalGreen, label = "Kalman")
            LegendItem(color = RawAmber,   label = "GPS raw")
        }
    }
}

private fun DrawScope.drawGrid() {
    val color = Color(0xFF161C20)
    val step  = size.width / 5f
    var x = 0f
    while (x <= size.width)  { drawLine(color, Offset(x, 0f), Offset(x, size.height)); x += step }
    var y = 0f
    while (y <= size.height) { drawLine(color, Offset(0f, y), Offset(size.width, y)); y += step }
}

private fun DrawScope.drawPolyline(pts: List<Offset>, color: Color, strokeWidth: Float) {
    if (pts.size < 2) return
    val path = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
    }
    drawPath(path, color, style = Stroke(
        width     = strokeWidth,
        cap       = StrokeCap.Round,
        join      = StrokeJoin.Round
    ))
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(Modifier.size(8.dp, 2.dp).background(color))
        Text(label, style = ReadoutStyle.copy(fontSize = 10.sp, color = TextSecondary))
    }
}


@Composable
private fun KalmanReadoutGrid(r: KalmanReadout) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SectionLabel("ВЕКТОР СОСТОЯНИЯ")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DataCell("Lat", "%.6f°".format(r.filteredLat), SignalGreen, Modifier.weight(1f))
            DataCell("Lon", "%.6f°".format(r.filteredLon), SignalGreen, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DataCell("Vx", "%.3f м/с".format(r.vxMs), PrecisionCyan, Modifier.weight(1f))
            DataCell("Vy", "%.3f м/с".format(r.vyMs), PrecisionCyan, Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(4.dp))
        SectionLabel("КОЭФФИЦИЕНТ R (ШУМ ИЗМЕРЕНИЙ)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DataCell("R[0,0]", "%.3f м²".format(r.rXX), RawAmber,       Modifier.weight(1f))
            DataCell("R[1,1]", "%.3f м²".format(r.rYY), RawAmber,       Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(4.dp))
        SectionLabel("НЕОПРЕДЕЛЁННОСТЬ ПОЗИЦИИ")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DataCell("σ_pos", "%.2f м".format(r.posUncertaintyM), ErrorRed, Modifier.weight(1f))
            DataCell("Δt",    "%.0f мс".format(r.dtMs),           TextMono, Modifier.weight(1f))
        }
    }
}


@Composable
private fun GainBar(label: String, value: Double) {
    val clamped = value.coerceIn(0.0, 1.0).toFloat()
    val animatedWidth by animateFloatAsState(
        targetValue   = clamped,
        animationSpec = tween(durationMillis = 400),
        label         = "bar_$label"
    )

    Column(
        modifier = Modifier.padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = ReadoutStyle.copy(color = PrecisionCyan, fontWeight = FontWeight.Bold))
            Text("%.4f".format(value), style = ReadoutStyle)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(SurfaceHigh)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedWidth)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(PrecisionCyan.copy(alpha = 0.8f))
            )
        }
    }
}


@Composable
private fun InnovationIndicator(magnitudeM: Double) {
    val color = when {
        magnitudeM < 2.0 -> SignalGreen
        magnitudeM < 5.0 -> RawAmber
        else             -> ErrorRed
    }
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceHigh)
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Column {
            Text("ИННОВАЦИЯ ||y||", style = ReadoutStyle.copy(fontSize = 9.sp, color = TextSecondary, letterSpacing = 3.sp))
            Spacer(modifier = Modifier.height(4.dp))
            Text("%.3f м".format(magnitudeM), style = ReadoutLargeStyle.copy(color = color))
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf(
                ErrorRed  to (magnitudeM >= 5.0),
                RawAmber  to (magnitudeM in 2.0..5.0),
                SignalGreen to (magnitudeM < 2.0)
            ).forEach { (c, active) ->
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (active) c else c.copy(alpha = 0.15f))
                )
            }
        }
    }
}


@Composable
private fun MetricsPanel(metrics: MetricsUiModel) {
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceHigh)
            .border(1.dp, SignalGreen.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionLabel("ИТОГОВЫЕ МЕТРИКИ  ·  ${metrics.sampleCount} точек")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCell("RMSE",    metrics.rmse,      SignalGreen,    Modifier.weight(1f))
            MetricCell("MAE",     metrics.mae,       PrecisionCyan,  Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCell("MAX ΔP",   metrics.maxError,  ErrorRed,      Modifier.weight(1f))
            MetricCell("JITTER",   metrics.stability, RawAmber,      Modifier.weight(1f))
        }
        MetricCell("LAG (cross-corr)", metrics.lag, TextSecondary, Modifier.fillMaxWidth())
    }
}

@Composable
private fun MetricCell(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Background)
            .padding(10.dp)
    ) {
        Text(label, style = ReadoutStyle.copy(fontSize = 9.sp, color = TextSecondary, letterSpacing = 2.sp))
        Spacer(modifier = Modifier.height(4.dp))
        Text(value, style = ReadoutLargeStyle.copy(fontSize = 18.sp, color = color))
    }
}


@Composable
private fun BottomActionBar(
    uiState:  FilterUiState,
    onStart:  () -> Unit,
    onStop:   () -> Unit,
    onReset:  () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier       = modifier.fillMaxWidth(),
        color          = Surface,
        tonalElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (uiState) {
                is FilterUiState.Idle -> {
                    Button(
                        onClick  = onStart,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape    = RoundedCornerShape(4.dp),
                        colors   = ButtonDefaults.buttonColors(
                            containerColor = SignalGreen.copy(alpha = 0.12f),
                            contentColor   = SignalGreen
                        ),
                        border = BorderStroke(1.dp, SignalGreen.copy(alpha = 0.5f))
                    ) {
                        Text(
                            "ЗАПУСТИТЬ ФИЛЬТР",
                            style = ReadoutStyle.copy(
                                color         = SignalGreen,
                                fontWeight    = FontWeight.Bold,
                                letterSpacing = 3.sp,
                                fontSize      = 13.sp
                            )
                        )
                    }
                }

                is FilterUiState.Running -> {
                    Button(
                        onClick  = onStop,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape    = RoundedCornerShape(4.dp),
                        colors   = ButtonDefaults.buttonColors(
                            containerColor = ErrorRed.copy(alpha = 0.15f),
                            contentColor   = ErrorRed
                        ),
                        border = BorderStroke(1.dp, ErrorRed.copy(alpha = 0.5f))
                    ) {
                        Text(
                            "ОСТАНОВИТЬ",
                            style = ReadoutStyle.copy(
                                color         = ErrorRed,
                                fontWeight    = FontWeight.Bold,
                                letterSpacing = 3.sp,
                                fontSize      = 13.sp
                            )
                        )
                    }
                }

                is FilterUiState.Finished -> {
                    Button(
                        onClick  = onReset,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape    = RoundedCornerShape(4.dp),
                        colors   = ButtonDefaults.buttonColors(
                            containerColor = SignalDim,
                            contentColor   = SignalGreen
                        )
                    ) {
                        Text(
                            "НОВАЯ СЕССИЯ",
                            style = ReadoutStyle.copy(
                                color         = SignalGreen,
                                fontWeight    = FontWeight.Bold,
                                letterSpacing = 3.sp,
                                fontSize      = 13.sp
                            )
                        )
                    }
                }

                else -> Unit
            }
        }
    }
}


@Composable
private fun SectionLabel(text: String) {
    Text(
        text  = text,
        style = ReadoutStyle.copy(
            fontSize      = 9.sp,
            color         = TextSecondary,
            letterSpacing = 3.sp
        )
    )
}

@Composable
private fun DataCell(label: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(SurfaceHigh)
            .border(1.dp, Divider, RoundedCornerShape(6.dp))
            .padding(10.dp)
    ) {
        Text(label, style = ReadoutStyle.copy(fontSize = 9.sp, color = TextSecondary))
        Spacer(modifier = Modifier.height(4.dp))
        Text(value, style = ReadoutStyle.copy(color = valueColor, fontWeight = FontWeight.SemiBold, fontSize = 13.sp))
    }
}

private fun formatElapsed(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%02d:%02d".format(m, s)
}

@Composable
private fun IdleContent() {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceHigh)
                .border(1.dp, SignalGreen.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text  = "КЛАССИЧЕСКИЙ ФИЛЬТР КАЛМАНА",
                style = ReadoutStyle.copy(
                    color         = SignalGreen,
                    fontWeight    = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    fontSize      = 11.sp
                )
            )
            Text(
                text  = "Адаптивная оценка шума измерений R через инновационную последовательность (Sage-Husa). " +
                        "Нажмите «Старт» и начните движение.",
                style = ReadoutStyle.copy(color = TextSecondary, fontSize = 11.sp)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceHigh)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Q σ_a", style = ReadoutStyle.copy(fontSize = 9.sp, color = TextSecondary))
                Text("0.1 м/с²", style = ReadoutStyle.copy(color = PrecisionCyan, fontWeight = FontWeight.Bold))
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceHigh)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("R окно", style = ReadoutStyle.copy(fontSize = 9.sp, color = TextSecondary))
                Text("40 точек", style = ReadoutStyle.copy(color = RawAmber, fontWeight = FontWeight.Bold))
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceHigh)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Warm-up", style = ReadoutStyle.copy(fontSize = 9.sp, color = TextSecondary))
                Text("acc < 15 м", style = ReadoutStyle.copy(color = SignalGreen, fontWeight = FontWeight.Bold))
            }
        }
    }
}
