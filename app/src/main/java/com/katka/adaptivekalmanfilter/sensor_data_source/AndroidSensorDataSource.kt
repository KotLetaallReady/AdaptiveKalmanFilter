package com.katka.adaptivekalmanfilter.sensor_data_source


import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.katka.data.SensorDataSource
import com.katka.model.Observation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Android implementation of [SensorDataSource].
 *
 * ── GPS ───────────────────────────────────────────────────────────────────────
 *   Uses [FusedLocationProviderClient] (Google Play Services) with
 *   PRIORITY_HIGH_ACCURACY at [gpsIntervalMs] (default 1 000 ms).
 *
 *   Each Location fix provides:
 *     • latitude, longitude (degrees)
 *     • accuracy (1-σ horizontal, metres) ← primary input for R matrix
 *     • speed (m/s) and bearing (°), when available
 *
 * ── IMU ───────────────────────────────────────────────────────────────────────
 *   Uses [Sensor.TYPE_LINEAR_ACCELERATION] — gravity already subtracted by the
 *   Android sensor fusion stack.  The device-frame [ax, ay, az] values are
 *   snapshotted atomically when a GPS fix arrives and injected as the control
 *   input u = [ax, ay] (az is ignored by the 2-D filter but recorded).
 *
 *   IMU is registered at [SENSOR_DELAY_GAME] ≈ 20 ms to stay responsive without
 *   flooding the processing pipeline.  GPS drives the filter cadence; IMU only
 *   enriches each GPS observation.
 *
 * ── Threading ─────────────────────────────────────────────────────────────────
 *   GPS callbacks arrive on the main looper.
 *   IMU values are written by the SensorManager thread and read by the GPS
 *   callback thread — all three fields are @Volatile to ensure visibility.
 *
 * ── Permissions required ─────────────────────────────────────────────────────
 *   ACCESS_FINE_LOCATION (runtime), or ACCESS_COARSE_LOCATION for degraded mode.
 *
 * @param context         Application context (not Activity, to avoid leaks).
 * @param gpsIntervalMs   Minimum time between GPS updates (milliseconds).
 * @param minDisplacementM Minimum distance between updates (metres, 0 = no filter).
 */
class AndroidSensorDataSource(
    private val context: Context,
    private val gpsIntervalMs: Long = 1_000L,
    private val minDisplacementM: Float = 0f
) : SensorDataSource, SensorEventListener {

    // ── Shared flow — backpressure drops the oldest unprocessed observation ──
    private val _observations = MutableSharedFlow<Observation>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val observations: Flow<Observation> = _observations.asSharedFlow()

    // ── Platform clients ─────────────────────────────────────────────────────
    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // ── Latest IMU snapshot (written by sensor thread, read by GPS callback) ─
    @Volatile private var latestAx: Double = 0.0
    @Volatile private var latestAy: Double = 0.0
    @Volatile private var latestAz: Double = 0.0
    @Volatile private var hasImu: Boolean  = false

    // ── State ────────────────────────────────────────────────────────────────
    override var isRunning: Boolean = false
        private set

    // ── GPS LocationCallback ─────────────────────────────────────────────────
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return

            val observation = Observation(
                timestamp  = location.time,
                latitude   = location.latitude,
                longitude  = location.longitude,
                accuracy   = location.accuracy,
                altitude   = if (location.hasAltitude()) location.altitude else 0.0,
                speed      = if (location.hasSpeed())    location.speed    else 0f,
                bearing    = if (location.hasBearing())  location.bearing  else 0f,
                hasSpeed   = location.hasSpeed(),
                hasBearing = location.hasBearing(),
                // Snapshot the most recent IMU reading
                ax = latestAx, ay = latestAy, az = latestAz,
                hasImu   = hasImu,
                provider = location.provider ?: "fused"
            )

            // tryEmit is non-suspending; safe to call from a callback
            _observations.tryEmit(observation)
        }
    }

    // ── SensorEventListener (IMU) ─────────────────────────────────────────────
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            // Device frame: x = right, y = up, z = toward user
            // For walking/running the filter uses x (East-ish) and y (North-ish)
            // after coordinate rotation — simplified here to raw device frame.
            latestAx = event.values[0].toDouble()
            latestAy = event.values[1].toDouble()
            latestAz = event.values[2].toDouble()
            hasImu   = true
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // No action needed for linear acceleration
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")  // Caller must check permissions before calling start()
    override fun start() {
        if (isRunning) return

        // GPS
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            gpsIntervalMs
        )
            .setMinUpdateDistanceMeters(minDisplacementM)
            .setWaitForAccurateLocation(false)
            .build()

        fusedClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )

        // IMU — TYPE_LINEAR_ACCELERATION has gravity already removed
        val linearAccSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        if (linearAccSensor != null) {
            sensorManager.registerListener(
                this,
                linearAccSensor,
                SensorManager.SENSOR_DELAY_GAME   // ~20 ms
            )
        }

        isRunning = true
    }

    override fun stop() {
        if (!isRunning) return
        fusedClient.removeLocationUpdates(locationCallback)
        sensorManager.unregisterListener(this)
        hasImu   = false
        isRunning = false
    }
}