package com.katka.adaptivekalmanfilter.ui

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
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.katka.adaptivekalmanfilter.design_system.*
import com.katka.adaptivekalmanfilter.model.*
import kotlin.math.roundToInt

// Акцентный цвет нейросетевого режима
private val NeuralAccent = Color(0xFF00E5FF)
private val NeuralDim    = Color(0xFF002A30)

@Composable
fun NeuralFilterScreen(
    uiState:             NeuralFilterUiState,
    onStartCollection:   () -> Unit,
    onStopAndTrain:      () -> Unit,
    onStartNeuralSession:() -> Unit,
    onStopNeuralSession: () -> Unit,
    onReset:             () -> Unit,
    onRetrain:           () -> Unit
) {
    val scrollState = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize().background(Background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(bottom = 110.dp)
        ) {
            NeuralHeader(uiState)
            Spacer(Modifier.height(12.dp))

            when (uiState) {
                is NeuralFilterUiState.NotTrained     -> OnboardingContent()
                is NeuralFilterUiState.CollectingData -> CollectionContent(uiState)
                is NeuralFilterUiState.Training       -> TrainingContent(uiState)
                is NeuralFilterUiState.ReadyToRun     -> ReadyContent()
                is NeuralFilterUiState.Running        -> RunningContent(uiState)
                is NeuralFilterUiState.Finished       -> FinishedContent(uiState)
                is NeuralFilterUiState.Error          -> ErrorContent(uiState.message)
                else                                  -> Unit
            }
        }

        NeuralBottomBar(
            uiState              = uiState,
            onStartCollection    = onStartCollection,
            onStopAndTrain       = onStopAndTrain,
            onStartNeuralSession = onStartNeuralSession,
            onStopNeuralSession  = onStopNeuralSession,
            onReset              = onReset,
            onRetrain            = onRetrain,
            modifier             = Modifier.align(Alignment.BottomCenter)
        )
    }
}

// ── Header ────────────────────────────────────────────────────────────────────

@Composable
private fun NeuralHeader(uiState: NeuralFilterUiState) {
    val (statusText, statusColor) = when (uiState) {
        is NeuralFilterUiState.NotTrained     -> "НЕТ МОДЕЛИ"   to TextSecondary
        is NeuralFilterUiState.CollectingData -> "СБОР ДАННЫХ"  to RawAmber
        is NeuralFilterUiState.Training       -> "ОБУЧЕНИЕ"     to NeuralAccent
        is NeuralFilterUiState.ReadyToRun     -> "ГОТОВ"        to SignalGreen
        is NeuralFilterUiState.Running        -> "АКТИВЕН"      to NeuralAccent
        is NeuralFilterUiState.Finished       -> "ЗАВЕРШЕНО"    to SignalGreen
        else                                  -> "—"            to TextSecondary
    }

    val elapsed = when (uiState) {
        is NeuralFilterUiState.CollectingData -> uiState.elapsedSeconds
        is NeuralFilterUiState.Running        -> uiState.elapsedSeconds
        is NeuralFilterUiState.Finished       -> uiState.elapsedSeconds
        else -> null
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
                text  = "NEURAL KALMAN",
                style = ReadoutStyle.copy(color = NeuralAccent, letterSpacing = 4.sp, fontSize = 10.sp)
            )
            Text(
                text  = "MLP · offline training · K-inference",
                style = ReadoutStyle.copy(color = TextSecondary, fontSize = 11.sp)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (uiState is NeuralFilterUiState.Running || uiState is NeuralFilterUiState.CollectingData) {
                PulsingDot(NeuralAccent)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text  = statusText,
                    style = ReadoutStyle.copy(color = statusColor, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 2.sp)
                )
                elapsed?.let {
                    Text(
                        text  = formatElapsedNeural(it),
                        style = ReadoutLargeStyle.copy(fontSize = 18.sp, color = NeuralAccent)
                    )
                }
            }
        }
    }
}

@Composable
private fun PulsingDot(color: Color) {
    val inf = rememberInfiniteTransition(label = "dot")
    val alpha by inf.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "a"
    )
    Box(Modifier.size(8.dp).clip(CircleShape).background(color.copy(alpha = alpha)))
}

