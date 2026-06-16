package com.katka.adaptivekalmanfilter.ui.neural_filter

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.katka.adaptivekalmanfilter.model.KalmanReadout
import com.katka.adaptivekalmanfilter.model.NeuralFilterUiState
import com.katka.adaptivekalmanfilter.model.TrackMetrics
import com.katka.adaptivekalmanfilter.model.TrackPoint
import com.katka.adaptivekalmanfilter.sensor_data_source.AndroidSensorDataSource
import com.katka.engine.KalmanFilter
import com.katka.engine.KalmanFilterCoordinator
import com.katka.engine.coefficient_startegy.ClassicalCoefficientStrategy
import com.katka.engine.model.FilterResult
import com.katka.engine.neural.NetworkConfig
import com.katka.engine.neural.NeuralNetwork
import com.katka.engine.neural.NeuralNetworkTrainer
import com.katka.engine.neural.NetworkPersistenceManager
import com.katka.engine.smoothing.FeatureNormalizer
import com.katka.engine.smoothing.NeuralTrajectorySmoother
import com.katka.engine.smoothing.SmoothedSample
import com.katka.engine.smoothing.SmootherInput
import com.katka.engine.smoothing.SmoothingTrainingCollector
import com.katka.model.Observation
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.hypot
import kotlin.math.sqrt

private const val MAX_TRACK_POINTS = 500

/**
 * Drives the three-phase neural-smoother workflow:
 *
 *  1. **Collect** — run the classical Kalman filter over a real route and feed
 *     every step into a [SmoothingTrainingCollector], which produces
 *     (context features → optimal α*) pairs.
 *  2. **Train**   — fit the feature normaliser, train the MLP (Adam/MSE) to
 *     predict α, and persist weights + μ/σ.
 *  3. **Run**     — run the classical filter again and post-process its output
 *     with a [NeuralTrajectorySmoother], drawing the smoothed (lagged) track.
 */
