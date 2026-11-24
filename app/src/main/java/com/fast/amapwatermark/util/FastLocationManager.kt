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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Real-time location & trusted NTP telemetry snapshot (v9.0.0).
 * Cold start uses 0.0/0.0 with hasRealFix=false so fake Beijing coordinates are never emitted.
 */
data class LocationSnapshot(
    val wgsLat: Double = 0.0,
    val wgsLon: Double = 0.0,
    val gcjLat: Double = 0.0,
    val gcjLon: Double = 0.0,
    val rawWgsLat: Double = 0.0,
    val rawWgsLon: Double = 0.0,
    val rawGcjLat: Double = 0.0,
    val rawGcjLon: Double = 0.0,
    val isManualCoord: Boolean = false,
    val accuracyMeters: Float = 0f,
    val altitudeMeters: Double = 0.0,
    val hasAltitude: Boolean = false,
    val azimuthDeg: Int = 0,
    val azimuthText: String = "正北 000°",
    val shortPoi: String = "定位中...",
    val fullAddress: String = "正在获取高德 GCJ-02 实时定位...",
    val nearbyPois: List<String> = listOf("实拍点位", "1号巡检点", "主干道施工段", "隐蔽工程核验点", "测绘控制点A"),
    val hasRealFix: Boolean = false,
    val providerName: String = "INIT",
    val ntpSynced: Boolean = false,
    val ntpOffsetMs: Long = 0L,
    val ntpSource: String = "本机时钟"
) {
    fun trustedCurrentTimeMillis(): Long {
        return System.currentTimeMillis() + ntpOffsetMs
    }

    fun hasValidCoordinates(): Boolean {
        return hasRealFix && (gcjLat != 0.0 || gcjLon != 0.0)
    }
}

class FastLocationManager(context: Context) {
    private val appContext: Context = context.applicationContext
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _locationState = mutableStateOf(LocationSnapshot())
    val locationState: State<LocationSnapshot> get() = _locationState

    private var customPoiOverride: String? = null
    private var customAddressOverride: String? = null
    private var manualCoordLocked: Boolean = false
    private var lastAutoPoi: String? = null
    private var lastAutoAddress: String? = null
    private var lastGeocodedLat: Double = 0.0
    private var lastGeocodedLon: Double = 0.0
    private var lastFixElapsedRealtimeMs: Long = 0L
    private var lastGpsFixElapsedMs: Long = 0L
    private var lastGpsWgsLat: Double = 0.0
    private var lastGpsWgsLon: Double = 0.0
    private var lastAzimuthUpdateMs: Long = 0L
    private var lastStartUpdatesElapsedMs: Long = 0L
    private var geocodeJob: Job? = null
    private var ntpSyncJob: Job? = null
    private var ntpElapsedBaseMs: Long = 0L
    private var ntpEpochBaseMs: Long = 0L

    // 100m grid cache ("%.3f,%.3f") shared for batch reverse geocoding
    private val gridGeocodeCache = ConcurrentHashMap<String, Pair<String, String>>()

