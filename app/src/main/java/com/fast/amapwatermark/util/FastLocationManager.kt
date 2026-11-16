package com.fast.amapwatermark.util

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

data class LocationSnapshot(
    val wgsLat: Double = 39.907419,
    val wgsLon: Double = 116.391228,
    val gcjLat: Double = 39.908823,
    val gcjLon: Double = 116.397470,
    val accuracyMeters: Float = 9.9f,
    val altitudeMeters: Double = 46.0,
    val hasAltitude: Boolean = false,
    val azimuthDeg: Int = 0,
    val azimuthText: String = "正北 0°",
    val shortPoi: String = "定位中...",
    val fullAddress: String = "正在获取卫星与网络定位...",
    val hasRealFix: Boolean = false,
    val providerName: String = "INIT"
)

class FastLocationManager(private val context: Context) {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _locationState = mutableStateOf(LocationSnapshot())
    val locationState: State<LocationSnapshot> get() = _locationState

    private var customPoiOverride: String? = null
    private var customAddressOverride: String? = null
    private var lastAutoPoi: String? = null
    private var lastAutoAddress: String? = null
    private var lastGeocodedLat: Double = 0.0
    private var lastGeocodedLon: Double = 0.0
    private var lastFixElapsedRealtimeMs: Long = 0L
    private var lastGpsFixElapsedMs: Long = 0L
    private var lastGpsWgsLat: Double = 0.0
    private var lastGpsWgsLon: Double = 0.0
    private var lastAzimuthUpdateMs: Long = 0L
    private var geocodeJob: Job? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            handleNewLocation(loc)
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (event == null) return
            if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastAzimuthUpdateMs < 350L) return
                try {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientationAngles)
                    val deg = ((Math.toDegrees(orientationAngles[0].toDouble()).roundToInt() + 360) % 360)
                    val prev = _locationState.value.azimuthDeg
                    if (abs(deg - prev) >= 3) {
                        lastAzimuthUpdateMs = now
                        _locationState.value = _locationState.value.copy(
                            azimuthDeg = deg,
                            azimuthText = formatAzimuthText(deg)
                        )
                    }
                } catch (_: Exception) {}
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun formatAzimuthText(deg: Int): String {
        val d = ((deg % 360) + 360) % 360
        val dir = when (d) {
            in 0..10, in 350..359 -> "正北"
            in 11..79 -> "东北"
            in 80..100 -> "正东"
            in 101..169 -> "东南"
            in 170..190 -> "正南"
            in 191..259 -> "西南"
            in 260..280 -> "正西"
            else -> "西北"
        }
        return String.format(Locale.CHINA, "%s %03d°", dir, d)
    }

    @SuppressLint("MissingPermission")
    fun startHighFrequencyUpdates() {
        try {
            locationManager.removeUpdates(listener)
            val allProviders = mutableListOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                allProviders.add(LocationManager.FUSED_PROVIDER)
            }

            var bestLastKnown: Location? = null
            for (provider in allProviders.distinct()) {
                try {
                    if (locationManager.isProviderEnabled(provider)) {
                        val last = locationManager.getLastKnownLocation(provider)
                        if (last != null && isBetterLocation(last, bestLastKnown)) {
                            bestLastKnown = last
                        }
                        locationManager.requestLocationUpdates(
                            provider,
                            800L,
                            0f,
                            listener,
                            Looper.getMainLooper()
                        )
                    }
                } catch (_: Exception) {}
            }

            if (bestLastKnown != null) {
                handleNewLocation(bestLastKnown)
            }
        } catch (_: Exception) {}

        try {
            val rotSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            if (rotSensor != null) {
                sensorManager?.registerListener(sensorListener, rotSensor, SensorManager.SENSOR_DELAY_UI)
            }
        } catch (_: Exception) {}
    }

    fun stopUpdates() {
        try {
            locationManager.removeUpdates(listener)
        } catch (_: Exception) {}
        try {
            sensorManager?.unregisterListener(sensorListener)
        } catch (_: Exception) {}
    }

    fun setCustomLocationText(shortPoi: String?, fullAddress: String?) {
        customPoiOverride = shortPoi?.takeIf { it.isNotBlank() }
        customAddressOverride = fullAddress?.takeIf { it.isNotBlank() }
        val current = _locationState.value
        val restoredPoi = customPoiOverride
            ?: lastAutoPoi
            ?: if (current.hasRealFix) "已锁定高精位置 (点此改名)" else "定位中..."
        val restoredAddr = customAddressOverride
            ?: lastAutoAddress
            ?: "高德坐标 ${String.format(Locale.US, "%.6f, %.6f", current.gcjLat, current.gcjLon)}"

        _locationState.value = current.copy(
            shortPoi = restoredPoi,
            fullAddress = restoredAddr
        )

        if (customPoiOverride == null || customAddressOverride == null) {
            triggerReverseGeocode(current.wgsLat, current.wgsLon, current.gcjLat, current.gcjLon, force = true)
        }
    }

    fun updateManualCoordinates(newGcjLat: Double, newGcjLon: Double) {
        val wgs = CoordinateConverter.gcj02ToWgs84(newGcjLat, newGcjLon)
        val curr = _locationState.value
        lastFixElapsedRealtimeMs = SystemClock.elapsedRealtime()
        _locationState.value = curr.copy(
            wgsLat = wgs.lat,
            wgsLon = wgs.lon,
            gcjLat = newGcjLat,
            gcjLon = newGcjLon,
            hasRealFix = true
        )
        triggerReverseGeocode(wgs.lat, wgs.lon, newGcjLat, newGcjLon, force = true)
    }

    /**
     * Zero-shutter-lag async convergence:
     * Default maxWaitMs = 1100L (covers a full 800ms GPS update interval).
     */
    suspend fun awaitOptimalLocation(thresholdMeters: Float = 10f, maxWaitMs: Long = 1100L): LocationSnapshot {
        var best = _locationState.value
        if (best.hasRealFix && best.accuracyMeters <= thresholdMeters) return best
        val step = 100L
        var waited = 0L
        while (waited < maxWaitMs) {
            delay(step)
            waited += step
            val curr = _locationState.value
            if (!best.hasRealFix && curr.hasRealFix) {
                best = curr
            } else if (curr.hasRealFix && curr.accuracyMeters < best.accuracyMeters) {
                best = curr
            }
            if (best.hasRealFix && best.accuracyMeters <= thresholdMeters) break
        }
        return best
    }

    private fun handleNewLocation(loc: Location) {
        if (loc.latitude == 0.0 && loc.longitude == 0.0) return
        val curr = _locationState.value
        val nowElapsed = SystemClock.elapsedRealtime()
        val ageSinceLastFixMs = nowElapsed - lastFixElapsedRealtimeMs
        val isGps = (loc.provider == LocationManager.GPS_PROVIDER)

        // Do not overwrite a recent (< 20s) high-accuracy GPS fix with a coarse network fix
        if (!isGps && curr.hasRealFix &&
            (nowElapsed - lastGpsFixElapsedMs) in 0..20_000L &&
            loc.accuracy > curr.accuracyMeters * 1.8f
        ) {
            return
        }
        if (curr.hasRealFix &&
            ageSinceLastFixMs in 0..20_000L &&
            loc.accuracy > curr.accuracyMeters * 2.5f &&
            loc.accuracy > 35f
        ) {
            return
        }

        lastFixElapsedRealtimeMs = nowElapsed
        val rawLat = loc.latitude
        val rawLon = loc.longitude

        // Smart double-offset guard for Chinese ROM NETWORK_PROVIDER:
        // Hardware GPS is strictly WGS-84. If a network provider reports a coordinate that is already
        // closer to our recent GPS's GCJ-02 than its WGS-84 (indicating the ROM network provider returned GCJ-02),
        // treat it as GCJ-02 and inverse-convert to WGS-84 to prevent double encryption offset!
        val wgsLat: Double
        val wgsLon: Double
        val gcjLat: Double
        val gcjLon: Double

        if (isGps) {
            lastGpsFixElapsedMs = nowElapsed
            lastGpsWgsLat = rawLat
            lastGpsWgsLon = rawLon
            wgsLat = rawLat
            wgsLon = rawLon
            val gcj = CoordinateConverter.wgs84ToGcj02(wgsLat, wgsLon)
            gcjLat = gcj.lat
            gcjLon = gcj.lon
        } else if (lastGpsWgsLat != 0.0 && (nowElapsed - lastGpsFixElapsedMs) < 120_000L) {
            val expectedGcj = CoordinateConverter.wgs84ToGcj02(lastGpsWgsLat, lastGpsWgsLon)
            val distToWgs = CoordinateConverter.distanceMeters(rawLat, rawLon, lastGpsWgsLat, lastGpsWgsLon)
            val distToGcj = CoordinateConverter.distanceMeters(rawLat, rawLon, expectedGcj.lat, expectedGcj.lon)
            if (distToGcj + 80.0 < distToWgs) {
                // Network provider returned GCJ-02 directly
                gcjLat = rawLat
                gcjLon = rawLon
                val inv = CoordinateConverter.gcj02ToWgs84(gcjLat, gcjLon)
                wgsLat = inv.lat
                wgsLon = inv.lon
            } else {
                wgsLat = rawLat
                wgsLon = rawLon
                val gcj = CoordinateConverter.wgs84ToGcj02(wgsLat, wgsLon)
                gcjLat = gcj.lat
                gcjLon = gcj.lon
            }
        } else {
            wgsLat = rawLat
            wgsLon = rawLon
            val gcj = CoordinateConverter.wgs84ToGcj02(wgsLat, wgsLon)
            gcjLat = gcj.lat
            gcjLon = gcj.lon
        }

        val acc = if (loc.hasAccuracy()) loc.accuracy else 6.0f
        val alt = if (loc.hasAltitude() && loc.altitude != 0.0) loc.altitude else curr.altitudeMeters
        val hasAlt = (loc.hasAltitude() && loc.altitude != 0.0) || curr.hasAltitude

        val fallbackPoi = lastAutoPoi ?: if (curr.hasRealFix && curr.shortPoi != "定位中...") {
            curr.shortPoi
        } else {
            "已锁定高精位置 (点此改名)"
        }
        val fallbackAddr = lastAutoAddress ?: if (curr.hasRealFix && !curr.fullAddress.startsWith("正在获取")) {
            curr.fullAddress
        } else {
            "高德坐标 ${String.format(Locale.US, "%.6f, %.6f", gcjLat, gcjLon)}"
        }

        _locationState.value = curr.copy(
            wgsLat = wgsLat,
            wgsLon = wgsLon,
            gcjLat = gcjLat,
            gcjLon = gcjLon,
            accuracyMeters = acc,
            altitudeMeters = alt,
            hasAltitude = hasAlt,
            shortPoi = customPoiOverride ?: fallbackPoi,
            fullAddress = customAddressOverride ?: fallbackAddr,
            hasRealFix = true,
            providerName = loc.provider ?: "GPS"
        )

        triggerReverseGeocode(wgsLat, wgsLon, gcjLat, gcjLon, force = false)
    }

    private fun triggerReverseGeocode(
        wgsLat: Double,
        wgsLon: Double,
        gcjLat: Double,
        gcjLon: Double,
        force: Boolean
    ) {
        val movedMeters = CoordinateConverter.distanceMeters(gcjLat, gcjLon, lastGeocodedLat, lastGeocodedLon)
        if (!force && movedMeters < 15.0 && lastGeocodedLat != 0.0) return
        lastGeocodedLat = gcjLat
        lastGeocodedLon = gcjLon

        geocodeJob?.cancel()
        geocodeJob = scope.launch {
            val resolved = withTimeoutOrNull(2500L) {
                withContext(Dispatchers.IO) {
                    try {
                        if (!Geocoder.isPresent()) return@withContext null
                        val geocoder = Geocoder(context, Locale.CHINA)
                        // Query with GCJ-02 first for Chinese ROM domestic map services, fallback to WGS-84
                        @Suppress("DEPRECATION")
                        val list = geocoder.getFromLocation(gcjLat, gcjLon, 1)
                            ?.takeIf { it.isNotEmpty() }
                            ?: geocoder.getFromLocation(wgsLat, wgsLon, 1)
                        if (!list.isNullOrEmpty()) {
                            extractPoiAndAddress(list[0], gcjLat, gcjLon)
                        } else null
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            if (resolved != null) {
                val (poi, fullAddr) = resolved
                lastAutoPoi = poi
                lastAutoAddress = fullAddr
                val latest = _locationState.value
                _locationState.value = latest.copy(
                    shortPoi = customPoiOverride ?: poi,
                    fullAddress = customAddressOverride ?: fullAddr
                )
            }
        }
    }

    private fun extractPoiAndAddress(addr: Address, gcjLat: Double, gcjLon: Double): Pair<String, String> {
        val feature = addr.featureName?.trim().orEmpty()
        val subLoc = addr.subLocality?.trim().orEmpty()
        val thorough = addr.thoroughfare?.trim().orEmpty()
        val locality = addr.locality?.trim().orEmpty()

        val shortPoi = when {
            feature.isNotEmpty() && !feature.all { it.isDigit() } && feature.length <= 14 -> feature
            thorough.isNotEmpty() -> thorough
            subLoc.isNotEmpty() -> subLoc
            locality.isNotEmpty() -> locality
            else -> String.format(Locale.US, "%.4f°N,%.4f°E", gcjLat, gcjLon)
        }

        val fullLine = addr.getAddressLine(0)?.trim().orEmpty().ifEmpty {
            buildString {
                if (addr.adminArea != null) append(addr.adminArea)
                if (locality.isNotEmpty() && locality != addr.adminArea) append(locality)
                if (subLoc.isNotEmpty()) append(subLoc)
                if (thorough.isNotEmpty()) append(thorough)
                if (feature.isNotEmpty() && feature != thorough) append(feature)
            }
        }.ifEmpty {
            "高德GCJ-02: ${String.format(Locale.US, "%.6f, %.6f", gcjLat, gcjLon)}"
        }

        return shortPoi to fullLine
    }

    private fun isBetterLocation(location: Location, currentBest: Location?): Boolean {
        if (currentBest == null) return true
        val timeDelta = location.time - currentBest.time
        val isSignificantlyNewer = timeDelta > 60_000L
        val isSignificantlyOlder = timeDelta < -60_000L
        if (isSignificantlyNewer) return true
        if (isSignificantlyOlder) return false
        val accuracyDelta = (location.accuracy - currentBest.accuracy).toInt()
        return accuracyDelta <= 0
    }
}
