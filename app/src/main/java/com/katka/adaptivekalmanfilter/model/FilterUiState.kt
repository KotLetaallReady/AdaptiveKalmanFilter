package com.katka.adaptivekalmanfilter.model

/** UI states of the classical-filter screen. */
sealed class FilterUiState {

    object NeedsPermission : FilterUiState()

    object Idle : FilterUiState()

    data class Running(
        val readout: KalmanReadout,
        val trackPoints: List<TrackPoint>,
        val rawPoints: List<TrackPoint>,
        val elapsedSeconds: Int
    ) : FilterUiState()

    data class Finished(
        val readout: KalmanReadout,
        val trackPoints: List<TrackPoint>,
        val rawPoints: List<TrackPoint>,
        val metrics: MetricsUiModel,
        val elapsedSeconds: Int
    ) : FilterUiState()

    data class Error(val message: String) : FilterUiState()
}

/** Per-step readout values shown on the filter screens. */
data class KalmanReadout(
    val filteredLat: Double  = 0.0,
    val filteredLon: Double  = 0.0,
    val rawLat: Double       = 0.0,
    val rawLon: Double       = 0.0,
    val vxMs: Double         = 0.0,
    val vyMs: Double         = 0.0,
    val rXX: Double          = 0.0,
    val rYY: Double          = 0.0,
    val kPosX: Double        = 0.0,
    val kPosY: Double        = 0.0,
    /** Smoother trust weight alpha in [0,1] (0 = pure Kalman). */
    val alpha: Double        = 0.0,
    val posUncertaintyM: Double = 0.0,
    val innovationMagnitude: Double = 0.0,
    val dtMs: Double         = 0.0,
    val gpsAccuracyM: Float  = 0f
)

/** A single track point in local metres relative to the reference point. */
data class TrackPoint(
    val x: Float,
    val y: Float
)

/** Formatted accuracy metrics for display. */
data class MetricsUiModel(
    val rmse: String,
    val mae: String,
    val maxError: String,
    val stability: String,
    val lag: String,
    val sampleCount: Int
) {
    companion object {
        val EMPTY = MetricsUiModel("—", "—", "—", "—", "—", 0)
    }
}
