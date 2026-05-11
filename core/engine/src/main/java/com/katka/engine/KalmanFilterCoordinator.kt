package core.engine


import com.katka.data.SensorDataSource
import com.katka.engine.CoefficientStartegy.ClassicalCoefficientStrategy
import com.katka.engine.CoefficientStartegy.CoefficientStrategy
import com.katka.engine.KalmanFilter
import com.katka.metrics.MetricsEvaluator
import com.katka.engine.model.AccuracyMetrics
import com.katka.engine.model.FilterMode
import com.katka.engine.model.FilterResult
import com.katka.model.Observation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*

/**
 * Use-case that drives the full Kalman filter pipeline for one tracking session.
 *
 * ── Why the previous version broke ──────────────────────────────────────────
 *  [SensorDataSource.observations] is a [SharedFlow] in the Android impl.
 *  Applying `.flowOn()` to a SharedFlow is a no-op (and deprecated) because
 *  SharedFlow is a hot source — it has no upstream to redirect.
 *  Fix: collect the SharedFlow inside a cold [flow { }] builder, which *can*
 *  be switched with flowOn.
 *
 * ── Usage ────────────────────────────────────────────────────────────────────
 *
 *   // In ViewModel (Hilt example)
 *   val coordinator = KalmanFilterCoordinator(
 *       sensorSource = androidSensorDataSource,
 *       filter       = KalmanFilter(processNoiseStd = 0.5),
 *       strategy     = ClassicalCoefficientStrategy(adaptiveR = true),
 *       scope        = viewModelScope          // ← your DI-provided scope
 *   )
 *   coordinator.results
 *       .onEach { result -> _uiState.update { it.copy(latestResult = result) } }
 *       .launchIn(viewModelScope)
 *
 *   coordinator.start()
 *   // ... user stops session ...
 *   coordinator.stop()
 *   val metrics = coordinator.computeMetrics()
 *
 * @param sensorSource   Produces [Observation]s (GPS + IMU). Its [observations]
 *                       flow may be cold or hot — the coordinator wraps it safely.
 * @param filter         Kalman filter instance, reset at [start].
 * @param strategy       How to compute K (classical or neural).
 * @param scope          CoroutineScope that owns the shared flow (ViewModel scope,
 *                       or a DI-provided application scope for background tracking).
 * @param dispatcher     Dispatcher for filter computation (default [Dispatchers.Default]).
 * @param filterMode     Tag attached to each [FilterResult] for UI display.
 */
class KalmanFilterCoordinator(
    private val sensorSource: SensorDataSource,
    private val filter: KalmanFilter,
    private val strategy: CoefficientStrategy,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val filterMode: FilterMode = FilterMode.CLASSICAL
) {
    // ── Accumulated history (for metrics and chart data) ─────────────────────
    private val resultHistory = mutableListOf<FilterResult>()

    /**
     * Raw GPS positions in local metric coordinates (metres from the reference
     * point), recorded *before* the Kalman update so they serve as an unfiltered
     * reference for [computeMetrics].
     */
    private val rawGpsHistory = mutableListOf<Pair<Double, Double>>()

    // Fixed H matrix — extracted here to avoid re-allocating it every step
    private val H: Array<DoubleArray> = arrayOf(
        doubleArrayOf(1.0, 0.0, 0.0, 0.0),
        doubleArrayOf(0.0, 1.0, 0.0, 0.0)
    )

    // ── Public results flow ───────────────────────────────────────────────────

    /**
     * Cold-wrapped, dispatcher-switched pipeline that collects from the sensor
     * source and emits one [FilterResult] per GPS fix.
     *
     * Wrapping inside `flow { }` is the correct pattern when the upstream may
     * be a SharedFlow: the outer cold flow subscribes to the hot source as a
     * normal collector, and `flowOn` switches *that collector's* dispatcher.
     *
     * shareIn turns the result into a hot flow that survives collector restarts
     * (e.g. screen rotation) for up to 5 seconds.
     */
    val results: Flow<FilterResult> = flow {
        sensorSource.observations.collect { obs ->
            val result = processObservation(obs)
            if (result != null) emit(result)
        }
    }
        .flowOn(dispatcher)         // filter math runs off the main thread
        .onEach { result ->
            resultHistory.add(result)
        }
        .shareIn(
            scope   = scope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L),
            replay  = 1
        )

    // ── Session control ───────────────────────────────────────────────────────

    fun start() {
        filter.reset(strategy)
        resultHistory.clear()
        rawGpsHistory.clear()
        sensorSource.start()
    }

    fun stop() {
        sensorSource.stop()
    }

    // ── Metrics ───────────────────────────────────────────────────────────────

    /**
     * Compute accuracy metrics comparing the filtered track to the raw GPS track.
     * Returns [AccuracyMetrics.EMPTY] if the session has fewer than 2 samples.
     */
    fun computeMetrics(): AccuracyMetrics {
        if (resultHistory.size < 2 || rawGpsHistory.size < 2) return AccuracyMetrics.EMPTY
        val n         = minOf(resultHistory.size, rawGpsHistory.size)
        val estimated = resultHistory.take(n).map { it.state.x to it.state.y }
        val reference = rawGpsHistory.take(n)
        return MetricsEvaluator.compute(estimated, reference)
    }

    fun getHistory(): List<FilterResult> = resultHistory.toList()
    fun getRawGpsHistory(): List<Pair<Double, Double>> = rawGpsHistory.toList()

    // ── Private ───────────────────────────────────────────────────────────────

    private fun processObservation(obs: Observation): FilterResult? {
        return try {
            // Record raw GPS *before* the filter update, so it serves as an
            // unfiltered reference. On the very first observation the filter
            // is not yet initialised — geoToLocal will use (0,0) as reference
            // anchor and will compute real local coords after init.
            val isFirstObs = !filter.isInitialised

            val result = filter.process(obs, strategy)

            // After the first call the reference point is established
            val (rawX, rawY) = filter.geoToLocal(obs.latitude, obs.longitude)
            rawGpsHistory.add(rawX to rawY)

            // Feed innovation back to the strategy for adaptive-R updates
            if (strategy is ClassicalCoefficientStrategy && !isFirstObs) {
                strategy.updateInnovation(
                    innovation = result.innovation,
                    H          = H,
                    P_pred     = result.predicted.P
                )
            }

            result
        } catch (e: Exception) {
            // A single bad fix must not crash the session
            e.printStackTrace()
            null
        }
    }
}