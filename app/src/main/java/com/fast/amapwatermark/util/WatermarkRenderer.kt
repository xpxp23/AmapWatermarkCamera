package com.fast.amapwatermark.util

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Watermark configuration stored alongside each captured photo's clean base image.
 * Supports unlimited non-destructive re-rendering in both Portrait and Landscape,
 * plus v7.0.0 High-Def Vector Mini-Map PiP and Anti-Tamper Cryptographic Verification.
 */
data class WatermarkConfig(
    var styleTemplate: Int = 0,
    var customTitle: String = "现场点位核验",
    var coordSystem: String = "WGS-84", // "WGS-84", "CGCS2000", or "GCJ-02"
    var useDms: Boolean = false,
    var gcjLat: Double = 39.908823,
    var gcjLon: Double = 116.397470,
    var wgsLat: Double = 39.907419,
    var wgsLon: Double = 116.391228,
    var origGcjLat: Double = 39.908823,
    var origGcjLon: Double = 116.397470,
    var accuracyMeters: Float = 2.8f,
    var altitudeMeters: Double = 46.0,
    var azimuthDeg: Int = 0,
    var azimuthText: String = "正北 000°",
    var shortPoi: String = "定位中...",
    var address: String = "",
    var timeHm: String = "19:09",
    var dateWeek: String = "2026-11-17 星期二",
    var timestamp: String = "2026-11-17 19:09:40",
    var note: String = "",
    var showTime: Boolean = true,
    var showLocation: Boolean = true,
    var showCoords: Boolean = true,
    var showCoordTag: Boolean = true,
    var showAccuracy: Boolean = true,
    var showAltitude: Boolean = false,
    var showAzimuth: Boolean = false,
    var showQrCode: Boolean = true,
    var showAddress: Boolean = false,
    var showNote: Boolean = true,
    var showMiniMap: Boolean = true,
    var miniMapPosition: Int = 0, // 0: 卡片左侧内嵌 (160x160), 1: 画面左上角独立悬浮
    var antiTamperMode: Boolean = false,
    var ntpSynced: Boolean = false,
    var antiTamperHash: String = "",
    var bottomRight: Boolean = false,
    var filterIndex: Int = 0,
    var bgAlpha: Int = 185,
    var fontScale: Float = 1.0f, // 0.85f (小), 1.0f (标准), 1.15f (大)
    var accentColorIndex: Int = 0, // 0: 天际蓝, 1: 翡翠绿, 2: 琥珀金, 3: 珊瑚红
    var mediaStoreUri: String = ""
) {
    fun ensureAntiTamperSignature(): String {
        if (antiTamperHash.isNotBlank() && antiTamperHash.length >= 12) return antiTamperHash
        val computed = QrCodeGenerator.computeAntiTamperHash(gcjLat, gcjLon, timestamp, shortPoi, note)
        antiTamperHash = computed
        return computed
    }

    fun isSignatureValid(): Boolean {
        if (!antiTamperMode || antiTamperHash.isBlank()) return false
        return QrCodeGenerator.verifyAntiTamperHash(gcjLat, gcjLon, timestamp, shortPoi, antiTamperHash, note)
    }

    fun toJson(): String {
        return JSONObject().apply {
            put("styleTemplate", styleTemplate)
            put("customTitle", customTitle)
            put("coordSystem", coordSystem)
            put("useDms", useDms)
            put("gcjLat", gcjLat)
            put("gcjLon", gcjLon)
            put("wgsLat", wgsLat)
            put("wgsLon", wgsLon)
            put("origGcjLat", origGcjLat)
            put("origGcjLon", origGcjLon)
            put("accuracyMeters", accuracyMeters.toDouble())
            put("altitudeMeters", altitudeMeters)
            put("azimuthDeg", azimuthDeg)
            put("azimuthText", azimuthText)
            put("shortPoi", shortPoi)
            put("address", address)
            put("timeHm", timeHm)
            put("dateWeek", dateWeek)
            put("timestamp", timestamp)
            put("note", note)
            put("showTime", showTime)
            put("showLocation", showLocation)
            put("showCoords", showCoords)
            put("showCoordTag", showCoordTag)
            put("showAccuracy", showAccuracy)
            put("showAltitude", showAltitude)
            put("showAzimuth", showAzimuth)
            put("showQrCode", showQrCode)
            put("showAddress", showAddress)
            put("showNote", showNote)
            put("showMiniMap", showMiniMap)
            put("miniMapPosition", miniMapPosition)
            put("antiTamperMode", antiTamperMode)
            put("ntpSynced", ntpSynced)
            put("antiTamperHash", antiTamperHash)
            put("bottomRight", bottomRight)
            put("filterIndex", filterIndex)
            put("bgAlpha", bgAlpha)
            put("fontScale", fontScale.toDouble())
            put("accentColorIndex", accentColorIndex)
            put("mediaStoreUri", mediaStoreUri)
        }.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): WatermarkConfig {
            val o = JSONObject(jsonStr)
            return WatermarkConfig(
                styleTemplate = o.optInt("styleTemplate", 0),
                customTitle = o.optString("customTitle", "现场点位核验"),
                coordSystem = o.optString("coordSystem", "WGS-84"),
                useDms = o.optBoolean("useDms", false),
                gcjLat = o.optDouble("gcjLat", 39.908823),
                gcjLon = o.optDouble("gcjLon", 116.397470),
                wgsLat = o.optDouble("wgsLat", 39.907419),
                wgsLon = o.optDouble("wgsLon", 116.391228),
                origGcjLat = o.optDouble("origGcjLat", 39.908823),
                origGcjLon = o.optDouble("origGcjLon", 116.397470),
                accuracyMeters = o.optDouble("accuracyMeters", 2.8).toFloat(),
                altitudeMeters = o.optDouble("altitudeMeters", 46.0),
                azimuthDeg = o.optInt("azimuthDeg", 0),
                azimuthText = o.optString("azimuthText", "正北 000°"),
                shortPoi = o.optString("shortPoi", "实拍点位"),
                address = o.optString("address", ""),
                timeHm = o.optString("timeHm", "19:09"),
                dateWeek = o.optString("dateWeek", "2026-11-17 星期二"),
                timestamp = o.optString("timestamp", "2026-11-17 19:09:40"),
                note = o.optString("note", ""),
                showTime = o.optBoolean("showTime", true),
                showLocation = o.optBoolean("showLocation", true),
                showCoords = o.optBoolean("showCoords", true),
                showCoordTag = o.optBoolean("showCoordTag", true),
                showAccuracy = o.optBoolean("showAccuracy", true),
                showAltitude = o.optBoolean("showAltitude", false),
                showAzimuth = o.optBoolean("showAzimuth", false),
                showQrCode = o.optBoolean("showQrCode", true),
                showAddress = o.optBoolean("showAddress", false),
                showNote = o.optBoolean("showNote", true),
                showMiniMap = o.optBoolean("showMiniMap", true),
                miniMapPosition = o.optInt("miniMapPosition", 0),
                antiTamperMode = o.optBoolean("antiTamperMode", false),
                ntpSynced = o.optBoolean("ntpSynced", false),
                antiTamperHash = o.optString("antiTamperHash", ""),
                bottomRight = o.optBoolean("bottomRight", false),
                filterIndex = o.optInt("filterIndex", 0),
                bgAlpha = o.optInt("bgAlpha", 185),
                fontScale = o.optDouble("fontScale", 1.0).toFloat(),
                accentColorIndex = o.optInt("accentColorIndex", 0),
                mediaStoreUri = o.optString("mediaStoreUri", "")
            )
        }

        fun currentTimeStrings(date: Date = Date()): Triple<String, String, String> {
            val hm = SimpleDateFormat("HH:mm", Locale.CHINA).format(date)
            val datePart = SimpleDateFormat("yyyy-MM-dd EEEE", Locale.CHINA).format(date)
            val fullTs = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(date)
            return Triple(hm, datePart, fullTs)
        }

        fun customTimeStrings(datePartStr: String, hmStr: String, secStr: String): Triple<String, String, String> {
            val cleanDate = datePartStr.trim().ifBlank { "2026-11-17" }
            val cleanHm = hmStr.trim().ifBlank { "09:20" }
            val secInt = (secStr.trim().toIntOrNull() ?: 0).coerceIn(0, 59)
            val cleanSec = String.format(Locale.US, "%02d", secInt)
            val fullCandidate = "$cleanDate $cleanHm:$cleanSec"
            return try {
                val parsed = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).parse(fullCandidate)
                if (parsed != null) currentTimeStrings(parsed) else Triple(cleanHm, "$cleanDate 星期二", fullCandidate)
            } catch (_: Exception) {
                Triple(cleanHm, "$cleanDate 星期二", fullCandidate)
            }
        }

        fun parseExifDateStrings(rawExifDate: String?): Triple<String, String, String> {
            if (rawExifDate.isNullOrBlank()) return currentTimeStrings()
            return try {
                val clean = rawExifDate.trim()
                val pattern = if (clean.length >= 10 && clean[4] == ':') {
                    "yyyy:MM:dd HH:mm:ss"
                } else {
                    "yyyy-MM-dd HH:mm:ss"
                }
                val parsed = SimpleDateFormat(pattern, Locale.CHINA).parse(clean)
                if (parsed != null) currentTimeStrings(parsed) else currentTimeStrings()
            } catch (_: Exception) {
                currentTimeStrings()
            }
        }
    }
}

data class PhotoEntry(
    val id: String,
    val cleanFile: File,
    val watermarkedFile: File,
    val metaFile: File,
    var config: WatermarkConfig
)

data class SystemCameraPhoto(
    val mediaId: Long,
    val contentUri: Uri,
    val displayName: String,
    val dateTakenStr: String,
    val hasGps: Boolean,
    val gcjLat: Double,
    val gcjLon: Double,
    val wgsLat: Double,
    val wgsLon: Double,
    val labelText: String,
    val antiTamperVerified: Boolean = false,
    val antiTamperHash: String = ""
)

