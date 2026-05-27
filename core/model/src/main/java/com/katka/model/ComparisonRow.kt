package com.katka.model

/**
 * Один шаг сравнительной сессии — данные обоих фильтров на одном GPS-фиксе.
 */
data class ComparisonRow(
    val stepIndex:      Int,
    val timestampMs:    Long,
    val dtMs:           Double,
    // ── Сырой GPS ──────────────────────────────────────────────────────────
    val rawLat:         Double,
    val rawLon:         Double,
    val gpsAccuracyM:   Float,
    val gpsSpeedMs:     Float,
    // ── Классический фильтр ────────────────────────────────────────────────
    val classLat:       Double,
    val classLon:       Double,
    val classVx:        Double,
    val classVy:        Double,
    val classKx:        Double,
    val classKy:        Double,
    val classRxx:       Double,
    val classSigmaPos:  Double,
    val classInnov:     Double,
    // ── Нейросетевой фильтр ────────────────────────────────────────────────
    val neuralLat:      Double,
    val neuralLon:      Double,
    val neuralVx:       Double,
    val neuralVy:       Double,
    val neuralKx:       Double,
    val neuralKy:       Double,
    val neuralRxx:      Double,
    val neuralSigmaPos: Double,
    val neuralInnov:    Double,
    val isNeuralActive: Boolean   // false = fallback на классику
)