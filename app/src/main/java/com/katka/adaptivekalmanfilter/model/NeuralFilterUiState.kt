package com.katka.adaptivekalmanfilter.model

sealed class NeuralFilterUiState {

    object NeedsPermission : NeuralFilterUiState()

    /** Сети нет на диске — показываем онбординг */
    object NotTrained : NeuralFilterUiState()

    /** Классический фильтр запущен для накопления обучающих пар */
    data class CollectingData(
        val sampleCount: Int,
        val readout: KalmanReadout = KalmanReadout(),
        val trackPoints: List<TrackPoint> = emptyList(),
        val rawPoints: List<TrackPoint> = emptyList(),
        val elapsedSeconds: Int = 0
    ) : NeuralFilterUiState() {
        val progress: Float get() = (sampleCount.toFloat() / MIN_SAMPLES).coerceIn(0f, 1f)
        val isReadyToTrain: Boolean get() = sampleCount >= MIN_SAMPLES
    }

    /** Adam-обучение идёт в фоне */
    data class Training(
        val epoch: Int,
        val totalEpochs: Int,
        val currentLoss: Double,
        val lossHistory: List<Double>
    ) : NeuralFilterUiState() {
        val progress: Float get() = epoch.toFloat() / totalEpochs.coerceAtLeast(1)
    }

    /** Сеть сохранена, готова к инференсу */
    object ReadyToRun : NeuralFilterUiState()

    /** Нейросетевой фильтр работает в реальном времени */
    data class Running(
        val readout: KalmanReadout,
        val trackPoints: List<TrackPoint>,
        val rawPoints: List<TrackPoint>,
        val elapsedSeconds: Int,
        /** false → окно сглаживателя ещё не заполнено (первые ~L шагов), сглаживание не идёт */
        val isSmoothing: Boolean = true
    ) : NeuralFilterUiState()

    /** Сессия завершена */
    data class Finished(
        val readout: KalmanReadout,
        val trackPoints: List<TrackPoint>,
        val rawPoints: List<TrackPoint>,
        val metrics: MetricsUiModel,
        val elapsedSeconds: Int
    ) : NeuralFilterUiState()

    data class Error(val message: String) : NeuralFilterUiState()

    companion object {
        const val MIN_SAMPLES = 50
        const val TRAINING_EPOCHS = 80
    }
}