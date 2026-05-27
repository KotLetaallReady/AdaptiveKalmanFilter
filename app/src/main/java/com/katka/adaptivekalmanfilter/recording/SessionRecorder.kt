package com.katka.adaptivekalmanfilter.recording

import com.katka.engine.KalmanFilter
import com.katka.engine.coefficient_startegy.NeuralCoefficientStrategy
import com.katka.engine.model.FilterResult
import com.katka.model.ComparisonRow
import kotlin.math.sqrt

/**
 * Накапливает строки сравнительной сессии.
 *
 * Вызывается из ViewModel на каждом шаге когда оба фильтра вернули результат.
 * Потокобезопасность: все вызовы идут из одного coroutine (Dispatchers.Default).
 */
class SessionRecorder {

    private val _rows = mutableListOf<ComparisonRow>()
    val rows: List<ComparisonRow> get() = _rows.toList()
    val rowCount: Int get() = _rows.size

    fun reset() = _rows.clear()

    /**
     * @param classResult   результат классического фильтра
     * @param neuralResult  результат нейросетевого фильтра
     * @param classFilter   фильтр (нужен для localToGeo)
     * @param neuralFilter  фильтр (нужен для localToGeo)
     * @param neuralStrategy нужен для [NeuralCoefficientStrategy.isNetworkReady]
     * @param rawLat/rawLon сырой GPS в географических координатах
     * @param gpsAccuracy   точность GPS этого фикса
     * @param gpsSpeed      скорость из GPS
     */
    fun record(
        classResult:    FilterResult,
        neuralResult:   FilterResult,
        classFilter:    KalmanFilter,
        neuralFilter:   KalmanFilter,
        neuralStrategy: NeuralCoefficientStrategy,
        rawLat:         Double,
        rawLon:         Double,
        gpsAccuracy:    Float,
        gpsSpeed:       Float
    ) {
        val step = _rows.size

        val (cLat, cLon) = classFilter.localToGeo(classResult.state.x, classResult.state.y)
        val (nLat, nLon) = neuralFilter.localToGeo(neuralResult.state.x, neuralResult.state.y)

        fun innovMag(r: FilterResult): Double {
            val i = r.innovation
            return if (i.size >= 2) sqrt(i[0] * i[0] + i[1] * i[1]) else 0.0
        }

        _rows.add(
            ComparisonRow(
                stepIndex      = step,
                timestampMs    = classResult.timestamp,
                dtMs           = classResult.dt * 1000.0,
                rawLat         = rawLat,
                rawLon         = rawLon,
                gpsAccuracyM   = gpsAccuracy,
                gpsSpeedMs     = gpsSpeed,
                // Классика
                classLat       = cLat,
                classLon       = cLon,
                classVx        = classResult.state.vx,
                classVy        = classResult.state.vy,
                classKx        = classResult.kalmanGain.getOrNull(0)?.getOrNull(0) ?: 0.0,
                classKy        = classResult.kalmanGain.getOrNull(1)?.getOrNull(1) ?: 0.0,
                classRxx       = classResult.measurementNoiseR.getOrNull(0)?.getOrNull(0) ?: 0.0,
                classSigmaPos  = classResult.state.positionUncertaintyMeters,
                classInnov     = innovMag(classResult),
                // Нейросеть
                neuralLat      = nLat,
                neuralLon      = nLon,
                neuralVx       = neuralResult.state.vx,
                neuralVy       = neuralResult.state.vy,
                neuralKx       = neuralResult.kalmanGain.getOrNull(0)?.getOrNull(0) ?: 0.0,
                neuralKy       = neuralResult.kalmanGain.getOrNull(1)?.getOrNull(1) ?: 0.0,
                neuralRxx      = neuralResult.measurementNoiseR.getOrNull(0)?.getOrNull(0) ?: 0.0,
                neuralSigmaPos = neuralResult.state.positionUncertaintyMeters,
                neuralInnov    = innovMag(neuralResult),
                isNeuralActive = neuralStrategy.isNetworkReady
            )
        )
    }
}