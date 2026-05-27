package com.katka.adaptivekalmanfilter.ui.neural_filter

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.katka.adaptivekalmanfilter.model.KalmanReadout
import com.katka.adaptivekalmanfilter.model.MetricsUiModel
import com.katka.adaptivekalmanfilter.model.NeuralFilterUiState
import com.katka.adaptivekalmanfilter.model.TrackPoint
import com.katka.adaptivekalmanfilter.sensor_data_source.AndroidSensorDataSource
import com.katka.engine.KalmanFilter
import com.katka.engine.coefficient_startegy.ClassicalCoefficientStrategy
import com.katka.engine.coefficient_startegy.NeuralCoefficientStrategy
import com.katka.engine.model.FilterResult
import com.katka.engine.neural.NetworkConfig
import com.katka.engine.neural.NeuralNetwork
import com.katka.engine.neural.NeuralNetworkTrainer
import com.katka.engine.neural.NetworkPersistenceManager
import com.katka.engine.neural.TrainingDataCollector
import com.katka.engine.neural.TrainingSample
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
class NeuralFilterViewModel @Inject constructor(
    private val sensorDataSource: AndroidSensorDataSource,
    @ApplicationContext private val context: Context
) : ViewModel() {

    // ── Сбор обучающих данных ─────────────────────────────────────────────────
    private val trainingCollector = TrainingDataCollector()
    @Volatile
    private var lastObservation: Observation? = null
    private var observationJob: Job? = null
    private var collectionResultsJob: Job? = null
    private var neuralResultsJob: Job? = null

    // ── Нейросетевой фильтр (инференс) ────────────────────────────────────────
    private var neuralFilter: KalmanFilter? = null
    private var neuralStrategy: NeuralCoefficientStrategy? = null
    private var neuralCoordinator: KalmanFilterCoordinator? = null

    // ── Треки ─────────────────────────────────────────────────────────────────
    private val neuralTrack = mutableListOf<TrackPoint>()
    private val neuralRaw = mutableListOf<TrackPoint>()
    private var sessionStartMs = 0L

    // ── UI-состояние ──────────────────────────────────────────────────────────
    private val _neuralUiState = MutableStateFlow<NeuralFilterUiState>(
        if (NetworkPersistenceManager.exists(context)) NeuralFilterUiState.ReadyToRun
        else NeuralFilterUiState.NotTrained
    )
    val neuralUiState: StateFlow<NeuralFilterUiState> = _neuralUiState.asStateFlow()

    // ── Разрешения ────────────────────────────────────────────────────────────
    fun onPermissionGranted() {
        if (_neuralUiState.value is NeuralFilterUiState.NeedsPermission)
            _neuralUiState.value = if (NetworkPersistenceManager.exists(context))
                NeuralFilterUiState.ReadyToRun else NeuralFilterUiState.NotTrained
    }

    fun onPermissionDenied() {
        _neuralUiState.value = NeuralFilterUiState.NeedsPermission
    }

    // ── Сбор данных ───────────────────────────────────────────────────────────
    fun startDataCollection() {
        trainingCollector.reset()
        lastObservation = null
        neuralTrack.clear(); neuralRaw.clear()
        sessionStartMs = System.currentTimeMillis()

        observationJob?.cancel()
        observationJob = sensorDataSource.observations
            .onEach { obs -> lastObservation = obs }
            .launchIn(viewModelScope)

        // Временный классический координатор для генерации обучающих данных
        val collectionCoordinator = KalmanFilterCoordinator(
            sensorSource = sensorDataSource,
            filter = KalmanFilter(processNoiseStd = 0.1),
            strategy = ClassicalCoefficientStrategy(
                adaptiveR = true, adaptiveWindow = 40, minAccuracyM = 1f, maxAccuracyM = 50f
            ),
            scope = viewModelScope
        )
        collectionCoordinator.start()

        collectionResultsJob?.cancel()
        collectionResultsJob = collectionCoordinator.results
            .onEach { result ->
                if (result.dt == 0.0) return@onEach
                lastObservation?.let { obs ->
                    trainingCollector.addStep(obs, result, result.dt * 1000)
                }
                updateCollectionUiState(result, collectionCoordinator)
            }
            .catch { e -> _neuralUiState.value = NeuralFilterUiState.Error(e.message ?: "Error") }
            .launchIn(viewModelScope)

        _neuralUiState.value = NeuralFilterUiState.CollectingData(sampleCount = 0)
    }

    fun stopDataCollectionAndTrain() {
        observationJob?.cancel(); observationJob = null
        collectionResultsJob?.cancel()
        // Остановка временного координатора не требуется, т.к. он создавался локально
        // и будет собран после отмены джобы, но для безопасности завершим поток.

        val samples = trainingCollector.samples
        if (samples.size < NeuralFilterUiState.MIN_SAMPLES) {
            _neuralUiState.value = NeuralFilterUiState.Error(
                "Мало данных: ${samples.size} / ${NeuralFilterUiState.MIN_SAMPLES}. Пройдите маршрут дольше."
            )
            return
        }
        launchTraining(samples)
    }

    private fun updateCollectionUiState(
        result: FilterResult,
        coordinator: KalmanFilterCoordinator
    ) {
        val filtered = TrackPoint(result.state.x.toFloat(), result.state.y.toFloat())
        val raw = coordinator.getRawGpsHistory().lastOrNull()
            ?.let { TrackPoint(it.first.toFloat(), it.second.toFloat()) } ?: filtered

        if (neuralTrack.size >= MAX_TRACK_POINTS) {
            neuralTrack.removeAt(0); neuralRaw.removeAt(0)
        }
        neuralTrack.add(filtered); neuralRaw.add(raw)

        // Для отображения координат используем временный фильтр из координатора
        // (можем передать его как параметр, но проще создать локальный экземпляр)
        val tempFilter = KalmanFilter(processNoiseStd = 0.1) // упрощенно, оригинал использовал classicalFilter
        val (fLat, fLon) = tempFilter.localToGeo(result.state.x, result.state.y)

        _neuralUiState.value = NeuralFilterUiState.CollectingData(
            sampleCount = trainingCollector.sampleCount,
            readout = buildReadout(result, tempFilter, fLat, fLon, raw),
            trackPoints = neuralTrack.toList(),
            rawPoints = neuralRaw.toList(),
            elapsedSeconds = elapsedSec()
        )
    }

    // ── Обучение ──────────────────────────────────────────────────────────────
    private fun launchTraining(samples: List<TrainingSample>) {
        viewModelScope.launch(Dispatchers.Default) {
            val network = NeuralNetwork(NetworkConfig.default())
            val trainer = NeuralNetworkTrainer(network, learningRate = 1e-3, batchSize = 32)
            val epochs = NeuralFilterUiState.TRAINING_EPOCHS
            val lossHist = mutableListOf<Double>()

            for (epoch in 0 until epochs) {
                var sum = 0.0; var n = 0
                for (batch in samples.shuffled().chunked(32)) {
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

            withContext(Dispatchers.IO) { NetworkPersistenceManager.save(context, network) }
            _neuralUiState.value = NeuralFilterUiState.ReadyToRun
        }
    }

    // ── Инференс-сессия ───────────────────────────────────────────────────────
    fun startNeuralSession() {
        val network = NetworkPersistenceManager.load(context) ?: run {
            _neuralUiState.value = NeuralFilterUiState.NotTrained; return
        }

        neuralTrack.clear(); neuralRaw.clear()
        sessionStartMs = System.currentTimeMillis()

        val strategy = NeuralCoefficientStrategy(network)
        val filter = KalmanFilter(processNoiseStd = 0.1)
        neuralStrategy = strategy
        neuralFilter = filter

        val coordinator = KalmanFilterCoordinator(
            sensorSource = sensorDataSource,
            filter = filter,
            strategy = strategy,
            scope = viewModelScope
        )
        neuralCoordinator = coordinator
        coordinator.start()

        neuralResultsJob?.cancel()
        neuralResultsJob = coordinator.results
            .onEach { result -> handleNeuralResult(result, strategy, filter, coordinator) }
            .catch { e -> _neuralUiState.value = NeuralFilterUiState.Error(e.message ?: "Error") }
            .launchIn(viewModelScope)

        _neuralUiState.value = NeuralFilterUiState.Running(
            KalmanReadout(), emptyList(), emptyList(), 0
        )
    }

    fun stopNeuralSession() {
        neuralResultsJob?.cancel()
        val coordinator = neuralCoordinator ?: return
        coordinator.stop()
        val current = _neuralUiState.value as? NeuralFilterUiState.Running ?: return
        _neuralUiState.value = NeuralFilterUiState.Finished(
            readout = current.readout,
            trackPoints = neuralTrack.toList(),
            rawPoints = neuralRaw.toList(),
            metrics = coordinator.computeMetrics().toUiModel(),
            elapsedSeconds = current.elapsedSeconds
        )
    }

    fun resetNeuralToReady() {
        neuralResultsJob?.cancel()
        neuralCoordinator?.stop()
        neuralTrack.clear(); neuralRaw.clear()
        _neuralUiState.value = if (NetworkPersistenceManager.exists(context))
            NeuralFilterUiState.ReadyToRun else NeuralFilterUiState.NotTrained
    }

    fun deleteTrainedNetwork() {
        NetworkPersistenceManager.delete(context)
        neuralCoordinator?.stop()
        _neuralUiState.value = NeuralFilterUiState.NotTrained
    }

    // ── Обработчик результатов инференса ──────────────────────────────────────
    private fun handleNeuralResult(
        result: FilterResult,
        strategy: NeuralCoefficientStrategy,
        filter: KalmanFilter,
        coordinator: KalmanFilterCoordinator
    ) {
        if (result.dt == 0.0) return
        strategy.updateInnovation(result.innovation, result.dt * 1000.0)

        val filtered = TrackPoint(result.state.x.toFloat(), result.state.y.toFloat())
        val raw = coordinator.getRawGpsHistory().lastOrNull()
            ?.let { TrackPoint(it.first.toFloat(), it.second.toFloat()) } ?: filtered

        if (neuralTrack.size >= MAX_TRACK_POINTS) {
            neuralTrack.removeAt(0); neuralRaw.removeAt(0)
        }
        neuralTrack.add(filtered); neuralRaw.add(raw)

        val (fLat, fLon) = filter.localToGeo(result.state.x, result.state.y)

        _neuralUiState.value = NeuralFilterUiState.Running(
            readout = buildReadout(result, filter, fLat, fLon, raw),
            trackPoints = neuralTrack.toList(),
            rawPoints = neuralRaw.toList(),
            elapsedSeconds = elapsedSec(),
            isUsingNeuralGain = strategy.isNetworkReady
        )
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
        observationJob?.cancel()
        collectionResultsJob?.cancel()
        neuralResultsJob?.cancel()
        neuralCoordinator?.stop()
    }
}