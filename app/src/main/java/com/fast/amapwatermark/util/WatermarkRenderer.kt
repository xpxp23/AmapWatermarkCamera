package com.fast.amapwatermark.util

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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

/**
 * Watermark configuration stored alongside each captured photo's clean base image.
 * Supports non-destructive re-rendering at any time.
 *
 * styleTemplate:
 *   0 = 图3同款·大时间居中经典款 (Big time + Date/Weekday/📍POI + Lat/Lon pill)
 *   1 = 工程外业·左下角经纬卡片款 (Structured left card with accent bar)
 */
data class WatermarkConfig(
    var styleTemplate: Int = 0,
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
    var bgAlpha: Int = 175
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("styleTemplate", styleTemplate)
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
        }.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): WatermarkConfig {
            val o = JSONObject(jsonStr)
            return WatermarkConfig(
                styleTemplate = o.optInt("styleTemplate", 0),
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
                bgAlpha = o.optInt("bgAlpha", 175)
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

object WatermarkRenderer {

    val FILTER_NAMES = listOf("原图", "鲜明", "暖阳", "清冷", "黑白")

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
     * Render watermark (and optional color filter) onto clean base Bitmap without mutating the clean source.
     */
    fun renderWatermarkedBitmap(cleanBitmap: Bitmap, cfg: WatermarkConfig): Bitmap {
        val out = Bitmap.createBitmap(cleanBitmap.width, cleanBitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        // 1. Draw base photo with optional color filter
        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        getAndroidColorMatrix(cfg.filterIndex)?.let { cm ->
            basePaint.colorFilter = ColorMatrixColorFilter(cm)
        }
        canvas.drawBitmap(cleanBitmap, 0f, 0f, basePaint)

        // 2. Render selected watermark style
        if (cfg.styleTemplate == 0) {
            drawFig3CenteredWatermark(canvas, out.width, out.height, cfg)
        } else {
            drawEngineeringCardWatermark(canvas, out.width, out.height, cfg)
        }
        return out
    }

    /**
     * Style 0: Matches Figure 3 ("19:09" + "2026-10-19 星期一 📍小庄村" + High-Precision Coordinate Line)
     */
    private fun drawFig3CenteredWatermark(canvas: Canvas, width: Int, height: Int, cfg: WatermarkConfig) {
        val scale = width / 1080f
        val cx = width / 2f

        val shownLat = if (cfg.coordSystem == "WGS-84") cfg.wgsLat else cfg.gcjLat
        val shownLon = if (cfg.coordSystem == "WGS-84") cfg.wgsLon else cfg.gcjLon
        val coordStr = CoordinateConverter.formatCoordinate(shownLat, shownLon, cfg.useDms)
        val sysTag = if (cfg.showCoordTag) " [${cfg.coordSystem}]" else ""
        val accTag = if (cfg.showAccuracy) String.format(Locale.US, " ±%.1fm", cfg.accuracyMeters) else ""
        val fullCoordLine = "$coordStr$sysTag$accTag"

        // Bottom subtle gradient scrim to guarantee white text legibility on bright scenes
        val scrimTop = height - 380f * scale
        val scrimPaint = Paint().apply {
            shader = LinearGradient(
                0f, scrimTop, 0f, height.toFloat(),
                Color.TRANSPARENT, Color.argb(120, 0, 0, 0),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, scrimTop, width.toFloat(), height.toFloat(), scrimPaint)

        var cursorBottomY = height - 42f * scale

        // Optional Note line at the bottom
        if (cfg.showNote && cfg.note.isNotBlank()) {
            val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(253, 230, 138)
                textSize = 26f * scale
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                setShadowLayer(6f * scale, 0f, 2f * scale, Color.argb(190, 0, 0, 0))
            }
            canvas.drawText(cfg.note, cx, cursorBottomY, notePaint)
            cursorBottomY -= 40f * scale
        }

        // Optional Full Address line
        if (cfg.showAddress && cfg.address.isNotBlank()) {
            val addrPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(235, 241, 245, 249)
                textSize = 25f * scale
                textAlign = Paint.Align.CENTER
                setShadowLayer(6f * scale, 0f, 2f * scale, Color.argb(190, 0, 0, 0))
            }
            canvas.drawText(cfg.address, cx, cursorBottomY, addrPaint)
            cursorBottomY -= 40f * scale
        }

        // Coordinate Pill Line (Core requirement: accurate Lat/Lon on photo)
        if (cfg.showCoords) {
            val coordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(125, 211, 252)
                textSize = 26f * scale
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            }
            val textW = coordPaint.measureText(fullCoordLine)
            val pillPadX = 22f * scale
            val pillH = 42f * scale
            val pillTop = cursorBottomY - 31f * scale
            val pillRect = RectF(cx - textW / 2f - pillPadX, pillTop, cx + textW / 2f + pillPadX, pillTop + pillH)
            val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(135, 15, 23, 42)
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(pillRect, 21f * scale, 21f * scale, pillBgPaint)
            coordPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(fullCoordLine, cx, cursorBottomY, coordPaint)
            cursorBottomY -= 54f * scale
        }

        // Date + Weekday + Red Pin Icon + Short POI ("2026-10-19 星期一 📍小庄村")
        if (cfg.showTime || cfg.showLocation) {
            val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 36f * scale
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                setShadowLayer(8f * scale, 0f, 2.5f * scale, Color.argb(180, 0, 0, 0))
            }
            val datePart = if (cfg.showTime) cfg.dateWeek else ""
            val poiPart = if (cfg.showLocation) cfg.shortPoi else ""
            val pinWidth = if (poiPart.isNotEmpty()) 34f * scale else 0f
            val dateW = if (datePart.isNotEmpty()) subPaint.measureText("$datePart ") else 0f
            val poiW = if (poiPart.isNotEmpty()) subPaint.measureText(poiPart) else 0f
            val totalW = dateW + pinWidth + poiW
            var startX = cx - totalW / 2f

            if (datePart.isNotEmpty()) {
                subPaint.textAlign = Paint.Align.LEFT
                canvas.drawText("$datePart ", startX, cursorBottomY, subPaint)
                startX += dateW
            }
            if (poiPart.isNotEmpty()) {
                // Draw crisp red pin icon matching Figure 3
                val pinCenterX = startX + 14f * scale
                val pinHeadY = cursorBottomY - 22f * scale
                val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(239, 68, 68)
                    style = Paint.Style.FILL
                }
                val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(220, 38, 38)
                    strokeWidth = 3.5f * scale
                    strokeCap = Paint.Cap.ROUND
                }
                canvas.drawLine(pinCenterX, pinHeadY + 6f * scale, pinCenterX, cursorBottomY - 2f * scale, needlePaint)
                canvas.drawCircle(pinCenterX, pinHeadY, 9.5f * scale, pinPaint)
                val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(254, 202, 202)
                }
                canvas.drawCircle(pinCenterX - 2.5f * scale, pinHeadY - 2.5f * scale, 2.8f * scale, highlightPaint)

                startX += pinWidth
                subPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(poiPart, startX, cursorBottomY, subPaint)
            }
            // Leave generous vertical clearance above 36px date text before 128px big time baseline
            cursorBottomY -= 82f * scale
        }

        // Huge Centered Time ("19:09")
        if (cfg.showTime) {
            val bigTimePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 128f * scale
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                setShadowLayer(10f * scale, 0f, 3f * scale, Color.argb(175, 0, 0, 0))
            }
            canvas.drawText(cfg.timeHm, cx, cursorBottomY, bigTimePaint)
        }
    }

    /**
     * Style 1: Left/Right Engineering Coordinate Card Watermark
     */
    private fun drawEngineeringCardWatermark(canvas: Canvas, width: Int, height: Int, cfg: WatermarkConfig) {
        val scale = width / 1080f
        val lines = mutableListOf<Pair<String, String>>()
        if (cfg.showCoords) {
            val shownLat = if (cfg.coordSystem == "WGS-84") cfg.wgsLat else cfg.gcjLat
            val shownLon = if (cfg.coordSystem == "WGS-84") cfg.wgsLon else cfg.gcjLon
            val coordText = CoordinateConverter.formatCoordinate(shownLat, shownLon, cfg.useDms)
            val tagPart = if (cfg.showCoordTag) " [${cfg.coordSystem}]" else ""
            val accPart = if (cfg.showAccuracy) String.format(Locale.US, " ±%.1fm", cfg.accuracyMeters) else ""
            lines.add("经纬" to (coordText + tagPart + accPart))
        }
        if (cfg.showLocation && cfg.shortPoi.isNotBlank()) {
            val locStr = if (cfg.showAddress && cfg.address.isNotBlank()) "${cfg.shortPoi} (${cfg.address})" else cfg.shortPoi
            lines.add("地点" to locStr)
        } else if (cfg.showAddress && cfg.address.isNotBlank()) {
            lines.add("地点" to cfg.address)
        }
        if (cfg.showTime && cfg.timestamp.isNotBlank()) {
            lines.add("时间" to cfg.timestamp)
        }
        if (cfg.showNote && cfg.note.isNotBlank()) {
            lines.add("备注" to cfg.note)
        }
        if (lines.isEmpty()) return

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 28f * scale
            color = Color.WHITE
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }

        val padX = 28f * scale
        val padY = 24f * scale
        val lineGap = 42f * scale
        val headerHeight = 46f * scale

        var maxW = 460f * scale
        for ((label, value) in lines) {
            val w = textPaint.measureText("$label：$value")
            if (w > maxW) maxW = w
        }

        val boxW = (maxW + padX * 2).coerceAtMost(width - 48f * scale)
        val boxH = padY * 2 + headerHeight + lines.size * lineGap
        val margin = 28f * scale
        val boxLeft = if (cfg.bottomRight) (width - boxW - margin) else margin
        val boxTop = height - boxH - margin

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(cfg.bgAlpha.coerceIn(80, 240), 15, 23, 42)
            style = Paint.Style.FILL
        }
        val rect = RectF(boxLeft, boxTop, boxLeft + boxW, boxTop + boxH)
        canvas.drawRoundRect(rect, 20f * scale, 20f * scale, bgPaint)

        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(56, 189, 248)
        }
        canvas.drawRect(
            boxLeft + padX,
            boxTop + padY,
            boxLeft + padX + 8f * scale,
            boxTop + padY + 30f * scale,
            accentPaint
        )
        canvas.drawText(
            "高德精准经纬水印 · ${cfg.coordSystem}",
            boxLeft + padX + 18f * scale,
            boxTop + padY + 26f * scale,
            textPaint
        )

        lines.forEachIndexed { idx, (label, value) ->
            val y = boxTop + padY + headerHeight + (idx + 0.8f) * lineGap
            textPaint.color = Color.rgb(148, 163, 184)
            canvas.drawText("$label：", boxLeft + padX, y, textPaint)
            val lw = textPaint.measureText("$label：")
            textPaint.color = if (idx == 0) Color.rgb(125, 211, 252) else Color.WHITE
            canvas.drawText(value, boxLeft + padX + lw, y, textPaint)
        }
    }

    /**
     * Save composited JPEG with Dual-Track EXIF:
     * - Standard GPSLatitude / GPSLongitude written in WGS-84 (universal compatibility)
     * - TAG_USER_COMMENT written with full JSON containing exact GCJ-02 & WGS-84 (prevents double-offset!)
     */
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

    /**
     * Export the watermarked JPEG to Android system gallery (DCIM/AmapWatermarkCamera)
     */
    fun exportToSystemGallery(context: Context, wmFile: File, cfg: WatermarkConfig) {
        try {
            val filename = "AMAP_WM_${System.currentTimeMillis()}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/AmapWatermarkCamera")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
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
        } catch (_: Exception) {}
    }

    /**
     * Persist a newly captured clean bitmap + config into app layer-separated storage
     */
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
        metaFile.writeText(cfg.toJson())

        exportToSystemGallery(context, wmFile, cfg)
        if (alsoExportOriginal) {
            exportToSystemGallery(context, cleanFile, cfg)
        }

        return PhotoEntry(id = id, cleanFile = cleanFile, watermarkedFile = wmFile, metaFile = metaFile, config = cfg)
    }

    /**
     * Load all previously saved photos from app storage (sorted newest first)
     */
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

    /**
     * Import an external image from system gallery URI, parse its EXIF GPS (auto-convert WGS84 -> GCJ02),
     * and save as a layer-separated PhotoEntry!
     */
    fun importExternalPhotoUri(context: Context, uri: Uri, fallbackLoc: LocationSnapshot, defaultSys: String): PhotoEntry? {
        return try {
            val dir = File(context.filesDir, "captured_photos").apply { mkdirs() }
            val tempFile = File(dir, "temp_import_${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return null

            val parsedCfg = readPhotoMetadataOrExif(tempFile, fallbackLoc, defaultSys)
            val rawBmp = BitmapFactory.decodeFile(tempFile.absolutePath)
            tempFile.delete()
            if (rawBmp == null) return null

            // Scale down if huge (> 2400px) to keep editing snappy
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
                    address = String.format(Locale.US, "系统相册EXIF解析坐标: %.6f, %.6f", gcj.lat, gcj.lon),
                    timeHm = hm,
                    dateWeek = dateWeek,
                    timestamp = exifDate,
                    note = "相册EXIF导入 · 已自动纠偏对齐高德"
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
            note = "相册导入照片"
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
