package com.katka.adaptivekalmanfilter.ui.comparison_screen

import android.net.Uri
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.katka.adaptivekalmanfilter.design_system.*
import com.katka.adaptivekalmanfilter.model.*

private val NeuralCyan = Color(0xFF00E5FF)
private val ClassGreen = SignalGreen

@Composable
fun ComparisonScreen(
    uiState:    ComparisonUiState,
    onStart:    () -> Unit,
    onStop:     () -> Unit,
    onReset:    () -> Unit,
    onShare:    (Uri) -> Unit
) {
    val scrollState = rememberScrollState()

    Box(Modifier.fillMaxSize().background(Background)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(bottom = 110.dp)
        ) {
            ComparisonHeader(uiState)
            Spacer(Modifier.height(12.dp))

            when (uiState) {
                is ComparisonUiState.NeuralNotTrained -> NeuralNotTrainedContent()
                is ComparisonUiState.Idle             -> IdleComparisonContent()
                is ComparisonUiState.Running          -> RunningContent(uiState)
                is ComparisonUiState.Finished         -> FinishedContent(uiState, onShare)
                is ComparisonUiState.Error            -> ErrorContent(uiState.message)
                else -> Unit
            }
        }

        ComparisonBottomBar(
            uiState  = uiState,
            onStart  = onStart,
            onStop   = onStop,
            onReset  = onReset,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}


@Composable
private fun ComparisonHeader(uiState: ComparisonUiState) {
    val (statusText, statusColor) = when (uiState) {
        is ComparisonUiState.NeuralNotTrained -> "СЕТЬ НЕ ОБУЧЕНА" to TextSecondary
        is ComparisonUiState.Idle             -> "ГОТОВ"           to SignalGreen
        is ComparisonUiState.Running          -> "ЗАПИСЬ"          to NeuralCyan
        is ComparisonUiState.Finished         -> "ЗАВЕРШЕНО"       to SignalGreen
        is ComparisonUiState.Error            -> "ОШИБКА"          to ErrorRed
        else                                  -> "—"               to TextSecondary
    }
    val elapsed = when (uiState) {
        is ComparisonUiState.Running  -> uiState.elapsedSeconds
        is ComparisonUiState.Finished -> uiState.elapsedSeconds
        else                          -> null
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
                "СРАВНЕНИЕ ФИЛЬТРОВ",
                style = ReadoutStyle.copy(color = NeuralCyan, letterSpacing = 4.sp, fontSize = 10.sp)
            )
            Text(
                "Калман  vs  Сглаживатель (α)",
                style = ReadoutStyle.copy(color = TextSecondary, fontSize = 11.sp)
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                statusText,
                style = ReadoutStyle.copy(color = statusColor, fontWeight = FontWeight.Bold,
                    fontSize = 10.sp, letterSpacing = 2.sp)
            )
            elapsed?.let {
                Text(
                    "%02d:%02d".format(it / 60, it % 60),
                    style = ReadoutLargeStyle.copy(fontSize = 18.sp, color = NeuralCyan)
                )
            }
        }
    }
}


@Composable
private fun NeuralNotTrainedContent() {
    InfoCard(
        title   = "НЕЙРОСЕТЬ НЕ ОБУЧЕНА",
        content = "Для сравнительной сессии нужна обученная нейросеть. " +
                "Перейдите во вкладку «Neural Kalman», соберите данные и обучите сеть.",
        color   = ErrorRed
    )
}

@Composable
private fun IdleComparisonContent() {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        InfoCard(
            title   = "КАК РАБОТАЕТ СРАВНЕНИЕ",
            content = "Один фильтр Калмана и нейросглаживатель поверх него запускаются на одном " +
                    "GPS-потоке. Сравниваются три трека: сырой GPS, фильтр Калмана и сглаженный. " +
                    "После остановки данные сохраняются в Downloads как CSV.",
            color   = NeuralCyan
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LegendChip("Калман", ClassGreen, Modifier.weight(1f))
            LegendChip("Сглажено", NeuralCyan, Modifier.weight(1f))
            LegendChip("GPS raw", RawAmber, Modifier.weight(1f))
        }
        InfoCard(
            title   = "СОДЕРЖИМОЕ CSV",
            content = "step · timestamp · raw_lat/lon · gps_accuracy · gps_speed · " +
                    "kf_lat/lon · kf_vx/vy · kf_sigma · kf_innov · " +
                    "sg_lat/lon · smoothed_lat/lon · alpha",
            color   = TextSecondary
        )
    }
}

