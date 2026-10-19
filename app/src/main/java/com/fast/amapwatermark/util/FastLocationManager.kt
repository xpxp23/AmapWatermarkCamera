package com.fast.amapwatermark.util

import android.annotation.SuppressLint
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class LocationSnapshot(
    val wgsLat: Double = 39.907419,
    val wgsLon: Double = 116.391228,
    val gcjLat: Double = 39.908823,
    val gcjLon: Double = 116.397470,
    val accuracyMeters: Float = 9.9f,
    val shortPoi: String = "定位中...",
    val fullAddress: String = "正在获取卫星与网络定位...",
    val hasRealFix: Boolean = false,
    val providerName: String = "INIT"
)

class FastLocationManager(private val context: Context) {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _locationState = mutableStateOf(LocationSnapshot())
    val locationState: State<LocationSnapshot> get() = _locationState

    private var customPoiOverride: String? = null
    private var customAddressOverride: String? = null
    private var lastGeocodedLat: Double = 0.0
    private var lastGeocodedLon: Double = 0.0

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            handleNewLocation(loc)
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    fun startHighFrequencyUpdates() {
        try {
            val allProviders = mutableListOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                allProviders.add(LocationManager.FUSED_PROVIDER)
            }

            var bestLastKnown: Location? = null
            for (provider in allProviders.distinct()) {
                try {
                    if (locationManager.isProviderEnabled(provider) || provider == LocationManager.PASSIVE_PROVIDER) {
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
    }

    fun stopUpdates() {
        try {
            locationManager.removeUpdates(listener)
        } catch (_: Exception) {}
    }

    fun setCustomLocationText(shortPoi: String?, fullAddress: String?) {
        customPoiOverride = shortPoi?.takeIf { it.isNotBlank() }
        customAddressOverride = fullAddress?.takeIf { it.isNotBlank() }
        val current = _locationState.value
        _locationState.value = current.copy(
            shortPoi = customPoiOverride ?: current.shortPoi,
            fullAddress = customAddressOverride ?: current.fullAddress
        )
    }

    fun updateManualCoordinates(newGcjLat: Double, newGcjLon: Double) {
        val wgs = CoordinateConverter.gcj02ToWgs84(newGcjLat, newGcjLon)
        val curr = _locationState.value
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
     * If accuracy > thresholdMeters when shutter is pressed, sample for up to maxWaitMs
     * and pick the sharpest GPS fix before compositing the watermark.
     */
    suspend fun awaitOptimalLocation(thresholdMeters: Float = 10f, maxWaitMs: Long = 1000L): LocationSnapshot {
        var best = _locationState.value
        if (best.hasRealFix && best.accuracyMeters <= thresholdMeters) return best
        val step = 120L
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
        // Do not overwrite a high-accuracy GPS fix with a coarse 500m network fix if recent
        if (curr.hasRealFix && loc.accuracy > curr.accuracyMeters * 2.5f && loc.accuracy > 35f) {
            return
        }

        val wgsLat = loc.latitude
        val wgsLon = loc.longitude
        val gcj = CoordinateConverter.wgs84ToGcj02(wgsLat, wgsLon)
        val acc = if (loc.hasAccuracy()) loc.accuracy else 6.0f

        val fallbackPoi = if (curr.hasRealFix && curr.shortPoi != "定位中...") {
            curr.shortPoi
        } else {
            "已锁定高精位置 (点此改名)"
        }
        val fallbackAddr = if (curr.hasRealFix && !curr.fullAddress.startsWith("正在获取")) {
            curr.fullAddress
        } else {
            "高德坐标 ${String.format(Locale.US, "%.6f, %.6f", gcj.lat, gcj.lon)}"
        }

        _locationState.value = LocationSnapshot(
            wgsLat = wgsLat,
            wgsLon = wgsLon,
            gcjLat = gcj.lat,
            gcjLon = gcj.lon,
            accuracyMeters = acc,
            shortPoi = customPoiOverride ?: fallbackPoi,
            fullAddress = customAddressOverride ?: fallbackAddr,
            hasRealFix = true,
            providerName = loc.provider ?: "GPS"
        )

        triggerReverseGeocode(wgsLat, wgsLon, gcj.lat, gcj.lon, force = false)
    }

    private fun triggerReverseGeocode(
        wgsLat: Double,
        wgsLon: Double,
        gcjLat: Double,
        gcjLon: Double,
        force: Boolean
    ) {
        val movedMeters = CoordinateConverter.distanceMeters(wgsLat, wgsLon, lastGeocodedLat, lastGeocodedLon)
        if (!force && movedMeters < 15.0 && lastGeocodedLat != 0.0) return
        lastGeocodedLat = wgsLat
        lastGeocodedLon = wgsLon

        scope.launch {
            val resolved = withContext(Dispatchers.IO) {
                try {
                    val geocoder = Geocoder(context, Locale.CHINA)
                    @Suppress("DEPRECATION")
                    val list = geocoder.getFromLocation(wgsLat, wgsLon, 1)
                    if (!list.isNullOrEmpty()) {
                        extractPoiAndAddress(list[0], gcjLat, gcjLon)
                    } else null
                } catch (_: Exception) {
                    null
                }
            }
            if (resolved != null) {
                val (poi, fullAddr) = resolved
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