    private data class CityAnchor(val name: String, val city: String, val lat: Double, val lon: Double)
    private val cityAnchors = listOf(
        CityAnchor("北京中心区", "北京市朝阳区", 39.908823, 116.397470),
        CityAnchor("上海陆家嘴", "上海市浦东新区", 31.239702, 121.499763),
        CityAnchor("广州珠江新城", "广东省广州市天河区", 23.119293, 113.321206),
        CityAnchor("深圳南山科技园", "广东省深圳市南山区", 22.540503, 113.953412),
        CityAnchor("杭州西湖滨江", "浙江省杭州市西湖区", 30.259244, 120.219375),
        CityAnchor("成都天府新区", "四川省成都市武侯区", 30.657420, 104.065840),
        CityAnchor("武汉光谷片区", "湖北省武汉市洪山区", 30.505600, 114.408600),
        CityAnchor("南京河西新城", "江苏省南京市建邺区", 32.003600, 118.732500),
        CityAnchor("西安高新片区", "陕西省西安市雁塔区", 34.218600, 108.889100),
        CityAnchor("重庆江北嘴", "重庆市江北区", 29.571000, 106.574000)
    )

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
                if (now - lastAzimuthUpdateMs < 550L) return
                try {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientationAngles)
                    val deg = ((Math.toDegrees(orientationAngles[0].toDouble()).roundToInt() + 360) % 360)
                    val prev = _locationState.value.azimuthDeg
                    val diff = abs(deg - prev).let { if (it > 180) 360 - it else it }
                    if (diff >= 5) {
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

    fun formatAzimuthText(deg: Int): String {
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

    fun syncTrustedNetworkTime() {
        if (ntpSyncJob?.isActive == true) return
        ntpSyncJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                queryUdpNtp("ntp.aliyun.com")
                    ?: queryUdpNtp("cn.pool.ntp.org")
                    ?: queryHttpsDateHeader("https://uri.amap.com")
                    ?: queryHttpsDateHeader("https://www.baidu.com")
            }
            if (result != null) {
                val (netEpochMs, sourceName) = result
                val nowElapsed = SystemClock.elapsedRealtime()
                val offset = netEpochMs - System.currentTimeMillis()
                ntpElapsedBaseMs = nowElapsed
                ntpEpochBaseMs = netEpochMs
                _locationState.value = _locationState.value.copy(
                    ntpSynced = true,
                    ntpOffsetMs = offset,
                    ntpSource = sourceName
                )
            }
        }
    }

    fun getTrustedDate(): Date {
        val snap = _locationState.value
        if (snap.ntpSynced && ntpEpochBaseMs > 0L) {
            val delta = SystemClock.elapsedRealtime() - ntpElapsedBaseMs
            return Date(ntpEpochBaseMs + delta)
        }
        return Date(System.currentTimeMillis() + snap.ntpOffsetMs)
    }

