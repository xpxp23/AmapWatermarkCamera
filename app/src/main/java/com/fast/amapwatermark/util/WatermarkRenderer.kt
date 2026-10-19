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
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Watermark configuration stored alongside each captured photo's clean base image.
 * Supports unlimited non-destructive re-rendering in both Portrait and Landscape.
 *
 * styleTemplate:
 *   0 = 经典大字时间 (Big centered time + Date/Weekday/📍POI + Lat/Lon pill)
 *   1 = 工程巡检卡片 (Structured glassmorphism card with custom title & accent bar)
 *   2 = 经纬网格测绘 (GIS/Survey HUD coordinate banner + 2-column grid)
 *   3 = 极简胶片底栏 (Full-width bottom cinema footer bar, zero scene obstruction)
 *   4 = 外业标准台账 (Bordered engineering ledger table with strict cell clipping)
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

        fun currentTimeStrings(): Triple<String, String, String> {
            val now = Date()
            val hm = SimpleDateFormat("HH:mm", Locale.CHINA).format(now)
            val datePart = SimpleDateFormat("yyyy-MM-dd EEEE", Locale.CHINA).format(now)
            val fullTs = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(now)
            return Triple(hm, datePart, fullTs)
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
        cache.evictAll()
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
    val ACCENT_COLORS = listOf(
        Color.rgb(56, 189, 248),  // 天际蓝
        Color.rgb(52, 211, 153),  // 翡翠绿
        Color.rgb(251, 191, 36),  // 琥珀金
        Color.rgb(248, 113, 113)  // 珊瑚红
    )

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

    /**
     * Render watermark onto a clean Bitmap (used when saving photos or re-editing watermarks).
     */
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

    /**
     * Shared 100% WYSIWYG Canvas Watermark Renderer!
     * Called BOTH by the Live Camera Viewfinder (with orientation matrix applied)
     * AND by renderWatermarkedBitmap() on capture!
     * Guarantees zero discrepancy between live preview and captured photo in Portrait & Landscape!
     */
    fun drawWatermarkOverlay(canvas: Canvas, width: Int, height: Int, cfg: WatermarkConfig) {
        val isLandscape = width > height
        // In landscape (e.g. 1440x1080), scale slightly more compactly (0.86x) so vertical height is sleek
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
    }

    private fun buildCoordString(cfg: WatermarkConfig): String {
        val shownLat = if (cfg.coordSystem == "WGS-84") cfg.wgsLat else cfg.gcjLat
        val shownLon = if (cfg.coordSystem == "WGS-84") cfg.wgsLon else cfg.gcjLon
        val coordStr = CoordinateConverter.formatCoordinate(shownLat, shownLon, cfg.useDms)
        val sysTag = if (cfg.showCoordTag) " [${cfg.coordSystem}]" else ""
        val accTag = if (cfg.showAccuracy) String.format(Locale.US, " ±%.1fm", cfg.accuracyMeters) else ""
        return "$coordStr$sysTag$accTag"
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
     * In Portrait: Stacked centered big time + Date/Weekday/📍POI + Frosted Coordinate Pill
     * In Landscape: Compact vertical spacing with refined glass pill so it never crowds the center
     */
    private fun drawStyle0CenteredBigTime(
        canvas: Canvas,
        width: Int,
        height: Int,
        scale: Float,
        isLandscape: Boolean,
        cfg: WatermarkConfig
    ) {
        val cx = width / 2f
        val fullCoordLine = buildCoordString(cfg)
        val accentColor = ACCENT_COLORS.getOrElse(cfg.accentColorIndex) { ACCENT_COLORS[0] }

        val scrimH = if (isLandscape) 270f * scale else 340f * scale
        val scrimTop = height - scrimH
        val scrimPaint = Paint().apply {
            shader = LinearGradient(
                0f, scrimTop, 0f, height.toFloat(),
                Color.TRANSPARENT, Color.argb(135, 0, 0, 0),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, scrimTop, width.toFloat(), height.toFloat(), scrimPaint)

        var cursorBottomY = height - (if (isLandscape) 28f * scale else 38f * scale)
        val maxLineW = width * 0.86f

        if (cfg.showNote && cfg.note.isNotBlank()) {
            val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(253, 230, 138)
                textSize = 25f * scale
                textAlign = Paint.Align.CENTER
                setShadowLayer(6f * scale, 0f, 2f * scale, Color.argb(200, 0, 0, 0))
            }
            val clippedNote = ellipsizeToWidth(cfg.note, notePaint, maxLineW)
            canvas.drawText(clippedNote, cx, cursorBottomY, notePaint)
            cursorBottomY -= 36f * scale
        }

        if (cfg.showAddress && cfg.address.isNotBlank()) {
            val addrPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(235, 241, 245, 249)
                textSize = 24f * scale
                textAlign = Paint.Align.CENTER
                setShadowLayer(6f * scale, 0f, 2f * scale, Color.argb(200, 0, 0, 0))
            }
            val clippedAddr = ellipsizeToWidth(cfg.address, addrPaint, maxLineW)
            canvas.drawText(clippedAddr, cx, cursorBottomY, addrPaint)
            cursorBottomY -= 36f * scale
        }

        if (cfg.showCoords) {
            val coordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accentColor
                textSize = 25f * scale
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            }
            val clippedCoord = ellipsizeToWidth(fullCoordLine, coordPaint, maxLineW - 44f * scale)
            val textW = coordPaint.measureText(clippedCoord)
            val pillPadX = 22f * scale
            val pillH = 40f * scale
            val pillTop = cursorBottomY - 29f * scale
            val pillRect = RectF(cx - textW / 2f - pillPadX, pillTop, cx + textW / 2f + pillPadX, pillTop + pillH)

            val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(cfg.bgAlpha.coerceIn(110, 235), 11, 19, 43)
                style = Paint.Style.FILL
            }
            val pillBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(95, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
                style = Paint.Style.STROKE
                strokeWidth = 1.5f * scale
            }
            canvas.drawRoundRect(pillRect, 20f * scale, 20f * scale, pillBgPaint)
            canvas.drawRoundRect(pillRect, 20f * scale, 20f * scale, pillBorderPaint)

            coordPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(clippedCoord, cx, cursorBottomY, coordPaint)
            cursorBottomY -= 48f * scale
        }

        if (cfg.showTime || cfg.showLocation) {
            val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 34f * scale
                setShadowLayer(8f * scale, 0f, 2.5f * scale, Color.argb(190, 0, 0, 0))
            }
            val datePart = if (cfg.showTime) cfg.dateWeek else ""
            val rawPoi = if (cfg.showLocation) cfg.shortPoi else ""
            val poiPart = ellipsizeToWidth(rawPoi, subPaint, width * 0.48f)
            val pinWidth = if (poiPart.isNotEmpty()) 32f * scale else 0f
            val dateW = if (datePart.isNotEmpty()) subPaint.measureText("$datePart  ") else 0f
            val poiW = if (poiPart.isNotEmpty()) subPaint.measureText(poiPart) else 0f
            val totalW = dateW + pinWidth + poiW
            var startX = cx - totalW / 2f

            if (datePart.isNotEmpty()) {
                subPaint.textAlign = Paint.Align.LEFT
                canvas.drawText("$datePart  ", startX, cursorBottomY, subPaint)
                startX += dateW
            }
            if (poiPart.isNotEmpty()) {
                val pinCenterX = startX + 13f * scale
                val pinHeadY = cursorBottomY - 20f * scale
                val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(239, 68, 68)
                    style = Paint.Style.FILL
                }
                val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(220, 38, 38)
                    strokeWidth = 3.2f * scale
                    strokeCap = Paint.Cap.ROUND
                }
                canvas.drawLine(pinCenterX, pinHeadY + 5f * scale, pinCenterX, cursorBottomY - 2f * scale, needlePaint)
                canvas.drawCircle(pinCenterX, pinHeadY, 9f * scale, pinPaint)
                val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(254, 202, 202)
                }
                canvas.drawCircle(pinCenterX - 2.2f * scale, pinHeadY - 2.2f * scale, 2.5f * scale, highlightPaint)

                startX += pinWidth
                subPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(poiPart, startX, cursorBottomY, subPaint)
            }
            cursorBottomY -= (if (isLandscape) 66f * scale else 76f * scale)
        }

        if (cfg.showTime) {
            val bigTimePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = (if (isLandscape) 104f else 120f) * scale
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                setShadowLayer(10f * scale, 0f, 3f * scale, Color.argb(180, 0, 0, 0))
            }
            canvas.drawText(cfg.timeHm, cx, cursorBottomY, bigTimePaint)
        }
    }

    /**
     * Style 1: 工程巡检卡片 (Compact glassmorphism card with bounded width & accent badge)
     */
    private fun drawStyle1EngineeringCard(
        canvas: Canvas,
        width: Int,
        height: Int,
        scale: Float,
        isLandscape: Boolean,
        cfg: WatermarkConfig
    ) {
        val lines = mutableListOf<Pair<String, String>>()
        if (cfg.showCoords) {
            lines.add("经纬" to buildCoordString(cfg))
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
        if (lines.isEmpty()) return

        val margin = 28f * scale
        val maxAllowedBoxW = if (isLandscape) (width * 0.56f) else (width - margin * 2)
        val padX = 24f * scale
        val padY = 20f * scale
        val lineGap = 38f * scale
        val headerHeight = 46f * scale

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 25f * scale
            color = Color.WHITE
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }

        val titleText = cfg.customTitle.ifBlank { "现场点位核验" }
        var naturalMaxW = textPaint.measureText(titleText) + 160f * scale
        for ((label, value) in lines) {
            val w = textPaint.measureText("$label：$value")
            if (w > naturalMaxW) naturalMaxW = w
        }

        val boxW = (naturalMaxW + padX * 2).coerceIn(380f * scale, maxAllowedBoxW)
        val boxH = padY * 2 + headerHeight + lines.size * lineGap
        val boxLeft = if (cfg.bottomRight) (width - boxW - margin) else margin
        val boxTop = height - boxH - margin

        val accentColor = ACCENT_COLORS.getOrElse(cfg.accentColorIndex) { ACCENT_COLORS[0] }
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(cfg.bgAlpha.coerceIn(100, 240), 11, 19, 43)
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(90, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * scale
        }
        val rect = RectF(boxLeft, boxTop, boxLeft + boxW, boxTop + boxH)
        canvas.drawRoundRect(rect, 18f * scale, 18f * scale, bgPaint)
        canvas.drawRoundRect(rect, 18f * scale, 18f * scale, borderPaint)

        // Header bar
        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
        canvas.drawRoundRect(
            RectF(boxLeft + padX, boxTop + padY, boxLeft + padX + 7f * scale, boxTop + padY + 28f * scale),
            3.5f * scale, 3.5f * scale, accentPaint
        )
        val headerTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 27f * scale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val maxTitleW = boxW - padX * 2 - 130f * scale
        canvas.drawText(ellipsizeToWidth(titleText, headerTitlePaint, maxTitleW), boxLeft + padX + 16f * scale, boxTop + padY + 24f * scale, headerTitlePaint)

        // Coordinate system pill on top-right of card
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(55, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        }
        val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            textSize = 20f * scale
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val badgeW = 96f * scale
        val badgeRect = RectF(boxLeft + boxW - padX - badgeW, boxTop + padY - 2f * scale, boxLeft + boxW - padX, boxTop + padY + 30f * scale)
        canvas.drawRoundRect(badgeRect, 8f * scale, 8f * scale, badgePaint)
        canvas.drawText(cfg.coordSystem, badgeRect.centerX(), boxTop + padY + 21f * scale, badgeTextPaint)

        // Divider line
        val divPaint = Paint().apply {
            color = Color.argb(45, 255, 255, 255)
            strokeWidth = 1.2f * scale
        }
        canvas.drawLine(boxLeft + padX, boxTop + padY + headerHeight - 8f * scale, boxLeft + boxW - padX, boxTop + padY + headerHeight - 8f * scale, divPaint)

        val maxContentW = boxW - padX * 2
        lines.forEachIndexed { idx, (label, value) ->
            val y = boxTop + padY + headerHeight + (idx + 0.78f) * lineGap
            textPaint.color = Color.rgb(148, 163, 184)
            val prefix = "$label："
            canvas.drawText(prefix, boxLeft + padX, y, textPaint)
            val lw = textPaint.measureText(prefix)
            textPaint.color = if (label == "经纬") accentColor else if (label == "备注") Color.rgb(253, 230, 138) else Color.WHITE
            val clippedVal = ellipsizeToWidth(value, textPaint, maxContentW - lw)
            canvas.drawText(clippedVal, boxLeft + padX + lw, y, textPaint)
        }
    }

    /**
     * Style 2: 经纬网格测绘 (GIS/Survey HUD coordinate banner + 2-column metadata grid)
     */
    private fun drawStyle2GisSurveyGrid(
        canvas: Canvas,
        width: Int,
        height: Int,
        scale: Float,
        isLandscape: Boolean,
        cfg: WatermarkConfig
    ) {
        val margin = 28f * scale
        val maxAllowedW = if (isLandscape) (width * 0.58f) else (width - margin * 2)
        val boxW = (680f * scale).coerceAtMost(maxAllowedW)
        val hasFooter = (cfg.showAddress && cfg.address.isNotBlank()) || (cfg.showNote && cfg.note.isNotBlank())
        val boxH = if (hasFooter) 196f * scale else 150f * scale
        val boxLeft = if (cfg.bottomRight) (width - boxW - margin) else margin
        val boxTop = height - boxH - margin

        val accentColor = ACCENT_COLORS.getOrElse(cfg.accentColorIndex) { ACCENT_COLORS[0] }
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(cfg.bgAlpha.coerceIn(110, 240), 9, 14, 26)
        }
        val rect = RectF(boxLeft, boxTop, boxLeft + boxW, boxTop + boxH)
        canvas.drawRoundRect(rect, 16f * scale, 16f * scale, bgPaint)

        // Top Accent Coordinate Header Band
        val headerH = 56f * scale
        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(48, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        }
        canvas.drawRoundRect(RectF(boxLeft, boxTop, boxLeft + boxW, boxTop + headerH), 16f * scale, 16f * scale, headerPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(130, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * scale
        }
        canvas.drawRoundRect(rect, 16f * scale, 16f * scale, borderPaint)

        val coordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            textSize = 25f * scale
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val clippedCoord = ellipsizeToWidth(buildCoordString(cfg), coordPaint, boxW - 36f * scale)
        canvas.drawText(clippedCoord, boxLeft + 18f * scale, boxTop + 37f * scale, coordPaint)

        // Grid lines
        val gridLinePaint = Paint().apply {
            color = Color.argb(55, 148, 163, 184)
            strokeWidth = 1.2f * scale
        }
        canvas.drawLine(boxLeft, boxTop + headerH, boxLeft + boxW, boxTop + headerH, gridLinePaint)

        val midX = boxLeft + boxW * 0.48f
        val row2Bottom = boxTop + headerH + 88f * scale
        canvas.drawLine(midX, boxTop + headerH, midX, row2Bottom, gridLinePaint)

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(148, 163, 184)
            textSize = 19f * scale
        }
        val valPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 24f * scale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        // Left Cell: 点位名称
        canvas.drawText("测绘点位", boxLeft + 18f * scale, boxTop + headerH + 30f * scale, labelPaint)
        val poiStr = ellipsizeToWidth(cfg.shortPoi.ifBlank { "实拍点位" }, valPaint, boxW * 0.48f - 28f * scale)
        canvas.drawText(poiStr, boxLeft + 18f * scale, boxTop + headerH + 68f * scale, valPaint)

        // Right Cell: 采样时间
        canvas.drawText("采样时间", midX + 18f * scale, boxTop + headerH + 30f * scale, labelPaint)
        val timePaint = Paint(valPaint).apply {
            textSize = 22f * scale
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val timeStr = ellipsizeToWidth(cfg.timestamp, timePaint, boxW * 0.52f - 28f * scale)
        canvas.drawText(timeStr, midX + 18f * scale, boxTop + headerH + 68f * scale, timePaint)

        if (hasFooter) {
            canvas.drawLine(boxLeft, row2Bottom, boxLeft + boxW, row2Bottom, gridLinePaint)
            val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(253, 230, 138)
                textSize = 21f * scale
            }
            val footerRaw = buildString {
                if (cfg.showAddress && cfg.address.isNotBlank()) append(cfg.address)
                if (cfg.showNote && cfg.note.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(cfg.note)
                }
            }
            val clippedFooter = ellipsizeToWidth(footerRaw, footerPaint, boxW - 36f * scale)
            canvas.drawText(clippedFooter, boxLeft + 18f * scale, row2Bottom + 34f * scale, footerPaint)
        }
    }

    /**
     * Style 3: 极简胶片底栏 (Full-width bottom cinema footer bar with strict left/right width bounds)
     */
    private fun drawStyle3MinimalistFilmBar(
        canvas: Canvas,
        width: Int,
        height: Int,
        scale: Float,
        isLandscape: Boolean,
        cfg: WatermarkConfig
    ) {
        val barH = (if (isLandscape) 92f else 106f) * scale
        val barTop = height - barH
        val bgPaint = Paint().apply {
            color = Color.argb(cfg.bgAlpha.coerceIn(150, 245), 8, 12, 20)
        }
        canvas.drawRect(0f, barTop, width.toFloat(), height.toFloat(), bgPaint)

        val accentColor = ACCENT_COLORS.getOrElse(cfg.accentColorIndex) { ACCENT_COLORS[0] }
        val linePaint = Paint().apply {
            color = accentColor
            strokeWidth = 2.5f * scale
        }
        canvas.drawLine(0f, barTop, width.toFloat(), barTop, linePaint)

        val padX = 26f * scale
        val maxLeftW = width * 0.44f
        val maxRightW = width * 0.50f

        val leftTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f * scale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val poiText = ellipsizeToWidth(cfg.shortPoi.ifBlank { cfg.customTitle }, leftTitlePaint, maxLeftW)
        canvas.drawText(poiText, padX, barTop + 40f * scale, leftTitlePaint)

        val subLeftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(148, 163, 184)
            textSize = 21f * scale
        }
        val subLeftRaw = cfg.note.ifBlank { cfg.address.ifBlank { cfg.dateWeek } }
        canvas.drawText(ellipsizeToWidth(subLeftRaw, subLeftPaint, maxLeftW), padX, barTop + 76f * scale, subLeftPaint)

        val rightCoordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            textSize = 23f * scale
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val coordClipped = ellipsizeToWidth(buildCoordString(cfg), rightCoordPaint, maxRightW)
        canvas.drawText(coordClipped, width - padX, barTop + 40f * scale, rightCoordPaint)

        val subRightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(226, 232, 240)
            textSize = 21f * scale
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        val timeLine = "${cfg.timestamp} ${cfg.dateWeek.substringAfter(" ")}"
        canvas.drawText(ellipsizeToWidth(timeLine, subRightPaint, maxRightW), width - padX, barTop + 76f * scale, subRightPaint)
    }

    /**
     * Style 4: 外业标准台账 (Bordered engineering ledger table with strict cell clipping)
     */
    private fun drawStyle4LedgerTable(
        canvas: Canvas,
        width: Int,
        height: Int,
        scale: Float,
        isLandscape: Boolean,
        cfg: WatermarkConfig
    ) {
        val margin = 28f * scale
        val rows = mutableListOf<Pair<String, String>>()
        if (cfg.showCoords) rows.add("经纬坐标" to buildCoordString(cfg))
        if (cfg.showLocation) {
            val locVal = if (cfg.showAddress && cfg.address.isNotBlank()) "${cfg.shortPoi} · ${cfg.address}" else cfg.shortPoi
            rows.add("点位地址" to locVal)
        }
        if (cfg.showTime) rows.add("记录时间" to "${cfg.timestamp} ${cfg.dateWeek.substringAfter(" ")}")
        if (cfg.showNote && cfg.note.isNotBlank()) rows.add("现场备注" to cfg.note)
        if (rows.isEmpty()) rows.add("核验状态" to "高德GCJ-02已对齐")

        val headerH = 44f * scale
        val rowH = 40f * scale
        val maxAllowedW = if (isLandscape) (width * 0.58f) else (width - margin * 2)
        val boxW = (700f * scale).coerceAtMost(maxAllowedW)
        val boxH = headerH + rows.size * rowH
        val boxLeft = if (cfg.bottomRight) (width - boxW - margin) else margin
        val boxTop = height - boxH - margin

        val accentColor = ACCENT_COLORS.getOrElse(cfg.accentColorIndex) { ACCENT_COLORS[0] }
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(cfg.bgAlpha.coerceIn(120, 240), 11, 19, 43)
        }
        canvas.drawRect(boxLeft, boxTop, boxLeft + boxW, boxTop + boxH, bgPaint)

        // Table Header Fill
        val headerFillPaint = Paint().apply {
            color = Color.argb(65, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        }
        canvas.drawRect(boxLeft, boxTop, boxLeft + boxW, boxTop + headerH, headerFillPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            style = Paint.Style.STROKE
            strokeWidth = 2.0f * scale
        }
        canvas.drawRect(boxLeft, boxTop, boxLeft + boxW, boxTop + boxH, borderPaint)

        val headerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 24f * scale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val titleStr = "${cfg.customTitle.ifBlank { "外业定点巡检台账" }} · ${cfg.coordSystem}"
        canvas.drawText(ellipsizeToWidth(titleStr, headerTextPaint, boxW - 28f * scale), boxLeft + 14f * scale, boxTop + 30f * scale, headerTextPaint)

        val colSplitX = boxLeft + 136f * scale
        borderPaint.strokeWidth = 1.2f * scale
        canvas.drawLine(boxLeft, boxTop + headerH, boxLeft + boxW, boxTop + headerH, borderPaint)
        canvas.drawLine(colSplitX, boxTop + headerH, colSplitX, boxTop + boxH, borderPaint)

        val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 22f * scale
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val maxValW = (boxLeft + boxW) - colSplitX - 24f * scale

        rows.forEachIndexed { i, (k, v) ->
            val topY = boxTop + headerH + i * rowH
            if (i > 0) {
                canvas.drawLine(boxLeft, topY, boxLeft + boxW, topY, borderPaint)
            }
            val baselineY = topY + 28f * scale
            cellPaint.color = accentColor
            canvas.drawText(k, boxLeft + 14f * scale, baselineY, cellPaint)
            cellPaint.color = if (k == "现场备注") Color.rgb(253, 230, 138) else Color.WHITE
            val clippedV = ellipsizeToWidth(v, cellPaint, maxValW)
            canvas.drawText(clippedV, colSplitX + 12f * scale, baselineY, cellPaint)
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
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, cfg.timestamp)
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, cfg.toJson())
            exif.saveAttributes()
        } catch (_: Exception) {}
    }

    fun exportOrUpdateSystemCameraGallery(context: Context, wmFile: File, cfg: WatermarkConfig): String {
        val resolver = context.contentResolver
        if (cfg.mediaStoreUri.isNotBlank()) {
            try {
                val existingUri = Uri.parse(cfg.mediaStoreUri)
                resolver.openOutputStream(existingUri, "wt")?.use { outStream ->
                    wmFile.inputStream().use { inStream ->
                        inStream.copyTo(outStream)
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

    fun scanSystemCameraPhotos(context: Context, maxCount: Int = 50): List<SystemCameraPhoto> {
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
            val rawBmp = BitmapFactory.decodeFile(tempFile.absolutePath)
            tempFile.delete()
            if (rawBmp == null) return null

            val maxDim = max(rawBmp.width, rawBmp.height)
            val cleanBmp = if (maxDim > 2400) {
                val ratio = 2400f / maxDim
                Bitmap.createScaledBitmap(rawBmp, (rawBmp.width * ratio).toInt(), (rawBmp.height * ratio).toInt(), true)
            } else rawBmp

            saveNewPhotoEntry(context, cleanBmp, parsedCfg, alsoExportOriginal = false)
        } catch (_: Exception) {
            null
        }
    }

    private fun readPhotoMetadataOrExif(file: File, fallbackLoc: LocationSnapshot, defaultSys: String): WatermarkConfig {
        val (hm, dateWeek, fullTs) = WatermarkConfig.currentTimeStrings()
        try {
            val exif = ExifInterface(file.absolutePath)
            val comment = exif.getAttribute(ExifInterface.TAG_USER_COMMENT)
            if (!comment.isNullOrBlank() && comment.trim().startsWith("{")) {
                return WatermarkConfig.fromJson(comment.trim())
            }
            val latLong = FloatArray(2)
            if (exif.getLatLong(latLong)) {
                val wgsLat = latLong[0].toDouble()
                val wgsLon = latLong[1].toDouble()
                val gcj = CoordinateConverter.wgs84ToGcj02(wgsLat, wgsLon)
                val exifDate = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: fullTs
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
                    shortPoi = String.format(Locale.US, "EXIF点位(%.4f°N)", gcj.lat),
                    address = String.format(Locale.US, "原相机EXIF坐标: %.6f, %.6f", gcj.lat, gcj.lon),
                    timeHm = hm,
                    dateWeek = dateWeek,
                    timestamp = exifDate,
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
            shortPoi = fallbackLoc.shortPoi,
            address = fallbackLoc.fullAddress,
            timeHm = hm,
            dateWeek = dateWeek,
            timestamp = fullTs,
            note = "外部相册导入"
        )
    }

    private fun decimalToExifDms(coord: Double): String {
        val absVal = abs(coord)
        val deg = floor(absVal).toInt()
        val minFloat = (absVal - deg) * 60.0
        val min = floor(minFloat).toInt()
        val sec = ((minFloat - min) * 60.0 * 10000).toInt()
        return "$deg/1,$min/1,$sec/10000"
    }
}
