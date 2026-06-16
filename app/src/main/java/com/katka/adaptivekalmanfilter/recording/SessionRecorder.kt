package com.katka.adaptivekalmanfilter.recording

import com.katka.engine.KalmanFilter
import com.katka.engine.smoothing.SmoothedSample
import com.katka.model.ComparisonRow

/**
 * Накапливает строки сравнительной сессии.
 *
 * В новой архитектуре сессия использует **один** классический фильтр Калмана и
 * нейросглаживатель поверх него. Каждая [SmoothedSample] самодостаточна — несёт
 * центральную точку фильтра, аппроксимацию Савицкого-Голея, сглаженный выход и
 * α, поэтому склейка двух потоков больше не нужна.
 *
 * Потокобезопасность: все вызовы идут из одного coroutine (Dispatchers.Default).
 */
class SessionRecorder {

    private val _rows = mutableListOf<ComparisonRow>()
    val rows: List<ComparisonRow> get() = _rows.toList()
    val rowCount: Int get() = _rows.size

    fun reset() = _rows.clear()

    /**
     * @param sample результат сглаживателя по центральной точке окна
     * @param filter фильтр Калмана (нужен только для localToGeo — общий reference-point)
     */
    fun record(sample: SmoothedSample, filter: KalmanFilter) {
        val (rawLat, rawLon)       = filter.localToGeo(sample.rawX, sample.rawY)
        val (kfLat, kfLon)         = filter.localToGeo(sample.kfX, sample.kfY)
        val (sgLat, sgLon)         = filter.localToGeo(sample.sgX, sample.sgY)
        val (smoothedLat, smLon)   = filter.localToGeo(sample.outX, sample.outY)

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