// ── Контент по состояниям ────────────────────────────────────────────────────

@Composable
private fun OnboardingContent() {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        NeuralInfoCard(
            title = "КАК РАБОТАЕТ НЕЙРОСЕТЕВОЙ РЕЖИМ",
            content = "Нейросеть заменяет формулу Риккати при вычислении коэффициента K. " +
                    "Она обучается воспроизводить решения классического фильтра, " +
                    "но при этом запоминает паттерны шума вашего конкретного устройства."
        )

        OnboardingStep(
            number = "1",
            title  = "Сбор данных",
            body   = "Запустите сбор данных и пройдите маршрут. Классический фильтр параллельно " +
                    "записывает обучающие пары: (наблюдение → коэффициент K).",
            color  = RawAmber
        )
        OnboardingStep(
            number = "2",
            title  = "Обучение",
            body   = "После сбора ≥ ${NeuralFilterUiState.MIN_SAMPLES} точек нейросеть " +
                    "обучается за ${NeuralFilterUiState.TRAINING_EPOCHS} эпох на вашем устройстве. " +
                    "Веса сохраняются на диск.",
            color  = NeuralAccent
        )
        OnboardingStep(
            number = "3",
            title  = "Использование",
            body   = "Запустите нейросетевую сессию. Фильтр работает в реальном времени, " +
                    "K вычисляется нейросетью (<0.5 мс на шаг).",
            color  = SignalGreen
        )
    }
}

@Composable
private fun OnboardingStep(number: String, title: String, body: String, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceHigh)
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.15f))
                .border(1.dp, color.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(number, style = ReadoutStyle.copy(color = color, fontWeight = FontWeight.Bold))
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = ReadoutStyle.copy(color = color, fontWeight = FontWeight.Bold))
            Text(body,  style = ReadoutStyle.copy(color = TextSecondary, fontSize = 11.sp))
        }
    }
}

@Composable
private fun CollectionContent(state: NeuralFilterUiState.CollectingData) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Прогресс-бар сбора образцов
        SampleProgressCard(state.sampleCount, NeuralFilterUiState.MIN_SAMPLES, state.progress)

        // Мини-трек
        if (state.trackPoints.isNotEmpty()) {
            NeuralTrackCanvas(state.trackPoints, state.rawPoints)
        }

        // Краткий readout
        SectionLabel("ВЕКТОР СОСТОЯНИЯ")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DataCell("Lat", "%.6f°".format(state.readout.filteredLat), SignalGreen, Modifier.weight(1f))
            DataCell("Lon", "%.6f°".format(state.readout.filteredLon), SignalGreen, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DataCell("σ_pos", "%.2f м".format(state.readout.posUncertaintyM), RawAmber, Modifier.weight(1f))
            DataCell("K_x",  "%.4f".format(state.readout.kPosX),              NeuralAccent, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SampleProgressCard(current: Int, required: Int, progress: Float) {
    val animProg by animateFloatAsState(progress, tween(400), label = "sp")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceHigh)
            .border(1.dp, if (progress >= 1f) SignalGreen.copy(0.5f) else NeuralAccent.copy(0.3f), RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("ОБУЧАЮЩИЕ ДАННЫЕ", style = ReadoutStyle.copy(fontSize = 9.sp, color = TextSecondary, letterSpacing = 3.sp))
            Text(
                "$current / $required",
                style = ReadoutStyle.copy(
                    color = if (progress >= 1f) SignalGreen else NeuralAccent,
                    fontWeight = FontWeight.Bold
                )
            )
        }
        Box(
            Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Background)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(animProg)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (progress >= 1f) SignalGreen else NeuralAccent)
            )
        }
        Text(
            text = if (progress >= 1f) "✓ Достаточно данных — можно обучать"
            else "Продолжайте маршрут для сбора данных...",
            style = ReadoutStyle.copy(
                fontSize = 10.sp,
                color = if (progress >= 1f) SignalGreen else TextSecondary
            )
        )
    }
}