@Composable
private fun RunningContent(state: ComparisonUiState.Running) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceHigh)
                .padding(12.dp),
            Arrangement.SpaceBetween, Alignment.CenterVertically
        ) {
            Text("ТОЧЕК ЗАПИСАНО", style = ReadoutStyle.copy(fontSize = 9.sp, color = TextSecondary))
            Text(
                "${state.stepCount}",
                style = ReadoutLargeStyle.copy(fontSize = 22.sp, color = NeuralCyan)
            )
        }

        ComparisonTrackCanvas(
            classPoints  = state.trackPoints,
            neuralPoints = state.neuralPoints,
            rawPoints    = state.rawPoints
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterReadoutColumn(
                label   = "КАЛМАН",
                color   = ClassGreen,
                readout = state.classReadout,
                modifier = Modifier.weight(1f)
            )
            FilterReadoutColumn(
                label   = "СГЛАЖЕНО",
                color   = NeuralCyan,
                readout = state.neuralReadout,
                modifier = Modifier.weight(1f)
            )
        }

        NeuralStatusBadge(state.isNeuralActive)
    }
}

@Composable
private fun FinishedContent(state: ComparisonUiState.Finished, onShare: (Uri) -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ComparisonTrackCanvas(
            classPoints  = state.trackPoints,
            neuralPoints = state.neuralPoints,
            rawPoints    = state.rawPoints
        )

        val csvColor = if (state.exportedUri != null) SignalGreen else ErrorRed
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceHigh)
                .border(1.dp, csvColor.copy(0.4f), RoundedCornerShape(8.dp))
                .padding(12.dp),
            Arrangement.SpaceBetween, Alignment.CenterVertically
        ) {
            Column {
                Text(
                    if (state.exportedUri != null) "✓ CSV сохранён в Downloads" else "✗ Ошибка сохранения",
                    style = ReadoutStyle.copy(color = csvColor, fontWeight = FontWeight.Bold)
                )
                Text(
                    "${state.stepCount} точек · ${"%02d:%02d".format(state.elapsedSeconds / 60, state.elapsedSeconds % 60)}",
                    style = ReadoutStyle.copy(color = TextSecondary, fontSize = 10.sp)
                )
            }
            if (state.exportedUri != null) {
                Button(
                    onClick  = { onShare(state.exportedUri) },
                    shape    = RoundedCornerShape(4.dp),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = NeuralCyan.copy(0.12f),
                        contentColor   = NeuralCyan
                    ),
                    border = BorderStroke(1.dp, NeuralCyan.copy(0.4f)),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text("ПОДЕЛИТЬСЯ", style = ReadoutStyle.copy(fontSize = 9.sp,
                        letterSpacing = 2.sp, fontWeight = FontWeight.Bold))
                }
            }
        }

        Text(
            "МЕТРИКИ",
            style = ReadoutStyle.copy(fontSize = 9.sp, color = TextSecondary, letterSpacing = 3.sp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricsColumn("КЛАССИКА", ClassGreen, state.classMetrics, Modifier.weight(1f))
            MetricsColumn("НЕЙРОСЕТЬ", NeuralCyan, state.neuralMetrics, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ErrorContent(message: String) {
    Box(
        Modifier.fillMaxWidth().padding(16.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(ErrorRed.copy(0.1f))
            .border(1.dp, ErrorRed.copy(0.4f), RoundedCornerShape(8.dp))
            .padding(16.dp)
    ) {
        Text(message, style = ReadoutStyle.copy(color = ErrorRed))
    }
}


@Composable
private fun ComparisonTrackCanvas(
    classPoints:  List<TrackPoint>,
    neuralPoints: List<TrackPoint>,
    rawPoints:    List<TrackPoint>
) {
    Box(
        Modifier.fillMaxWidth().height(240.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceHigh)
            .border(1.dp, Divider, RoundedCornerShape(8.dp))
    ) {
        if (classPoints.isEmpty() && neuralPoints.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("Ожидание GPS...", style = ReadoutStyle.copy(color = TextSecondary))
            }
        } else {
            Canvas(Modifier.fillMaxSize().padding(12.dp)) {
                val all = classPoints + neuralPoints + rawPoints
                if (all.isEmpty()) return@Canvas

                val minX = all.minOf { it.x }; val maxX = all.maxOf { it.x }
                val minY = all.minOf { it.y }; val maxY = all.maxOf { it.y }
                val spanX = (maxX - minX).coerceAtLeast(1f)
                val spanY = (maxY - minY).coerceAtLeast(1f)
                val scale = minOf(size.width / spanX, size.height / spanY) * 0.85f
                val cx = size.width / 2f; val cy = size.height / 2f
                val mx = (minX + maxX) / 2f; val my = (minY + maxY) / 2f

                fun norm(pts: List<TrackPoint>) = pts.map { p ->
                    Offset(cx + (p.x - mx) * scale, cy - (p.y - my) * scale)
                }

                val gc = Color(0xFF161C20)
                repeat(5) { i ->
                    val x = size.width * i / 4f
                    drawLine(gc, Offset(x, 0f), Offset(x, size.height))
                    drawLine(gc, Offset(0f, x), Offset(size.width, x))
                }

                norm(rawPoints).forEach { drawCircle(RawAmber.copy(0.35f), 2.5f, it) }

                val cPts = norm(classPoints)
                if (cPts.size >= 2) {
                    val path = Path().apply { moveTo(cPts[0].x, cPts[0].y); cPts.drop(1).forEach { lineTo(it.x, it.y) } }
                    drawPath(path, ClassGreen, style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                cPts.lastOrNull()?.let { drawCircle(ClassGreen, 6f, it); drawCircle(Background, 2.5f, it) }

                val nPts = norm(neuralPoints)
                if (nPts.size >= 2) {
                    val path = Path().apply { moveTo(nPts[0].x, nPts[0].y); nPts.drop(1).forEach { lineTo(it.x, it.y) } }
                    drawPath(path, NeuralCyan, style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                nPts.lastOrNull()?.let { drawCircle(NeuralCyan, 6f, it); drawCircle(Background, 2.5f, it) }
            }
        }

        Row(
            Modifier.align(Alignment.BottomStart).padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LegendDot(ClassGreen, "Калман")
            LegendDot(NeuralCyan, "Сглажено")
            LegendDot(RawAmber,   "GPS raw")
        }
    }
}

@Composable
private fun FilterReadoutColumn(
    label:    String,
    color:    Color,
    readout:  KalmanReadout,
    modifier: Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceHigh)
            .border(1.dp, color.copy(0.25f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(label, style = ReadoutStyle.copy(color = color, fontWeight = FontWeight.Bold,
            fontSize = 9.sp, letterSpacing = 2.sp))
        MiniCell("Lat", "%.5f°".format(readout.filteredLat), color)
        MiniCell("Lon", "%.5f°".format(readout.filteredLon), color)
        MiniCell("α",   "%.3f".format(readout.alpha), color)
        MiniCell("σ",   "%.2f м".format(readout.posUncertaintyM), RawAmber)
        MiniCell("‖y‖", "%.2f м".format(readout.innovationMagnitude), TextSecondary)
    }
}

@Composable
private fun MiniCell(label: String, value: String, valueColor: Color) {
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
        Text(label, style = ReadoutStyle.copy(fontSize = 9.sp, color = TextSecondary))
        Text(value, style = ReadoutStyle.copy(fontSize = 10.sp, color = valueColor,
            fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun MetricsColumn(label: String, color: Color, metrics: MetricsUiModel, modifier: Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceHigh)
            .border(1.dp, color.copy(0.3f), RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(label, style = ReadoutStyle.copy(color = color, fontWeight = FontWeight.Bold,
            fontSize = 9.sp, letterSpacing = 2.sp))
        MetricRow("RMSE",   metrics.rmse,      color)
        MetricRow("MAE",    metrics.mae,        PrecisionCyan)
        MetricRow("MAX",    metrics.maxError,   ErrorRed)
        MetricRow("JITTER", metrics.stability,  RawAmber)
    }
}

@Composable
private fun MetricRow(label: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
        Text(label, style = ReadoutStyle.copy(fontSize = 9.sp, color = TextSecondary))
        Text(value, style = ReadoutStyle.copy(fontSize = 11.sp, color = color, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun NeuralStatusBadge(isActive: Boolean) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (isActive) Color(0xFF002A30) else SurfaceHigh)
            .border(1.dp, if (isActive) NeuralCyan.copy(0.4f) else Divider, RoundedCornerShape(6.dp))
            .padding(10.dp),
        Arrangement.SpaceBetween, Alignment.CenterVertically
    ) {
        Text(
            if (isActive) "🧠 Сглаживание активно" else "⏳ Прогрев окна...",
            style = ReadoutStyle.copy(color = if (isActive) NeuralCyan else TextSecondary,
                fontWeight = FontWeight.Bold)
        )
        Text(
            if (isActive) "α ← MLP" else "ожидание 11 точек",
            style = ReadoutStyle.copy(fontSize = 10.sp, color = TextSecondary)
        )
    }
}

@Composable
private fun InfoCard(title: String, content: String, color: Color) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(0.06f))
            .border(1.dp, color.copy(0.25f), RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, style = ReadoutStyle.copy(color = color, fontWeight = FontWeight.Bold,
            fontSize = 10.sp, letterSpacing = 2.sp))
        Text(content, style = ReadoutStyle.copy(color = TextSecondary, fontSize = 11.sp))
    }
}

@Composable
private fun LegendChip(label: String, color: Color, modifier: Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(SurfaceHigh)
            .border(1.dp, color.copy(0.3f), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(label, style = ReadoutStyle.copy(fontSize = 10.sp, color = color))
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(8.dp, 2.dp).background(color))
        Text(label, style = ReadoutStyle.copy(fontSize = 9.sp, color = TextSecondary))
    }
}


@Composable
private fun ComparisonBottomBar(
    uiState:  ComparisonUiState,
    onStart:  () -> Unit,
    onStop:   () -> Unit,
    onReset:  () -> Unit,
    modifier: Modifier
) {
    Surface(modifier = modifier.fillMaxWidth(), color = Surface, tonalElevation = 8.dp) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when (uiState) {
                is ComparisonUiState.Idle -> {
                    CmpButton("ЗАПУСТИТЬ СРАВНЕНИЕ", NeuralCyan, Modifier.fillMaxWidth(), onStart)
                }
                is ComparisonUiState.Running -> {
                    CmpButton("ОСТАНОВИТЬ И СОХРАНИТЬ", ErrorRed, Modifier.fillMaxWidth(), onStop)
                }
                is ComparisonUiState.Finished -> {
                    CmpButton("НОВАЯ СЕССИЯ", NeuralCyan, Modifier.fillMaxWidth(), onReset)
                }
                is ComparisonUiState.Error -> {
                    CmpButton("ПОВТОРИТЬ", RawAmber, Modifier.fillMaxWidth(), onReset)
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun CmpButton(label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick  = onClick,
        modifier = modifier.height(52.dp),
        shape    = RoundedCornerShape(4.dp),
        colors   = ButtonDefaults.buttonColors(
            containerColor = color.copy(alpha = 0.12f),
            contentColor   = color
        ),
        border = BorderStroke(1.dp, color.copy(0.4f))
    ) {
        Text(label, style = ReadoutStyle.copy(color = color, fontWeight = FontWeight.Bold,
            letterSpacing = 3.sp, fontSize = 12.sp))
    }
}
