package com.katka.adaptivekalmanfilter.ui.comparison_screen

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.katka.adaptivekalmanfilter.model.ComparisonUiState
import com.katka.adaptivekalmanfilter.model.KalmanReadout
import com.katka.adaptivekalmanfilter.model.TrackMetrics
import com.katka.adaptivekalmanfilter.model.TrackPoint
import com.katka.adaptivekalmanfilter.recording.CsvExporter
import com.katka.adaptivekalmanfilter.recording.SessionRecorder
import com.katka.adaptivekalmanfilter.sensor_data_source.AndroidSensorDataSource
import com.katka.engine.KalmanFilter
import com.katka.engine.KalmanFilterCoordinator
import com.katka.engine.coefficient_startegy.ClassicalCoefficientStrategy
import com.katka.engine.model.FilterResult
import com.katka.engine.neural.NetworkPersistenceManager
import com.katka.engine.smoothing.FeatureNormalizer
import com.katka.engine.smoothing.NeuralTrajectorySmoother
import com.katka.engine.smoothing.SmoothedSample
import com.katka.engine.smoothing.SmootherInput
import com.katka.model.Observation
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.hypot
import kotlin.math.sqrt

private const val MAX_TRACK_POINTS = 500

/**
 * Comparison session.  In the reworked architecture there is **one** classical
 * Kalman filter; the neural stage is a post-filter [NeuralTrajectorySmoother].
 * So we compare three views of the same central point: raw GPS, the Kalman
 * estimate x_KF, and the smoothed output x_out — each [SmoothedSample] is
 * self-contained, so no two-stream alignment is needed.
 */
