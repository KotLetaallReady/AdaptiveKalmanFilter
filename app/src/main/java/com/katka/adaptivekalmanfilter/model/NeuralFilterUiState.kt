package com.katka.adaptivekalmanfilter.model

/** UI states of the neural-smoother workflow: collect → train → run. */
sealed class NeuralFilterUiState {

    object NeedsPermission : NeuralFilterUiState()

    /** No trained model on disk; show onboarding. */
    object NotTrained : NeuralFilterUiState()

    /** Running the classical filter to collect training pairs. */
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

    /** Adam training in progress. */
    data class Training(
        val epoch: Int,
        val totalEpochs: Int,
        val currentLoss: Double,
        val lossHistory: List<Double>
    ) : NeuralFilterUiState() {
        val progress: Float get() = epoch.toFloat() / totalEpochs.coerceAtLeast(1)
    }

    /** Model trained and ready to run. */
    object ReadyToRun : NeuralFilterUiState()

    /** Smoother running in real time. */
    data class Running(
        val readout: KalmanReadout,
        val trackPoints: List<TrackPoint>,
        val rawPoints: List<TrackPoint>,
        val elapsedSeconds: Int,
        /** false while the smoother window is still filling (first ~L steps). */
        val isSmoothing: Boolean = true
    ) : NeuralFilterUiState()

    /** Session finished. */
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
