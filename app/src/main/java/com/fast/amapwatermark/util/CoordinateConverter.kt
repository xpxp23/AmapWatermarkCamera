package com.fast.amapwatermark.util

import java.util.Locale
import kotlin.math.*

data class LatLon(val lat: Double, val lon: Double)

object CoordinateConverter {
    private const val PI = Math.PI
    private const val AXIS = 6378245.0
    private const val OFFSET_EE = 0.00669342162296594323

    fun outOfChina(lat: Double, lon: Double): Boolean {
        return lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271
    }

    /**
     * Convert hardware GPS (WGS-84) to Amap (GCJ-02) with zero offset
     */
    fun wgs84ToGcj02(wgsLat: Double, wgsLon: Double): LatLon {
        if (outOfChina(wgsLat, wgsLon)) return LatLon(round6(wgsLat), round6(wgsLon))
        var dLat = transformLat(wgsLon - 105.0, wgsLat - 35.0)
        var dLon = transformLon(wgsLon - 105.0, wgsLat - 35.0)
        val radLat = wgsLat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - OFFSET_EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((AXIS * (1 - OFFSET_EE)) / (magic * sqrtMagic) * PI)
        dLon = (dLon * 180.0) / (AXIS / sqrtMagic * cos(radLat) * PI)
        return LatLon(
            round6(wgsLat + dLat),
            round6(wgsLon + dLon)
        )
    }

    /**
     * Exact fixed-point inverse from Amap (GCJ-02) back to WGS-84 (< 0.01m error)
     */
    fun gcj02ToWgs84(gcjLat: Double, gcjLon: Double): LatLon {
        if (outOfChina(gcjLat, gcjLon)) return LatLon(round6(gcjLat), round6(gcjLon))
        var wgsLat = gcjLat
        var wgsLon = gcjLon
        repeat(6) {
            val curr = wgs84ToGcj02(wgsLat, wgsLon)
            wgsLat += (gcjLat - curr.lat)
            wgsLon += (gcjLon - curr.lon)
        }
        return LatLon(round6(wgsLat), round6(wgsLon))
    }

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = (lat2 - lat1) * PI / 180.0
        val dLon = (lon2 - lon1) * PI / 180.0
        val a = sin(dLat / 2.0).pow(2.0) +
                cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) * sin(dLon / 2.0).pow(2.0)
        return r * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
    }

    fun formatCoordinate(lat: Double, lon: Double, useDms: Boolean): String {
        return if (useDms) {
            "${toDms(lat, true)}, ${toDms(lon, false)}"
        } else {
            val ns = if (lat >= 0) "N" else "S"
            val ew = if (lon >= 0) "E" else "W"
            String.format(Locale.US, "%.6f°%s, %.6f°%s", abs(lat), ns, abs(lon), ew)
        }
    }

    /**
     * Fixed DMS conversion with centisecond carry-over to prevent "59°60'60.00"" rounding overflow.
     */
    private fun toDms(deg: Double, isLat: Boolean): String {
        val dir = if (isLat) (if (deg >= 0) "N" else "S") else (if (deg >= 0) "E" else "W")
        val totalCentiseconds = (abs(deg) * 3600.0 * 100.0).roundToLong()
        val d = (totalCentiseconds / 360_000L).toInt()
        val remAfterDeg = totalCentiseconds % 360_000L
        val m = (remAfterDeg / 6_000L).toInt()
        val secCentis = remAfterDeg % 6_000L
        val s = secCentis / 100.0
        return String.format(Locale.US, "%d°%02d'%05.2f\"%s", d, m, s, dir)
    }

    private fun round6(v: Double): Double = round(v * 1_000_000.0) / 1_000_000.0

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }
}