@Composable
private fun TrainingContent(state: NeuralFilterUiState.Training) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Эпоха + прогресс
        val animProg by animateFloatAsState(state.progress, tween(200), label = "ep")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceHigh)
                .border(1.dp, NeuralAccent.copy(0.4f), RoundedCornerShape(8.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                Text("ЭПОХА ОБУЧЕНИЯ", style = ReadoutStyle.copy(fontSize = 9.sp, color = TextSecondary, letterSpacing = 3.sp))
                Text("${state.epoch} / ${state.totalEpochs}",
                    style = ReadoutStyle.copy(color = NeuralAccent, fontWeight = FontWeight.Bold))
            }
            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Background)) {
                Box(
                    Modifier.fillMaxWidth(animProg).fillMaxHeight()
                        .clip(RoundedCornerShape(4.dp))
                        .background(Brush.horizontalGradient(listOf(NeuralAccent.copy(0.6f), NeuralAccent)))
                )
            }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                Text("MSE LOSS", style = ReadoutStyle.copy(fontSize = 9.sp, color = TextSecondary))
                Text(
                    "%.6f".format(state.currentLoss),
                    style = ReadoutLargeStyle.copy(fontSize = 20.sp, color = NeuralAccent)
                )
            }
        }

        // График потерь
        if (state.lossHistory.size >= 2) {
            LossChart(state.lossHistory)
        }

        NeuralInfoCard(
            title   = "Adam · lr=1e-3 · batch=32",
            content = "Нейросеть 24→32→16→4 обучается на ${state.totalEpochs} эпох. " +
                    "Веса сохранятся на диск после завершения."
        )
    }
}

@Composable
private fun LossChart(lossHistory: List<Double>) {
    val maxL = lossHistory.maxOrNull() ?: 1.0
    val minL = lossHistory.minOrNull() ?: 0.0
    val range = (maxL - minL).coerceAtLeast(1e-9)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(130.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceHigh)
            .border(1.dp, Divider, RoundedCornerShape(8.dp))
    ) {
        Canvas(Modifier.fillMaxSize().padding(12.dp)) {
            // Сетка
            val gridColor = Color(0xFF161C20)
            repeat(4) { i ->
                val y = size.height * i / 3f
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y))
            }
            // Кривая потерь
            val pts = lossHistory.mapIndexed { i, loss ->
                val x = i.toFloat() / (lossHistory.size - 1).coerceAtLeast(1) * size.width
                val y = (1.0 - (loss - minL) / range).toFloat() * size.height
                Offset(x, y.coerceIn(0f, size.height))
            }
            if (pts.size >= 2) {
                val path = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    pts.drop(1).forEach { lineTo(it.x, it.y) }
                }
                // Заливка под кривой
                val fillPath = Path().apply {
                    addPath(path)
                    lineTo(pts.last().x, size.height)
                    lineTo(pts.first().x, size.height)
                    close()
                }
                drawPath(fillPath, Brush.verticalGradient(
                    listOf(NeuralAccent.copy(alpha = 0.2f), Color.Transparent)
                ))
                drawPath(path, NeuralAccent, style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            pts.lastOrNull()?.let { drawCircle(NeuralAccent, 4f, it) }
        }
        // Подпись осей
        Text(
            text = "%.4f".format(maxL),
            style = ReadoutStyle.copy(fontSize = 8.sp, color = TextSecondary),
            modifier = Modifier.align(Alignment.TopStart).padding(start = 4.dp, top = 2.dp)
        )
        Text(
            text = "%.4f".format(minL),
            style = ReadoutStyle.copy(fontSize = 8.sp, color = TextSecondary),
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 4.dp, bottom = 2.dp)
        )
        Text(
            "LOSS",
            style = ReadoutStyle.copy(fontSize = 8.sp, color = NeuralAccent.copy(0.6f), letterSpacing = 2.sp),
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 6.dp, top = 4.dp)
        )
    }
}

@Composable
private fun ReadyContent() {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Статус готовности
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(SignalGreen.copy(alpha = 0.08f))
                .border(1.dp, SignalGreen.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("✓", fontSize = 32.sp, color = SignalGreen)
                Text("НЕЙРОСЕТЬ ОБУЧЕНА", style = ReadoutStyle.copy(
                    color = SignalGreen, fontWeight = FontWeight.Bold, letterSpacing = 3.sp
                ))
                Text("Архитектура: 24 → 32 → 16 → 4",
                    style = ReadoutStyle.copy(color = TextSecondary, fontSize = 11.sp))
            }
        }

        NeuralInfoCard(
            title   = "ГОТОВО К ЗАПУСКУ",
            content = "Нажмите «Запустить нейросеть» для начала сессии. " +
                    "K будет вычисляться нейросетью. Пока буфер инноваций не заполнится " +
                    "(первые ~10 шагов), автоматически используется классический fallback."
        )
    }
}

