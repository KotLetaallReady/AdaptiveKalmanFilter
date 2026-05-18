package com.katka.engine

import com.katka.engine.coefficient_startegy.CoefficientStrategy
import com.katka.engine.model.FilterMode
import com.katka.engine.model.FilterResult
import com.katka.engine.model.KalmanState
import com.katka.model.Observation
import kotlin.math.cos


/**
 * Classical discrete-time Kalman filter for 2-D pedestrian / vehicle tracking.
 *
 * ── State vector ─────────────────────────────────────────────────────────────
 *
 *   x = [x, y, vx, vy]ᵀ       (n = 4)
 *     x, y  — local metric position (metres, relative to a fixed reference point)
 *     vx,vy — velocity (m/s), estimated by the filter
 *
 * ── Measurement vector ───────────────────────────────────────────────────────
 *
 *   z = [x_gps, y_gps]ᵀ       (m = 2)
 *
 *   GPS provides only position; velocity is inferred through process dynamics.
 *
 * ── Prediction step ─────────────────────────────────────────────────────────
 *
 *   x̂_k|k-1 = F·x̂_k-1|k-1 + B·u_k
 *   P_k|k-1  = F·P_k-1|k-1·Fᵀ + Q
 *
 *   F — constant-velocity transition matrix (depends on dt)
 *   B — control-input matrix (maps IMU acceleration to state increment)
 *   u — control input = [ax, ay]ᵀ from IMU (zero when hasImu == false)
 *   Q — process noise covariance (continuous white-noise acceleration model)
 *
 * ── Update step ──────────────────────────────────────────────────────────────
 *
 *   K is produced by the injected [com.katka.engine.coefficient_startegy.CoefficientStrategy]:
 *     K = P_pred·Hᵀ·S⁻¹          (classical, computed inside the strategy)
 *
 *   State update:
 *     x̂_k|k = x̂_k|k-1 + K·(z_k − H·x̂_k|k-1)
 *
 *   Covariance update uses the numerically robust Joseph form (inside strategy).
 *
 * ── Coordinate handling ─────────────────────────────────────────────────────
 *
 *   The filter works entirely in a local Euclidean plane whose origin is the
 *   first GPS fix (reference point).  [geoToLocal] / [localToGeo] convert
 *   between WGS-84 and local metres using the equirectangular approximation
 *   (error < 0.1 % within 100 km of the reference point).
 *
 * @param processNoiseStd Spectral density of the acceleration process noise σ_a (m/s²).
 *                        Larger → filter trusts sensor more, follows GPS tightly.
 *                        Smaller → smoother but slower to react to turns/stops.
 *                        Typical range: 0.05 – 2.0.
 */