    private fun queryUdpNtp(host: String): Pair<Long, String>? {
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 1800
                val addr = InetAddress.getByName(host)
                val buf = ByteArray(48)
                buf[0] = 0x1B
                val req = DatagramPacket(buf, buf.size, addr, 123)
                val t1 = System.currentTimeMillis()
                socket.send(req)
                val resp = DatagramPacket(buf, buf.size)
                socket.receive(resp)
                val t4 = System.currentTimeMillis()

                fun readUint32(offset: Int): Long {
                    val b0 = buf[offset].toLong() and 0xFF
                    val b1 = buf[offset + 1].toLong() and 0xFF
                    val b2 = buf[offset + 2].toLong() and 0xFF
                    val b3 = buf[offset + 3].toLong() and 0xFF
                    return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
                }

                val secs = readUint32(40)
                val frac = readUint32(44)
                if (secs < 2208988800L) return null
                val ntpMs = (secs - 2208988800L) * 1000L + (frac * 1000L) / 0x100000000L
                val rttHalf = (t4 - t1) / 2L
                (ntpMs + rttHalf) to "NTP授时($host)"
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun queryHttpsDateHeader(urlStr: String): Pair<Long, String>? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 2000
                readTimeout = 2000
                instanceFollowRedirects = true
            }
            conn.connect()
            val serverDate = conn.date
            if (serverDate > 1700000000000L) {
                serverDate to "HTTPS授时校核"
            } else null
        } catch (_: Exception) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {}
        }
    }

    @SuppressLint("MissingPermission")
    fun startHighFrequencyUpdates(forceRestart: Boolean = false) {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (!forceRestart && nowElapsed - lastStartUpdatesElapsedMs < 1200L) {
            return
        }
        lastStartUpdatesElapsedMs = nowElapsed
        syncTrustedNetworkTime()
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
                            1000L,
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
            sensorManager?.unregisterListener(sensorListener)
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

    /**
     * Must be called in MainActivity.onDestroy() to prevent coroutine and listener leaks.
     */
    fun release() {
        stopUpdates()
        geocodeJob?.cancel()
        ntpSyncJob?.cancel()
        scope.cancel()
    }

    fun setCustomLocationText(shortPoi: String?, fullAddress: String?) {
        customPoiOverride = shortPoi?.takeIf { it.isNotBlank() }
        customAddressOverride = fullAddress?.takeIf { it.isNotBlank() }
        val current = _locationState.value
        if (!current.hasValidCoordinates()) {
            _locationState.value = current.copy(
                shortPoi = customPoiOverride ?: "定位中...",
                fullAddress = customAddressOverride ?: "正在获取高德 GCJ-02 实时定位..."
            )
            return
        }
        val smartFallback = inferSmartPoiFromCoords(current.gcjLat, current.gcjLon)
        val restoredPoi = customPoiOverride
            ?: lastAutoPoi
            ?: smartFallback.first
        val restoredAddr = customAddressOverride
            ?: lastAutoAddress
            ?: smartFallback.second

        _locationState.value = current.copy(
            shortPoi = restoredPoi,
            fullAddress = restoredAddr,
            nearbyPois = smartFallback.third
        )

        if (customPoiOverride == null || customAddressOverride == null) {
            triggerReverseGeocode(current.wgsLat, current.wgsLon, current.gcjLat, current.gcjLon, force = true)
        }
    }

    /**
     * 更新手动选点坐标并锁定 isManualCoord = true，防止被硬件 GPS 回调覆盖
     */
    fun updateManualCoordinates(newGcjLat: Double, newGcjLon: Double) {
        if (newGcjLat !in -90.0..90.0 || newGcjLon !in -180.0..180.0) return
        manualCoordLocked = true
        val wgs = CoordinateConverter.gcj02ToWgs84(newGcjLat, newGcjLon)
        val curr = _locationState.value
        lastFixElapsedRealtimeMs = SystemClock.elapsedRealtime()
        val smart = inferSmartPoiFromCoords(newGcjLat, newGcjLon)
        _locationState.value = curr.copy(
            wgsLat = wgs.lat,
            wgsLon = wgs.lon,
            gcjLat = newGcjLat,
            gcjLon = newGcjLon,
            isManualCoord = true,
            shortPoi = customPoiOverride ?: smart.first,
            fullAddress = customAddressOverride ?: smart.second,
            nearbyPois = smart.third,
            hasRealFix = true
        )
        triggerReverseGeocode(wgs.lat, wgs.lon, newGcjLat, newGcjLon, force = true)
    }

    /**
     * 恢复手机真实硬件 GPS 坐标（解除手动坐标锁，用于用户点击恢复真实GPS或开启防篡改模式时强制回滚）
     */
    fun restoreRealGpsCoordinates(clearCustomText: Boolean = false) {
        manualCoordLocked = false
        if (clearCustomText) {
            customPoiOverride = null
            customAddressOverride = null
        }
        val curr = _locationState.value
        val hasRaw = (curr.rawGcjLat != 0.0 || curr.rawGcjLon != 0.0)
        if (!hasRaw) {
            _locationState.value = curr.copy(
                isManualCoord = false,
                gcjLat = 0.0,
                gcjLon = 0.0,
                wgsLat = 0.0,
                wgsLon = 0.0,
                hasRealFix = false,
                shortPoi = customPoiOverride ?: "定位中...",
                fullAddress = customAddressOverride ?: "正在获取高德 GCJ-02 实时定位..."
            )
            return
        }
        val smart = inferSmartPoiFromCoords(curr.rawGcjLat, curr.rawGcjLon)
        _locationState.value = curr.copy(
            isManualCoord = false,
            gcjLat = curr.rawGcjLat,
            gcjLon = curr.rawGcjLon,
            wgsLat = curr.rawWgsLat,
            wgsLon = curr.rawWgsLon,
            shortPoi = customPoiOverride ?: lastAutoPoi ?: smart.first,
            fullAddress = customAddressOverride ?: lastAutoAddress ?: smart.second,
            nearbyPois = smart.third
        )
        triggerReverseGeocode(curr.rawWgsLat, curr.rawWgsLon, curr.rawGcjLat, curr.rawGcjLon, force = true)
    }

    suspend fun awaitOptimalLocation(thresholdMeters: Float = 10f, maxWaitMs: Long = 1100L): LocationSnapshot {
        var best = _locationState.value
        if (best.hasRealFix && best.accuracyMeters > 0f && best.accuracyMeters <= thresholdMeters) return best
        val step = 100L
        var waited = 0L
        while (waited < maxWaitMs) {
            delay(step)
            waited += step
            val curr = _locationState.value
            if (!best.hasRealFix && curr.hasRealFix) {
                best = curr
            } else if (curr.hasRealFix && curr.accuracyMeters > 0f && (best.accuracyMeters <= 0f || curr.accuracyMeters < best.accuracyMeters)) {
                best = curr
            }
            if (best.hasRealFix && best.accuracyMeters > 0f && best.accuracyMeters <= thresholdMeters) break
        }
        return best
    }

    /**
     * Batch reverse geocode with 100m grid memory cache ("%.3f,%.3f") to avoid redundant network calls.
     */
    suspend fun reverseGeocodeWithGridCache(
        wgsLat: Double,
        wgsLon: Double,
        gcjLat: Double,
        gcjLon: Double
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        val gridKey = String.format(Locale.US, "%.3f,%.3f", gcjLat, gcjLon)
        gridGeocodeCache[gridKey]?.let { return@withContext it }

        val resolved = withTimeoutOrNull(2400L) {
            resolveReverseGeocodeInternal(wgsLat, wgsLon, gcjLat, gcjLon)
        }
        val pair = if (resolved != null) {
            resolved.first to resolved.second
        } else {
            val smart = inferSmartPoiFromCoords(gcjLat, gcjLon)
            smart.first to smart.second
        }
        if (gridGeocodeCache.size > 256) {
            gridGeocodeCache.clear()
        }
        gridGeocodeCache[gridKey] = pair
        pair
    }

    /**
     * Detect whether an indoor network-only fix on Chinese ROMs is already GCJ-02 to prevent double GCJ-02 shift.
     */
    private fun isLikelyDomesticNetworkGcj02(loc: Location): Boolean {
        if (CoordinateConverter.outOfChina(loc.latitude, loc.longitude)) return false
        val extras = loc.extras
        if (extras != null) {
            val coordType = (extras.getString("coordType")
                ?: extras.getString("coortype")
                ?: extras.getString("networkLocationType")
                ?: "").lowercase(Locale.US)
            if (coordType.contains("gcj")) return true
            if (coordType.contains("wgs")) return false
            if (extras.containsKey("amap") || extras.containsKey("bd_loc")) return true
        }
        // On domestic China ROMs without GMS, NETWORK_PROVIDER is frequently backed by Amap/Baidu NLP returning GCJ-02.
        val mfr = Build.MANUFACTURER.lowercase(Locale.US)
        val isDomesticRom = mfr.contains("huawei") || mfr.contains("honor") ||
                mfr.contains("xiaomi") || mfr.contains("redmi") ||
                mfr.contains("oppo") || mfr.contains("realme") || mfr.contains("oneplus") ||
                mfr.contains("vivo") || mfr.contains("iqoo") || mfr.contains("meizu")
        val isZhLocale = Locale.getDefault().country.equals("CN", ignoreCase = true)
        return isDomesticRom && isZhLocale && loc.provider == LocationManager.NETWORK_PROVIDER
    }

    private fun handleNewLocation(loc: Location) {
        if (loc.latitude == 0.0 && loc.longitude == 0.0) return
        val curr = _locationState.value
        val nowElapsed = SystemClock.elapsedRealtime()
        val ageSinceLastFixMs = nowElapsed - lastFixElapsedRealtimeMs
        val isGps = (loc.provider == LocationManager.GPS_PROVIDER)

        if (isGps && !curr.ntpSynced && loc.time > 1700000000000L) {
            val satOffset = loc.time - System.currentTimeMillis()
            ntpElapsedBaseMs = nowElapsed
            ntpEpochBaseMs = loc.time
            _locationState.value = curr.copy(
                ntpSynced = true,
                ntpOffsetMs = satOffset,
                ntpSource = "GPS卫星原子钟"
            )
        }

        if (!isGps && curr.hasRealFix && curr.accuracyMeters > 0f &&
            (nowElapsed - lastGpsFixElapsedMs) in 0..20_000L &&
            loc.accuracy > curr.accuracyMeters * 1.8f
        ) {
            return
        }
        if (curr.hasRealFix && curr.accuracyMeters > 0f &&
            ageSinceLastFixMs in 0..20_000L &&
            loc.accuracy > curr.accuracyMeters * 2.5f &&
            loc.accuracy > 35f
        ) {
            return
        }

        lastFixElapsedRealtimeMs = nowElapsed
        val rawLat = loc.latitude
        val rawLon = loc.longitude

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
        } else if (isLikelyDomesticNetworkGcj02(loc)) {
            // Indoor network-only fix on domestic ROMs: treat as GCJ-02 directly to avoid double GCJ-02 offset
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

        val acc = if (loc.hasAccuracy()) loc.accuracy else 6.0f
        val alt = if (loc.hasAltitude() && loc.altitude != 0.0) loc.altitude else curr.altitudeMeters
        val hasAlt = (loc.hasAltitude() && loc.altitude != 0.0) || curr.hasAltitude

        // 若当前处于手动坐标锁定模式，仅在后台更新 rawGcjLat / rawWgsLat 硬件锚点，绝不覆盖用户手动坐标！
        if (manualCoordLocked) {
            _locationState.value = _locationState.value.copy(
                rawWgsLat = wgsLat,
                rawWgsLon = wgsLon,
                rawGcjLat = gcjLat,
                rawGcjLon = gcjLon,
                altitudeMeters = alt,
                hasAltitude = hasAlt,
                hasRealFix = true,
                providerName = loc.provider ?: "GPS"
            )
            return
        }

        val smartFallback = inferSmartPoiFromCoords(gcjLat, gcjLon)
        val fallbackPoi = lastAutoPoi ?: smartFallback.first
        val fallbackAddr = lastAutoAddress ?: smartFallback.second

        _locationState.value = _locationState.value.copy(
            wgsLat = wgsLat,
            wgsLon = wgsLon,
            gcjLat = gcjLat,
            gcjLon = gcjLon,
            rawWgsLat = wgsLat,
            rawWgsLon = wgsLon,
            rawGcjLat = gcjLat,
            rawGcjLon = gcjLon,
            isManualCoord = false,
            accuracyMeters = acc,
            altitudeMeters = alt,
            hasAltitude = hasAlt,
            shortPoi = customPoiOverride ?: fallbackPoi,
            fullAddress = customAddressOverride ?: fallbackAddr,
            nearbyPois = smartFallback.third,
            hasRealFix = true,
            providerName = loc.provider ?: "GPS"
        )

        triggerReverseGeocode(wgsLat, wgsLon, gcjLat, gcjLon, force = false)
    }

    private fun inferSmartPoiFromCoords(gcjLat: Double, gcjLon: Double): Triple<String, String, List<String>> {
        var nearest: CityAnchor? = null
        var minDegSq = 999999.0
        for (c in cityAnchors) {
            val dSq = (gcjLat - c.lat).pow(2.0) + (gcjLon - c.lon).pow(2.0)
            if (dSq < minDegSq) {
                minDegSq = dSq
                nearest = c
            }
        }
        if (nearest != null && minDegSq < 0.25) {
            val poi = nearest.name
            val addr = "${nearest.city} · 实测定位网格"
            val candidates = listOf(
                nearest.name,
                "实拍点位",
                "1号巡检点",
                "主干道施工段",
                "隐蔽工程核验点",
                "外业测绘基准桩"
            )
            return Triple(poi, addr, candidates)
        }
        val poi = "实拍点位"
        val addr = "高德GCJ-02坐标 ${CoordinateConverter.formatDecimalHemisphere(gcjLat, gcjLon, 6)}"
        val candidates = listOf(
            "实拍点位",
            "1号巡检点位",
            "隐蔽工程验收点",
            "测绘控制桩A",
            "外业打卡现场"
        )
        return Triple(poi, addr, candidates)
    }

    private fun triggerReverseGeocode(
        wgsLat: Double,
        wgsLon: Double,
        gcjLat: Double,
        gcjLon: Double,
        force: Boolean
    ) {
        if (gcjLat == 0.0 && gcjLon == 0.0) return
        val movedMeters = CoordinateConverter.distanceMeters(gcjLat, gcjLon, lastGeocodedLat, lastGeocodedLon)
        if (!force && movedMeters < 15.0 && lastGeocodedLat != 0.0) return
        lastGeocodedLat = gcjLat
        lastGeocodedLon = gcjLon

        geocodeJob?.cancel()
        geocodeJob = scope.launch {
            val resolved = withTimeoutOrNull(3000L) {
                withContext(Dispatchers.IO) {
                    resolveReverseGeocodeInternal(wgsLat, wgsLon, gcjLat, gcjLon)
                }
            }
            if (resolved != null) {
                val (poi, fullAddr, candidates) = resolved
                lastAutoPoi = poi
                lastAutoAddress = fullAddr
                val gridKey = String.format(Locale.US, "%.3f,%.3f", gcjLat, gcjLon)
                gridGeocodeCache[gridKey] = poi to fullAddr
                val latest = _locationState.value
                _locationState.value = latest.copy(
                    shortPoi = customPoiOverride ?: poi,
                    fullAddress = customAddressOverride ?: fullAddr,
                    nearbyPois = candidates
                )
            }
        }
    }

    private fun resolveReverseGeocodeInternal(
        wgsLat: Double,
        wgsLon: Double,
        gcjLat: Double,
        gcjLon: Double
    ): Triple<String, String, List<String>>? {
        val sysResult = try {
            if (Geocoder.isPresent()) {
                val geocoder = Geocoder(appContext, Locale.CHINA)
                @Suppress("DEPRECATION")
                val list = geocoder.getFromLocation(gcjLat, gcjLon, 3)
                    ?.takeIf { it.isNotEmpty() }
                    ?: geocoder.getFromLocation(wgsLat, wgsLon, 3)
                if (!list.isNullOrEmpty()) {
                    extractPoiAndAddress(list, gcjLat, gcjLon)
                } else null
            } else null
        } catch (_: Exception) {
            null
        }
        if (sysResult != null) return sysResult

        // Domestic-friendly fast fallback (BigDataCloud free reverse geocode, reliable in CN networks)
        val bdcResult = queryBigDataCloudReverseGeocode(wgsLat, wgsLon)
        if (bdcResult != null) return bdcResult

        // Secondary global OSM Nominatim fallback
        return queryWebReverseGeocode(wgsLat, wgsLon)
    }

    private fun queryBigDataCloudReverseGeocode(
        wgsLat: Double,
        wgsLon: Double
    ): Triple<String, String, List<String>>? {
        var conn: HttpURLConnection? = null
        return try {
            val urlStr = String.format(
                Locale.US,
                "https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=%.6f&longitude=%.6f&localityLanguage=zh",
                wgsLat,
                wgsLon
            )
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1600
                readTimeout = 1600
                setRequestProperty("User-Agent", "AmapWatermarkCamera/9.0")
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val province = json.optString("principalSubdivision", "").trim()
                val city = json.optString("city", "").trim()
                val locality = json.optString("locality", "").trim()
                val poi = when {
                    locality.isNotBlank() && locality.length <= 14 -> locality
                    city.isNotBlank() -> city
                    province.isNotBlank() -> province
                    else -> ""
                }
                if (poi.isBlank()) return null
                val fullAddr = buildString {
                    if (province.isNotBlank()) append(province)
                    if (city.isNotBlank() && city != province) append(city)
                    if (locality.isNotBlank() && locality != city) append(locality)
                }.ifBlank { poi }
                val candidates = listOfNotNull(
                    poi,
                    city.takeIf { it.isNotBlank() && it != poi },
                    "实拍点位",
                    "现场巡检核验点"
                ).distinct().take(6)
                Triple(poi, fullAddr, candidates)
            } else null
        } catch (_: Exception) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {}
        }
    }

    private fun queryWebReverseGeocode(
        wgsLat: Double,
        wgsLon: Double
    ): Triple<String, String, List<String>>? {
        var conn: HttpURLConnection? = null
        return try {
            val urlStr = String.format(
                Locale.US,
                "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=%.6f&lon=%.6f&zoom=18&accept-language=zh-CN",
                wgsLat,
                wgsLon
            )
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1800
                readTimeout = 1800
                setRequestProperty("User-Agent", "AmapWatermarkCamera/9.0")
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val name = json.optString("name", "").trim()
                val addrObj = json.optJSONObject("address")
                val road = addrObj?.optString("road", "").orEmpty()
                val suburb = addrObj?.optString("suburb", addrObj.optString("district", "")).orEmpty()
                val city = addrObj?.optString("city", addrObj.optString("state", "")).orEmpty()
                val poi = when {
                    name.isNotBlank() && name.length <= 14 -> name
                    road.isNotBlank() -> road
                    suburb.isNotBlank() -> suburb
                    else -> "实拍点位"
                }
                val fullAddr = buildString {
                    if (city.isNotBlank()) append(city)
                    if (suburb.isNotBlank() && suburb != city) append(suburb)
                    if (road.isNotBlank()) append(road)
                    if (name.isNotBlank() && name != road) append(name)
                }.ifBlank { json.optString("display_name", "").take(36) }
                val candidates = listOfNotNull(
                    "实拍点位",
                    name.takeIf { it.isNotBlank() },
                    road.takeIf { it.isNotBlank() },
                    suburb.takeIf { it.isNotBlank() },
                    "现场巡检核验点"
                ).distinct().take(6)
                Triple(poi, fullAddr, candidates)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {}
        }
    }

    private fun extractPoiAndAddress(
        addrList: List<Address>,
        gcjLat: Double,
        gcjLon: Double
    ): Triple<String, String, List<String>> {
        val addr = addrList[0]
        val feature = addr.featureName?.trim().orEmpty()
        val subLoc = addr.subLocality?.trim().orEmpty()
        val thorough = addr.thoroughfare?.trim().orEmpty()
        val locality = addr.locality?.trim().orEmpty()

        val shortPoi = when {
            feature.isNotEmpty() && !feature.all { it.isDigit() } && feature.length <= 14 -> feature
            thorough.isNotEmpty() -> thorough
            subLoc.isNotEmpty() -> subLoc
            locality.isNotEmpty() -> locality
            else -> "实拍点位"
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
            "高德GCJ-02: ${CoordinateConverter.formatDecimalHemisphere(gcjLat, gcjLon, 6)}"
        }

        val candidates = mutableListOf("实拍点位")
        for (a in addrList) {
            a.featureName?.trim()?.takeIf { it.isNotBlank() && !it.all(Char::isDigit) }?.let { candidates.add(it) }
            a.thoroughfare?.trim()?.takeIf { it.isNotBlank() }?.let { candidates.add(it) }
            a.subLocality?.trim()?.takeIf { it.isNotBlank() }?.let { candidates.add("${it}巡检点") }
        }
        candidates.add("现场隐蔽验收点")

        return Triple(shortPoi, fullLine, candidates.distinct().take(6))
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
