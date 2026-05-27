package com.katka.adaptivekalmanfilter.sensor_data_source

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.PowerManager
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
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
 *   Primary path uses [FusedLocationProviderClient] (Google Play Services) with
 *   PRIORITY_HIGH_ACCURACY at [gpsIntervalMs] (default 1 000 ms).
 *
 *   If Google Play Services are unavailable (e.g. Huawei devices without GMS),
 *   the data source falls back to [LocationManager] with
 *   [LocationManager.GPS_PROVIDER] (or [LocationManager.NETWORK_PROVIDER] when
 *   GPS is disabled).
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

    private val powerManager: PowerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val wakeLock: PowerManager.WakeLock by lazy {
        powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "KalmanFilter::GpsWakeLock"
        )
    }

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

    // Fallback location provider for devices without Google Play Services
    private val locationManager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    // Determine once whether Google Mobile Services are available
    private val hasGms: Boolean by lazy {
        try {
            GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        } catch (_: Exception) {
            false
        }
    }

    // ── Latest IMU snapshot (written by sensor thread, read by GPS callback) ─
    @Volatile private var latestAx: Double = 0.0
    @Volatile private var latestAy: Double = 0.0
    @Volatile private var latestAz: Double = 0.0
    @Volatile private var hasImu: Boolean  = false

    // ── State ────────────────────────────────────────────────────────────────
    override var isRunning: Boolean = false
        private set

    // ── GPS LocationCallback (Google Play Services) ──────────────────────────
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
                ax = latestAx, ay = latestAy, az = latestAz,
                hasImu   = hasImu,
                provider = location.provider ?: "fused"
            )

            _observations.tryEmit(observation)
        }
    }

    // ── Fallback LocationListener (android.location API) ─────────────────────
    private val fallbackListener = object : LocationListener {
        override fun onLocationChanged(location: android.location.Location) {
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
                ax = latestAx, ay = latestAy, az = latestAz,
                hasImu   = hasImu,
                provider = location.provider ?: "gps"
            )
            _observations.tryEmit(observation)
        }

        // Other LocationListener callbacks are not needed for the fallback path
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
        if (!wakeLock.isHeld) wakeLock.acquire(30 * 60 * 1000L)

        // Choose the appropriate location provider
        if (hasGms) {
            startWithFused()
        } else {
            startWithLocationManager()
        }

        // IMU — TYPE_LINEAR_ACCELERATION has gravity already removed
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let { sensor ->
            sensorManager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_GAME   // ~20 ms
            )
        }

        isRunning = true
    }

    @SuppressLint("MissingPermission")
    private fun startWithFused() {
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
    }

    @SuppressLint("MissingPermission")
    private fun startWithLocationManager() {
        val provider = when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                LocationManager.NETWORK_PROVIDER
            else -> return
        }
        locationManager.requestLocationUpdates(
            provider, gpsIntervalMs, minDisplacementM, fallbackListener, Looper.getMainLooper()
        )
    }

    override fun stop() {
        if (!isRunning) return
        if (wakeLock.isHeld) wakeLock.release()

        fusedClient.removeLocationUpdates(locationCallback)
        locationManager.removeUpdates(fallbackListener)
        sensorManager.unregisterListener(this)
        hasImu   = false
        isRunning = false
    }
}