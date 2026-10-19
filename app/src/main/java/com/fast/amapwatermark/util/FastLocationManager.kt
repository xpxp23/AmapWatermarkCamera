package com.fast.amapwatermark.util

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import kotlinx.coroutines.delay
import java.util.Locale

data class LocationSnapshot(
    val wgsLat: Double,
    val wgsLon: Double,
    val gcjLat: Double,
    val gcjLon: Double,
    val accuracyMeters: Float,
    val addressText: String
)

class FastLocationManager(private val context: Context) {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Volatile
    var latestLocation: LocationSnapshot = LocationSnapshot(
        wgsLat = 39.907419,
        wgsLon = 116.391228,
        gcjLat = 39.908823,
        gcjLon = 116.397470,
        accuracyMeters = 2.8f,
        addressText = "正在获取详细地址..."
    )
        private set

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            updateFromRawLocation(loc)
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    @SuppressLint("MissingPermission")
    fun startHighFrequencyUpdates() {
        try {
            val providers = locationManager.getProviders(true)
            for (p in providers) {
                locationManager.getLastKnownLocation(p)?.let { updateFromRawLocation(it) }
                locationManager.requestLocationUpdates(p, 500L, 0f, listener)
            }
        } catch (_: Exception) {}
    }

    fun stopUpdates() {
        try {
            locationManager.removeUpdates(listener)
        } catch (_: Exception) {}
    }

    /**
     * Zero-shutter-lag async convergence:
     * If accuracy > thresholdMeters when shutter is pressed, sample for up to 1200ms
     * and pick the sharpest GPS fix before compositing the watermark.
     */
    suspend fun awaitOptimalLocation(thresholdMeters: Float = 10f, maxWaitMs: Long = 1200L): LocationSnapshot {
        var best = latestLocation
        if (best.accuracyMeters <= thresholdMeters) return best
        val step = 150L
        var waited = 0L
        while (waited < maxWaitMs) {
            delay(step)
            waited += step
            val curr = latestLocation
            if (curr.accuracyMeters < best.accuracyMeters) {
                best = curr
            }
            if (best.accuracyMeters <= thresholdMeters) break
        }
        return best
    }

    private fun updateFromRawLocation(loc: Location) {
        val wgsLat = loc.latitude
        val wgsLon = loc.longitude
        val gcj = CoordinateConverter.wgs84ToGcj02(wgsLat, wgsLon)
        val addr = resolveAddress(wgsLat, wgsLon)
        latestLocation = LocationSnapshot(
            wgsLat = wgsLat,
            wgsLon = wgsLon,
            gcjLat = gcj.lat,
            gcjLon = gcj.lon,
            accuracyMeters = loc.accuracy,
            addressText = addr
        )
    }

    private fun resolveAddress(lat: Double, lon: Double): String {
        return try {
            val geocoder = Geocoder(context, Locale.CHINA)
            @Suppress("DEPRECATION")
            val list = geocoder.getFromLocation(lat, lon, 1)
            if (!list.isNullOrEmpty()) {
                list[0].getAddressLine(0) ?: ""
            } else ""
        } catch (_: Exception) {
            ""
        }
    }
}
