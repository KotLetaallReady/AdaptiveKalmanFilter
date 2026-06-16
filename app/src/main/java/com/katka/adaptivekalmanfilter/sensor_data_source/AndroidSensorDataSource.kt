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
 * ── IMU ───────────────────────────────────────────────────────────────────────
 *   Uses [Sensor.TYPE_LINEAR_ACCELERATION] — gravity already subtracted by the
 *   Android sensor fusion stack. Device-frame [ax, ay, az] values are
 *   snapshotted when a GPS fix arrives.
 *
 *   Uses [Sensor.TYPE_GAME_ROTATION_VECTOR] to obtain the device orientation
 *   matrix R (3×3, row-major). At each GPS callback device-frame acceleration
 *   is rotated into the geographic frame (East / North) via:
 *
 *     axRaw = R[0]·ax + R[1]·ay + R[2]·az   (East-ish, game frame)
 *     ayRaw = R[3]·ax + R[4]·ay + R[5]·az   (North-ish, game frame)
 *
 *   TYPE_GAME_ROTATION_VECTOR does not use a magnetometer, so its "North"
 *   reference is the device orientation at session start — not true geographic
 *   North. A yaw correction angle α is derived from the first reliable GPS
 *   bearing (speed > 1 m/s) and applied as a 2-D rotation:
 *
 *     axGeo =  axRaw·cos α − ayRaw·sin α
 *     ayGeo =  axRaw·sin α + ayRaw·cos α
 *
 *   Before the first bearing fix the correction is identity (α = 0).
 *   This approach is robust to indoor magnetic disturbances while still
 *   aligning the IMU frame with true North as soon as the user starts moving.
 *
 * ── Threading ─────────────────────────────────────────────────────────────────
 *   GPS callbacks arrive on the main looper.
 *   IMU values are written by the SensorManager thread — all fields are
 *   @Volatile. latestRotationMatrix is swapped as a whole reference; the
 *   FloatArray is never mutated after assignment.
 *
 * ── Permissions required ─────────────────────────────────────────────────────
 *   ACCESS_FINE_LOCATION (runtime). No additional permissions needed for
 *   TYPE_LINEAR_ACCELERATION or TYPE_GAME_ROTATION_VECTOR.
 *
 * @param context          Application context (not Activity, to avoid leaks).
 * @param gpsIntervalMs    Minimum time between GPS updates (milliseconds).
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
    private val locationManager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val hasGms: Boolean by lazy {
        try {
            GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        } catch (_: Exception) {
            false
        }
    }

    // ── IMU snapshot — device frame (written by sensor thread) ───────────────
    @Volatile private var latestAx: Double = 0.0
    @Volatile private var latestAy: Double = 0.0
    @Volatile private var latestAz: Double = 0.0
    @Volatile private var hasImu: Boolean = false

    // ── Rotation snapshot (written by sensor thread) ──────────────────────────
    // 3×3 rotation matrix in row-major order from SensorManager.
    // Reference is swapped atomically; array is never mutated after assignment.
    @Volatile private var latestRotationMatrix: FloatArray? = null
    @Volatile private var hasRotation: Boolean = false

    // ── Yaw alignment — game frame → true geographic North ───────────────────
    // TYPE_GAME_ROTATION_VECTOR has no magnetometer, so its azimuth reference
    // is the device orientation at session start. We correct this once using
    // the first reliable GPS bearing (speed > 1 m/s).
    // Stored as (cos α, sin α) to avoid recomputing trigonometry each fix.
    @Volatile private var yawCorrectionCos: Double = 1.0
    @Volatile private var yawCorrectionSin: Double = 0.0
    @Volatile private var isYawAligned: Boolean = false

    // ── State ────────────────────────────────────────────────────────────────
    override var isRunning: Boolean = false
        private set

    // ── GPS LocationCallback (Google Play Services) ──────────────────────────
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return

            // Align yaw on the first GPS fix with a reliable bearing
            if (location.hasBearing() && location.hasSpeed()) {
                tryAlignYaw(location.bearing, location.speed)
            }

            val (axGeo, ayGeo, rotValid) = computeGeoAcceleration()

            val observation = Observation(
                timestamp   = location.time,
                latitude    = location.latitude,
                longitude   = location.longitude,
                accuracy    = location.accuracy,
                altitude    = if (location.hasAltitude()) location.altitude else 0.0,
                speed       = if (location.hasSpeed())    location.speed    else 0f,
                bearing     = if (location.hasBearing())  location.bearing  else 0f,
                hasSpeed    = location.hasSpeed(),
                hasBearing  = location.hasBearing(),
                ax          = latestAx,
                ay          = latestAy,
                az          = latestAz,
                axGeo       = axGeo,
                ayGeo       = ayGeo,
                hasImu      = hasImu,
                hasRotation = rotValid,
                provider    = location.provider ?: "fused"
            )
            _observations.tryEmit(observation)
        }
    }

    // ── Fallback LocationListener (android.location API) ─────────────────────
    private val fallbackListener = object : LocationListener {
        override fun onLocationChanged(location: android.location.Location) {

            if (location.hasBearing() && location.hasSpeed()) {
                tryAlignYaw(location.bearing, location.speed)
            }

            val (axGeo, ayGeo, rotValid) = computeGeoAcceleration()

            val observation = Observation(
                timestamp   = location.time,
                latitude    = location.latitude,
                longitude   = location.longitude,
                accuracy    = location.accuracy,
                altitude    = if (location.hasAltitude()) location.altitude else 0.0,
                speed       = if (location.hasSpeed())    location.speed    else 0f,
                bearing     = if (location.hasBearing())  location.bearing  else 0f,
                hasSpeed    = location.hasSpeed(),
                hasBearing  = location.hasBearing(),
                ax          = latestAx,
                ay          = latestAy,
                az          = latestAz,
                axGeo       = axGeo,
                ayGeo       = ayGeo,
                hasImu      = hasImu,
                hasRotation = rotValid,
                provider    = location.provider ?: "gps"
            )
            _observations.tryEmit(observation)
        }
    }

    // ── SensorEventListener ───────────────────────────────────────────────────
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {

            Sensor.TYPE_LINEAR_ACCELERATION -> {
                // Device frame: x = right, y = forward, z = toward user (face-up)
                // Gravity is already removed by the Android sensor fusion stack
                latestAx = event.values[0].toDouble()
                latestAy = event.values[1].toDouble()
                latestAz = event.values[2].toDouble()
                hasImu   = true
            }

            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                // Obtain the 3×3 rotation matrix R such that v_world = R · v_device
                // Row 0 → East-ish, Row 1 → North-ish, Row 2 → Up (game frame)
                val rotMat = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotMat, event.values)
                latestRotationMatrix = rotMat   // atomic reference swap
                hasRotation = true
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // No action needed
    }

    // ── Yaw alignment ─────────────────────────────────────────────────────────

    /**
     * Computes and stores the yaw correction angle α = gpsBearing − gameAzimuth.
     *
     * Called on every GPS fix until alignment succeeds. Requires:
     *   • a valid rotation matrix from TYPE_GAME_ROTATION_VECTOR
     *   • GPS bearing available
     *   • speed > 1 m/s so the bearing is physically meaningful (not noise)
     *
     * After alignment (cos α, sin α) are used in [computeGeoAcceleration] to
     * rotate game-frame acceleration into true geographic coordinates.
     */
    private fun tryAlignYaw(bearing: Float, speed: Float) {
        if (isYawAligned || speed < 1.0f) return
        val rotMat = latestRotationMatrix ?: return

        // Extract the azimuth the game rotation vector currently reports.
        // This is relative to the phone's initial orientation, not true North.
        val orientationAngles = FloatArray(3)
        SensorManager.getOrientation(rotMat, orientationAngles)
        val gameAzimuthRad = orientationAngles[0].toDouble()   // radians

        // GPS bearing: degrees clockwise from true North → radians
        val gpsBearingRad = Math.toRadians(bearing.toDouble())

        // Correction: rotate game frame to align with true geographic North
        val alpha = gpsBearingRad - gameAzimuthRad
        yawCorrectionCos = kotlin.math.cos(alpha)
        yawCorrectionSin = kotlin.math.sin(alpha)
        isYawAligned = true
    }

    // ── Geo-acceleration ──────────────────────────────────────────────────────

    /**
     * Rotates the latest device-frame linear acceleration into true geographic
     * coordinates (East / North) in two steps:
     *
     *  1. Game-rotation-vector rotation: device frame → game frame
     *       axRaw = R[0]·ax + R[1]·ay + R[2]·az
     *       ayRaw = R[3]·ax + R[4]·ay + R[5]·az
     *
     *  2. Yaw correction: game frame → true geographic frame
     *       axGeo =  axRaw·cos α − ayRaw·sin α
     *       ayGeo =  axRaw·sin α + ayRaw·cos α
     *
     * Before the first GPS bearing fix α = 0 (identity), so step 2 is a no-op.
     *
     * Returns (axGeo, ayGeo, valid). If IMU or rotation data are not yet
     * available, valid = false and axGeo = ayGeo = 0.0 so the caller falls
     * back to u = [0, 0].
     */
    private fun computeGeoAcceleration(): Triple<Double, Double, Boolean> {
        val rotMat = latestRotationMatrix
        if (!hasImu || !hasRotation || rotMat == null) return Triple(0.0, 0.0, false)

        val ax = latestAx; val ay = latestAy; val az = latestAz

        // Step 1 — device frame → game rotation frame
        val axRaw = rotMat[0] * ax + rotMat[1] * ay + rotMat[2] * az
        val ayRaw = rotMat[3] * ax + rotMat[4] * ay + rotMat[5] * az

        // Step 2 — game frame → true geographic frame (2-D rotation by α)
        val axGeo = axRaw * yawCorrectionCos - ayRaw * yawCorrectionSin
        val ayGeo = axRaw * yawCorrectionSin + ayRaw * yawCorrectionCos

        return Triple(axGeo, ayGeo, true)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    override fun start() {
        if (isRunning) return
        if (!wakeLock.isHeld) wakeLock.acquire(30 * 60 * 1000L)

        if (hasGms) startWithFused() else startWithLocationManager()

        // Linear acceleration — gravity already removed by Android sensor fusion
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        }

        // Game rotation vector — magnetometer-free, immune to indoor magnetic
        // disturbances; provides the device → world rotation matrix at ~50 Hz
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)?.let { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        }

        // Reset yaw alignment for the new session
        isYawAligned     = false
        yawCorrectionCos = 1.0
        yawCorrectionSin = 0.0

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

        hasImu               = false
        hasRotation          = false
        latestRotationMatrix = null
        isYawAligned         = false
        yawCorrectionCos     = 1.0
        yawCorrectionSin     = 0.0
        isRunning            = false
    }
}