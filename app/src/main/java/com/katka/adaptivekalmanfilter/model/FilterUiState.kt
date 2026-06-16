package com.katka.adaptivekalmanfilter.model

/**
 * Всё состояние UI фильтра в одном sealed-классе.
 * ViewModel держит StateFlow<FilterUiState>.
 */
sealed class FilterUiState {

    /** Разрешения не выданы — показываем плашку-объяснение. */
    object NeedsPermission : FilterUiState()

    /** Сессия не запущена — главный экран меню. */
    object Idle : FilterUiState()

    /** Идёт сбор данных. */
    data class Running(
        val readout: KalmanReadout,
        val trackPoints: List<TrackPoint>,   // история для холста
        val rawPoints: List<TrackPoint>,
        val elapsedSeconds: Int
    ) : FilterUiState()

    /** Сессия завершена, показываем итоги. */
    data class Finished(
        val readout: KalmanReadout,
        val trackPoints: List<TrackPoint>,
        val rawPoints: List<TrackPoint>,
        val metrics: MetricsUiModel,
        val elapsedSeconds: Int
    ) : FilterUiState()

    /** Что-то пошло не так (например, FusedLocation недоступен). */
    data class Error(val message: String) : FilterUiState()
}

// ── Текущие значения фильтра (один шаг) ────────────────────────────────────

data class KalmanReadout(
    // Позиция
    val filteredLat: Double  = 0.0,
    val filteredLon: Double  = 0.0,
    val rawLat: Double       = 0.0,
    val rawLon: Double       = 0.0,

    // Вектор состояния
    val vxMs: Double         = 0.0,  // скорость X (м/с)
    val vyMs: Double         = 0.0,  // скорость Y (м/с)

    // Матрица R — дисперсия измерений (diag 2×2)
    val rXX: Double          = 0.0,
    val rYY: Double          = 0.0,

    // Усиление Калмана K — первые два диагональных элемента (позиционные)
    val kPosX: Double        = 0.0,
    val kPosY: Double        = 0.0,

    // Коэффициент доверия α ∈ [0,1] нейросглаживателя (0 = чистый фильтр Калмана)
    val alpha: Double        = 0.0,

    // Неопределённость позиции (σ из P)
    val posUncertaintyM: Double = 0.0,

    // Инновация ||y||
    val innovationMagnitude: Double = 0.0,

    // Временной шаг
    val dtMs: Double         = 0.0,

    // GPS accuracy с устройства
    val gpsAccuracyM: Float  = 0f
)

// ── Точка трека ─────────────────────────────────────────────────────────────

data class TrackPoint(
    val x: Float,   // локальные метры от reference-point
    val y: Float
)

// ── Итоговые метрики ─────────────────────────────────────────────────────────

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