@Composable
private fun RunningContent(state: NeuralFilterUiState.Running) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Бейдж нейросетевого режима
        NeuralModeBadge(state.isUsingNeuralGain)

        // Трек
        NeuralTrackCanvas(state.trackPoints, state.rawPoints)

        // Показания
        SectionLabel("ВЕКТОР СОСТОЯНИЯ")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DataCell("Lat", "%.6f°".format(state.readout.filteredLat), SignalGreen, Modifier.weight(1f))
            DataCell("Lon", "%.6f°".format(state.readout.filteredLon), SignalGreen, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DataCell("Vx", "%.3f м/с".format(state.readout.vxMs), PrecisionCyan, Modifier.weight(1f))
            DataCell("Vy", "%.3f м/с".format(state.readout.vyMs), PrecisionCyan, Modifier.weight(1f))
        }

        SectionLabel("НЕЙРОСЕТЕВОЙ КОЭФФИЦИЕНТ K")
        NeuralGainBar("K_x", state.readout.kPosX)
        NeuralGainBar("K_y", state.readout.kPosY)

        SectionLabel("ШУМ ИЗМЕРЕНИЙ R")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DataCell("R[0,0]", "%.2f м²".format(state.readout.rXX), RawAmber, Modifier.weight(1f))
            DataCell("R[1,1]", "%.2f м²".format(state.readout.rYY), RawAmber, Modifier.weight(1f))
        }

        // Инновация
        NeuralInnovationCard(state.readout.innovationMagnitude)
    }
}

@Composable
private fun FinishedContent(state: NeuralFilterUiState.Finished) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        NeuralTrackCanvas(state.trackPoints, state.rawPoints)
        NeuralMetricsPanel(state.metrics)
        SectionLabel("ПОСЛЕДНИЙ READOUT")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DataCell("Lat", "%.6f°".format(state.readout.filteredLat), SignalGreen, Modifier.weight(1f))
            DataCell("Lon", "%.6f°".format(state.readout.filteredLon), SignalGreen, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ErrorContent(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(ErrorRed.copy(0.1f))
            .border(1.dp, ErrorRed.copy(0.4f), RoundedCornerShape(8.dp))
            .padding(16.dp)
    ) {
        Text(message, style = ReadoutStyle.copy(color = ErrorRed))
    }
}

// ── Sub-components ────────────────────────────────────────────────────────────

@Composable
private fun NeuralModeBadge(isNeural: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (isNeural) NeuralDim else SurfaceHigh)
            .border(1.dp, if (isNeural) NeuralAccent.copy(0.5f) else Divider, RoundedCornerShape(6.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (isNeural) "🧠 NEURAL K" else "⏳ ПРОГРЕВ...",
            style = ReadoutStyle.copy(
                color = if (isNeural) NeuralAccent else TextSecondary,
                fontWeight = FontWeight.Bold, letterSpacing = 2.sp
            )
        )
        Text(
            text = if (isNeural) "K ← MLP(инновации)" else "K ← Риккати (fallback)",
            style = ReadoutStyle.copy(fontSize = 10.sp, color = TextSecondary)
        )
    }
}

