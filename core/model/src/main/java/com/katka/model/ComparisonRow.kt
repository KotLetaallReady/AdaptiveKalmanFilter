package com.katka.model

/**
 * Один шаг сравнительной сессии — данные по одной центральной точке сглаживателя.
 *
 * Сравниваются три представления одной и той же точки маршрута:
 *   • сырой GPS-фикс,
 *   • оценка классического фильтра Калмана (x_KF),
 *   • аппроксимация Савицкого-Голея (x_SG),
 *   • итог нейросглаживателя x_out = (1−α)·x_KF + α·x_SG.
 *
 * Все позиции — в географических координатах (после localToGeo).
 */
data class ComparisonRow(
    val stepIndex:     Int,
    val timestampMs:   Long,
    // ── Сырой GPS ──────────────────────────────────────────────────────────
    val rawLat:        Double,
    val rawLon:        Double,
    val gpsAccuracyM:  Float,
    val gpsSpeedMs:    Float,
    // ── Классический фильтр Калмана (центральная точка) ─────────────────────
    val kfLat:         Double,
    val kfLon:         Double,
    val kfVx:          Double,
    val kfVy:          Double,
    val kfSigmaPos:    Double,
    val kfInnov:       Double,
    // ── Аппроксимация Савицкого-Голея ───────────────────────────────────────
    val sgLat:         Double,
    val sgLon:         Double,
    // ── Нейросглаживатель ───────────────────────────────────────────────────
    val smoothedLat:   Double,
    val smoothedLon:   Double,
    /** Коэффициент доверия α ∈ [0,1], предсказанный нейросетью. */
    val alpha:         Double
)
