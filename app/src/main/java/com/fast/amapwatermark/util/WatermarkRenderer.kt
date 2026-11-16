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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Watermark configuration stored alongside each captured photo's clean base image.
 * Supports unlimited non-destructive re-rendering in both Portrait and Landscape.
 */
data class WatermarkConfig(
    var styleTemplate: Int = 0,
    var customTitle: String = "现场点位核验",
    var coordSystem: String = "GCJ-02", // "GCJ-02" or "WGS-84"
    var useDms: Boolean = false,
    var gcjLat: Double = 39.908823,
    var gcjLon: Double = 116.397470,
    var wgsLat: Double = 39.907419,
    var wgsLon: Double = 116.391228,
    var origGcjLat: Double = 39.908823,
    var origGcjLon: Double = 116.397470,
    var accuracyMeters: Float = 2.8f,
    var altitudeMeters: Double = 46.0,
    var azimuthText: String = "正北 000°",
    var shortPoi: String = "小庄村",
    var address: String = "",
    var timeHm: String = "19:09",
    var dateWeek: String = "2026-10-19 星期一",
    var timestamp: String = "2026-10-19 19:09:40",
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
    var bottomRight: Boolean = false,
    var filterIndex: Int = 0,
    var bgAlpha: Int = 185,
    var fontScale: Float = 1.0f, // 0.85f (小), 1.0f (标准), 1.15f (大)
    var accentColorIndex: Int = 0, // 0: 天际蓝, 1: 翡翠绿, 2: 琥珀金, 3: 珊瑚红
    var mediaStoreUri: String = ""
) {
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
                coordSystem = o.optString("coordSystem", "GCJ-02"),
                useDms = o.optBoolean("useDms", false),
                gcjLat = o.optDouble("gcjLat", 39.908823),
                gcjLon = o.optDouble("gcjLon", 116.397470),
                wgsLat = o.optDouble("wgsLat", 39.907419),
                wgsLon = o.optDouble("wgsLon", 116.391228),
                origGcjLat = o.optDouble("origGcjLat", 39.908823),
                origGcjLon = o.optDouble("origGcjLon", 116.397470),
                accuracyMeters = o.optDouble("accuracyMeters", 2.8).toFloat(),
                altitudeMeters = o.optDouble("altitudeMeters", 46.0),
                azimuthText = o.optString("azimuthText", "正北 000°"),
                shortPoi = o.optString("shortPoi", "实拍点位"),
                address = o.optString("address", ""),
                timeHm = o.optString("timeHm", "19:09"),
                dateWeek = o.optString("dateWeek", "2026-10-19 星期一"),
                timestamp = o.optString("timestamp", "2026-10-19 19:09:40"),
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
    val labelText: String
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

    /**
     * Decode full-resolution clean base image for lossless watermark re-rendering without downsampling.
     */
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

    // Pooled ThreadLocal Paint & RectF instances to eliminate per-frame allocations on the UI thread
    private class PaintPool {
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val monoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = TYPEFACE_MONO_BOLD }
        val rectF = RectF()
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

    fun drawWatermarkOverlay(canvas: Canvas, width: Int, height: Int, cfg: WatermarkConfig) {
        val isLandscape = width > height
        val orientFactor = if (isLandscape) 0.86f else 1.0f
        val baseScale = (min(width, height) / 1080f) * orientFactor * cfg.fontScale.coerceIn(0.78f, 1.25f)

        when (cfg.styleTemplate) {
            0 -> drawStyle0CenteredBigTime(canvas, width, height, baseScale, isLandscape, cfg)
            1 -> drawStyle1EngineeringCard(canvas, width, height, baseScale, isLandscape, cfg)
            2 -> drawStyle2GisSurveyGrid(canvas, width, height, baseScale, isLandscape, cfg)
            3 -> drawStyle3MinimalistFilmBar(canvas, width, height, baseScale, isLandscape, cfg)
            4 -> drawStyle4LedgerTable(canvas, width, height, baseScale, isLandscape, cfg)
            else -> drawStyle0CenteredBigTime(canvas, width, height, baseScale, isLandscape, cfg)
        }

        if (cfg.showQrCode) {
            drawAmapQrBadge(canvas, width, height, baseScale, isLandscape, cfg)
        }
    }

    /**
     * Draw sleek Amap Location QR Badge in the opposite corner (or top-right of footer)
     * so friends who receive the image on WeChat can long-press or scan to jump to the exact GCJ-02 point!
     */
    private fun drawAmapQrBadge(
        canvas: Canvas,
        width: Int,
        height: Int,
        scale: Float,
        isLandscape: Boolean,
        cfg: WatermarkConfig
    ) {
        val pool = paintPool.get()!!
        val url = QrCodeGenerator.buildAmapJumpUrl(cfg.gcjLat, cfg.gcjLon, cfg.shortPoi)
        val matrix = QrCodeGenerator.encodeUrlToMatrix(url)
        val modules = matrix.size
        val qrBoxSize = (if (isLandscape) 128f else 142f) * scale
        val labelH = 26f * scale
        val totalH = qrBoxSize + labelH
        val margin = 26f * scale

        // Position QR badge so it never collides with the active template
        val placeLeft = cfg.bottomRight && cfg.styleTemplate in listOf(1, 2, 4)
        val boxLeft = if (placeLeft) margin else (width - qrBoxSize - margin)
        val bottomOffset = if (cfg.styleTemplate == 3) (if (isLandscape) 104f else 118f) * scale else margin
        val boxTop = height - totalH - bottomOffset

        val accentColor = ACCENT_COLORS.getOrElse(cfg.accentColorIndex) { ACCENT_COLORS[0] }
        pool.fillPaint.shader = null
        pool.fillPaint.color = Color.argb(cfg.bgAlpha.coerceIn(135, 240), 11, 19, 43)
        pool.rectF.set(boxLeft, boxTop, boxLeft + qrBoxSize, boxTop + totalH)
        canvas.drawRoundRect(pool.rectF, 14f * scale, 14f * scale, pool.fillPaint)

        pool.strokePaint.color = Color.argb(140, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        pool.strokePaint.strokeWidth = 1.6f * scale
        canvas.drawRoundRect(pool.rectF, 14f * scale, 14f * scale, pool.strokePaint)

        // White quiet zone inside QR card
        val pad = 10f * scale
        val whiteSize = qrBoxSize - pad * 2f
        val whiteLeft = boxLeft + pad
        val whiteTop = boxTop + pad
        pool.fillPaint.color = Color.WHITE
        pool.rectF.set(whiteLeft, whiteTop, whiteLeft + whiteSize, whiteTop + whiteSize)
        canvas.drawRoundRect(pool.rectF, 6f * scale, 6f * scale, pool.fillPaint)

        val quietPad = 5f * scale
        val drawSize = whiteSize - quietPad * 2f
        val cellSize = drawSize / modules.toFloat()
        val startX = whiteLeft + quietPad
        val startY = whiteTop + quietPad

        pool.fillPaint.color = Color.rgb(11, 19, 43)
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

        // Caption "扫码跳高德"
        pool.textPaint.apply {
            clearShadowLayer()
            color = accentColor
            textSize = 15.5f * scale
            typeface = TYPEFACE_DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("扫码跳高德", boxLeft + qrBoxSize / 2f, boxTop + qrBoxSize + 18f * scale, pool.textPaint)
    }

    private fun buildCoordString(cfg: WatermarkConfig): String {
        val shownLat = if (cfg.coordSystem == "WGS-84") cfg.wgsLat else cfg.gcjLat
        val shownLon = if (cfg.coordSystem == "WGS-84") cfg.wgsLon else cfg.gcjLon
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
     * Style 0: 经典大字时间
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
        val cx = width / 2f
        val fullCoordLine = buildCoordString(cfg)
        val envLine = buildEnvTelemetryLine(cfg)
        val accentColor = ACCENT_COLORS.getOrElse(cfg.accentColorIndex) { ACCENT_COLORS[0] }

        val scrimH = if (isLandscape) 280f * scale else 360f * scale
        val scrimTop = height - scrimH
        pool.fillPaint.shader = LinearGradient(
            0f, scrimTop, 0f, height.toFloat(),
            Color.TRANSPARENT, Color.argb(145, 0, 0, 0),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, scrimTop, width.toFloat(), height.toFloat(), pool.fillPaint)
        pool.fillPaint.shader = null

        var cursorBottomY = height - (if (isLandscape) 28f * scale else 38f * scale)
        val qrReserve = if (cfg.showQrCode) 165f * scale else 0f
        val maxLineW = (width - qrReserve * 2f).coerceAtLeast(width * 0.62f)

        if (cfg.showNote && cfg.note.isNotBlank()) {
            pool.textPaint.apply {
                color = Color.rgb(253, 230, 138)
                textSize = 25f * scale
                typeface = Typeface.DEFAULT
                textAlign = Paint.Align.CENTER
                setShadowLayer(6f * scale, 0f, 2f * scale, Color.argb(200, 0, 0, 0))
            }
            val clippedNote = ellipsizeToWidth(cfg.note, pool.textPaint, maxLineW)
            canvas.drawText(clippedNote, cx, cursorBottomY, pool.textPaint)
            cursorBottomY -= 36f * scale
        }

        if (envLine.isNotEmpty()) {
            pool.textPaint.apply {
                color = Color.rgb(167, 243, 208)
                textSize = 22f * scale
                typeface = TYPEFACE_MONO_BOLD
                textAlign = Paint.Align.CENTER
                setShadowLayer(6f * scale, 0f, 2f * scale, Color.argb(200, 0, 0, 0))
            }
            canvas.drawText(ellipsizeToWidth(envLine, pool.textPaint, maxLineW), cx, cursorBottomY, pool.textPaint)
            cursorBottomY -= 34f * scale
        }

        if (cfg.showAddress && cfg.address.isNotBlank()) {
            pool.textPaint.apply {
                color = Color.argb(235, 241, 245, 249)
                textSize = 24f * scale
                typeface = Typeface.DEFAULT
                textAlign = Paint.Align.CENTER
                setShadowLayer(6f * scale, 0f, 2f * scale, Color.argb(200, 0, 0, 0))
            }
            val clippedAddr = ellipsizeToWidth(cfg.address, pool.textPaint, maxLineW)
            canvas.drawText(clippedAddr, cx, cursorBottomY, pool.textPaint)
            cursorBottomY -= 36f * scale
        }

        if (cfg.showCoords) {
            pool.monoPaint.apply {
                clearShadowLayer()
                color = accentColor
                textSize = 24.5f * scale
                typeface = TYPEFACE_MONO_BOLD
            }
            val clippedCoord = ellipsizeToWidth(fullCoordLine, pool.monoPaint, maxLineW - 44f * scale)
            val textW = pool.monoPaint.measureText(clippedCoord)
            val pillPadX = 22f * scale
            val pillH = 40f * scale
            val pillTop = cursorBottomY - 29f * scale
            pool.rectF.set(cx - textW / 2f - pillPadX, pillTop, cx + textW / 2f + pillPadX, pillTop + pillH)

            pool.fillPaint.color = Color.argb(cfg.bgAlpha.coerceIn(90, 240), 11, 19, 43)
            pool.strokePaint.color = Color.argb(95, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
            pool.strokePaint.strokeWidth = 1.5f * scale

            canvas.drawRoundRect(pool.rectF, 20f * scale, 20f * scale, pool.fillPaint)
            canvas.drawRoundRect(pool.rectF, 20f * scale, 20f * scale, pool.strokePaint)

            pool.monoPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(clippedCoord, cx, cursorBottomY, pool.monoPaint)
            cursorBottomY -= 48f * scale
        }

        if (cfg.showTime || cfg.showLocation) {
            pool.textPaint.apply {
                color = Color.WHITE
                textSize = 34f * scale
                typeface = Typeface.DEFAULT
                setShadowLayer(8f * scale, 0f, 2.5f * scale, Color.argb(190, 0, 0, 0))
            }
            val datePart = if (cfg.showTime) cfg.dateWeek else ""
            val rawPoi = if (cfg.showLocation) cfg.shortPoi else ""
            val poiPart = ellipsizeToWidth(rawPoi, pool.textPaint, maxLineW * 0.52f)
            val pinWidth = if (poiPart.isNotEmpty()) 32f * scale else 0f
            val dateW = if (datePart.isNotEmpty()) pool.textPaint.measureText("$datePart  ") else 0f
            val poiW = if (poiPart.isNotEmpty()) pool.textPaint.measureText(poiPart) else 0f
            val totalW = dateW + pinWidth + poiW
            var startX = cx - totalW / 2f

            if (datePart.isNotEmpty()) {
                pool.textPaint.textAlign = Paint.Align.LEFT
                canvas.drawText("$datePart  ", startX, cursorBottomY, pool.textPaint)
                startX += dateW
            }
            if (poiPart.isNotEmpty()) {
                val pinCenterX = startX + 13f * scale
                val pinHeadY = cursorBottomY - 20f * scale
                pool.strokePaint.apply {
                    color = Color.rgb(220, 38, 38)
                    strokeWidth = 3.2f * scale
                    strokeCap = Paint.Cap.ROUND
                }
                canvas.drawLine(pinCenterX, pinHeadY + 5f * scale, pinCenterX, cursorBottomY - 2f * scale, pool.strokePaint)
                pool.fillPaint.color = Color.rgb(239, 68, 68)
                canvas.drawCircle(pinCenterX, pinHeadY, 9f * scale, pool.fillPaint)
                pool.fillPaint.color = Color.rgb(254, 202, 202)
                canvas.drawCircle(pinCenterX - 2.2f * scale, pinHeadY - 2.2f * scale, 2.5f * scale, pool.fillPaint)

                startX += pinWidth
                pool.textPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(poiPart, startX, cursorBottomY, pool.textPaint)
            }
            cursorBottomY -= (if (isLandscape) 66f * scale else 76f * scale)
        }

        if (cfg.showTime) {
            pool.textPaint.apply {
                color = Color.WHITE
                textSize = (if (isLandscape) 104f else 120f) * scale
                textAlign = Paint.Align.CENTER
                typeface = TYPEFACE_SANS_LIGHT
                setShadowLayer(10f * scale, 0f, 3f * scale, Color.argb(180, 0, 0, 0))
            }
            canvas.drawText(cfg.timeHm, cx, cursorBottomY, pool.textPaint)
        }
    }

    /**
     * Style 1: 工程巡检卡片
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
        val lines = mutableListOf<Pair<String, String>>()
        if (cfg.showCoords) {
            lines.add("经纬" to buildCoordString(cfg))
        }
        val envLine = buildEnvTelemetryLine(cfg)
        if (envLine.isNotEmpty()) {
            lines.add("环境" to envLine)
        }
        if (cfg.showLocation && cfg.shortPoi.isNotBlank()) {
            val locStr = if (cfg.showAddress && cfg.address.isNotBlank()) "${cfg.shortPoi} · ${cfg.address}" else cfg.shortPoi
            lines.add("地点" to locStr)
        } else if (cfg.showAddress && cfg.address.isNotBlank()) {
            lines.add("地点" to cfg.address)
        }
        if (cfg.showTime && cfg.timestamp.isNotBlank()) {
            lines.add("时间" to "${cfg.timestamp} ${cfg.dateWeek.substringAfter(" ")}")
        }
        if (cfg.showNote && cfg.note.isNotBlank()) {
            lines.add("备注" to cfg.note)
        }
        if (lines.isEmpty()) {
            lines.add("状态" to "高德 ${cfg.coordSystem} 零偏差已对齐")
        }

        val margin = 28f * scale
        val qrSpace = if (cfg.showQrCode) 172f * scale else 0f
        val maxAllowedBoxW = if (isLandscape) (width * 0.56f) else (width - margin * 2 - qrSpace)
        val padX = 24f * scale
        val padY = 20f * scale
        val lineGap = 38f * scale
        val headerHeight = 46f * scale

        pool.monoPaint.apply {
            clearShadowLayer()
            textSize = 24.5f * scale
            color = Color.WHITE
            typeface = TYPEFACE_MONO_BOLD
            textAlign = Paint.Align.LEFT
        }

        val titleText = cfg.customTitle.ifBlank { "现场点位核验" }
        var naturalMaxW = pool.monoPaint.measureText(titleText) + 160f * scale
        for ((label, value) in lines) {
            val w = pool.monoPaint.measureText("$label：$value")
            if (w > naturalMaxW) naturalMaxW = w
        }

        val boxW = (naturalMaxW + padX * 2).coerceIn(360f * scale, maxAllowedBoxW.coerceAtLeast(380f * scale))
        val boxH = padY * 2 + headerHeight + lines.size * lineGap
        val boxLeft = if (cfg.bottomRight) (width - boxW - margin) else margin
        val boxTop = height - boxH - margin

        val accentColor = ACCENT_COLORS.getOrElse(cfg.accentColorIndex) { ACCENT_COLORS[0] }
        pool.fillPaint.shader = null
        pool.fillPaint.color = Color.argb(cfg.bgAlpha.coerceIn(90, 240), 11, 19, 43)
        pool.strokePaint.color = Color.argb(95, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        pool.strokePaint.strokeWidth = 1.8f * scale
        pool.rectF.set(boxLeft, boxTop, boxLeft + boxW, boxTop + boxH)
        canvas.drawRoundRect(pool.rectF, 18f * scale, 18f * scale, pool.fillPaint)
        canvas.drawRoundRect(pool.rectF, 18f * scale, 18f * scale, pool.strokePaint)

        pool.fillPaint.color = accentColor
        pool.rectF.set(boxLeft + padX, boxTop + padY, boxLeft + padX + 7f * scale, boxTop + padY + 28f * scale)
        canvas.drawRoundRect(pool.rectF, 3.5f * scale, 3.5f * scale, pool.fillPaint)

        pool.textPaint.apply {
            clearShadowLayer()
            color = Color.WHITE
            textSize = 27f * scale
            typeface = TYPEFACE_DEFAULT_BOLD
            textAlign = Paint.Align.LEFT
        }
        val maxTitleW = boxW - padX * 2 - 130f * scale
        canvas.drawText(ellipsizeToWidth(titleText, pool.textPaint, maxTitleW), boxLeft + padX + 16f * scale, boxTop + padY + 24f * scale, pool.textPaint)

        if (cfg.showCoordTag) {
            pool.fillPaint.color = Color.argb(55, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
            val badgeW = 96f * scale
            pool.rectF.set(boxLeft + boxW - padX - badgeW, boxTop + padY - 2f * scale, boxLeft + boxW - padX, boxTop + padY + 30f * scale)
            canvas.drawRoundRect(pool.rectF, 8f * scale, 8f * scale, pool.fillPaint)

            pool.monoPaint.apply {
                color = accentColor
                textSize = 20f * scale
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(cfg.coordSystem, pool.rectF.centerX(), boxTop + padY + 21f * scale, pool.monoPaint)
        }

        pool.strokePaint.color = Color.argb(45, 255, 255, 255)
        pool.strokePaint.strokeWidth = 1.2f * scale
        canvas.drawLine(boxLeft + padX, boxTop + padY + headerHeight - 8f * scale, boxLeft + boxW - padX, boxTop + padY + headerHeight - 8f * scale, pool.strokePaint)

        val maxContentW = boxW - padX * 2
        pool.monoPaint.textSize = 24f * scale
        pool.monoPaint.textAlign = Paint.Align.LEFT
        lines.forEachIndexed { idx, (label, value) ->
            val y = boxTop + padY + headerHeight + (idx + 0.78f) * lineGap
            pool.monoPaint.color = Color.rgb(148, 163, 184)
            val prefix = "$label："
            canvas.drawText(prefix, boxLeft + padX, y, pool.monoPaint)
            val lw = pool.monoPaint.measureText(prefix)
            pool.monoPaint.color = when (label) {
                "经纬" -> accentColor
                "环境" -> Color.rgb(167, 243, 208)
                "备注" -> Color.rgb(253, 230, 138)
                else -> Color.WHITE
            }
            val clippedVal = ellipsizeToWidth(value, pool.monoPaint, maxContentW - lw)
            canvas.drawText(clippedVal, boxLeft + padX + lw, y, pool.monoPaint)
        }
    }

    /**
     * Style 2: 经纬网格测绘
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
        val margin = 28f * scale
        val qrSpace = if (cfg.showQrCode) 172f * scale else 0f
        val maxAllowedW = if (isLandscape) (width * 0.58f) else (width - margin * 2 - qrSpace)
        val boxW = (680f * scale).coerceAtMost(maxAllowedW.coerceAtLeast(380f * scale))
        val envLine = buildEnvTelemetryLine(cfg)
        val hasFooter = (cfg.showAddress && cfg.address.isNotBlank()) || (cfg.showNote && cfg.note.isNotBlank()) || envLine.isNotEmpty()
        val boxH = if (hasFooter) 196f * scale else 150f * scale
        val boxLeft = if (cfg.bottomRight) (width - boxW - margin) else margin
        val boxTop = height - boxH - margin

        val accentColor = ACCENT_COLORS.getOrElse(cfg.accentColorIndex) { ACCENT_COLORS[0] }
        pool.fillPaint.shader = null
        pool.fillPaint.color = Color.argb(cfg.bgAlpha.coerceIn(90, 240), 9, 14, 26)
        pool.rectF.set(boxLeft, boxTop, boxLeft + boxW, boxTop + boxH)
        canvas.drawRoundRect(pool.rectF, 16f * scale, 16f * scale, pool.fillPaint)

        val headerH = 56f * scale
        pool.fillPaint.color = Color.argb(48, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        pool.rectF.set(boxLeft, boxTop, boxLeft + boxW, boxTop + headerH)
        canvas.drawRoundRect(pool.rectF, 16f * scale, 16f * scale, pool.fillPaint)

        pool.strokePaint.color = Color.argb(130, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        pool.strokePaint.strokeWidth = 1.8f * scale
        pool.rectF.set(boxLeft, boxTop, boxLeft + boxW, boxTop + boxH)
        canvas.drawRoundRect(pool.rectF, 16f * scale, 16f * scale, pool.strokePaint)

        pool.monoPaint.apply {
            clearShadowLayer()
            color = accentColor
            textSize = 24f * scale
            typeface = TYPEFACE_MONO_BOLD
            textAlign = Paint.Align.LEFT
        }
        val headerLineText = if (cfg.showCoords) {
            buildCoordString(cfg)
        } else {
            "${cfg.customTitle.ifBlank { "经纬网格测绘" }} · ${cfg.coordSystem}"
        }
        canvas.drawText(ellipsizeToWidth(headerLineText, pool.monoPaint, boxW - 36f * scale), boxLeft + 18f * scale, boxTop + 37f * scale, pool.monoPaint)

        pool.strokePaint.color = Color.argb(55, 148, 163, 184)
        pool.strokePaint.strokeWidth = 1.2f * scale
        canvas.drawLine(boxLeft, boxTop + headerH, boxLeft + boxW, boxTop + headerH, pool.strokePaint)

        val midX = boxLeft + boxW * 0.48f
        val row2Bottom = boxTop + headerH + 88f * scale
        canvas.drawLine(midX, boxTop + headerH, midX, row2Bottom, pool.strokePaint)

        pool.textPaint.apply {
            clearShadowLayer()
            color = Color.rgb(148, 163, 184)
            textSize = 19f * scale
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.LEFT
        }

        val leftLabel = if (cfg.showLocation) "测绘点位" else "核验项目"
        val leftValRaw = if (cfg.showLocation) cfg.shortPoi.ifBlank { "实拍点位" } else cfg.customTitle.ifBlank { "现场点位核验" }
        canvas.drawText(leftLabel, boxLeft + 18f * scale, boxTop + headerH + 30f * scale, pool.textPaint)

        pool.textPaint.apply {
            color = Color.WHITE
            textSize = 24f * scale
            typeface = TYPEFACE_DEFAULT_BOLD
        }
        canvas.drawText(ellipsizeToWidth(leftValRaw, pool.textPaint, boxW * 0.48f - 28f * scale), boxLeft + 18f * scale, boxTop + headerH + 68f * scale, pool.textPaint)

        pool.textPaint.apply {
            color = Color.rgb(148, 163, 184)
            textSize = 19f * scale
            typeface = Typeface.DEFAULT
        }
        val rightLabel = if (cfg.showTime) "采样时间" else "定位基准"
        val rightValRaw = if (cfg.showTime) cfg.timestamp else "${cfg.coordSystem} 零偏差对齐"
        canvas.drawText(rightLabel, midX + 18f * scale, boxTop + headerH + 30f * scale, pool.textPaint)

        pool.monoPaint.apply {
            color = Color.WHITE
            textSize = 21.5f * scale
        }
        canvas.drawText(ellipsizeToWidth(rightValRaw, pool.monoPaint, boxW * 0.52f - 28f * scale), midX + 18f * scale, boxTop + headerH + 68f * scale, pool.monoPaint)

        if (hasFooter) {
            canvas.drawLine(boxLeft, row2Bottom, boxLeft + boxW, row2Bottom, pool.strokePaint)
            pool.textPaint.apply {
                color = Color.rgb(253, 230, 138)
                textSize = 20.5f * scale
                typeface = Typeface.DEFAULT
            }
            val footerRaw = buildString {
                if (envLine.isNotEmpty()) append(envLine)
                if (cfg.showAddress && cfg.address.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(cfg.address)
                }
                if (cfg.showNote && cfg.note.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(cfg.note)
                }
            }
            canvas.drawText(ellipsizeToWidth(footerRaw, pool.textPaint, boxW - 36f * scale), boxLeft + 18f * scale, row2Bottom + 34f * scale, pool.textPaint)
        }
    }

    /**
     * Style 3: 极简胶片底栏
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
        val barH = (if (isLandscape) 92f else 106f) * scale
        val barTop = height - barH
        pool.fillPaint.shader = null
        pool.fillPaint.color = Color.argb(cfg.bgAlpha.coerceIn(110, 245), 8, 12, 20)
        canvas.drawRect(0f, barTop, width.toFloat(), height.toFloat(), pool.fillPaint)

        val accentColor = ACCENT_COLORS.getOrElse(cfg.accentColorIndex) { ACCENT_COLORS[0] }
        pool.strokePaint.color = accentColor
        pool.strokePaint.strokeWidth = 2.5f * scale
        canvas.drawLine(0f, barTop, width.toFloat(), barTop, pool.strokePaint)

        val padX = 26f * scale
        val maxLeftW = width * 0.44f
        val maxRightW = width * 0.50f

        pool.textPaint.apply {
            clearShadowLayer()
            color = Color.WHITE
            textSize = 28f * scale
            typeface = TYPEFACE_DEFAULT_BOLD
            textAlign = Paint.Align.LEFT
        }
        val leftPrimary = if (cfg.showLocation && cfg.shortPoi.isNotBlank()) {
            cfg.shortPoi
        } else {
            cfg.customTitle.ifBlank { "极速高德水印" }
        }
        canvas.drawText(ellipsizeToWidth(leftPrimary, pool.textPaint, maxLeftW), padX, barTop + 40f * scale, pool.textPaint)

        val envLine = buildEnvTelemetryLine(cfg)
        pool.textPaint.apply {
            color = Color.rgb(148, 163, 184)
            textSize = 21f * scale
            typeface = Typeface.DEFAULT
        }
        val subLeftRaw = when {
            cfg.showNote && cfg.note.isNotBlank() -> cfg.note
            envLine.isNotEmpty() -> envLine
            cfg.showAddress && cfg.address.isNotBlank() -> cfg.address
            cfg.showTime -> cfg.dateWeek
            else -> cfg.customTitle
        }
        canvas.drawText(ellipsizeToWidth(subLeftRaw, pool.textPaint, maxLeftW), padX, barTop + 76f * scale, pool.textPaint)

        if (cfg.showCoords) {
            pool.monoPaint.apply {
                clearShadowLayer()
                color = accentColor
                textSize = 23f * scale
                textAlign = Paint.Align.RIGHT
                typeface = TYPEFACE_MONO_BOLD
            }
            val coordClipped = ellipsizeToWidth(buildCoordString(cfg), pool.monoPaint, maxRightW)
            canvas.drawText(coordClipped, width - padX, barTop + 40f * scale, pool.monoPaint)
        }

        if (cfg.showTime) {
            pool.monoPaint.apply {
                color = Color.rgb(226, 232, 240)
                textSize = 21f * scale
                textAlign = Paint.Align.RIGHT
                typeface = TYPEFACE_MONO_NORMAL
            }
            val timeLine = "${cfg.timestamp} ${cfg.dateWeek.substringAfter(" ")}"
            val yPos = if (cfg.showCoords) (barTop + 76f * scale) else (barTop + 56f * scale)
            canvas.drawText(ellipsizeToWidth(timeLine, pool.monoPaint, maxRightW), width - padX, yPos, pool.monoPaint)
        }
    }

    /**
     * Style 4: 外业标准台账 (Upgraded with modern rounded border & sleek header fill)
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
        val margin = 28f * scale
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
        if (rows.isEmpty()) rows.add("核验状态" to "高德GCJ-02已对齐")

        val headerH = 44f * scale
        val rowH = 40f * scale
        val qrSpace = if (cfg.showQrCode) 172f * scale else 0f
        val maxAllowedW = if (isLandscape) (width * 0.58f) else (width - margin * 2 - qrSpace)
        val boxW = (700f * scale).coerceAtMost(maxAllowedW.coerceAtLeast(380f * scale))
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
            textSize = 24f * scale
            typeface = TYPEFACE_DEFAULT_BOLD
            textAlign = Paint.Align.LEFT
        }
        val titleStr = "${cfg.customTitle.ifBlank { "外业定点巡检台账" }} · ${cfg.coordSystem}"
        canvas.drawText(ellipsizeToWidth(titleStr, pool.textPaint, boxW - 28f * scale), boxLeft + 14f * scale, boxTop + 30f * scale, pool.textPaint)

        val colSplitX = boxLeft + 136f * scale
        pool.strokePaint.strokeWidth = 1.2f * scale
        canvas.drawLine(boxLeft, boxTop + headerH, boxLeft + boxW, boxTop + headerH, pool.strokePaint)
        canvas.drawLine(colSplitX, boxTop + headerH, colSplitX, boxTop + boxH, pool.strokePaint)

        pool.monoPaint.apply {
            clearShadowLayer()
            textSize = 22f * scale
            typeface = TYPEFACE_MONO_BOLD
            textAlign = Paint.Align.LEFT
        }
        val maxValW = (boxLeft + boxW) - colSplitX - 24f * scale

        rows.forEachIndexed { i, (k, v) ->
            val topY = boxTop + headerH + i * rowH
            if (i > 0) {
                canvas.drawLine(boxLeft, topY, boxLeft + boxW, topY, pool.strokePaint)
            }
            val baselineY = topY + 28f * scale
            pool.monoPaint.color = accentColor
            canvas.drawText(k, boxLeft + 14f * scale, baselineY, pool.monoPaint)
            pool.monoPaint.color = when (k) {
                "现场备注" -> Color.rgb(253, 230, 138)
                "高程方位" -> Color.rgb(167, 243, 208)
                else -> Color.WHITE
            }
            val clippedV = ellipsizeToWidth(v, pool.monoPaint, maxValW)
            canvas.drawText(clippedV, colSplitX + 12f * scale, baselineY, pool.monoPaint)
        }
    }

    fun saveJpegWithDualTrackExif(bitmap: Bitmap, targetFile: File, cfg: WatermarkConfig) {
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
            } catch (_: Exception) {}
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
                            labelText = label
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

            // Apply EXIF orientation so portrait photos from system cameras never lie sideways!
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
        val (nowHm, nowDateWeek, nowFullTs) = WatermarkConfig.currentTimeStrings()
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