@Composable
private fun NeuralTrackCanvas(filteredPoints: List<TrackPoint>, rawPoints: List<TrackPoint>) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceHigh)
            .border(1.dp, NeuralAccent.copy(0.2f), RoundedCornerShape(8.dp))
    ) {
        if (filteredPoints.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("Ожидание GPS...", style = ReadoutStyle.copy(color = TextSecondary))
            }
        } else {
            Canvas(Modifier.fillMaxSize().padding(12.dp)) {
                val all = filteredPoints + rawPoints
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

                // Сетка
                val gc = Color(0xFF161C20)
                repeat(5) { i ->
                    val x = size.width * i / 4f
                    drawLine(gc, Offset(x, 0f), Offset(x, size.height))
                    drawLine(gc, Offset(0f, size.width * i / 4f), Offset(size.width, size.width * i / 4f))
                }

                // Сырой GPS
                norm(rawPoints).forEach { drawCircle(RawAmber.copy(0.4f), 2.5f, it) }

                // Нейросетевой трек — cyan вместо зелёного
                val fPts = norm(filteredPoints)
                if (fPts.size >= 2) {
                    val path = Path().apply { moveTo(fPts[0].x, fPts[0].y); fPts.drop(1).forEach { lineTo(it.x, it.y) } }
                    drawPath(path, NeuralAccent, style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                fPts.lastOrNull()?.let {
                    drawCircle(NeuralAccent, 7f, it)
                    drawCircle(Background,   3f, it)
                }
            }
        }

        Row(
            Modifier.align(Alignment.BottomStart).padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            LegendItem(NeuralAccent, "Neural K")
            LegendItem(RawAmber,    "GPS raw")
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(8.dp, 2.dp).background(color))
        Text(label, style = ReadoutStyle.copy(fontSize = 10.sp, color = TextSecondary))
    }
}

@Composable
private fun NeuralGainBar(label: String, value: Double) {
    val animated by animateFloatAsState(value.toFloat().coerceIn(0f, 1f), tween(400), label = "g")
    Column {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text(label, style = ReadoutStyle.copy(color = NeuralAccent, fontWeight = FontWeight.Bold))
            Text("%.4f".format(value), style = ReadoutStyle)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(SurfaceHigh)) {
            Box(
                Modifier.fillMaxWidth(animated).fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(Brush.horizontalGradient(listOf(NeuralAccent.copy(0.5f), NeuralAccent)))
            )
        }
    }
}

@Composable
private fun NeuralInnovationCard(magnitudeM: Double) {
    val color = when {
        magnitudeM < 2.0 -> SignalGreen
        magnitudeM < 5.0 -> RawAmber
        else             -> ErrorRed
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceHigh)
            .border(1.dp, color.copy(0.3f), RoundedCornerShape(8.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("ИННОВАЦИЯ ||y||", style = ReadoutStyle.copy(fontSize = 9.sp, color = TextSecondary, letterSpacing = 3.sp))
            Spacer(Modifier.height(4.dp))
            Text("%.3f м".format(magnitudeM), style = ReadoutLargeStyle.copy(color = color))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(ErrorRed to (magnitudeM >= 5.0), RawAmber to (magnitudeM in 2.0..5.0), SignalGreen to (magnitudeM < 2.0))
                .forEach { (c, active) ->
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (active) c else c.copy(0.15f)))
                }
        }
    }
}

@Composable
private fun NeuralMetricsPanel(metrics: MetricsUiModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceHigh)
            .border(1.dp, NeuralAccent.copy(0.3f), RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("МЕТРИКИ НЕЙРОСЕТЕВОЙ СЕССИИ · ${metrics.sampleCount} точек",
            style = ReadoutStyle.copy(fontSize = 9.sp, color = TextSecondary, letterSpacing = 3.sp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCell("RMSE",   metrics.rmse,      NeuralAccent, Modifier.weight(1f))
            MetricCell("MAE",    metrics.mae,        PrecisionCyan, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCell("MAX ΔP", metrics.maxError,  ErrorRed,     Modifier.weight(1f))
            MetricCell("JITTER", metrics.stability, RawAmber,     Modifier.weight(1f))
        }
    }
}

@Composable
private fun MetricCell(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(Background).padding(10.dp)
    ) {
        Text(label, style = ReadoutStyle.copy(fontSize = 9.sp, color = TextSecondary, letterSpacing = 2.sp))
        Spacer(Modifier.height(4.dp))
        Text(value, style = ReadoutLargeStyle.copy(fontSize = 18.sp, color = color))
    }
}

@Composable
private fun NeuralInfoCard(title: String, content: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(NeuralDim)
            .border(1.dp, NeuralAccent.copy(0.2f), RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, style = ReadoutStyle.copy(color = NeuralAccent, fontWeight = FontWeight.Bold,
            fontSize = 10.sp, letterSpacing = 2.sp))
        Text(content, style = ReadoutStyle.copy(color = TextSecondary, fontSize = 11.sp))
    }
}