@HiltViewModel
class ComparisonViewModel @Inject constructor(
    private val sensorDataSource: AndroidSensorDataSource,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val sessionRecorder = SessionRecorder()

    private val _comparisonUiState = MutableStateFlow<ComparisonUiState>(
        if (NetworkPersistenceManager.exists(context)) ComparisonUiState.Idle
        else ComparisonUiState.NeuralNotTrained
    )
    val comparisonUiState: StateFlow<ComparisonUiState> = _comparisonUiState.asStateFlow()

    // Tracks for the canvas
    private val kfTrack = mutableListOf<TrackPoint>()
    private val smoothTrack = mutableListOf<TrackPoint>()
    private val rawTrack = mutableListOf<TrackPoint>()

    // Accumulators (local metres) for metrics
    private val kfEst = mutableListOf<Pair<Double, Double>>()
    private val smoothEst = mutableListOf<Pair<Double, Double>>()
    private val rawRef = mutableListOf<Pair<Double, Double>>()

    private var coordinator: KalmanFilterCoordinator? = null
    private var smoother: NeuralTrajectorySmoother? = null

    @Volatile private var lastObs: Observation? = null
    private var obsJob: Job? = null
    private var resultsJob: Job? = null
    private var sessionStartMs = 0L

    fun startComparisonSession() {
        val loaded = NetworkPersistenceManager.loadSmoother(context) ?: run {
            _comparisonUiState.value = ComparisonUiState.NeuralNotTrained
            return
        }

        sessionRecorder.reset()
        kfTrack.clear(); smoothTrack.clear(); rawTrack.clear()
        kfEst.clear(); smoothEst.clear(); rawRef.clear()
        lastObs = null
        sessionStartMs = System.currentTimeMillis()

        smoother = NeuralTrajectorySmoother(
            network = loaded.network,
            normalizer = FeatureNormalizer(loaded.featureMean, loaded.featureStd)
        )

        val sessionFilter = KalmanFilter(processNoiseStd = 0.1)
        val sessionCoordinator = KalmanFilterCoordinator(
            sensorSource = sensorDataSource,
            filter = sessionFilter,
            strategy = ClassicalCoefficientStrategy(
                adaptiveR = true, adaptiveWindow = 40, minAccuracyM = 1f, maxAccuracyM = 50f
            ),
            scope = viewModelScope
        )
        coordinator = sessionCoordinator
        sessionCoordinator.start()

        obsJob?.cancel()
        obsJob = sensorDataSource.observations
            .onEach { obs -> lastObs = obs }
            .launchIn(viewModelScope)

        resultsJob?.cancel()
        resultsJob = sessionCoordinator.results
            .onEach { result -> handleResult(result, sessionCoordinator, sessionFilter) }
            .catch { e -> _comparisonUiState.value = ComparisonUiState.Error(e.message ?: "Error") }
            .launchIn(viewModelScope)

        _comparisonUiState.value = ComparisonUiState.Running(stepCount = 0, elapsedSeconds = 0)
    }

    private fun handleResult(
        result: FilterResult,
        coordinator: KalmanFilterCoordinator,
        filter: KalmanFilter
    ) {
        if (result.dt == 0.0) return
        val rawXY = coordinator.getRawGpsHistory().lastOrNull() ?: return
        val sample = smoother?.push(buildInput(result, lastObs, rawXY)) ?: return

        sessionRecorder.record(sample, filter)

        addPoint(kfTrack, TrackPoint(sample.kfX.toFloat(), sample.kfY.toFloat()))
        addPoint(smoothTrack, TrackPoint(sample.outX.toFloat(), sample.outY.toFloat()))
        addPoint(rawTrack, TrackPoint(sample.rawX.toFloat(), sample.rawY.toFloat()))
        kfEst.add(sample.kfX to sample.kfY)
        smoothEst.add(sample.outX to sample.outY)
        rawRef.add(sample.rawX to sample.rawY)

        val (kfLat, kfLon) = filter.localToGeo(sample.kfX, sample.kfY)
        val (outLat, outLon) = filter.localToGeo(sample.outX, sample.outY)

        _comparisonUiState.value = ComparisonUiState.Running(
            stepCount = sessionRecorder.rowCount,
            elapsedSeconds = elapsedSec(),
            classReadout = kfReadout(sample, kfLat, kfLon),
            neuralReadout = smoothReadout(sample, outLat, outLon),
            trackPoints = kfTrack.toList(),
            neuralPoints = smoothTrack.toList(),
            rawPoints = rawTrack.toList(),
            isNeuralActive = true
        )
    }

    fun stopComparisonSession() {
        obsJob?.cancel(); obsJob = null
        resultsJob?.cancel(); resultsJob = null
        coordinator?.stop()

        val current = _comparisonUiState.value as? ComparisonUiState.Running ?: return

        val classMetrics = TrackMetrics.compute(kfEst, rawRef)
        val neuralMetrics = TrackMetrics.compute(smoothEst, rawRef)

        viewModelScope.launch(Dispatchers.IO) {
            val uri = try {
                CsvExporter.export(context, sessionRecorder.rows)
            } catch (e: Exception) {
                null
            }
            _comparisonUiState.value = ComparisonUiState.Finished(
                stepCount = sessionRecorder.rowCount,
                elapsedSeconds = current.elapsedSeconds,
                classMetrics = classMetrics,
                neuralMetrics = neuralMetrics,
                exportedUri = uri,
                trackPoints = kfTrack.toList(),
                neuralPoints = smoothTrack.toList(),
                rawPoints = rawTrack.toList()
            )
        }
    }

    fun resetComparisonToIdle() {
        obsJob?.cancel(); obsJob = null
        resultsJob?.cancel(); resultsJob = null
        coordinator?.stop()
        kfTrack.clear(); smoothTrack.clear(); rawTrack.clear()
        kfEst.clear(); smoothEst.clear(); rawRef.clear()
        sessionRecorder.reset()
        _comparisonUiState.value = if (NetworkPersistenceManager.exists(context))
            ComparisonUiState.Idle else ComparisonUiState.NeuralNotTrained
    }

    fun refreshNetworkStatus() {
        _comparisonUiState.value = if (NetworkPersistenceManager.exists(context)) {
            when (_comparisonUiState.value) {
                is ComparisonUiState.Idle,
                is ComparisonUiState.NeuralNotTrained,
                is ComparisonUiState.Error -> ComparisonUiState.Idle
                else -> _comparisonUiState.value
            }
        } else {
            ComparisonUiState.NeuralNotTrained
        }
    }

    fun shareComparisonCsv(uri: Uri) {
        CsvExporter.share(context, uri)
    }

    // ── Builders / utils ──────────────────────────────────────────────────────
    private fun buildInput(
        result: FilterResult,
        obs: Observation?,
        rawXY: Pair<Double, Double>
    ): SmootherInput {
        val innov = result.innovation
        val innovMag = if (innov.size >= 2) sqrt(innov[0] * innov[0] + innov[1] * innov[1]) else 0.0
        val speed = when {
            obs != null && obs.hasSpeed -> obs.speed.toDouble()
            else -> hypot(result.state.vx, result.state.vy)
        }
        return SmootherInput(
            timestamp = result.timestamp,
            kfX = result.state.x, kfY = result.state.y,
            rawX = rawXY.first, rawY = rawXY.second,
            vx = result.state.vx, vy = result.state.vy,
            speed = speed,
            accuracy = obs?.accuracy?.toDouble() ?: 10.0,
            innovationMag = innovMag,
            sigmaPos = result.state.positionUncertaintyMeters
        )
    }

    /** Classical column = pure Kalman point (α = 0). */
    private fun kfReadout(sample: SmoothedSample, lat: Double, lon: Double) = KalmanReadout(
        filteredLat = lat, filteredLon = lon,
        vxMs = sample.vx, vyMs = sample.vy,
        alpha = 0.0,
        posUncertaintyM = sample.sigmaPos,
        innovationMagnitude = sample.innovationMag,
        gpsAccuracyM = sample.accuracy.toFloat()
    )

    /** Neural column = smoothed point with the predicted α. */
    private fun smoothReadout(sample: SmoothedSample, lat: Double, lon: Double) = KalmanReadout(
        filteredLat = lat, filteredLon = lon,
        vxMs = sample.vx, vyMs = sample.vy,
        alpha = sample.alpha,
        posUncertaintyM = sample.sigmaPos,
        innovationMagnitude = sample.innovationMag,
        gpsAccuracyM = sample.accuracy.toFloat()
    )

    private fun addPoint(list: MutableList<TrackPoint>, point: TrackPoint) {
        if (list.size >= MAX_TRACK_POINTS) list.removeAt(0)
        list.add(point)
    }

    private fun elapsedSec() = ((System.currentTimeMillis() - sessionStartMs) / 1000).toInt()

    override fun onCleared() {
        super.onCleared()
        obsJob?.cancel()
        resultsJob?.cancel()
        coordinator?.stop()
    }
}