@HiltViewModel
class NeuralFilterViewModel @Inject constructor(
    private val sensorDataSource: AndroidSensorDataSource,
    @ApplicationContext private val context: Context
) : ViewModel() {

    // ── Training-data collection ──────────────────────────────────────────────
    private val trainingCollector = SmoothingTrainingCollector()
    @Volatile private var lastObservation: Observation? = null
    private var observationJob: Job? = null
    private var resultsJob: Job? = null

    // ── Runtime stages ────────────────────────────────────────────────────────
    private var coordinator: KalmanFilterCoordinator? = null
    private var smoother: NeuralTrajectorySmoother? = null

    // ── Tracks / metrics ──────────────────────────────────────────────────────
    private val track = mutableListOf<TrackPoint>()   // KF track (collect) or smoothed track (run)
    private val raw = mutableListOf<TrackPoint>()
    private val smoothedEst = mutableListOf<Pair<Double, Double>>()  // x_out (local metres)
    private val smoothedRef = mutableListOf<Pair<Double, Double>>()  // central raw GPS
    private var sessionStartMs = 0L

    // ── UI state ──────────────────────────────────────────────────────────────
    private val _neuralUiState = MutableStateFlow<NeuralFilterUiState>(
        if (NetworkPersistenceManager.exists(context)) NeuralFilterUiState.ReadyToRun
        else NeuralFilterUiState.NotTrained
    )
    val neuralUiState: StateFlow<NeuralFilterUiState> = _neuralUiState.asStateFlow()

    // ── Permissions ───────────────────────────────────────────────────────────
    fun onPermissionGranted() {
        if (_neuralUiState.value is NeuralFilterUiState.NeedsPermission)
            _neuralUiState.value = if (NetworkPersistenceManager.exists(context))
                NeuralFilterUiState.ReadyToRun else NeuralFilterUiState.NotTrained
    }

    fun onPermissionDenied() {
        _neuralUiState.value = NeuralFilterUiState.NeedsPermission
    }

    // ── Phase 1: collect training data ────────────────────────────────────────
    fun startDataCollection() {
        trainingCollector.reset()
        lastObservation = null
        resetTracks()
        sessionStartMs = System.currentTimeMillis()

        observationJob?.cancel()
        observationJob = sensorDataSource.observations
            .onEach { obs -> lastObservation = obs }
            .launchIn(viewModelScope)

        val collectionFilter = KalmanFilter(processNoiseStd = 0.1)
        val collectionCoordinator = KalmanFilterCoordinator(
            sensorSource = sensorDataSource,
            filter = collectionFilter,
            strategy = ClassicalCoefficientStrategy(
                adaptiveR = true, adaptiveWindow = 40, minAccuracyM = 1f, maxAccuracyM = 50f
            ),
            scope = viewModelScope
        )
        coordinator = collectionCoordinator
        collectionCoordinator.start()

        resultsJob?.cancel()
        resultsJob = collectionCoordinator.results
            .onEach { result ->
                if (result.dt == 0.0) return@onEach
                val rawXY = collectionCoordinator.getRawGpsHistory().lastOrNull() ?: return@onEach
                trainingCollector.addStep(buildInput(result, lastObservation, rawXY))
                updateCollectionUiState(result, collectionFilter, rawXY)
            }
            .catch { e -> _neuralUiState.value = NeuralFilterUiState.Error(e.message ?: "Error") }
            .launchIn(viewModelScope)

        _neuralUiState.value = NeuralFilterUiState.CollectingData(sampleCount = 0)
    }

    fun stopDataCollectionAndTrain() {
        observationJob?.cancel(); observationJob = null
        resultsJob?.cancel(); resultsJob = null
        coordinator?.stop()

        if (trainingCollector.sampleCount < NeuralFilterUiState.MIN_SAMPLES) {
            _neuralUiState.value = NeuralFilterUiState.Error(
                "Мало данных: ${trainingCollector.sampleCount} / ${NeuralFilterUiState.MIN_SAMPLES}. " +
                        "Пройдите маршрут дольше."
            )
            return
        }
        launchTraining()
    }

    // ── Phase 2: train the α-network ──────────────────────────────────────────
    private fun launchTraining() {
        viewModelScope.launch(Dispatchers.Default) {
            val dataset = trainingCollector.buildDataset()
            val network = NeuralNetwork(NetworkConfig.default())
            val trainer = NeuralNetworkTrainer(network, learningRate = 1e-3, batchSize = 32)
            val epochs = NeuralFilterUiState.TRAINING_EPOCHS
            val lossHist = mutableListOf<Double>()

            for (epoch in 0 until epochs) {
                var sum = 0.0; var n = 0
                for (batch in dataset.samples.shuffled().chunked(32)) {
                    sum += trainer.trainBatch(batch); n++
                }
                lossHist.add(if (n > 0) sum / n else 0.0)
                _neuralUiState.value = NeuralFilterUiState.Training(
                    epoch = epoch + 1,
                    totalEpochs = epochs,
                    currentLoss = lossHist.last(),
                    lossHistory = lossHist.toList()
                )
            }

            withContext(Dispatchers.IO) {
                NetworkPersistenceManager.saveSmoother(
                    context, network, dataset.normalizer.mean, dataset.normalizer.std
                )
            }
            _neuralUiState.value = NeuralFilterUiState.ReadyToRun
        }
    }

    // ── Phase 3: run the smoother ─────────────────────────────────────────────
    fun startNeuralSession() {
        val loaded = NetworkPersistenceManager.loadSmoother(context) ?: run {
            _neuralUiState.value = NeuralFilterUiState.NotTrained; return
        }

        lastObservation = null
        resetTracks()
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

        observationJob?.cancel()
        observationJob = sensorDataSource.observations
            .onEach { obs -> lastObservation = obs }
            .launchIn(viewModelScope)

        resultsJob?.cancel()
        resultsJob = sessionCoordinator.results
            .onEach { result -> handleRunResult(result, sessionCoordinator, sessionFilter) }
            .catch { e -> _neuralUiState.value = NeuralFilterUiState.Error(e.message ?: "Error") }
            .launchIn(viewModelScope)

        _neuralUiState.value = NeuralFilterUiState.Running(
            readout = KalmanReadout(),
            trackPoints = emptyList(),
            rawPoints = emptyList(),
            elapsedSeconds = 0,
            isSmoothing = false
        )
    }

    fun stopNeuralSession() {
        resultsJob?.cancel(); resultsJob = null
        observationJob?.cancel(); observationJob = null
        coordinator?.stop()

        val current = _neuralUiState.value as? NeuralFilterUiState.Running ?: return
        _neuralUiState.value = NeuralFilterUiState.Finished(
            readout = current.readout,
            trackPoints = track.toList(),
            rawPoints = raw.toList(),
            metrics = TrackMetrics.compute(smoothedEst, smoothedRef),
            elapsedSeconds = current.elapsedSeconds
        )
    }

    fun resetNeuralToReady() {
        resultsJob?.cancel()
        observationJob?.cancel()
        coordinator?.stop()
        resetTracks()
        _neuralUiState.value = if (NetworkPersistenceManager.exists(context))
            NeuralFilterUiState.ReadyToRun else NeuralFilterUiState.NotTrained
    }

    fun deleteTrainedNetwork() {
        NetworkPersistenceManager.delete(context)
        coordinator?.stop()
        _neuralUiState.value = NeuralFilterUiState.NotTrained
    }

    // ── Result handlers ───────────────────────────────────────────────────────
    private fun updateCollectionUiState(
        result: FilterResult,
        filter: KalmanFilter,
        rawXY: Pair<Double, Double>
    ) {
        val filtered = TrackPoint(result.state.x.toFloat(), result.state.y.toFloat())
        val rawPt = TrackPoint(rawXY.first.toFloat(), rawXY.second.toFloat())
        addPoint(filtered, rawPt)

        val (fLat, fLon) = filter.localToGeo(result.state.x, result.state.y)
        _neuralUiState.value = NeuralFilterUiState.CollectingData(
            sampleCount = trainingCollector.sampleCount,
            readout = buildKfReadout(result, filter, fLat, fLon, rawPt),
            trackPoints = track.toList(),
            rawPoints = raw.toList(),
            elapsedSeconds = elapsedSec()
        )
    }

    private fun handleRunResult(
        result: FilterResult,
        coordinator: KalmanFilterCoordinator,
        filter: KalmanFilter
    ) {
        if (result.dt == 0.0) return
        val rawXY = coordinator.getRawGpsHistory().lastOrNull() ?: return
        val sample = smoother?.push(buildInput(result, lastObservation, rawXY)) ?: return

        val outPt = TrackPoint(sample.outX.toFloat(), sample.outY.toFloat())
        val rawPt = TrackPoint(sample.rawX.toFloat(), sample.rawY.toFloat())
        addPoint(outPt, rawPt)
        smoothedEst.add(sample.outX to sample.outY)
        smoothedRef.add(sample.rawX to sample.rawY)

        val (fLat, fLon) = filter.localToGeo(sample.outX, sample.outY)
        _neuralUiState.value = NeuralFilterUiState.Running(
            readout = buildSmoothReadout(sample, filter, fLat, fLon),
            trackPoints = track.toList(),
            rawPoints = raw.toList(),
            elapsedSeconds = elapsedSec(),
            isSmoothing = true
        )
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

    /** Full Kalman readout (with K, R) for the collection phase. */
    private fun buildKfReadout(
        result: FilterResult,
        filter: KalmanFilter,
        fLat: Double,
        fLon: Double,
        rawPt: TrackPoint
    ): KalmanReadout {
        val K = result.kalmanGain
        val R = result.measurementNoiseR
        val innov = result.innovation
        val innovMag = if (innov.size >= 2) sqrt(innov[0] * innov[0] + innov[1] * innov[1]) else 0.0
        val (rLat, rLon) = filter.localToGeo(rawPt.x.toDouble(), rawPt.y.toDouble())
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
            dtMs = result.dt * 1000.0
        )
    }

    /** Smoother readout (with α) for the inference phase. */
    private fun buildSmoothReadout(
        sample: SmoothedSample,
        filter: KalmanFilter,
        fLat: Double,
        fLon: Double
    ): KalmanReadout {
        val (rLat, rLon) = filter.localToGeo(sample.rawX, sample.rawY)
        return KalmanReadout(
            filteredLat = fLat, filteredLon = fLon,
            rawLat = rLat, rawLon = rLon,
            vxMs = sample.vx, vyMs = sample.vy,
            alpha = sample.alpha,
            posUncertaintyM = sample.sigmaPos,
            innovationMagnitude = sample.innovationMag,
            gpsAccuracyM = sample.accuracy.toFloat()
        )
    }

    private fun addPoint(estimated: TrackPoint, rawPt: TrackPoint) {
        if (track.size >= MAX_TRACK_POINTS) {
            track.removeAt(0); raw.removeAt(0)
        }
        track.add(estimated); raw.add(rawPt)
    }

    private fun resetTracks() {
        track.clear(); raw.clear()
        smoothedEst.clear(); smoothedRef.clear()
    }

    private fun elapsedSec() = ((System.currentTimeMillis() - sessionStartMs) / 1000).toInt()

    override fun onCleared() {
        super.onCleared()
        observationJob?.cancel()
        resultsJob?.cancel()
        coordinator?.stop()
    }
}
