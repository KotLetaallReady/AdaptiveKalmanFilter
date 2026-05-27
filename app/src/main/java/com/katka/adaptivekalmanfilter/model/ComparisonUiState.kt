package com.katka.adaptivekalmanfilter.model

/**
 * Состояния сравнительной сессии (оба фильтра параллельно).
 */
sealed class ComparisonUiState {

    object NeedsPermission : ComparisonUiState()
    object NeuralNotTrained : ComparisonUiState()
    object Idle : ComparisonUiState()

    data class Running(
        val stepCount:      Int,
        val elapsedSeconds: Int,
        val classReadout:   KalmanReadout = KalmanReadout(),
        val neuralReadout:  KalmanReadout = KalmanReadout(),
        val trackPoints:    List<TrackPoint> = emptyList(),  // классика
        val neuralPoints:   List<TrackPoint> = emptyList(),
        val rawPoints:      List<TrackPoint> = emptyList(),
        val isNeuralActive: Boolean = false
    ) : ComparisonUiState()

    /** Сессия завершена, CSV сохранён. */
    data class Finished(
        val stepCount:     Int,
        val elapsedSeconds:Int,
        val classMetrics:  MetricsUiModel,
        val neuralMetrics: MetricsUiModel,
        val exportedUri:   android.net.Uri?,
        val trackPoints:   List<TrackPoint> = emptyList(),
        val neuralPoints:  List<TrackPoint> = emptyList(),
        val rawPoints:     List<TrackPoint> = emptyList()
    ) : ComparisonUiState()

    data class Error(val message: String) : ComparisonUiState()
}