// ── Bottom Bar ────────────────────────────────────────────────────────────────

@Composable
private fun NeuralBottomBar(
    uiState:              NeuralFilterUiState,
    onStartCollection:    () -> Unit,
    onStopAndTrain:       () -> Unit,
    onStartNeuralSession: () -> Unit,
    onStopNeuralSession:  () -> Unit,
    onReset:              () -> Unit,
    onRetrain:            () -> Unit,
    modifier:             Modifier = Modifier
) {
    Surface(modifier = modifier.fillMaxWidth(), color = Surface, tonalElevation = 8.dp) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (uiState) {
                is NeuralFilterUiState.NotTrained -> {
                    NeuralActionButton("НАЧАТЬ СБОР ДАННЫХ", NeuralAccent, Modifier.fillMaxWidth(), onStartCollection)
                }
                is NeuralFilterUiState.CollectingData -> {
                    NeuralActionButton(
                        label   = if (uiState.isReadyToTrain) "ОБУЧИТЬ СЕТЬ ✓" else "ОБУЧИТЬ (${uiState.sampleCount}/${NeuralFilterUiState.MIN_SAMPLES})",
                        color   = if (uiState.isReadyToTrain) SignalGreen else TextSecondary,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onStopAndTrain,
                        enabled = uiState.isReadyToTrain
                    )
                }
                is NeuralFilterUiState.Training -> {
                    Box(Modifier.fillMaxWidth().height(52.dp)
                        .clip(RoundedCornerShape(4.dp)).background(SurfaceHigh),
                        Alignment.Center
                    ) {
                        Text(
                            "Эпоха ${uiState.epoch} / ${uiState.totalEpochs} · обучение...",
                            style = ReadoutStyle.copy(color = NeuralAccent, letterSpacing = 2.sp)
                        )
                    }
                }
                is NeuralFilterUiState.ReadyToRun -> {
                    NeuralActionButton("ЗАПУСТИТЬ НЕЙРОСЕТЬ", NeuralAccent, Modifier.fillMaxWidth(), onStartNeuralSession)
                }
                is NeuralFilterUiState.Running -> {
                    NeuralActionButton("ОСТАНОВИТЬ", ErrorRed, Modifier.fillMaxWidth(), onStopNeuralSession)
                }
                is NeuralFilterUiState.Finished -> {
                    NeuralActionButton("НОВАЯ СЕССИЯ", NeuralAccent, Modifier.weight(1f), onReset)
                    NeuralActionButton("ПЕРЕОБУЧИТЬ", TextSecondary, Modifier.weight(1f), onRetrain)
                }
                is NeuralFilterUiState.Error -> {
                    NeuralActionButton("ПОВТОРИТЬ", RawAmber, Modifier.fillMaxWidth(), onReset)
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun NeuralActionButton(
    label:    String,
    color:    Color,
    modifier: Modifier,
    onClick:  () -> Unit,
    enabled:  Boolean = true
) {
    Button(
        onClick  = onClick,
        enabled  = enabled,
        modifier = modifier.height(52.dp),
        shape    = RoundedCornerShape(4.dp),
        colors   = ButtonDefaults.buttonColors(
            containerColor         = color.copy(alpha = 0.12f),
            contentColor           = color,
            disabledContainerColor = SurfaceHigh,
            disabledContentColor   = TextSecondary
        ),
        border = BorderStroke(1.dp, if (enabled) color.copy(0.4f) else Divider)
    ) {
        Text(label, style = ReadoutStyle.copy(
            color = if (enabled) color else TextSecondary,
            fontWeight = FontWeight.Bold, letterSpacing = 3.sp, fontSize = 12.sp
        ))
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = ReadoutStyle.copy(fontSize = 9.sp, color = TextSecondary, letterSpacing = 3.sp))
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
        Spacer(Modifier.height(4.dp))
        Text(value, style = ReadoutStyle.copy(color = valueColor, fontWeight = FontWeight.SemiBold, fontSize = 13.sp))
    }
}

private fun formatElapsedNeural(seconds: Int) = "%02d:%02d".format(seconds / 60, seconds % 60)