class KalmanFilter(
    private val processNoiseStd: Double = 0.5
) {
    // ── Internal state ───────────────────────────────────────────────────────

    private var state: KalmanState? = null
    private var lastTimestamp: Long = -1L

    private var lastWallClockMs: Long = -1L

    /** Reference geographic point — origin of the local coordinate system. */
    var refLat: Double = 0.0
        private set
    var refLon: Double = 0.0
        private set

    val isInitialised: Boolean get() = state != null

    // ── Measurement matrix H (fixed, 2×4) ────────────────────────────────────
    //   Selects [x, y] from [x, y, vx, vy]
    private val H: Array<DoubleArray> = arrayOf(
        doubleArrayOf(1.0, 0.0, 0.0, 0.0),
        doubleArrayOf(0.0, 1.0, 0.0, 0.0)
    )

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Reset everything.  Call this at the start of a new tracking session.
     * Passes [com.katka.engine.coefficient_startegy.CoefficientStrategy.reset] down to the strategy so it can flush
     * its own internal buffers (e.g. an adaptive-R window).
     */
    fun reset(strategy: CoefficientStrategy? = null) {
        state = null
        lastTimestamp   = -1L
        lastWallClockMs = -1L
        refLat = 0.0
        refLon = 0.0
        strategy?.reset()
    }


    /**
     * Process one observation.
     *
     * On the very first call the filter initialises itself from the GPS fix and
     * returns a [FilterResult] whose [FilterResult.dt] is 0.
     *
     * @param obs      Raw sensor observation.
     * @param strategy How to compute the Kalman gain K (classical or neural).
     * @return         Posterior state and diagnostics for this step.
     */
    fun process(obs: Observation, strategy: CoefficientStrategy): FilterResult {
        // ── Initialise on first observation ──────────────────────────────────
        if (!isInitialised) {
            return initialise(obs)
        }

        val nowMs = System.currentTimeMillis()

        // ── dt с защитой от кривых Huawei-timestamps ─────────────────────────
        val dtFromGps  = (obs.timestamp - lastTimestamp) / 1000.0
        val dtFromWall = if (lastWallClockMs > 0)
            (nowMs - lastWallClockMs) / 1000.0
        else
            dtFromGps

        val dt = when {
            dtFromGps <= 0 ->
                dtFromWall                          // GPS-timestamp ушёл назад
            dtFromWall <= 0 ->
                dtFromGps.coerceIn(0.001, 30.0)    // wall clock не инициализирован
            dtFromGps / dtFromWall > 3.0 ->
                dtFromWall                          // GPS-timestamp сильно впереди wall
            dtFromWall / dtFromGps > 3.0 ->
                dtFromWall                          // GPS-timestamp сильно отстаёт (кэш)
            else ->
                dtFromGps
        }.coerceIn(0.001, 30.0)

        lastTimestamp   = obs.timestamp
        lastWallClockMs = nowMs

        val currentState = state!!

        // ── PREDICT ──────────────────────────────────────────────────────────
        val (xPred, PPred) = predict(currentState, dt, obs)

        // ── Convert GPS fix to local metres ──────────────────────────────────
        val (gpsX, gpsY) = geoToLocal(obs.latitude, obs.longitude)
        val z = doubleArrayOf(gpsX, gpsY)

        // ── Innovation  y = z − H·x̂_pred ─────────────────────────────────────
        val hxPred     = MatrixOps.mulVec(H, xPred.toVector())
        val innovation = DoubleArray(H.size) { i -> z[i] - hxPred[i] }

        // ── Innovation gating — отбрасываем физически невозможные фиксы ──────
        val innovationMag = kotlin.math.sqrt(
            innovation[0] * innovation[0] + innovation[1] * innovation[1]
        )
        // Порог = 5·σ_pos, но не меньше 50м (на старте σ большая)
        val gateThreshold = (xPred.positionUncertaintyMeters * 5.0).coerceAtLeast(50.0)

        if (innovationMag > gateThreshold) {
            // Фикс отброшен — возвращаем предсказанное состояние без обновления
            // P продолжает расти, поэтому следующий валидный фикс будет доверен больше
            state = xPred
            val zeroK = MatrixOps.zeros(KalmanState.DIM, 2)
            val zeroR = MatrixOps.zeros(2, 2)
            return FilterResult(
                timestamp         = obs.timestamp,
                state             = xPred,
                predicted         = xPred,
                innovation        = innovation,
                innovationCovS    = zeroR,
                kalmanGain        = zeroK,
                measurementNoiseR = zeroR,
                filterMode        = FilterMode.CLASSICAL,
                dt                = dt
            )
        }

        // ── Compute gain & R ──────────────────────────────────────────────────
        val gainResult = strategy.computeGain(PPred, H, obs)
        val K = gainResult.K
        val R = gainResult.R

        // ── Innovation covariance  S = H·P_pred·Hᵀ + R ───────────────────────
        val HP = MatrixOps.mul(H, PPred)
        val S  = MatrixOps.add(MatrixOps.mul(HP, MatrixOps.transpose(H)), R)

        // ── State update  x̂ = x̂_pred + K·y ──────────────────────────────────
        val Ky       = MatrixOps.mulVec(K, innovation)
        val xPredVec = xPred.toVector()
        val xNew     = DoubleArray(KalmanState.DIM) { i -> xPredVec[i] + Ky[i] }

        // ── Clamp скорости до физически разумных значений ─────────────────────
        // 15 м/с ≈ 54 км/ч — достаточно для пешехода, велосипеда и медленного авто
        val maxSpeedMs = 15.0
        xNew[2] = xNew[2].coerceIn(-maxSpeedMs, maxSpeedMs)
        xNew[3] = xNew[3].coerceIn(-maxSpeedMs, maxSpeedMs)

        val posterior = KalmanState(
            x  = xNew[0], y  = xNew[1],
            vx = xNew[2], vy = xNew[3],
            P  = gainResult.P_updated
        )
        state = posterior

        return FilterResult(
            timestamp         = obs.timestamp,
            state             = posterior,
            predicted         = xPred,
            innovation        = innovation,
            innovationCovS    = S,
            kalmanGain        = K,
            measurementNoiseR = R,
            filterMode        = FilterMode.CLASSICAL,
            dt                = dt
        )
    }
    /** Return the current posterior estimate without processing a new observation. */
    fun getCurrentState(): KalmanState? = state

    // ── Coordinate conversion ────────────────────────────────────────────────

    /**
     * WGS-84 → local Euclidean metres (equirectangular projection).
     *
     *   x = R · Δlon · cos(lat_ref)    (East direction, metres)
     *   y = R · Δlat                   (North direction, metres)
     */
    fun geoToLocal(lat: Double, lon: Double): Pair<Double, Double> {
        val R = EARTH_RADIUS_M
        val dLat = Math.toRadians(lat - refLat)
        val dLon = Math.toRadians(lon - refLon)
        val x = R * dLon * cos(Math.toRadians(refLat))
        val y = R * dLat
        return x to y
    }

    /** Local metres → WGS-84 (inverse of [geoToLocal]). */
    fun localToGeo(x: Double, y: Double): Pair<Double, Double> {
        val R = EARTH_RADIUS_M
        val lat = refLat + Math.toDegrees(y / R)
        val lon = refLon + Math.toDegrees(x / (R * cos(Math.toRadians(refLat))))
        return lat to lon
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun initialise(obs: Observation): FilterResult {
        refLat = obs.latitude
        refLon = obs.longitude
        lastTimestamp = obs.timestamp

        val posVariance = (obs.accuracy * obs.accuracy).toDouble().coerceAtLeast(1.0)
        val initState = KalmanState.initial(x = 0.0, y = 0.0, posVariance = posVariance)
        state = initState

        // Dummy result — no prediction or update on the very first step
        val zeroR = MatrixOps.zeros(2, 2)
        return FilterResult(
            timestamp = obs.timestamp,
            state = initState,
            predicted = initState,
            innovation = DoubleArray(2),
            innovationCovS = zeroR,
            kalmanGain = MatrixOps.zeros(KalmanState.DIM, 2),
            measurementNoiseR = zeroR,
            filterMode = FilterMode.CLASSICAL,
            dt = 0.0
        )
    }

    /**
     * Prediction equations:
     *   x̂_pred = F·x̂ + B·u
     *   P_pred  = F·P·Fᵀ + Q
     *
     * F — constant-velocity state transition (dt-dependent):
     *   ┌ 1  0  dt  0  ┐
     *   │ 0  1  0   dt │
     *   │ 0  0  1   0  │
     *   └ 0  0  0   1  ┘
     *
     * B — control-input matrix that maps [ax, ay] to state increment:
     *   ┌ ½dt²  0    ┐
     *   │ 0     ½dt² │
     *   │ dt    0    │
     *   └ 0     dt   ┘
     *
     * Q — continuous white-noise acceleration model (σ_a²·Γ·Γᵀ):
     *   where Γ = [½dt², ½dt², dt, dt]ᵀ  per axis
     *
     *   Q =  σ_a² · ┌ dt⁴/4  0      dt³/2  0     ┐
     *               │ 0      dt⁴/4  0      dt³/2  │
     *               │ dt³/2  0      dt²    0       │
     *               └ 0      dt³/2  0      dt²     ┘
     */
    private fun predict(
        s: KalmanState,
        dt: Double,
        obs: Observation
    ): Pair<KalmanState, Array<DoubleArray>> {

        val dt2 = dt * dt
        val dt3 = dt2 * dt
        val dt4 = dt3 * dt
        val halfDt2 = 0.5 * dt2

        // F
        val F = arrayOf(
            doubleArrayOf(1.0, 0.0, dt,  0.0),
            doubleArrayOf(0.0, 1.0, 0.0, dt ),
            doubleArrayOf(0.0, 0.0, 1.0, 0.0),
            doubleArrayOf(0.0, 0.0, 0.0, 1.0)
        )

        // B
        val B = arrayOf(
            doubleArrayOf(halfDt2, 0.0    ),
            doubleArrayOf(0.0,     halfDt2),
            doubleArrayOf(dt,      0.0    ),
            doubleArrayOf(0.0,     dt     )
        )

        // u — acceleration control input (zero if no IMU)
        val u = if (obs.hasImu) doubleArrayOf(obs.ax, obs.ay) else doubleArrayOf(0.0, 0.0)

        // x̂_pred = F·x + B·u
        val Fx  = MatrixOps.mulVec(F, s.toVector())
        val Bu  = MatrixOps.mulVec(B, u)
        val xPredVec = DoubleArray(KalmanState.DIM) { i -> Fx[i] + Bu[i] }

        // Q (continuous white-noise acceleration model)
        val q = processNoiseStd * processNoiseStd
        val Q = arrayOf(
            doubleArrayOf(q * dt4 / 4, 0.0,        q * dt3 / 2, 0.0       ),
            doubleArrayOf(0.0,         q * dt4 / 4, 0.0,        q * dt3 / 2),
            doubleArrayOf(q * dt3 / 2, 0.0,        q * dt2,    0.0        ),
            doubleArrayOf(0.0,         q * dt3 / 2, 0.0,        q * dt2   )
        )

        // P_pred = F·P·Fᵀ + Q
        val FP    = MatrixOps.mul(F, s.P)
        val FPFt  = MatrixOps.mul(FP, MatrixOps.transpose(F))
        val PPred = MatrixOps.symmetrise(MatrixOps.add(FPFt, Q))

        val xPred = KalmanState(
            x  = xPredVec[0], y  = xPredVec[1],
            vx = xPredVec[2], vy = xPredVec[3],
            P  = PPred
        )
        return xPred to PPred
    }

    private companion object {
        const val EARTH_RADIUS_M = 6_371_000.0
    }
}