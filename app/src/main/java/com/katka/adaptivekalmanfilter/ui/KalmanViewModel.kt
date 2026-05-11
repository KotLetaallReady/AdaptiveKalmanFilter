package com.katka.adaptivekalmanfilter.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.katka.adaptivekalmanfilter.model.FilterUiState
import com.katka.adaptivekalmanfilter.model.KalmanReadout
import com.katka.adaptivekalmanfilter.model.MetricsUiModel
import com.katka.adaptivekalmanfilter.model.TrackPoint
import com.katka.adaptivekalmanfilter.sensor_data_source.AndroidSensorDataSource
import com.katka.engine.CoefficientStartegy.ClassicalCoefficientStrategy
import com.katka.engine.KalmanFilter
import com.katka.engine.model.FilterResult
import core.engine.KalmanFilterCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import kotlin.math.sqrt

private const val TAG = "KalmanViewModel"
private const val MAX_TRACK_POINTS = 500   // ограничиваем память

@HiltViewModel
class KalmanViewModel @Inject constructor(
    private val sensorDataSource: AndroidSensorDataSource
) : ViewModel() {

    // ── Kalman engine ────────────────────────────────────────────────────────

    private val filter   = KalmanFilter(processNoiseStd = 0.5)
    private val strategy = ClassicalCoefficientStrategy(
        adaptiveR = true,
        adaptiveWindow = 20,
        minAccuracyM = 1f,
        maxAccuracyM = 50f
    )

    // Coordinator создаётся один раз в scope ViewModel
    private val coordinator = KalmanFilterCoordinator(
        sensorSource = sensorDataSource,
        filter = filter,
        strategy = strategy,
        scope = viewModelScope
    )

    // ── UI state ─────────────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow<FilterUiState>(FilterUiState.Idle)
    val uiState: StateFlow<FilterUiState> = _uiState.asStateFlow()

    // ── Internal accumulators ────────────────────────────────────────────────

    private val trackPoints = mutableListOf<TrackPoint>()
    private val rawPoints   = mutableListOf<TrackPoint>()
    private var sessionStartMs = 0L

    // ── Session control ───────────────────────────────────────────────────────

    fun startSession() {
        trackPoints.clear()
        rawPoints.clear()
        sessionStartMs = System.currentTimeMillis()

        coordinator.start()

        // Подписываемся на результаты фильтра
        coordinator.results
            .onEach { result -> handleResult(result) }
            .catch  { e -> _uiState.value = FilterUiState.Error(e.message ?: "Unknown error") }
            .launchIn(viewModelScope)

        _uiState.value = FilterUiState.Running(
            readout        = KalmanReadout(),
            trackPoints    = emptyList(),
            rawPoints      = emptyList(),
            elapsedSeconds = 0
        )
    }

    fun stopSession() {
        coordinator.stop()

        val metrics = coordinator.computeMetrics()
        val metricsUi = MetricsUiModel(
            rmse = "%.2f м".format(metrics.rmse),
            mae = "%.2f м".format(metrics.mae),
            maxError = "%.2f м".format(metrics.maxError),
            stability = "%.3f м".format(metrics.stability),
            lag = "%.1f шаг".format(metrics.lag),
            sampleCount = metrics.sampleCount
        )

        val current = _uiState.value
        val running = current as? FilterUiState.Running ?: return

        _uiState.value = FilterUiState.Finished(
            readout        = running.readout,
            trackPoints    = trackPoints.toList(),
            rawPoints      = rawPoints.toList(),
            metrics        = metricsUi,
            elapsedSeconds = running.elapsedSeconds
        )
    }

    fun resetToIdle() {
        coordinator.stop()
        trackPoints.clear()
        rawPoints.clear()
        _uiState.value = FilterUiState.Idle
    }

    fun onPermissionGranted() {
        if (_uiState.value is FilterUiState.NeedsPermission) {
            _uiState.value = FilterUiState.Idle
        }
    }

    fun onPermissionDenied() {
        _uiState.value = FilterUiState.NeedsPermission
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun handleResult(result: FilterResult) {
        // Пропускаем инициализационный шаг (dt == 0)
        if (result.dt == 0.0) return

        // Накапливаем трек (downsampling если очень много точек)
        val filteredPoint = TrackPoint(result.state.x.toFloat(), result.state.y.toFloat())
        val rawPoint      = coordinator.getRawGpsHistory()
            .lastOrNull()
            ?.let { TrackPoint(it.first.toFloat(), it.second.toFloat()) }
            ?: filteredPoint

        if (trackPoints.size >= MAX_TRACK_POINTS) {
            trackPoints.removeAt(0)
            rawPoints.removeAt(0)
        }
        trackPoints.add(filteredPoint)
        rawPoints.add(rawPoint)

        // K — берём позиционные элементы [0][0] и [1][1]
        val K = result.kalmanGain
        val kPosX = if (K.isNotEmpty() && K[0].isNotEmpty()) K[0][0] else 0.0
        val kPosY = if (K.size > 1 && K[1].isNotEmpty()) K[1][1] else 0.0

        // R — диагональные элементы
        val R = result.measurementNoiseR
        val rXX = if (R.isNotEmpty() && R[0].isNotEmpty()) R[0][0] else 0.0
        val rYY = if (R.size > 1 && R[1].isNotEmpty()) R[1][1] else 0.0

        // Инновация ||y||
        val innov = result.innovation
        val innovMag = if (innov.size >= 2)
            sqrt(innov[0] * innov[0] + innov[1] * innov[1]) else 0.0

        // Обратная конвертация filtered X,Y → lat/lon для отображения
        val (fLat, fLon) = filter.localToGeo(result.state.x, result.state.y)
        val (rLat, rLon) = rawPoints.lastOrNull()
            ?.let { filter.localToGeo(it.x.toDouble(), it.y.toDouble()) }
            ?: (fLat to fLon)

        val elapsed = ((System.currentTimeMillis() - sessionStartMs) / 1000).toInt()

        val readout = KalmanReadout(
            filteredLat         = fLat,
            filteredLon         = fLon,
            rawLat              = rLat,
            rawLon              = rLon,
            vxMs                = result.state.vx,
            vyMs                = result.state.vy,
            rXX                 = rXX,
            rYY                 = rYY,
            kPosX               = kPosX,
            kPosY               = kPosY,
            posUncertaintyM     = result.state.positionUncertaintyMeters,
            innovationMagnitude = innovMag,
            dtMs                = result.dt * 1000.0,
            gpsAccuracyM        = 0f  // raw accuracy хранится в Observation, не в FilterResult
        )

        _uiState.value = FilterUiState.Running(
            readout        = readout,
            trackPoints    = trackPoints.toList(),
            rawPoints      = rawPoints.toList(),
            elapsedSeconds = elapsed
        )
    }

    override fun onCleared() {
        super.onCleared()
        coordinator.stop()
    }
}