object BitmapCacheManager {
    private val maxMem = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cache = object : LruCache<String, Bitmap>(maxMem / 8) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun loadThumbnail(file: File, reqWidth: Int = 256, reqHeight: Int = 256): Bitmap? {
        if (!file.exists()) return null
        val key = "${file.absolutePath}_${file.lastModified()}_${reqWidth}x${reqHeight}"
        cache.get(key)?.let { if (!it.isRecycled) return it }

        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            opts.inSampleSize = calculateInSampleSize(opts, reqWidth, reqHeight)
            opts.inJustDecodeBounds = false
            opts.inPreferredConfig = Bitmap.Config.RGB_565
            val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
            if (bmp != null) cache.put(key, bmp)
            bmp
        } catch (_: Exception) {
            null
        }
    }

    fun loadDisplayBitmap(file: File, maxDim: Int = 1440): Bitmap? {
        if (!file.exists()) return null
        val key = "disp_${file.absolutePath}_${file.lastModified()}_$maxDim"
        cache.get(key)?.let { if (!it.isRecycled) return it }

        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            opts.inSampleSize = calculateInSampleSize(opts, maxDim, maxDim)
            opts.inJustDecodeBounds = false
            val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
            if (bmp != null) cache.put(key, bmp)
            bmp
        } catch (_: Exception) {
            null
        }
    }

    fun decodeFullResolutionCleanBitmap(file: File, maxDimCap: Int = 3200): Bitmap? {
        if (!file.exists()) return null
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            var sample = 1
            while (max(opts.outWidth, opts.outHeight) / sample > maxDimCap) {
                sample *= 2
            }
            opts.inSampleSize = sample
            opts.inJustDecodeBounds = false
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (_: Exception) {
            null
        }
    }

    fun loadUriThumbnail(context: Context, uri: Uri, reqSize: Int = 256): Bitmap? {
        val key = "uri_${uri}_$reqSize"
        cache.get(key)?.let { if (!it.isRecycled) return it }
        return try {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, boundsOpts)
            }
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(boundsOpts, reqSize, reqSize)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bmp = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            }
            if (bmp != null) cache.put(key, bmp)
            bmp
        } catch (_: Exception) {
            null
        }
    }

    fun invalidate(file: File) {
        val pathPrefix = file.absolutePath
        val keysToRemove = cache.snapshot().keys.filter { it.contains(pathPrefix) }
        for (k in keysToRemove) {
            cache.remove(k)
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }
}

object WatermarkRenderer {

    val FILTER_NAMES = listOf("原图", "鲜明", "暖阳", "清冷", "黑白")
    val STYLE_NAMES = listOf(
        "经典大字时间",
        "工程巡检卡片",
        "经纬网格测绘",
        "极简胶片底栏",
        "外业标准台账"
    )
    val STYLE_SHORT_NAMES = listOf(
        "大字时间",
        "巡检卡片",
        "网格测绘",
        "胶片底栏",
        "标准台账"
    )
    val ACCENT_COLORS = listOf(
        Color.rgb(56, 189, 248),  // 天际蓝
        Color.rgb(52, 211, 153),  // 翡翠绿
        Color.rgb(251, 191, 36),  // 琥珀金
        Color.rgb(248, 113, 113)  // 珊瑚红
    )

    private val TYPEFACE_MONO_BOLD: Typeface by lazy { Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
    private val TYPEFACE_MONO_NORMAL: Typeface by lazy { Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL) }
    private val TYPEFACE_SANS_LIGHT: Typeface by lazy { Typeface.create("sans-serif-light", Typeface.NORMAL) }
    private val TYPEFACE_DEFAULT_BOLD: Typeface by lazy { Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }

