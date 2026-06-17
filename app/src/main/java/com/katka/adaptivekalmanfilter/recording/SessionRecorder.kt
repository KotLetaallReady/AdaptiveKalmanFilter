package com.katka.adaptivekalmanfilter.recording

import com.katka.engine.KalmanFilter
import com.katka.engine.smoothing.SmoothedSample
import com.katka.model.ComparisonRow

/** Accumulates comparison-session rows from self-contained [SmoothedSample]s (one classical filter plus the smoother). */
class SessionRecorder {

    private val _rows = mutableListOf<ComparisonRow>()
    val rows: List<ComparisonRow> get() = _rows.toList()
    val rowCount: Int get() = _rows.size

    fun reset() = _rows.clear()

    /** Records one central-point sample; [filter] supplies localToGeo via the shared reference point. */
    fun record(sample: SmoothedSample, filter: KalmanFilter) {
        val (rawLat, rawLon)     = filter.localToGeo(sample.rawX, sample.rawY)
        val (kfLat, kfLon)       = filter.localToGeo(sample.kfX, sample.kfY)
        val (sgLat, sgLon)       = filter.localToGeo(sample.sgX, sample.sgY)
        val (smoothedLat, smLon) = filter.localToGeo(sample.outX, sample.outY)

        _rows.add(
            ComparisonRow(
                stepIndex     = _rows.size,
                timestampMs   = sample.timestamp,
                rawLat        = rawLat,
                rawLon        = rawLon,
                gpsAccuracyM  = sample.accuracy.toFloat(),
                gpsSpeedMs    = sample.speed.toFloat(),
                kfLat         = kfLat,
                kfLon         = kfLon,
                kfVx          = sample.vx,
                kfVy          = sample.vy,
                kfSigmaPos    = sample.sigmaPos,
                kfInnov       = sample.innovationMag,
                sgLat         = sgLat,
                sgLon         = sgLon,
                smoothedLat   = smoothedLat,
                smoothedLon   = smLon,
                alpha         = sample.alpha
            )
        )
    }
}
