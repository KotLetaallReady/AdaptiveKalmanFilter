package com.katka.adaptivekalmanfilter.ui.comparison_screen

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.katka.adaptivekalmanfilter.model.ComparisonUiState
import com.katka.adaptivekalmanfilter.model.KalmanReadout
import com.katka.adaptivekalmanfilter.model.MetricsUiModel
import com.katka.adaptivekalmanfilter.model.TrackPoint
import com.katka.adaptivekalmanfilter.recording.CsvExporter
import com.katka.adaptivekalmanfilter.recording.SessionRecorder
import com.katka.adaptivekalmanfilter.sensor_data_source.AndroidSensorDataSource
import com.katka.engine.KalmanFilter
import com.katka.engine.coefficient_startegy.ClassicalCoefficientStrategy
import com.katka.engine.coefficient_startegy.NeuralCoefficientStrategy
import com.katka.engine.model.FilterResult
import com.katka.engine.neural.NetworkPersistenceManager
import com.katka.model.AccuracyMetrics
import com.katka.model.Observation
import core.engine.KalmanFilterCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.sqrt

private const val MAX_TRACK_POINTS = 500

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

    private val compClassTrack = mutableListOf<TrackPoint>()
    private val compNeuralTrack = mutableListOf<TrackPoint>()
    private val compRawTrack = mutableListOf<TrackPoint>()

    private var compClassJob: Job? = null
    private var compNeuralJob: Job? = null
    private var compObsJob: Job? = null

    @Volatile
    private var lastClassResult: FilterResult? = null
    @Volatile
    private var lastClassRaw: Pair<Double, Double>? = null
    @Volatile
    private var lastCompObs: Observation? = null

    private var sessionStartMs = 0L

    // Координаторы
    private val compClassCoordinator = KalmanFilterCoordinator(
        sensorSource = sensorDataSource,
        filter = KalmanFilter(processNoiseStd = 0.1),
        strategy = ClassicalCoefficientStrategy(
            adaptiveR = true, adaptiveWindow = 40, minAccuracyM = 1f, maxAccuracyM = 50f
        ),
        scope = viewModelScope
    )

    private var compNeuralCoordinator: KalmanFilterCoordinator? = null

    fun startComparisonSession() {
        val network = NetworkPersistenceManager.load(context)
        if (network == null) {
            _comparisonUiState.value = ComparisonUiState.NeuralNotTrained
            return
        }

        sessionRecorder.reset()
        compClassTrack.clear()
        compNeuralTrack.clear()
        compRawTrack.clear()
        lastClassResult = null
        lastClassRaw = null
        lastCompObs = null
        sessionStartMs = System.currentTimeMillis()

        val nStrategy = NeuralCoefficientStrategy(network)
        val nFilter = KalmanFilter(processNoiseStd = 0.1)
        val nCoordinator = KalmanFilterCoordinator(
            sensorSource = sensorDataSource,
            filter = nFilter,
            strategy = nStrategy,
            scope = viewModelScope
        )
        compNeuralCoordinator = nCoordinator

        compObsJob?.cancel()
        compObsJob = sensorDataSource.observations
            .onEach { obs -> lastCompObs = obs }
            .launchIn(viewModelScope)

        compClassCoordinator.start()
        nCoordinator.start()

        compClassJob?.cancel()
        compClassJob = compClassCoordinator.results
            .onEach { result ->
                if (result.dt == 0.0) return@onEach
                lastClassResult = result
                lastClassRaw = compClassCoordinator.getRawGpsHistory().lastOrNull()
            }
            .catch { e -> _comparisonUiState.value = ComparisonUiState.Error(e.message ?: "Error") }
            .launchIn(viewModelScope)

        compNeuralJob?.cancel()
        compNeuralJob = nCoordinator.results
            .onEach { neuralResult ->
                if (neuralResult.dt == 0.0) return@onEach
                nStrategy.updateInnovation(neuralResult.innovation, neuralResult.dt * 1000.0)

                val classResult = lastClassResult ?: return@onEach
                val rawPair = lastClassRaw ?: return@onEach
                val obs = lastCompObs ?: return@onEach

                val (rawX, rawY) = rawPair
                val (rawLat, rawLon) = compClassCoordinator.filter.localToGeo(rawX, rawY)

                sessionRecorder.record(
                    classResult = classResult,
                    neuralResult = neuralResult,
                    classFilter = compClassCoordinator.filter,
                    neuralFilter = nFilter,
                    neuralStrategy = nStrategy,
                    rawLat = rawLat,
                    rawLon = rawLon,
                    gpsAccuracy = obs.accuracy,
                    gpsSpeed = obs.speed
                )

                val cFiltered = TrackPoint(classResult.state.x.toFloat(), classResult.state.y.toFloat())
                val nFiltered = TrackPoint(neuralResult.state.x.toFloat(), neuralResult.state.y.toFloat())
                val raw = TrackPoint(rawX.toFloat(), rawY.toFloat())

                if (compClassTrack.size >= MAX_TRACK_POINTS) compClassTrack.removeAt(0)
                if (compNeuralTrack.size >= MAX_TRACK_POINTS) compNeuralTrack.removeAt(0)
                if (compRawTrack.size >= MAX_TRACK_POINTS) compRawTrack.removeAt(0)

                compClassTrack.add(cFiltered)
                compNeuralTrack.add(nFiltered)
                compRawTrack.add(raw)

                val (cLat, cLon) = compClassCoordinator.filter.localToGeo(classResult.state.x, classResult.state.y)
                val (nLat, nLon) = nFilter.localToGeo(neuralResult.state.x, neuralResult.state.y)

                val classReadout = buildReadout(classResult, compClassCoordinator.filter, cLat, cLon, raw)
                val neuralReadout = buildReadout(neuralResult, nFilter, nLat, nLon, raw)

                _comparisonUiState.value = ComparisonUiState.Running(
                    stepCount = sessionRecorder.rowCount,
                    elapsedSeconds = elapsedSec(),
                    classReadout = classReadout,
                    neuralReadout = neuralReadout,
                    trackPoints = compClassTrack.toList(),
                    neuralPoints = compNeuralTrack.toList(),
                    rawPoints = compRawTrack.toList(),
                    isNeuralActive = nStrategy.isNetworkReady
                )
            }
            .catch { e -> _comparisonUiState.value = ComparisonUiState.Error(e.message ?: "Error") }
            .launchIn(viewModelScope)

        _comparisonUiState.value = ComparisonUiState.Running(stepCount = 0, elapsedSeconds = 0)
    }

    fun stopComparisonSession() {
        compObsJob?.cancel(); compObsJob = null
        compClassJob?.cancel(); compClassJob = null
        compNeuralJob?.cancel(); compNeuralJob = null
        compClassCoordinator.stop()
        compNeuralCoordinator?.stop()

        val current = _comparisonUiState.value as? ComparisonUiState.Running ?: return

        val classMetrics = compClassCoordinator.computeMetrics().toUiModel()
        val neuralMetrics = compNeuralCoordinator?.computeMetrics()?.toUiModel() ?: MetricsUiModel.EMPTY

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
                trackPoints = compClassTrack.toList(),
                neuralPoints = compNeuralTrack.toList(),
                rawPoints = compRawTrack.toList()
            )
        }
    }

    fun resetComparisonToIdle() {
        compObsJob?.cancel(); compObsJob = null
        compClassJob?.cancel(); compClassJob = null
        compNeuralJob?.cancel(); compNeuralJob = null
        compClassCoordinator.stop()
        compNeuralCoordinator?.stop()
        compClassTrack.clear()
        compNeuralTrack.clear()
        compRawTrack.clear()
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

    // ── Утилиты ───────────────────────────────────────────────────────────────
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
        compObsJob?.cancel()
        compClassJob?.cancel()
        compNeuralJob?.cancel()
        compClassCoordinator.stop()
        compNeuralCoordinator?.stop()
    }
}