    private class PaintPool {
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val monoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = TYPEFACE_MONO_BOLD }
        val rectF = RectF()
        val path = Path()
    }
    private val paintPool = object : ThreadLocal<PaintPool>() {
        override fun initialValue(): PaintPool = PaintPool()
    }

    fun rotateBitmapIfNeeded(source: Bitmap, rotationDegrees: Int): Bitmap {
        val norm = ((rotationDegrees % 360) + 360) % 360
        if (norm == 0) return source
        val matrix = Matrix().apply { postRotate(norm.toFloat()) }
        return try {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        } catch (_: Exception) {
            source
        }
    }

    fun readExifRotationDegrees(file: File): Int {
        return try {
            val exif = ExifInterface(file.absolutePath)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (_: Exception) {
            0
        }
    }

    fun getAndroidColorMatrix(filterIndex: Int): ColorMatrix? {
        return when (filterIndex) {
            1 -> ColorMatrix(
                floatArrayOf(
                    1.18f, 0f, 0f, 0f, -10f,
                    0f, 1.18f, 0f, 0f, -10f,
                    0f, 0f, 1.18f, 0f, -10f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            2 -> ColorMatrix(
                floatArrayOf(
                    1.12f, 0f, 0f, 0f, 12f,
                    0f, 1.05f, 0f, 0f, 6f,
                    0f, 0f, 0.92f, 0f, -8f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            3 -> ColorMatrix(
                floatArrayOf(
                    0.92f, 0f, 0f, 0f, -6f,
                    0f, 1.04f, 0f, 0f, 4f,
                    0f, 0f, 1.16f, 0f, 14f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            4 -> ColorMatrix().apply { setSaturation(0f) }
            else -> null
        }
    }

    fun renderWatermarkedBitmap(cleanBitmap: Bitmap, cfg: WatermarkConfig): Bitmap {
        if (cfg.antiTamperMode) {
            cfg.ensureAntiTamperSignature()
        }
        val out = Bitmap.createBitmap(cleanBitmap.width, cleanBitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        getAndroidColorMatrix(cfg.filterIndex)?.let { cm ->
            basePaint.colorFilter = ColorMatrixColorFilter(cm)
        }
        canvas.drawBitmap(cleanBitmap, 0f, 0f, basePaint)

        drawWatermarkOverlay(canvas, out.width, out.height, cfg)
        return out
    }

    fun drawWatermarkOverlay(canvas: Canvas, width: Int, height: Int, cfg: WatermarkConfig, isLivePreview: Boolean = false) {
        val isLandscape = width > height
        val orientFactor = if (isLandscape) 0.86f else 1.0f
        val baseScale = (min(width, height) / 1080f) * orientFactor * cfg.fontScale.coerceIn(0.78f, 1.25f)

        if (cfg.antiTamperMode && cfg.antiTamperHash.isBlank()) {
            cfg.ensureAntiTamperSignature()
        }

        // Top-left floating Mini-Map mode (when miniMapPosition == 1 and template is 1 or 2)
        if (cfg.showMiniMap && cfg.miniMapPosition == 1 && cfg.styleTemplate in listOf(1, 2)) {
            val mapSz = 160f * baseScale
            val margin = 28f * baseScale
            val topY = margin + 52f * baseScale
            drawVectorMiniMapBox(canvas, margin, topY, mapSz, baseScale, cfg)
        }

        when (cfg.styleTemplate) {
            0 -> drawStyle0CenteredBigTime(canvas, width, height, baseScale, isLandscape, cfg)
            1 -> drawStyle1EngineeringCard(canvas, width, height, baseScale, isLandscape, cfg)
            2 -> drawStyle2GisSurveyGrid(canvas, width, height, baseScale, isLandscape, cfg)
            3 -> drawStyle3MinimalistFilmBar(canvas, width, height, baseScale, isLandscape, cfg)
            4 -> drawStyle4LedgerTable(canvas, width, height, baseScale, isLandscape, cfg)
            else -> drawStyle0CenteredBigTime(canvas, width, height, baseScale, isLandscape, cfg)
        }

        if (cfg.showQrCode) {
            drawAmapQrBadge(canvas, width, height, baseScale, isLandscape, cfg, isLivePreview)
        }
    }

    /**
     * Draw 160x160px High-Definition Vector Amap Mini-Map PiP (Picture-in-Picture)
     * with surrounding road network, greenbelt/waterways, coordinate crosshair, azimuth view cone, and red pin!
     */
    private fun drawVectorMiniMapBox(
        canvas: Canvas,
        boxX: Float,
        boxY: Float,
        boxSize: Float,
        scale: Float,
        cfg: WatermarkConfig
    ) {
        val pool = paintPool.get()!!
        val cornerR = 14f * scale

        canvas.save()
        pool.path.reset()
        pool.rectF.set(boxX, boxY, boxX + boxSize, boxY + boxSize)
        pool.path.addRoundRect(pool.rectF, cornerR, cornerR, Path.Direction.CW)
        canvas.clipPath(pool.path)

        // 1. Dark GIS cartographic base fill
        pool.fillPaint.shader = null
        pool.fillPaint.color = Color.rgb(12, 22, 41)
        canvas.drawRect(boxX, boxY, boxX + boxSize, boxY + boxSize, pool.fillPaint)

        // Deterministic coordinate-driven road & terrain offset
        val latSeed = (abs(cfg.gcjLat * 10000.0).toLong() % 100L).toFloat()
        val lonSeed = (abs(cfg.gcjLon * 10000.0).toLong() % 100L).toFloat()
        val shiftX = ((lonSeed - 50f) / 100f) * (boxSize * 0.22f)
        val shiftY = ((latSeed - 50f) / 100f) * (boxSize * 0.22f)

        // 2. Greenbelt park & river waterway polygon
        pool.fillPaint.color = Color.argb(36, 16, 185, 129)
        pool.rectF.set(
            boxX + 8f * scale + shiftX,
            boxY + 10f * scale + shiftY,
            boxX + 8f * scale + shiftX + boxSize * 0.35f,
            boxY + 10f * scale + shiftY + boxSize * 0.28f
        )
        canvas.drawRoundRect(pool.rectF, 6f * scale, 6f * scale, pool.fillPaint)

        pool.fillPaint.color = Color.argb(42, 14, 165, 233)
        pool.path.reset()
        pool.path.moveTo(boxX, boxY + boxSize * 0.72f + shiftY * 0.5f)
        pool.path.quadTo(
            boxX + boxSize * 0.48f,
            boxY + boxSize * 0.56f + shiftX * 0.4f,
            boxX + boxSize,
            boxY + boxSize * 0.82f
        )
        pool.path.lineTo(boxX + boxSize, boxY + boxSize)
        pool.path.lineTo(boxX, boxY + boxSize)
        pool.path.close()
        canvas.drawPath(pool.path, pool.fillPaint)

        // 3. Survey grid lines
        pool.strokePaint.color = Color.argb(36, 56, 189, 248)
        pool.strokePaint.strokeWidth = 1.0f * scale
        val step = boxSize / 4f
        for (i in 1..3) {
            canvas.drawLine(boxX + i * step, boxY, boxX + i * step, boxY + boxSize, pool.strokePaint)
            canvas.drawLine(boxX, boxY + i * step, boxX + boxSize, boxY + i * step, pool.strokePaint)
        }

        // 4. Secondary road network
        pool.strokePaint.color = Color.argb(75, 148, 163, 184)
        pool.strokePaint.strokeWidth = 3.5f * scale
        canvas.drawLine(boxX, boxY + boxSize * 0.32f + shiftY, boxX + boxSize, boxY + boxSize * 0.27f + shiftY, pool.strokePaint)
        canvas.drawLine(boxX + boxSize * 0.28f + shiftX, boxY, boxX + boxSize * 0.33f + shiftX, boxY + boxSize, pool.strokePaint)
        canvas.drawLine(boxX + boxSize * 0.74f + shiftX * 0.6f, boxY, boxX + boxSize * 0.68f + shiftX * 0.6f, boxY + boxSize, pool.strokePaint)

        // Arterial highway (Amap amber road highlight)
        pool.strokePaint.color = Color.argb(125, 245, 158, 11)
        pool.strokePaint.strokeWidth = 5.0f * scale
        canvas.drawLine(boxX, boxY + boxSize * 0.56f + shiftY * 0.7f, boxX + boxSize, boxY + boxSize * 0.48f + shiftY * 0.7f, pool.strokePaint)
        canvas.drawLine(boxX + boxSize * 0.52f + shiftX * 0.7f, boxY, boxX + boxSize * 0.47f + shiftX * 0.7f, boxY + boxSize, pool.strokePaint)

        val cx = boxX + boxSize / 2f
        val cy = boxY + boxSize / 2f

        // 5. GPS accuracy radar circle
        pool.fillPaint.color = Color.argb(32, 56, 189, 248)
        canvas.drawCircle(cx, cy, boxSize * 0.24f, pool.fillPaint)
        pool.strokePaint.color = Color.argb(115, 56, 189, 248)
        pool.strokePaint.strokeWidth = 1.2f * scale
        canvas.drawCircle(cx, cy, boxSize * 0.24f, pool.strokePaint)

        // 6. Electronic Compass Azimuth Sector Cone
        val coneRadius = boxSize * 0.36f
        pool.fillPaint.color = Color.argb(76, 56, 189, 248)
        pool.rectF.set(cx - coneRadius, cy - coneRadius, cx + coneRadius, cy + coneRadius)
        val startAngle = (cfg.azimuthDeg - 90 - 28).toFloat()
        canvas.drawArc(pool.rectF, startAngle, 56f, true, pool.fillPaint)

        // 7. Center Amap Red Pin
        pool.strokePaint.color = Color.rgb(220, 38, 38)
        pool.strokePaint.strokeWidth = 2.8f * scale
        canvas.drawLine(cx, cy - 8f * scale, cx, cy + 2f * scale, pool.strokePaint)

        pool.fillPaint.color = Color.rgb(239, 68, 68)
        canvas.drawCircle(cx, cy - 10f * scale, 6.5f * scale, pool.fillPaint)
        pool.strokePaint.color = Color.WHITE
        pool.strokePaint.strokeWidth = 1.5f * scale
        canvas.drawCircle(cx, cy - 10f * scale, 6.5f * scale, pool.strokePaint)

        pool.fillPaint.color = Color.WHITE
        canvas.drawCircle(cx - 1.5f * scale, cy - 11.5f * scale, 1.8f * scale, pool.fillPaint)

        // 8. North indicator badge & bottom POI pill
        pool.fillPaint.color = Color.argb(210, 15, 23, 42)
        pool.rectF.set(boxX + 6f * scale, boxY + 6f * scale, boxX + 44f * scale, boxY + 25f * scale)
        canvas.drawRoundRect(pool.rectF, 5f * scale, 5f * scale, pool.fillPaint)
        pool.textPaint.apply {
            clearShadowLayer()
            color = Color.rgb(56, 189, 248)
            textSize = 11.5f * scale
            typeface = TYPEFACE_DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("▲N", boxX + 25f * scale, boxY + 19.5f * scale, pool.textPaint)

        pool.fillPaint.color = Color.argb(220, 11, 19, 43)
        canvas.drawRect(boxX, boxY + boxSize - 24f * scale, boxX + boxSize, boxY + boxSize, pool.fillPaint)
        pool.textPaint.apply {
            color = Color.rgb(226, 232, 240)
            textSize = 12f * scale
            typeface = TYPEFACE_DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val poiLabel = "📍" + cfg.shortPoi.ifBlank { "高德测绘点" }
        canvas.drawText(ellipsizeToWidth(poiLabel, pool.textPaint, boxSize - 10f * scale), cx, boxY + boxSize - 7.5f * scale, pool.textPaint)

        canvas.restore()

        // Outer border
        val accentColor = ACCENT_COLORS.getOrElse(cfg.accentColorIndex) { ACCENT_COLORS[0] }
        pool.strokePaint.color = Color.argb(165, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        pool.strokePaint.strokeWidth = 1.8f * scale
        pool.rectF.set(boxX, boxY, boxX + boxSize, boxY + boxSize)
        canvas.drawRoundRect(pool.rectF, cornerR, cornerR, pool.strokePaint)
    }

    /**
     * Draw sleek Amap Location QR Badge with ISO-compliant 4-module quiet zone!
     */
    private fun drawAmapQrBadge(
        canvas: Canvas,
        width: Int,
        height: Int,
        scale: Float,
        isLandscape: Boolean,
        cfg: WatermarkConfig,
        isLivePreview: Boolean = false
    ) {
        val pool = paintPool.get()!!
        val effectiveTs = if (isLivePreview && cfg.antiTamperMode) {
            "${cfg.dateWeek.substringBefore(" ")} ${cfg.timeHm}:00"
        } else {
            cfg.timestamp
        }
        val url = QrCodeGenerator.buildAmapJumpUrl(
            gcjLat = cfg.gcjLat,
            gcjLon = cfg.gcjLon,
            shortPoi = cfg.shortPoi,
            antiTamperEnabled = cfg.antiTamperMode,
            timestamp = effectiveTs,
            antiTamperHash = if (isLivePreview) "" else cfg.antiTamperHash,
            ntpSynced = cfg.ntpSynced,
            note = cfg.note
        )
        val matrix = QrCodeGenerator.encodeUrlToMatrix(url)
        val modules = matrix.size
        val qrBoxSize = (if (isLandscape) 136f else 150f) * scale
        val labelH = 26f * scale
        val totalH = qrBoxSize + labelH
        val margin = 26f * scale

        val placeLeft = cfg.bottomRight && cfg.styleTemplate in listOf(1, 2, 4)
        val boxLeft = if (placeLeft) margin else (width - qrBoxSize - margin)
        val bottomOffset = if (cfg.styleTemplate == 3) (if (isLandscape) 104f else 118f) * scale else margin
        val boxTop = height - totalH - bottomOffset

        val accentColor = ACCENT_COLORS.getOrElse(cfg.accentColorIndex) { ACCENT_COLORS[0] }
        pool.fillPaint.shader = null
        pool.fillPaint.color = Color.argb(cfg.bgAlpha.coerceIn(145, 245), 11, 19, 43)
        pool.rectF.set(boxLeft, boxTop, boxLeft + qrBoxSize, boxTop + totalH)
        canvas.drawRoundRect(pool.rectF, 14f * scale, 14f * scale, pool.fillPaint)

        pool.strokePaint.color = Color.argb(155, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        pool.strokePaint.strokeWidth = 1.6f * scale
        canvas.drawRoundRect(pool.rectF, 14f * scale, 14f * scale, pool.strokePaint)

        // White quiet zone inside QR card (strictly >= 4 modules on all 4 sides per ISO/IEC 18004)
        val pad = 8f * scale
        val whiteSize = qrBoxSize - pad * 2f
        val whiteLeft = boxLeft + pad
        val whiteTop = boxTop + pad
        pool.fillPaint.color = Color.WHITE
        pool.rectF.set(whiteLeft, whiteTop, whiteLeft + whiteSize, whiteTop + whiteSize)
        canvas.drawRoundRect(pool.rectF, 6f * scale, 6f * scale, pool.fillPaint)

        val totalModulesWithQuiet = modules + 8 // 4 modules quiet zone on each side
        val cellSize = whiteSize / totalModulesWithQuiet.toFloat()
        val startX = whiteLeft + cellSize * 4f
        val startY = whiteTop + cellSize * 4f

        pool.fillPaint.color = Color.BLACK
        for (r in 0 until modules) {
            val row = matrix[r]
            val y0 = startY + r * cellSize
            for (c in 0 until modules) {
                if (row[c]) {
                    val x0 = startX + c * cellSize
                    canvas.drawRect(x0, y0, x0 + cellSize + 0.35f, y0 + cellSize + 0.35f, pool.fillPaint)
                }
            }
        }

        val caption = if (cfg.antiTamperMode) "扫码验真跳高德" else "扫码跳高德"
        pool.textPaint.apply {
            clearShadowLayer()
            color = if (cfg.antiTamperMode) Color.rgb(52, 211, 153) else accentColor
            textSize = 14.5f * scale
            typeface = TYPEFACE_DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(caption, boxLeft + qrBoxSize / 2f, boxTop + qrBoxSize + 18f * scale, pool.textPaint)
    }

    private fun buildCoordString(cfg: WatermarkConfig): String {
        val isGcj = (cfg.coordSystem == "GCJ-02")
        val shownLat = if (isGcj) cfg.gcjLat else cfg.wgsLat
        val shownLon = if (isGcj) cfg.gcjLon else cfg.wgsLon
        val coordStr = CoordinateConverter.formatCoordinate(shownLat, shownLon, cfg.useDms)
        val sysTag = if (cfg.showCoordTag) " [${cfg.coordSystem}]" else ""
        val accTag = if (cfg.showAccuracy) String.format(Locale.US, " ±%.1fm", cfg.accuracyMeters) else ""
        return "$coordStr$sysTag$accTag"
    }

    private fun buildEnvTelemetryLine(cfg: WatermarkConfig): String {
        val parts = mutableListOf<String>()
        if (cfg.showAltitude) {
            parts.add(String.format(Locale.US, "海拔 %.1fm", cfg.altitudeMeters))
        }
        if (cfg.showAzimuth && cfg.azimuthText.isNotBlank()) {
            parts.add("方位 ${cfg.azimuthText}")
        }
        return parts.joinToString(" · ")
    }

    private fun buildAntiTamperLine(cfg: WatermarkConfig): String {
        if (!cfg.antiTamperMode) return ""
        val sig = cfg.ensureAntiTamperSignature().take(12)
        val ntpLabel = if (cfg.ntpSynced) "NTP授时已锁" else "防伪锁定"
        return "🛡 $ntpLabel · SHA256:$sig"
    }

    private fun ellipsizeToWidth(text: String, paint: Paint, maxWidth: Float): String {
        if (text.isEmpty() || maxWidth <= 0f) return ""
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "..."
        val ellW = paint.measureText(ellipsis)
        var end = text.length
        while (end > 1 && paint.measureText(text, 0, end) + ellW > maxWidth) {
            end--
        }
        return text.substring(0, end) + ellipsis
    }

    /**
     * Split long text into up to 2 lines within maxWidth so long addresses/POI names never get cut off prematurely
     * and never collide with the QR code!
     */
    private fun wrapTextToTwoLines(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isEmpty() || maxWidth <= 0f) return emptyList()
        if (paint.measureText(text) <= maxWidth) return listOf(text)
        var splitIdx = 1
        while (splitIdx < text.length && paint.measureText(text, 0, splitIdx + 1) <= maxWidth) {
            splitIdx++
        }
        val line1 = text.substring(0, splitIdx)
        val rem = text.substring(splitIdx).trimStart()
        val line2 = ellipsizeToWidth(rem, paint, maxWidth)
        return listOf(line1, line2)
    }

    /**
     * Style 0: 经典大字时间 (Fixed QR collision math: shifts center or bounds width strictly clear of QR badge!)
     */
    private fun drawStyle0CenteredBigTime(
        canvas: Canvas,
        width: Int,
        height: Int,
        scale: Float,
        isLandscape: Boolean,
        cfg: WatermarkConfig
    ) {
        val pool = paintPool.get()!!
        val qrReserveRight = if (cfg.showQrCode) 182f * scale else 28f * scale
        val leftMargin = 28f * scale
        val safeZoneWidth = (width - leftMargin - qrReserveRight).coerceAtLeast(width * 0.55f)
        val cx = if (cfg.showQrCode) (leftMargin + safeZoneWidth / 2f) else (width / 2f)
        val maxLineW = safeZoneWidth - 16f * scale

        val fullCoordLine = buildCoordString(cfg)
        val envLine = buildEnvTelemetryLine(cfg)
        val antiLine = buildAntiTamperLine(cfg)
        val accentColor = ACCENT_COLORS.getOrElse(cfg.accentColorIndex) { ACCENT_COLORS[0] }

        val scrimH = if (isLandscape) 300f * scale else 390f * scale
        val scrimTop = height - scrimH
        pool.fillPaint.shader = LinearGradient(
            0f, scrimTop, 0f, height.toFloat(),
            Color.TRANSPARENT, Color.argb(155, 0, 0, 0),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, scrimTop, width.toFloat(), height.toFloat(), pool.fillPaint)
        pool.fillPaint.shader = null

        var cursorBottomY = height - (if (isLandscape) 28f * scale else 36f * scale)

        if (antiLine.isNotEmpty()) {
            pool.monoPaint.apply {
                color = Color.rgb(52, 211, 153)
                textSize = 20f * scale
                typeface = TYPEFACE_MONO_BOLD
                textAlign = Paint.Align.CENTER
                setShadowLayer(6f * scale, 0f, 2f * scale, Color.argb(210, 0, 0, 0))
            }
            canvas.drawText(ellipsizeToWidth(antiLine, pool.monoPaint, maxLineW), cx, cursorBottomY, pool.monoPaint)
            cursorBottomY -= 32f * scale
        }

        if (cfg.showNote && cfg.note.isNotBlank()) {
            pool.textPaint.apply {
                color = Color.rgb(253, 230, 138)
                textSize = 24f * scale
                typeface = Typeface.DEFAULT
                textAlign = Paint.Align.CENTER
                setShadowLayer(6f * scale, 0f, 2f * scale, Color.argb(200, 0, 0, 0))
            }
            val clippedNote = ellipsizeToWidth("备注：${cfg.note}", pool.textPaint, maxLineW)
            canvas.drawText(clippedNote, cx, cursorBottomY, pool.textPaint)
            cursorBottomY -= 34f * scale
        }

        if (envLine.isNotEmpty()) {
            pool.textPaint.apply {
                color = Color.rgb(167, 243, 208)
                textSize = 21.5f * scale
                typeface = TYPEFACE_MONO_BOLD
                textAlign = Paint.Align.CENTER
                setShadowLayer(6f * scale, 0f, 2f * scale, Color.argb(200, 0, 0, 0))
            }
            canvas.drawText(ellipsizeToWidth(envLine, pool.textPaint, maxLineW), cx, cursorBottomY, pool.textPaint)
            cursorBottomY -= 32f * scale
        }

        if (cfg.showAddress && cfg.address.isNotBlank()) {
            pool.textPaint.apply {
                color = Color.argb(235, 241, 245, 249)
                textSize = 22.5f * scale
                typeface = Typeface.DEFAULT
                textAlign = Paint.Align.CENTER
                setShadowLayer(6f * scale, 0f, 2f * scale, Color.argb(200, 0, 0, 0))
            }
            val clippedAddr = ellipsizeToWidth(cfg.address, pool.textPaint, maxLineW)
            canvas.drawText(clippedAddr, cx, cursorBottomY, pool.textPaint)
            cursorBottomY -= 34f * scale
        }

        if (cfg.showCoords) {
            pool.monoPaint.apply {
                clearShadowLayer()
                color = accentColor
                textSize = 23.5f * scale
                typeface = TYPEFACE_MONO_BOLD
            }
            val clippedCoord = ellipsizeToWidth(fullCoordLine, pool.monoPaint, maxLineW - 44f * scale)
            val textW = pool.monoPaint.measureText(clippedCoord)
            val pillPadX = 20f * scale
            val pillH = 38f * scale
            val pillTop = cursorBottomY - 28f * scale
            pool.rectF.set(cx - textW / 2f - pillPadX, pillTop, cx + textW / 2f + pillPadX, pillTop + pillH)

            pool.fillPaint.color = Color.argb(cfg.bgAlpha.coerceIn(90, 240), 11, 19, 43)
            pool.strokePaint.color = Color.argb(105, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
            pool.strokePaint.strokeWidth = 1.5f * scale

            canvas.drawRoundRect(pool.rectF, 19f * scale, 19f * scale, pool.fillPaint)
            canvas.drawRoundRect(pool.rectF, 19f * scale, 19f * scale, pool.strokePaint)

            pool.monoPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(clippedCoord, cx, cursorBottomY, pool.monoPaint)
            cursorBottomY -= 46f * scale
        }

        if (cfg.showTime || cfg.showLocation) {
            pool.textPaint.apply {
                color = Color.WHITE
                textSize = 31f * scale
                typeface = Typeface.DEFAULT
                setShadowLayer(8f * scale, 0f, 2.5f * scale, Color.argb(190, 0, 0, 0))
            }
            val datePart = if (cfg.showTime) cfg.dateWeek else ""
            val rawPoi = if (cfg.showLocation) cfg.shortPoi else ""
            val dateW = if (datePart.isNotEmpty()) pool.textPaint.measureText("$datePart  ") else 0f
            val pinWidth = if (rawPoi.isNotEmpty()) 30f * scale else 0f
            val availableForPoi = (maxLineW - dateW - pinWidth).coerceAtLeast(110f * scale)
            val poiPart = ellipsizeToWidth(rawPoi, pool.textPaint, availableForPoi)
            val poiW = if (poiPart.isNotEmpty()) pool.textPaint.measureText(poiPart) else 0f
            val totalW = dateW + pinWidth + poiW
            var startX = cx - totalW / 2f

            if (datePart.isNotEmpty()) {
                pool.textPaint.textAlign = Paint.Align.LEFT
                canvas.drawText("$datePart  ", startX, cursorBottomY, pool.textPaint)
                startX += dateW
            }
            if (poiPart.isNotEmpty()) {
                val pinCenterX = startX + 12f * scale
                val pinHeadY = cursorBottomY - 19f * scale
                pool.strokePaint.apply {
                    color = Color.rgb(220, 38, 38)
                    strokeWidth = 3.0f * scale
                    strokeCap = Paint.Cap.ROUND
                }
                canvas.drawLine(pinCenterX, pinHeadY + 5f * scale, pinCenterX, cursorBottomY - 2f * scale, pool.strokePaint)
                pool.fillPaint.color = Color.rgb(239, 68, 68)
                canvas.drawCircle(pinCenterX, pinHeadY, 8.5f * scale, pool.fillPaint)
                pool.fillPaint.color = Color.rgb(254, 202, 202)
                canvas.drawCircle(pinCenterX - 2f * scale, pinHeadY - 2f * scale, 2.2f * scale, pool.fillPaint)

                startX += pinWidth
                pool.textPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(poiPart, startX, cursorBottomY, pool.textPaint)
            }
            cursorBottomY -= (if (isLandscape) 64f * scale else 72f * scale)
        }

        if (cfg.showTime) {
            pool.textPaint.apply {
                color = Color.WHITE
                textSize = (if (isLandscape) 98f else 114f) * scale
                textAlign = Paint.Align.CENTER
                typeface = TYPEFACE_SANS_LIGHT
                setShadowLayer(10f * scale, 0f, 3f * scale, Color.argb(180, 0, 0, 0))
            }
            canvas.drawText(cfg.timeHm, cx, cursorBottomY, pool.textPaint)
        }
    }

    /**
     * Style 1: 工程巡检卡片 (Supports embedded 160x160 Mini-Map PiP, 2-line location wrapping, and Anti-Tamper seal)
     */
    private fun drawStyle1EngineeringCard(
        canvas: Canvas,
        width: Int,
        height: Int,
        scale: Float,
        isLandscape: Boolean,
        cfg: WatermarkConfig
    ) {
        val pool = paintPool.get()!!
        val margin = 26f * scale
        val qrSpace = if (cfg.showQrCode) 178f * scale else 0f
        val maxAllowedBoxW = if (isLandscape) (width * 0.62f) else (width - margin * 2 - qrSpace)
        val embedMiniMap = cfg.showMiniMap && cfg.miniMapPosition == 0
        val mapSize = if (embedMiniMap) 160f * scale else 0f
        val mapGap = if (embedMiniMap) 18f * scale else 0f
        val padX = 22f * scale
        val padY = 18f * scale
        val lineGap = 35f * scale
        val headerHeight = 44f * scale

        val boxW = maxAllowedBoxW.coerceIn(420f * scale, 840f * scale)
        val textColLeftOffset = padX + mapSize + mapGap
        val maxContentW = (boxW - textColLeftOffset - padX).coerceAtLeast(200f * scale)

        pool.monoPaint.apply {
            clearShadowLayer()
            textSize = 22.5f * scale
            color = Color.WHITE
            typeface = TYPEFACE_MONO_BOLD
            textAlign = Paint.Align.LEFT
        }

        val lines = mutableListOf<Pair<String, String>>()
        if (cfg.showCoords) {
            lines.add("经纬" to buildCoordString(cfg))
        }
        val envLine = buildEnvTelemetryLine(cfg)
        if (envLine.isNotEmpty()) {
            lines.add("环境" to envLine)
        }
        if (cfg.showLocation && cfg.shortPoi.isNotBlank()) {
            val locRaw = if (cfg.showAddress && cfg.address.isNotBlank()) "${cfg.shortPoi} (${cfg.address})" else cfg.shortPoi
            val prefixW = pool.monoPaint.measureText("地点：")
            val wrapped = wrapTextToTwoLines(locRaw, pool.monoPaint, maxContentW - prefixW)
            if (wrapped.isNotEmpty()) {
                lines.add("地点" to wrapped[0])
                if (wrapped.size > 1 && wrapped[1].isNotBlank()) {
                    lines.add("地址" to wrapped[1])
                }
            }
        } else if (cfg.showAddress && cfg.address.isNotBlank()) {
            lines.add("地点" to cfg.address)
        }
        if (cfg.showTime && cfg.timestamp.isNotBlank()) {
            lines.add("时间" to "${cfg.timestamp} ${cfg.dateWeek.substringAfter(" ")}")
        }
        if (cfg.showNote && cfg.note.isNotBlank()) {
            lines.add("备注" to cfg.note)
        }
        if (cfg.antiTamperMode) {
            lines.add("验真" to "SHA256:${cfg.ensureAntiTamperSignature().take(12)} (${if (cfg.ntpSynced) "NTP真时" else "已锁"})")
        }
        if (lines.isEmpty()) {
            lines.add("状态" to "高德 ${cfg.coordSystem} 零偏差已对齐")
        }

        val contentH = headerHeight + lines.size * lineGap
        val boxH = padY * 2 + max(contentH, mapSize + (if (embedMiniMap) headerHeight else 0f))
        val boxLeft = if (cfg.bottomRight) (width - boxW - margin) else margin
        val boxTop = height - boxH - margin

        val accentColor = ACCENT_COLORS.getOrElse(cfg.accentColorIndex) { ACCENT_COLORS[0] }
        pool.fillPaint.shader = null
        pool.fillPaint.color = Color.argb(cfg.bgAlpha.coerceIn(95, 242), 11, 19, 43)
        pool.strokePaint.color = Color.argb(115, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        pool.strokePaint.strokeWidth = 1.8f * scale
        pool.rectF.set(boxLeft, boxTop, boxLeft + boxW, boxTop + boxH)
        canvas.drawRoundRect(pool.rectF, 18f * scale, 18f * scale, pool.fillPaint)
        canvas.drawRoundRect(pool.rectF, 18f * scale, 18f * scale, pool.strokePaint)

        // Header bar
        pool.fillPaint.color = accentColor
        pool.rectF.set(boxLeft + padX, boxTop + padY, boxLeft + padX + 7f * scale, boxTop + padY + 26f * scale)
        canvas.drawRoundRect(pool.rectF, 3.5f * scale, 3.5f * scale, pool.fillPaint)

        pool.textPaint.apply {
            clearShadowLayer()
            color = Color.WHITE
            textSize = 25.5f * scale
            typeface = TYPEFACE_DEFAULT_BOLD
            textAlign = Paint.Align.LEFT
        }
        val titleText = cfg.customTitle.ifBlank { "现场点位核验" }
        val badgeW = if (cfg.showCoordTag) 106f * scale else 0f
        val maxTitleW = boxW - padX * 2 - badgeW - 24f * scale
        canvas.drawText(ellipsizeToWidth(titleText, pool.textPaint, maxTitleW), boxLeft + padX + 15f * scale, boxTop + padY + 22f * scale, pool.textPaint)

        if (cfg.showCoordTag) {
            pool.fillPaint.color = if (cfg.antiTamperMode) Color.argb(65, 16, 185, 129) else Color.argb(55, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
            pool.rectF.set(boxLeft + boxW - padX - badgeW, boxTop + padY - 2f * scale, boxLeft + boxW - padX, boxTop + padY + 28f * scale)
            canvas.drawRoundRect(pool.rectF, 8f * scale, 8f * scale, pool.fillPaint)

            pool.monoPaint.apply {
                color = if (cfg.antiTamperMode) Color.rgb(52, 211, 153) else accentColor
                textSize = 18.5f * scale
                textAlign = Paint.Align.CENTER
            }
            val tagTxt = if (cfg.antiTamperMode) "🛡防伪已锁" else cfg.coordSystem
            canvas.drawText(tagTxt, pool.rectF.centerX(), boxTop + padY + 19.5f * scale, pool.monoPaint)
        }

        pool.strokePaint.color = Color.argb(45, 255, 255, 255)
        pool.strokePaint.strokeWidth = 1.2f * scale
        val sepY = boxTop + padY + headerHeight - 8f * scale
        canvas.drawLine(boxLeft + padX, sepY, boxLeft + boxW - padX, sepY, pool.strokePaint)

        if (embedMiniMap) {
            drawVectorMiniMapBox(
                canvas = canvas,
                boxX = boxLeft + padX,
                boxY = boxTop + padY + headerHeight,
                boxSize = mapSize,
                scale = scale,
                cfg = cfg
            )
        }

        val textStartX = boxLeft + textColLeftOffset
        pool.monoPaint.textSize = 22f * scale
        pool.monoPaint.textAlign = Paint.Align.LEFT
        lines.forEachIndexed { idx, (label, value) ->
            val y = boxTop + padY + headerHeight + (idx + 0.78f) * lineGap
            pool.monoPaint.color = Color.rgb(148, 163, 184)
            val prefix = "$label："
            canvas.drawText(prefix, textStartX, y, pool.monoPaint)
            val lw = pool.monoPaint.measureText(prefix)
            pool.monoPaint.color = when (label) {
                "经纬" -> accentColor
                "环境" -> Color.rgb(167, 243, 208)
                "备注" -> Color.rgb(253, 230, 138)
                "验真" -> Color.rgb(52, 211, 153)
                else -> Color.WHITE
            }
            val clippedVal = ellipsizeToWidth(value, pool.monoPaint, maxContentW - lw)
            canvas.drawText(clippedVal, textStartX + lw, y, pool.monoPaint)
        }
    }

    /**
     * Style 2: 经纬网格测绘 (Supports embedded 160x160 Mini-Map PiP & Anti-Tamper verification line)
     */
    private fun drawStyle2GisSurveyGrid(
        canvas: Canvas,
        width: Int,
        height: Int,
        scale: Float,
        isLandscape: Boolean,
        cfg: WatermarkConfig
    ) {
        val pool = paintPool.get()!!
        val margin = 26f * scale
        val qrSpace = if (cfg.showQrCode) 178f * scale else 0f
        val maxAllowedW = if (isLandscape) (width * 0.62f) else (width - margin * 2 - qrSpace)
        val embedMiniMap = cfg.showMiniMap && cfg.miniMapPosition == 0
        val mapSize = if (embedMiniMap) 160f * scale else 0f
        val boxW = maxAllowedW.coerceIn(420f * scale, 820f * scale)
        val envLine = buildEnvTelemetryLine(cfg)
        val antiLine = buildAntiTamperLine(cfg)
        val hasFooter = (cfg.showAddress && cfg.address.isNotBlank()) || (cfg.showNote && cfg.note.isNotBlank()) || envLine.isNotEmpty() || antiLine.isNotEmpty()
        val baseGridH = if (hasFooter) 196f * scale else 156f * scale
        val boxH = if (embedMiniMap) max(baseGridH, mapSize + 24f * scale) else baseGridH
        val boxLeft = if (cfg.bottomRight) (width - boxW - margin) else margin
        val boxTop = height - boxH - margin

        val accentColor = ACCENT_COLORS.getOrElse(cfg.accentColorIndex) { ACCENT_COLORS[0] }
        pool.fillPaint.shader = null
        pool.fillPaint.color = Color.argb(cfg.bgAlpha.coerceIn(95, 242), 9, 14, 26)
        pool.rectF.set(boxLeft, boxTop, boxLeft + boxW, boxTop + boxH)
        canvas.drawRoundRect(pool.rectF, 16f * scale, 16f * scale, pool.fillPaint)

        val gridLeft = if (embedMiniMap) (boxLeft + mapSize + 22f * scale) else boxLeft
        val gridW = (boxLeft + boxW) - gridLeft

        if (embedMiniMap) {
            drawVectorMiniMapBox(
                canvas = canvas,
                boxX = boxLeft + 12f * scale,
                boxY = boxTop + (boxH - mapSize) / 2f,
                boxSize = mapSize,
                scale = scale,
                cfg = cfg
            )
        }

        val headerH = 52f * scale
        pool.fillPaint.color = Color.argb(48, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        pool.rectF.set(gridLeft, boxTop, boxLeft + boxW, boxTop + headerH)
        canvas.drawRoundRect(pool.rectF, 14f * scale, 14f * scale, pool.fillPaint)

        pool.strokePaint.color = Color.argb(135, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        pool.strokePaint.strokeWidth = 1.8f * scale
        pool.rectF.set(boxLeft, boxTop, boxLeft + boxW, boxTop + boxH)
        canvas.drawRoundRect(pool.rectF, 16f * scale, 16f * scale, pool.strokePaint)

        pool.monoPaint.apply {
            clearShadowLayer()
            color = accentColor
            textSize = 22f * scale
            typeface = TYPEFACE_MONO_BOLD
            textAlign = Paint.Align.LEFT
        }
        val headerLineText = if (cfg.showCoords) {
            buildCoordString(cfg)
        } else {
            "${cfg.customTitle.ifBlank { "经纬网格测绘" }} · ${cfg.coordSystem}"
        }
        canvas.drawText(ellipsizeToWidth(headerLineText, pool.monoPaint, gridW - 28f * scale), gridLeft + 14f * scale, boxTop + 34f * scale, pool.monoPaint)

        pool.strokePaint.color = Color.argb(55, 148, 163, 184)
        pool.strokePaint.strokeWidth = 1.2f * scale
        canvas.drawLine(gridLeft, boxTop + headerH, boxLeft + boxW, boxTop + headerH, pool.strokePaint)

        val midX = gridLeft + gridW * 0.48f
        val row2Bottom = boxTop + headerH + 84f * scale
        canvas.drawLine(midX, boxTop + headerH, midX, row2Bottom, pool.strokePaint)

        pool.textPaint.apply {
            clearShadowLayer()
            color = Color.rgb(148, 163, 184)
            textSize = 18f * scale
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.LEFT
        }

        val leftLabel = if (cfg.showLocation) "测绘点位" else "核验项目"
        val leftValRaw = if (cfg.showLocation) cfg.shortPoi.ifBlank { "实拍点位" } else cfg.customTitle.ifBlank { "现场点位核验" }
        canvas.drawText(leftLabel, gridLeft + 14f * scale, boxTop + headerH + 28f * scale, pool.textPaint)

        pool.textPaint.apply {
            color = Color.WHITE
            textSize = 22.5f * scale
            typeface = TYPEFACE_DEFAULT_BOLD
        }
        canvas.drawText(ellipsizeToWidth(leftValRaw, pool.textPaint, gridW * 0.48f - 22f * scale), gridLeft + 14f * scale, boxTop + headerH + 64f * scale, pool.textPaint)

        pool.textPaint.apply {
            color = Color.rgb(148, 163, 184)
            textSize = 18f * scale
            typeface = Typeface.DEFAULT
        }
        val rightLabel = if (cfg.showTime) "采样时间" else "定位基准"
        val rightValRaw = if (cfg.showTime) cfg.timestamp else "${cfg.coordSystem} 零偏差对齐"
        canvas.drawText(rightLabel, midX + 14f * scale, boxTop + headerH + 28f * scale, pool.textPaint)

        pool.monoPaint.apply {
            color = Color.WHITE
            textSize = 20f * scale
        }
        canvas.drawText(ellipsizeToWidth(rightValRaw, pool.monoPaint, gridW * 0.52f - 22f * scale), midX + 14f * scale, boxTop + headerH + 64f * scale, pool.monoPaint)

        if (hasFooter) {
            canvas.drawLine(gridLeft, row2Bottom, boxLeft + boxW, row2Bottom, pool.strokePaint)
            pool.textPaint.apply {
                color = if (cfg.antiTamperMode) Color.rgb(52, 211, 153) else Color.rgb(253, 230, 138)
                textSize = 19.5f * scale
                typeface = Typeface.DEFAULT
            }
            val footerRaw = buildString {
                if (antiLine.isNotEmpty()) append(antiLine)
                if (envLine.isNotEmpty()) {
                    if (isNotEmpty()) append(" · ")
                    append(envLine)
                }
                if (cfg.showNote && cfg.note.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(cfg.note)
                } else if (cfg.showAddress && cfg.address.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(cfg.address)
                }
            }
            canvas.drawText(ellipsizeToWidth(footerRaw, pool.textPaint, gridW - 28f * scale), gridLeft + 14f * scale, row2Bottom + 32f * scale, pool.textPaint)
        }
    }

    /**
     * Style 3: 极简胶片底栏 (v8.0.0 全宽上下双层通栏布局：第一层左点位+右完整时间星期，第二层全宽经纬度+右侧防伪/备注角标，彻底根治DMS截断与日期重复)
     */
    private fun drawStyle3MinimalistFilmBar(
        canvas: Canvas,
        width: Int,
        height: Int,
        scale: Float,
        isLandscape: Boolean,
        cfg: WatermarkConfig
    ) {
        val pool = paintPool.get()!!
        val barH = (if (isLandscape) 100f else 114f) * scale
        val barTop = height - barH
        pool.fillPaint.shader = null
        pool.fillPaint.color = Color.argb(cfg.bgAlpha.coerceIn(115, 245), 8, 12, 20)
        canvas.drawRect(0f, barTop, width.toFloat(), height.toFloat(), pool.fillPaint)

        val accentColor = ACCENT_COLORS.getOrElse(cfg.accentColorIndex) { ACCENT_COLORS[0] }
        pool.strokePaint.color = accentColor
        pool.strokePaint.strokeWidth = 2.8f * scale
        canvas.drawLine(0f, barTop, width.toFloat(), barTop, pool.strokePaint)

        val padX = 26f * scale
        // Row 1: Left POI name (50% width), Right non-duplicated full timestamp + weekday (46% width)
        pool.textPaint.apply {
            clearShadowLayer()
            color = Color.WHITE
            textSize = 26.5f * scale
            typeface = TYPEFACE_DEFAULT_BOLD
            textAlign = Paint.Align.LEFT
        }
        val leftPrimary = if (cfg.showLocation && cfg.shortPoi.isNotBlank()) {
            "📍 ${cfg.shortPoi}"
        } else {
            cfg.customTitle.ifBlank { "极速高德水印" }
        }
        canvas.drawText(ellipsizeToWidth(leftPrimary, pool.textPaint, width * 0.50f), padX, barTop + 42f * scale, pool.textPaint)

        if (cfg.showTime) {
            pool.monoPaint.apply {
                clearShadowLayer()
                color = Color.rgb(226, 232, 240)
                textSize = 20.5f * scale
                textAlign = Paint.Align.RIGHT
                typeface = TYPEFACE_MONO_BOLD
            }
            val weekPart = cfg.dateWeek.substringAfter(" ", "")
            val timeLine = "${cfg.timestamp} $weekPart".trim()
            canvas.drawText(ellipsizeToWidth(timeLine, pool.monoPaint, width * 0.46f), width - padX, barTop + 42f * scale, pool.monoPaint)
        }

        // Row 2: Left full-width Coordinates (up to 64% or 95% width so DMS never truncates!), Right badge
        val envLine = buildEnvTelemetryLine(cfg)
        val antiLine = buildAntiTamperLine(cfg)
        val rightBadgeRaw = when {
            antiLine.isNotEmpty() -> antiLine
            cfg.showNote && cfg.note.isNotBlank() -> "备注：${cfg.note}"
            cfg.showAddress && cfg.address.isNotBlank() -> cfg.address
            envLine.isNotEmpty() -> envLine
            else -> ""
        }
        val maxCoordW = if (rightBadgeRaw.isNotEmpty()) (width * 0.64f) else (width - padX * 2f)

        if (cfg.showCoords) {
            pool.monoPaint.apply {
                clearShadowLayer()
                color = accentColor
                textSize = (if (cfg.useDms) 20.5f else 22.5f) * scale
                textAlign = Paint.Align.LEFT
                typeface = TYPEFACE_MONO_BOLD
            }
            val coordClipped = ellipsizeToWidth(buildCoordString(cfg), pool.monoPaint, maxCoordW)
            canvas.drawText(coordClipped, padX, barTop + 86f * scale, pool.monoPaint)
        }

        if (rightBadgeRaw.isNotEmpty()) {
            pool.textPaint.apply {
                clearShadowLayer()
                color = if (antiLine.isNotEmpty()) Color.rgb(52, 211, 153) else Color.rgb(253, 230, 138)
                textSize = 19.5f * scale
                typeface = Typeface.DEFAULT
                textAlign = Paint.Align.RIGHT
            }
            canvas.drawText(ellipsizeToWidth(rightBadgeRaw, pool.textPaint, width * 0.34f), width - padX, barTop + 86f * scale, pool.textPaint)
        }
    }

    /**
     * Style 4: 外业标准台账
     */
    private fun drawStyle4LedgerTable(
        canvas: Canvas,
        width: Int,
        height: Int,
        scale: Float,
        isLandscape: Boolean,
        cfg: WatermarkConfig
    ) {
        val pool = paintPool.get()!!
        val margin = 26f * scale
        val rows = mutableListOf<Pair<String, String>>()
        if (cfg.showCoords) rows.add("经纬坐标" to buildCoordString(cfg))
        val envLine = buildEnvTelemetryLine(cfg)
        if (envLine.isNotEmpty()) rows.add("高程方位" to envLine)
        if (cfg.showLocation) {
            val locVal = if (cfg.showAddress && cfg.address.isNotBlank()) "${cfg.shortPoi} · ${cfg.address}" else cfg.shortPoi
            rows.add("点位地址" to locVal)
        } else if (cfg.showAddress && cfg.address.isNotBlank()) {
            rows.add("点位地址" to cfg.address)
        }
        if (cfg.showTime) rows.add("记录时间" to "${cfg.timestamp} ${cfg.dateWeek.substringAfter(" ")}")
        if (cfg.showNote && cfg.note.isNotBlank()) rows.add("现场备注" to cfg.note)
        if (cfg.antiTamperMode) rows.add("防伪签名" to "SHA256:${cfg.ensureAntiTamperSignature()} (${if (cfg.ntpSynced) "NTP" else "已锁"})")
        if (rows.isEmpty()) rows.add("核验状态" to "高德GCJ-02已对齐")

        val headerH = 44f * scale
        val rowH = 39f * scale
        val qrSpace = if (cfg.showQrCode) 178f * scale else 0f
        val maxAllowedW = if (isLandscape) (width * 0.60f) else (width - margin * 2 - qrSpace)
        val boxW = (720f * scale).coerceAtMost(maxAllowedW.coerceAtLeast(380f * scale))
        val boxH = headerH + rows.size * rowH
        val boxLeft = if (cfg.bottomRight) (width - boxW - margin) else margin
        val boxTop = height - boxH - margin

        val accentColor = ACCENT_COLORS.getOrElse(cfg.accentColorIndex) { ACCENT_COLORS[0] }
        pool.fillPaint.shader = null
        pool.fillPaint.color = Color.argb(cfg.bgAlpha.coerceIn(95, 240), 11, 19, 43)
        pool.rectF.set(boxLeft, boxTop, boxLeft + boxW, boxTop + boxH)
        canvas.drawRoundRect(pool.rectF, 14f * scale, 14f * scale, pool.fillPaint)

        pool.fillPaint.color = Color.argb(65, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        pool.rectF.set(boxLeft, boxTop, boxLeft + boxW, boxTop + headerH)
        canvas.drawRoundRect(pool.rectF, 14f * scale, 14f * scale, pool.fillPaint)

        pool.strokePaint.color = accentColor
        pool.strokePaint.strokeWidth = 2.0f * scale
        pool.rectF.set(boxLeft, boxTop, boxLeft + boxW, boxTop + boxH)
        canvas.drawRoundRect(pool.rectF, 14f * scale, 14f * scale, pool.strokePaint)

        pool.textPaint.apply {
            clearShadowLayer()
            color = Color.WHITE
            textSize = 23.5f * scale
            typeface = TYPEFACE_DEFAULT_BOLD
            textAlign = Paint.Align.LEFT
        }
        val titleStr = "${cfg.customTitle.ifBlank { "外业定点巡检台账" }} · ${cfg.coordSystem}"
        canvas.drawText(ellipsizeToWidth(titleStr, pool.textPaint, boxW - 28f * scale), boxLeft + 14f * scale, boxTop + 30f * scale, pool.textPaint)

        val colSplitX = boxLeft + 132f * scale
        pool.strokePaint.strokeWidth = 1.2f * scale
        canvas.drawLine(boxLeft, boxTop + headerH, boxLeft + boxW, boxTop + headerH, pool.strokePaint)
        canvas.drawLine(colSplitX, boxTop + headerH, colSplitX, boxTop + boxH, pool.strokePaint)

        pool.monoPaint.apply {
            clearShadowLayer()
            textSize = 21.5f * scale
            typeface = TYPEFACE_MONO_BOLD
            textAlign = Paint.Align.LEFT
        }
        val maxValW = (boxLeft + boxW) - colSplitX - 22f * scale

        rows.forEachIndexed { i, (k, v) ->
            val topY = boxTop + headerH + i * rowH
            if (i > 0) {
                canvas.drawLine(boxLeft, topY, boxLeft + boxW, topY, pool.strokePaint)
            }
            val baselineY = topY + 27f * scale
            pool.monoPaint.color = accentColor
            canvas.drawText(k, boxLeft + 14f * scale, baselineY, pool.monoPaint)
            pool.monoPaint.color = when (k) {
                "现场备注" -> Color.rgb(253, 230, 138)
                "高程方位" -> Color.rgb(167, 243, 208)
                "防伪签名" -> Color.rgb(52, 211, 153)
                else -> Color.WHITE
            }
            val clippedV = ellipsizeToWidth(v, pool.monoPaint, maxValW)
            canvas.drawText(clippedV, colSplitX + 12f * scale, baselineY, pool.monoPaint)
        }
    }

    fun saveJpegWithDualTrackExif(bitmap: Bitmap, targetFile: File, cfg: WatermarkConfig) {
        if (cfg.antiTamperMode) {
            cfg.ensureAntiTamperSignature()
        }
        FileOutputStream(targetFile).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
        }
        try {
            val exif = ExifInterface(targetFile.absolutePath)
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, if (cfg.wgsLat >= 0) "N" else "S")
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, if (cfg.wgsLon >= 0) "E" else "W")
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, decimalToExifDms(cfg.wgsLat))
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, decimalToExifDms(cfg.wgsLon))
            val exifFormattedDate = if (cfg.timestamp.length >= 10) {
                cfg.timestamp.substring(0, 10).replace('-', ':') + cfg.timestamp.substring(10)
            } else {
                cfg.timestamp
            }
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exifFormattedDate)
            exif.setAttribute(ExifInterface.TAG_DATETIME, exifFormattedDate)
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, cfg.toJson())
            if (cfg.antiTamperMode && cfg.antiTamperHash.isNotBlank()) {
                exif.setAttribute(
                    ExifInterface.TAG_IMAGE_DESCRIPTION,
                    "AMAP_WM_V7_SHA256:${cfg.antiTamperHash}|GCJ02:${cfg.gcjLat},${cfg.gcjLon}|TS:${cfg.timestamp}"
                )
            }
            exif.saveAttributes()
        } catch (_: Exception) {}
    }

    fun exportOrUpdateSystemCameraGallery(context: Context, wmFile: File, cfg: WatermarkConfig): String {
        val resolver = context.contentResolver
        if (cfg.mediaStoreUri.isNotBlank()) {
            try {
                val existingUri = Uri.parse(cfg.mediaStoreUri)
                val outStream = try {
                    resolver.openOutputStream(existingUri, "wt")
                } catch (_: Exception) {
                    resolver.openOutputStream(existingUri)
                }
                outStream?.use { out ->
                    wmFile.inputStream().use { inStream ->
                        inStream.copyTo(out)
                    }
                    return cfg.mediaStoreUri
                }
            } catch (_: Exception) {
                // Clean up stale MediaStore entry before inserting replacement to avoid duplicate orphan photos
                try {
                    resolver.delete(Uri.parse(cfg.mediaStoreUri), null, null)
                } catch (_: Exception) {}
            }
        }

        return try {
            val tsName = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "IMG_${tsName}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return ""
            resolver.openOutputStream(uri)?.use { outStream ->
                wmFile.inputStream().use { inStream ->
                    inStream.copyTo(outStream)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            uri.toString()
        } catch (_: Exception) {
            ""
        }
    }

    fun deletePhotoEntryAndMediaStore(context: Context, target: PhotoEntry) {
        BitmapCacheManager.invalidate(target.watermarkedFile)
        BitmapCacheManager.invalidate(target.cleanFile)
        if (target.config.mediaStoreUri.isNotBlank()) {
            try {
                context.contentResolver.delete(Uri.parse(target.config.mediaStoreUri), null, null)
            } catch (_: Exception) {}
        }
        target.cleanFile.delete()
        target.watermarkedFile.delete()
        target.metaFile.delete()
    }

    fun saveNewPhotoEntry(context: Context, cleanBitmap: Bitmap, cfg: WatermarkConfig, alsoExportOriginal: Boolean = false): PhotoEntry {
        val dir = File(context.filesDir, "captured_photos").apply { mkdirs() }
        val id = System.currentTimeMillis().toString()
        val cleanFile = File(dir, "clean_$id.jpg")
        val wmFile = File(dir, "wm_$id.jpg")
        val metaFile = File(dir, "meta_$id.json")

        if (cfg.antiTamperMode) {
            cfg.ensureAntiTamperSignature()
        }

        FileOutputStream(cleanFile).use { fos ->
            cleanBitmap.compress(Bitmap.CompressFormat.JPEG, 96, fos)
        }
        val wmBitmap = renderWatermarkedBitmap(cleanBitmap, cfg)
        saveJpegWithDualTrackExif(wmBitmap, wmFile, cfg)
        if (wmBitmap !== cleanBitmap && !wmBitmap.isRecycled) {
            wmBitmap.recycle()
        }

        val savedUriStr = exportOrUpdateSystemCameraGallery(context, wmFile, cfg)
        cfg.mediaStoreUri = savedUriStr
        metaFile.writeText(cfg.toJson())

        if (alsoExportOriginal) {
            val origCfg = cfg.copy(mediaStoreUri = "")
            exportOrUpdateSystemCameraGallery(context, cleanFile, origCfg)
        }

        return PhotoEntry(id = id, cleanFile = cleanFile, watermarkedFile = wmFile, metaFile = metaFile, config = cfg)
    }

    fun loadSavedPhotos(context: Context): List<PhotoEntry> {
        val dir = File(context.filesDir, "captured_photos")
        if (!dir.exists()) return emptyList()
        val metaFiles = dir.listFiles { f -> f.name.startsWith("meta_") && f.name.endsWith(".json") } ?: return emptyList()
        val list = mutableListOf<PhotoEntry>()
        for (mf in metaFiles) {
            try {
                val id = mf.name.removePrefix("meta_").removeSuffix(".json")
                val cleanFile = File(dir, "clean_$id.jpg")
                val wmFile = File(dir, "wm_$id.jpg")
                if (cleanFile.exists() && wmFile.exists()) {
                    val cfg = WatermarkConfig.fromJson(mf.readText())
                    list.add(PhotoEntry(id = id, cleanFile = cleanFile, watermarkedFile = wmFile, metaFile = mf, config = cfg))
                }
            } catch (_: Exception) {}
        }
        return list.sortedByDescending { it.id }
    }

    fun scanSystemCameraPhotos(context: Context, maxCount: Int = 40): List<SystemCameraPhoto> {
        val results = mutableListOf<SystemCameraPhoto>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_MODIFIED
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)

                var count = 0
                while (cursor.moveToNext() && count < maxCount) {
                    count++
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "IMG_$id.jpg"
                    val dateSec = cursor.getLong(dateCol)
                    val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(dateSec * 1000L))
                    val baseUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

                    val photoUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try { MediaStore.setRequireOriginal(baseUri) } catch (_: Exception) { baseUri }
                    } else baseUri

                    var hasGps = false
                    var gcjLat = 0.0
                    var gcjLon = 0.0
                    var wgsLat = 0.0
                    var wgsLon = 0.0
                    var label = "未包含EXIF定位信息"
                    var verified = false
                    var sigHash = ""

                    try {
                        context.contentResolver.openInputStream(photoUri)?.use { stream ->
                            val exif = ExifInterface(stream)
                            val comment = exif.getAttribute(ExifInterface.TAG_USER_COMMENT)
                            if (!comment.isNullOrBlank() && comment.trim().startsWith("{")) {
                                val parsed = WatermarkConfig.fromJson(comment.trim())
                                hasGps = true
                                gcjLat = parsed.gcjLat
                                gcjLon = parsed.gcjLon
                                wgsLat = parsed.wgsLat
                                wgsLon = parsed.wgsLon
                                verified = parsed.isSignatureValid()
                                sigHash = parsed.antiTamperHash
                                label = parsed.shortPoi.ifBlank { "本机水印照片" }
                            } else {
                                val latLong = FloatArray(2)
                                if (exif.getLatLong(latLong)) {
                                    wgsLat = latLong[0].toDouble()
                                    wgsLon = latLong[1].toDouble()
                                    if (wgsLat != 0.0 || wgsLon != 0.0) {
                                        val gcj = CoordinateConverter.wgs84ToGcj02(wgsLat, wgsLon)
                                        hasGps = true
                                        gcjLat = gcj.lat
                                        gcjLon = gcj.lon
                                        label = String.format(Locale.US, "原相机EXIF (%.4f°N, %.4f°E)", gcjLat, gcjLon)
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}

                    results.add(
                        SystemCameraPhoto(
                            mediaId = id,
                            contentUri = baseUri,
                            displayName = name,
                            dateTakenStr = dateStr,
                            hasGps = hasGps,
                            gcjLat = gcjLat,
                            gcjLon = gcjLon,
                            wgsLat = wgsLat,
                            wgsLon = wgsLon,
                            labelText = label,
                            antiTamperVerified = verified,
                            antiTamperHash = sigHash
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return results
    }

    fun importExternalPhotoUri(context: Context, uri: Uri, fallbackLoc: LocationSnapshot, defaultSys: String): PhotoEntry? {
        return try {
            val originalUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try { MediaStore.setRequireOriginal(uri) } catch (_: Exception) { uri }
            } else uri

            val dir = File(context.filesDir, "captured_photos").apply { mkdirs() }
            val tempFile = File(dir, "temp_import_${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(originalUri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return null

            val parsedCfg = readPhotoMetadataOrExif(tempFile, fallbackLoc, defaultSys)
            val exifRotation = readExifRotationDegrees(tempFile)
            val decodedBmp = BitmapFactory.decodeFile(tempFile.absolutePath)
            tempFile.delete()
            if (decodedBmp == null) return null

            val rotatedBmp = rotateBitmapIfNeeded(decodedBmp, exifRotation)
            if (rotatedBmp !== decodedBmp && !decodedBmp.isRecycled) {
                decodedBmp.recycle()
            }

            val maxDim = max(rotatedBmp.width, rotatedBmp.height)
            val cleanBmp = if (maxDim > 2560) {
                val ratio = 2560f / maxDim
                Bitmap.createScaledBitmap(rotatedBmp, (rotatedBmp.width * ratio).toInt(), (rotatedBmp.height * ratio).toInt(), true).also {
                    if (it !== rotatedBmp && !rotatedBmp.isRecycled) rotatedBmp.recycle()
                }
            } else rotatedBmp

            saveNewPhotoEntry(context, cleanBmp, parsedCfg, alsoExportOriginal = false).also {
                if (!cleanBmp.isRecycled) cleanBmp.recycle()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readPhotoMetadataOrExif(file: File, fallbackLoc: LocationSnapshot, defaultSys: String): WatermarkConfig {
        val (nowHm, nowDateWeek, nowFullTs) = WatermarkConfig.currentTimeStrings(Date(fallbackLoc.trustedCurrentTimeMillis()))
        try {
            val exif = ExifInterface(file.absolutePath)
            val comment = exif.getAttribute(ExifInterface.TAG_USER_COMMENT)
            if (!comment.isNullOrBlank() && comment.trim().startsWith("{")) {
                return WatermarkConfig.fromJson(comment.trim())
            }
            val rawExifDate = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            val (exifHm, exifDateWeek, exifFullTs) = WatermarkConfig.parseExifDateStrings(rawExifDate)

            val latLong = FloatArray(2)
            if (exif.getLatLong(latLong)) {
                val wgsLat = latLong[0].toDouble()
                val wgsLon = latLong[1].toDouble()
                val gcj = CoordinateConverter.wgs84ToGcj02(wgsLat, wgsLon)
                return WatermarkConfig(
                    styleTemplate = 0,
                    coordSystem = defaultSys,
                    gcjLat = gcj.lat,
                    gcjLon = gcj.lon,
                    wgsLat = wgsLat,
                    wgsLon = wgsLon,
                    origGcjLat = gcj.lat,
                    origGcjLon = gcj.lon,
                    accuracyMeters = 3.0f,
                    altitudeMeters = fallbackLoc.altitudeMeters,
                    azimuthDeg = fallbackLoc.azimuthDeg,
                    azimuthText = fallbackLoc.azimuthText,
                    shortPoi = String.format(Locale.US, "EXIF点位(%.4f°N)", gcj.lat),
                    address = String.format(Locale.US, "原相机EXIF坐标: %.6f, %.6f", gcj.lat, gcj.lon),
                    timeHm = exifHm,
                    dateWeek = exifDateWeek,
                    timestamp = exifFullTs,
                    note = "原相机EXIF识别 · 已对齐高德坐标"
                )
            }
        } catch (_: Exception) {}

        return WatermarkConfig(
            styleTemplate = 0,
            coordSystem = defaultSys,
            gcjLat = fallbackLoc.gcjLat,
            gcjLon = fallbackLoc.gcjLon,
            wgsLat = fallbackLoc.wgsLat,
            wgsLon = fallbackLoc.wgsLon,
            origGcjLat = fallbackLoc.gcjLat,
            origGcjLon = fallbackLoc.gcjLon,
            accuracyMeters = fallbackLoc.accuracyMeters,
            altitudeMeters = fallbackLoc.altitudeMeters,
            azimuthDeg = fallbackLoc.azimuthDeg,
            azimuthText = fallbackLoc.azimuthText,
            shortPoi = fallbackLoc.shortPoi,
            address = fallbackLoc.fullAddress,
            timeHm = nowHm,
            dateWeek = nowDateWeek,
            timestamp = nowFullTs,
            note = "外部相册导入"
        )
    }

    private fun decimalToExifDms(coord: Double): String {
        val totalTenThousandthsSec = (abs(coord) * 3600.0 * 10000.0).roundToLong()
        val deg = (totalTenThousandthsSec / 36_000_000L).toInt()
        val remAfterDeg = totalTenThousandthsSec % 36_000_000L
        val min = (remAfterDeg / 600_000L).toInt()
        val secTenThousandths = (remAfterDeg % 600_000L).toInt()
        return "$deg/1,$min/1,$secTenThousandths/10000"
    }
}
