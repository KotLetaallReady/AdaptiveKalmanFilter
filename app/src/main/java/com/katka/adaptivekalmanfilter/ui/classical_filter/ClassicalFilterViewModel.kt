package com.katka.adaptivekalmanfilter.ui.classical_filter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.katka.adaptivekalmanfilter.model.FilterUiState
import com.katka.adaptivekalmanfilter.model.KalmanReadout
import com.katka.adaptivekalmanfilter.model.MetricsUiModel
import com.katka.adaptivekalmanfilter.model.TrackPoint
import com.katka.android.AndroidSensorDataSource
import com.katka.engine.KalmanFilter
import com.katka.engine.KalmanFilterCoordinator
import com.katka.engine.Logger
import com.katka.engine.coefficient_startegy.ClassicalCoefficientStrategy
import com.katka.engine.model.FilterResult
import com.katka.model.AccuracyMetrics
import dagger.hilt.android.lifecycle.HiltViewModel
import jakarta.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.math.sqrt

private const val MAX_TRACK_POINTS = 500

@HiltViewModel
class ClassicalFilterViewModel @Inject constructor(
    private val sensorDataSource: AndroidSensorDataSource,
    private val logger: Logger
) : ViewModel() {

    private val classicalFilter = KalmanFilter(processNoiseStd = 0.1)
    private val classicalStrategy = ClassicalCoefficientStrategy(
        adaptiveR = true, adaptiveWindow = 40, minAccuracyM = 1f, maxAccuracyM = 50f
    )
    private val classicalCoordinator = KalmanFilterCoordinator(
        sensorSource = sensorDataSource,
        filter = classicalFilter,
        strategy = classicalStrategy,
        scope = viewModelScope,
        logger = logger
    )

    private val classicalTrack = mutableListOf<TrackPoint>()
    private val classicalRaw = mutableListOf<TrackPoint>()
    private var sessionStartMs = 0L

    private val _uiState = MutableStateFlow<FilterUiState>(FilterUiState.Idle)
    val uiState: StateFlow<FilterUiState> = _uiState.asStateFlow()

    private var classicalResultsJob: Job? = null

    fun onPermissionGranted() {
        if (_uiState.value is FilterUiState.NeedsPermission)
            _uiState.value = FilterUiState.Idle
    }

    fun onPermissionDenied() {
        _uiState.value = FilterUiState.NeedsPermission
    }

    fun startSession() {
        classicalTrack.clear(); classicalRaw.clear()
        sessionStartMs = System.currentTimeMillis()
        classicalCoordinator.start()
        classicalResultsJob?.cancel()

        classicalResultsJob = classicalCoordinator.results
            .onEach { handleClassicalResult(it) }
            .catch { e -> _uiState.value = FilterUiState.Error(e.message ?: "Error") }
            .launchIn(viewModelScope)

        _uiState.value = FilterUiState.Running(KalmanReadout(), emptyList(), emptyList(), 0)
    }

    fun stopSession() {
        classicalResultsJob?.cancel()
        classicalCoordinator.stop()
        val current = _uiState.value as? FilterUiState.Running ?: return
        _uiState.value = FilterUiState.Finished(
            readout = current.readout,
            trackPoints = classicalTrack.toList(),
            rawPoints = classicalRaw.toList(),
            metrics = classicalCoordinator.computeMetrics().toUiModel(),
            elapsedSeconds = current.elapsedSeconds
        )
    }

    fun resetToIdle() {
        classicalResultsJob?.cancel()
        classicalCoordinator.stop()
        classicalTrack.clear(); classicalRaw.clear()
        _uiState.value = FilterUiState.Idle
    }

    private fun handleClassicalResult(result: FilterResult) {
        if (result.dt == 0.0) return
        val filtered = TrackPoint(result.state.x.toFloat(), result.state.y.toFloat())
        val raw = classicalCoordinator.getRawGpsHistory().lastOrNull()
            ?.let { TrackPoint(it.first.toFloat(), it.second.toFloat()) } ?: filtered

        if (classicalTrack.size >= MAX_TRACK_POINTS) {
            classicalTrack.removeAt(0); classicalRaw.removeAt(0)
        }
        classicalTrack.add(filtered); classicalRaw.add(raw)

        val (fLat, fLon) = classicalFilter.localToGeo(result.state.x, result.state.y)

        _uiState.value = FilterUiState.Running(
            readout = buildReadout(result, classicalFilter, fLat, fLon, raw),
            trackPoints = classicalTrack.toList(),
            rawPoints = classicalRaw.toList(),
            elapsedSeconds = elapsedSec()
        )
    }

    private fun buildReadout(
        result: FilterResult,
        filter: KalmanFilter,
        fLat: Double,
        fLon: Double,
        raw: TrackPoint
    ): KalmanReadout {
        val K = result.kalmanGain
        val R = result.measurementNoiseR
        val innov = result.innovation
        val innovMag = if (innov.size >= 2) sqrt(innov[0] * innov[0] + innov[1] * innov[1]) else 0.0
        val (rLat, rLon) = filter.localToGeo(raw.x.toDouble(), raw.y.toDouble())
        return KalmanReadout(
            filteredLat = fLat, filteredLon = fLon,
            rawLat = rLat, rawLon = rLon,
            vxMs = result.state.vx, vyMs = result.state.vy,
            rXX = R.getOrNull(0)?.getOrNull(0) ?: 0.0,
            rYY = R.getOrNull(1)?.getOrNull(1) ?: 0.0,
            kPosX = K.getOrNull(0)?.getOrNull(0) ?: 0.0,
            kPosY = K.getOrNull(1)?.getOrNull(1) ?: 0.0,
            posUncertaintyM = result.state.positionUncertaintyMeters,
            innovationMagnitude = innovMag,
            dtMs = result.dt * 1000.0,
            gpsAccuracyM = 0f
        )
    }

    private fun elapsedSec() = ((System.currentTimeMillis() - sessionStartMs) / 1000).toInt()

    private fun AccuracyMetrics.toUiModel() = MetricsUiModel(
        rmse = "%.2f м".format(rmse),
        mae = "%.2f м".format(mae),
        maxError = "%.2f м".format(maxError),
        stability = "%.3f м".format(stability),
        lag = "%.1f шаг".format(lag),
        sampleCount = sampleCount
    )

    override fun onCleared() {
        super.onCleared()
        classicalResultsJob?.cancel()
        classicalCoordinator.stop()
    }
}
