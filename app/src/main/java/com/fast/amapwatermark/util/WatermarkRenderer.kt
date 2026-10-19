package com.fast.amapwatermark.util

import android.graphics.*
import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.floor

data class WatermarkConfig(
    var coordSystem: String = "GCJ-02", // "GCJ-02" or "WGS-84"
    var useDms: Boolean = false,
    var gcjLat: Double = 39.908823,
    var gcjLon: Double = 116.397470,
    var wgsLat: Double = 39.907419,
    var wgsLon: Double = 116.391228,
    var origGcjLat: Double = 39.908823,
    var origGcjLon: Double = 116.397470,
    var accuracyMeters: Float = 2.8f,
    var address: String = "",
    var timestamp: String = "",
    var note: String = "",
    var showCoords: Boolean = true,
    var showCoordTag: Boolean = true,
    var showAccuracy: Boolean = true,
    var showAddress: Boolean = true,
    var showTime: Boolean = true,
    var showNote: Boolean = true,
    var bottomRight: Boolean = false,
    var bgAlpha: Int = 185
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("coordSystem", coordSystem)
            put("useDms", useDms)
            put("gcjLat", gcjLat)
            put("gcjLon", gcjLon)
            put("wgsLat", wgsLat)
            put("wgsLon", wgsLon)
            put("origGcjLat", origGcjLat)
            put("origGcjLon", origGcjLon)
            put("accuracyMeters", accuracyMeters.toDouble())
            put("address", address)
            put("timestamp", timestamp)
            put("note", note)
            put("showCoords", showCoords)
            put("showCoordTag", showCoordTag)
            put("showAccuracy", showAccuracy)
            put("showAddress", showAddress)
            put("showTime", showTime)
            put("showNote", showNote)
            put("bottomRight", bottomRight)
            put("bgAlpha", bgAlpha)
        }.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): WatermarkConfig {
            val o = JSONObject(jsonStr)
            return WatermarkConfig(
                coordSystem = o.optString("coordSystem", "GCJ-02"),
                useDms = o.optBoolean("useDms", false),
                gcjLat = o.optDouble("gcjLat", 39.908823),
                gcjLon = o.optDouble("gcjLon", 116.397470),
                wgsLat = o.optDouble("wgsLat", 39.907419),
                wgsLon = o.optDouble("wgsLon", 116.391228),
                origGcjLat = o.optDouble("origGcjLat", 39.908823),
                origGcjLon = o.optDouble("origGcjLon", 116.397470),
                accuracyMeters = o.optDouble("accuracyMeters", 2.8).toFloat(),
                address = o.optString("address", ""),
                timestamp = o.optString("timestamp", ""),
                note = o.optString("note", ""),
                showCoords = o.optBoolean("showCoords", true),
                showCoordTag = o.optBoolean("showCoordTag", true),
                showAccuracy = o.optBoolean("showAccuracy", true),
                showAddress = o.optBoolean("showAddress", true),
                showTime = o.optBoolean("showTime", true),
                showNote = o.optBoolean("showNote", true),
                bottomRight = o.optBoolean("bottomRight", false),
                bgAlpha = o.optInt("bgAlpha", 185)
            )
        }
    }
}

object WatermarkRenderer {

    /**
     * Render watermark onto clean base Bitmap without mutating the clean source.
     * Supports unlimited non-destructive re-editing later in the app!
     */
    fun renderWatermarkedBitmap(cleanBitmap: Bitmap, cfg: WatermarkConfig): Bitmap {
        val out = cleanBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val scale = out.width / 1080f

        val lines = mutableListOf<Pair<String, String>>()
        if (cfg.showCoords) {
            val shownLat = if (cfg.coordSystem == "WGS-84") cfg.wgsLat else cfg.gcjLat
            val shownLon = if (cfg.coordSystem == "WGS-84") cfg.wgsLon else cfg.gcjLon
            val coordText = CoordinateConverter.formatCoordinate(shownLat, shownLon, cfg.useDms)
            val tagPart = if (cfg.showCoordTag) " [${cfg.coordSystem}]" else ""
            val accPart = if (cfg.showAccuracy) " ±${String.format("%.1fm", cfg.accuracyMeters)}" else ""
            lines.add("经纬" to (coordText + tagPart + accPart))
        }
        if (cfg.showAddress && cfg.address.isNotBlank()) {
            lines.add("地点" to cfg.address)
        }
        if (cfg.showTime && cfg.timestamp.isNotBlank()) {
            lines.add("时间" to cfg.timestamp)
        }
        if (cfg.showNote && cfg.note.isNotBlank()) {
            lines.add("备注" to cfg.note)
        }
        if (lines.isEmpty()) return out

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 30f * scale
            color = Color.WHITE
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }

        val padX = 28f * scale
        val padY = 24f * scale
        val lineGap = 44f * scale
        val headerHeight = 48f * scale

        var maxW = 480f * scale
        for ((label, value) in lines) {
            val w = textPaint.measureText("$label：$value")
            if (w > maxW) maxW = w
        }

        val boxW = (maxW + padX * 2).coerceAtMost(out.width - 48f * scale)
        val boxH = padY * 2 + headerHeight + lines.size * lineGap
        val margin = 28f * scale
        val boxLeft = if (cfg.bottomRight) (out.width - boxW - margin) else margin
        val boxTop = out.height - boxH - margin

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(cfg.bgAlpha.coerceIn(80, 240), 15, 23, 42)
            style = Paint.Style.FILL
        }
        val rect = RectF(boxLeft, boxTop, boxLeft + boxW, boxTop + boxH)
        canvas.drawRoundRect(rect, 20f * scale, 20f * scale, bgPaint)

        // Header accent
        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(56, 189, 248)
        }
        canvas.drawRect(
            boxLeft + padX,
            boxTop + padY,
            boxLeft + padX + 8f * scale,
            boxTop + padY + 32f * scale,
            accentPaint
        )
        canvas.drawText(
            "高德精准经纬水印",
            boxLeft + padX + 20f * scale,
            boxTop + padY + 28f * scale,
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

        return out
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
        val exif = ExifInterface(targetFile.absolutePath)
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, if (cfg.wgsLat >= 0) "N" else "S")
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, if (cfg.wgsLon >= 0) "E" else "W")
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, decimalToExifDms(cfg.wgsLat))
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, decimalToExifDms(cfg.wgsLon))
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, cfg.timestamp)
        exif.setAttribute(ExifInterface.TAG_USER_COMMENT, cfg.toJson())
        exif.saveAttributes()
    }

    /**
     * Read any photo (taken by this app or imported from system gallery).
     * Automatically distinguishes Dual-Track JSON vs external standard WGS-84 EXIF!
     */
    fun readPhotoMetadataOrExif(file: File): WatermarkConfig? {
        return try {
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
                WatermarkConfig(
                    coordSystem = "GCJ-02",
                    gcjLat = gcj.lat,
                    gcjLon = gcj.lon,
                    wgsLat = wgsLat,
                    wgsLon = wgsLon,
                    origGcjLat = gcj.lat,
                    origGcjLon = gcj.lon,
                    timestamp = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: ""
                )
            } else null
        } catch (_: Exception) {
            null
        }
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
