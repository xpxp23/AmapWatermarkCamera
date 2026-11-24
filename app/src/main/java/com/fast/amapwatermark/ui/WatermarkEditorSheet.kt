package com.fast.amapwatermark.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fast.amapwatermark.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Full-featured Material 3 ModalBottomSheet for non-destructive watermark editing (v9.0.0).
 * Replaces narrow AlertDialog, adds live thumbnail preview, date/time steppers with validation,
 * Template 0 bottom scrim toggle, and zero filter controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatermarkEditorBottomSheet(
    target: PhotoEntry,
    locationManager: FastLocationManager,
    onDismiss: () -> Unit,
    onSaved: (List<PhotoEntry>, PhotoEntry?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    if (target.config.antiTamperMode) {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Color(0xFF0F172A),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LockVectorIcon(tint = Color(0xFF34D399), modifier = Modifier.size(20.dp))
                    Text("防篡改原片禁止修改", color = Color(0xFF34D399), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            text = {
                Text(
                    "该照片在拍摄时已开启「外业防篡改验真模式」，其硬件真实 GPS 坐标、NTP 权威授时、点位备注及 SHA-256 + 图像指纹签名已锁定，禁止在相册中进行任何后期修改。",
                    color = Color.White,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(onClick = onDismiss) { Text("我知道了") }
            }
        )
        return
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var editTemplate by remember(target) { mutableStateOf(target.config.styleTemplate) }
    var editTitle by remember(target) { mutableStateOf(target.config.customTitle) }
    var editSys by remember(target) { mutableStateOf(target.config.coordSystem) }
    var editDms by remember(target) { mutableStateOf(target.config.useDms) }
    var editPoi by remember(target) { mutableStateOf(target.config.shortPoi) }
    var editAddr by remember(target) { mutableStateOf(target.config.address) }
    var editNote by remember(target) { mutableStateOf(target.config.note) }
    var editDatePart by remember(target) {
        mutableStateOf(target.config.dateWeek.substringBefore(" ").ifBlank { target.config.timestamp.substringBefore(" ") })
    }
    var editTimeHm by remember(target) { mutableStateOf(target.config.timeHm) }
    var editTimeSec by remember(target) {
        mutableStateOf(target.config.timestamp.substringAfterLast(":", "00").take(2).ifBlank { "00" })
    }
    var editLat by remember(target) { mutableStateOf(String.format(Locale.US, "%.6f", target.config.gcjLat)) }
    var editLon by remember(target) { mutableStateOf(String.format(Locale.US, "%.6f", target.config.gcjLon)) }
    var editAltitude by remember(target) { mutableStateOf(String.format(Locale.US, "%.1f", target.config.altitudeMeters)) }
    var editAzimuthDeg by remember(target) { mutableStateOf(target.config.azimuthDeg.toString()) }
    var editAccuracy by remember(target) { mutableStateOf(String.format(Locale.US, "%.1f", target.config.accuracyMeters)) }
    var editAccentColorIndex by remember(target) { mutableStateOf(target.config.accentColorIndex) }
    var editFontScale by remember(target) { mutableStateOf(target.config.fontScale) }
    var editBgAlpha by remember(target) { mutableStateOf(target.config.bgAlpha) }
    var editShowTime by remember(target) { mutableStateOf(target.config.showTime) }
    var editShowLoc by remember(target) { mutableStateOf(target.config.showLocation) }
    var editShowCoords by remember(target) { mutableStateOf(target.config.showCoords) }
    var editShowCoordTag by remember(target) { mutableStateOf(target.config.showCoordTag) }
    var editShowAccuracy by remember(target) { mutableStateOf(target.config.showAccuracy) }
    var editShowAltitude by remember(target) { mutableStateOf(target.config.showAltitude) }
    var editShowAzimuth by remember(target) { mutableStateOf(target.config.showAzimuth) }
    var editShowQr by remember(target) { mutableStateOf(target.config.showQrCode) }
    var editShowAddr by remember(target) { mutableStateOf(target.config.showAddress) }
    var editShowNote by remember(target) { mutableStateOf(target.config.showNote) }
    var editShowMiniMap by remember(target) { mutableStateOf(target.config.showMiniMap) }
    var editMiniMapPos by remember(target) { mutableStateOf(target.config.miniMapPosition) }
    var editShowBottomScrim by remember(target) { mutableStateOf(target.config.showBottomScrim) }
    var editBottomRight by remember(target) { mutableStateOf(target.config.bottomRight) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    // Load a lightweight clean base thumbnail once for live preview inside the sheet
    var previewBaseBmp by remember(target.id) { mutableStateOf<Bitmap?>(null) }
    var renderedPreviewBmp by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(target.id) {
        previewBaseBmp = withContext(Dispatchers.IO) {
            BitmapCacheManager.decodeFullResolutionCleanBitmap(target.cleanFile, maxDimCap = 540)
        }
    }

    DisposableEffect(target.id) {
        onDispose {
            renderedPreviewBmp?.let { if (!it.isRecycled) it.recycle() }
            previewBaseBmp?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    fun buildCandidateConfig(): WatermarkConfig {
        val newGcjLat = editLat.trim().toDoubleOrNull() ?: target.config.origGcjLat
        val newGcjLon = editLon.trim().toDoubleOrNull() ?: target.config.origGcjLon
        val newWgs = if (newGcjLat != 0.0 || newGcjLon != 0.0) {
            CoordinateConverter.gcj02ToWgs84(newGcjLat, newGcjLon)
        } else LatLon(0.0, 0.0)
        val (syncedHm, syncedDateWeek, syncedFullTs) = WatermarkConfig.customTimeStrings(
            editDatePart,
            editTimeHm,
            editTimeSec
        )
        val newAlt = editAltitude.trim().toDoubleOrNull() ?: target.config.altitudeMeters
        val newAzDeg = ((editAzimuthDeg.trim().toIntOrNull() ?: target.config.azimuthDeg) % 360 + 360) % 360
        val newAzText = locationManager.formatAzimuthText(newAzDeg)
        val newAcc = editAccuracy.trim().toFloatOrNull() ?: target.config.accuracyMeters

        return target.config.copy(
            styleTemplate = editTemplate,
            customTitle = editTitle,
            coordSystem = editSys,
            useDms = editDms,
            gcjLat = newGcjLat,
            gcjLon = newGcjLon,
            wgsLat = newWgs.lat,
            wgsLon = newWgs.lon,
            altitudeMeters = newAlt,
            azimuthDeg = newAzDeg,
            azimuthText = newAzText,
            accuracyMeters = newAcc,
            shortPoi = editPoi,
            address = editAddr,
            timeHm = syncedHm,
            dateWeek = syncedDateWeek,
            timestamp = syncedFullTs,
            note = editNote,
            accentColorIndex = editAccentColorIndex,
            fontScale = editFontScale,
            bgAlpha = editBgAlpha,
            showTime = editShowTime,
            showLocation = editShowLoc,
            showCoords = editShowCoords,
            showCoordTag = editShowCoordTag,
            showAccuracy = editShowAccuracy,
            showAltitude = editShowAltitude,
            showAzimuth = editShowAzimuth,
            showQrCode = editShowQr,
            showAddress = editShowAddr,
            showNote = editShowNote,
            showMiniMap = editShowMiniMap,
            miniMapPosition = editMiniMapPos,
            showBottomScrim = editShowBottomScrim,
            antiTamperMode = false,
            antiTamperHash = "",
            imageHash = "",
            bottomRight = editBottomRight
        )
    }

    LaunchedEffect(
        previewBaseBmp, editTemplate, editTitle, editSys, editDms, editPoi, editAddr, editNote,
        editDatePart, editTimeHm, editTimeSec, editLat, editLon, editAltitude, editAzimuthDeg, editAccuracy,
        editAccentColorIndex, editFontScale, editBgAlpha, editShowTime, editShowLoc, editShowCoords,
        editShowCoordTag, editShowAccuracy, editShowAltitude, editShowAzimuth, editShowQr,
        editShowAddr, editShowNote, editShowMiniMap, editMiniMapPos, editShowBottomScrim, editBottomRight
    ) {
        val base = previewBaseBmp ?: return@LaunchedEffect
        if (base.isRecycled) return@LaunchedEffect
        val cfg = buildCandidateConfig()
        val nextPreview = withContext(Dispatchers.IO) {
            WatermarkRenderer.renderWatermarkedBitmap(base, cfg)
        }
        val old = renderedPreviewBmp
        renderedPreviewBmp = nextPreview
        if (old != null && old !== nextPreview && !old.isRecycled) {
            old.recycle()
        }
    }

    fun shiftDateTime(daysDelta: Int = 0, minutesDelta: Int = 0) {
        try {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
            val parsed = fmt.parse("$editDatePart $editTimeHm:${editTimeSec.padStart(2, '0')}") ?: Date()
            val cal = Calendar.getInstance().apply {
                time = parsed
                add(Calendar.DAY_OF_MONTH, daysDelta)
                add(Calendar.MINUTE, minutesDelta)
            }
            val (hm, dw, ts) = WatermarkConfig.currentTimeStrings(cal.time)
            editDatePart = dw.substringBefore(" ")
            editTimeHm = hm
            editTimeSec = ts.substringAfterLast(":")
            validationError = null
        } catch (_: Exception) {}
    }

    ModalBottomSheet(
        onDismissRequest = { if (!isSaving) onDismiss() },
        sheetState = sheetState,
        containerColor = Color(0xFF0F172A),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color(0xFF475569)) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header with Save / Cancel
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("全要素无损修改水印", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.5.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !isSaving, onClick = onDismiss) {
                        Text("取消", color = Color(0xFF94A3B8))
                    }
                    Button(
                        enabled = !isSaving,
                        onClick = {
                            val latVal = editLat.trim().toDoubleOrNull()
                            val lonVal = editLon.trim().toDoubleOrNull()
                            if (latVal == null || lonVal == null || latVal !in -90.0..90.0 || lonVal !in -180.0..180.0) {
                                validationError = "经纬度格式无效：纬度需在 -90~90，经度需在 -180~180"
                                return@Button
                            }
                            val dateOk = Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(editDatePart.trim())
                            val hmOk = Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(editTimeHm.trim())
                            val secVal = editTimeSec.trim().toIntOrNull()
                            if (!dateOk || !hmOk || secVal == null || secVal !in 0..59) {
                                validationError = "时间格式无效：请使用 yyyy-MM-dd、HH:mm 及 00~59 秒"
                                return@Button
                            }
                            validationError = null
                            isSaving = true
                            val updatedCfg = buildCandidateConfig()
                            scope.launch {
                                val reloaded = withContext(Dispatchers.IO) {
                                    val cleanBmp = BitmapCacheManager.decodeFullResolutionCleanBitmap(target.cleanFile, 3200)
                                    if (cleanBmp != null) {
                                        var recomposited: Bitmap? = null
                                        try {
                                            recomposited = WatermarkRenderer.renderWatermarkedBitmap(cleanBmp, updatedCfg)
                                            WatermarkRenderer.saveJpegWithDualTrackExif(recomposited, target.watermarkedFile, updatedCfg)
                                        } finally {
                                            if (recomposited != null && recomposited !== cleanBmp && !recomposited.isRecycled) {
                                                recomposited.recycle()
                                            }
                                            if (!cleanBmp.isRecycled) cleanBmp.recycle()
                                        }
                                        val newUri = WatermarkRenderer.exportOrUpdateSystemCameraGallery(context, target.watermarkedFile, updatedCfg)
                                        updatedCfg.mediaStoreUri = newUri
                                        target.metaFile.writeText(updatedCfg.toJson())
                                        BitmapCacheManager.invalidate(target.watermarkedFile)
                                    }
                                    WatermarkRenderer.loadSavedPhotos(context)
                                }
                                target.config = updatedCfg
                                val newSelected = reloaded.find { it.id == target.id } ?: reloaded.firstOrNull()
                                isSaving = false
                                Toast.makeText(context, "水印已全尺寸无损重绘并同步至系统相册！", Toast.LENGTH_SHORT).show()
                                onSaved(reloaded, newSelected)
                            }
                        }
                    ) {
                        Text(if (isSaving) "重绘中..." else "保存重绘", fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Top Live Thumbnail Preview
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(175.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                val bmp = renderedPreviewBmp
                if (bmp != null && !bmp.isRecycled) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "水印实时预览",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text("正在生成实时预览...", color = Color(0xFF64748B), fontSize = 12.sp)
                }
            }

            validationError?.let { err ->
                Text(err, color = Color(0xFFF87171), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }

            // Scrollable Controls
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    WatermarkRenderer.STYLE_NAMES.forEachIndexed { idx, name ->
                        FilterChip(
                            selected = editTemplate == idx,
                            onClick = { editTemplate = idx },
                            label = { Text(name, fontSize = 11.5.sp) }
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    CompactToggleChip("WGS-84", editSys == "WGS-84", Modifier.weight(1f)) { editSys = "WGS-84" }
                    CompactToggleChip("CGCS2000", editSys == "CGCS2000", Modifier.weight(1f)) { editSys = "CGCS2000" }
                    CompactToggleChip("GCJ-02", editSys == "GCJ-02", Modifier.weight(1f)) { editSys = "GCJ-02" }
                    CompactToggleChip(if (editDms) "度分秒" else "十进制", editDms, Modifier.weight(1f)) { editDms = !editDms }
                }

                // Date & time steppers + inputs
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    CompactToggleChip("-1天", false, Modifier.weight(1f)) { shiftDateTime(daysDelta = -1) }
                    CompactToggleChip("+1天", false, Modifier.weight(1f)) { shiftDateTime(daysDelta = 1) }
                    CompactToggleChip("-30分", false, Modifier.weight(1f)) { shiftDateTime(minutesDelta = -30) }
                    CompactToggleChip("+30分", false, Modifier.weight(1f)) { shiftDateTime(minutesDelta = 30) }
                    CompactToggleChip("当前", false, Modifier.weight(1f)) {
                        val (hm, dw, ts) = WatermarkConfig.currentTimeStrings(Date())
                        editDatePart = dw.substringBefore(" ")
                        editTimeHm = hm
                        editTimeSec = ts.substringAfterLast(":")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = editDatePart,
                        onValueChange = { editDatePart = it; validationError = null },
                        label = { Text("日期 yyyy-MM-dd") },
                        singleLine = true,
                        modifier = Modifier.weight(1.3f)
                    )
                    OutlinedTextField(
                        value = editTimeHm,
                        onValueChange = { editTimeHm = it; validationError = null },
                        label = { Text("时分 HH:mm") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = editTimeSec,
                        onValueChange = { editTimeSec = it; validationError = null },
                        label = { Text("秒") },
                        singleLine = true,
                        modifier = Modifier.weight(0.65f)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editLat,
                        onValueChange = { editLat = it; validationError = null },
                        label = { Text("高德纬度 GCJ-02") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = editLon,
                        onValueChange = { editLon = it; validationError = null },
                        label = { Text("高德经度 GCJ-02") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                TextButton(
                    onClick = {
                        editLat = String.format(Locale.US, "%.6f", target.config.origGcjLat)
                        editLon = String.format(Locale.US, "%.6f", target.config.origGcjLon)
                        validationError = null
                    },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Text(
                        "恢复拍摄原始坐标 (${String.format(Locale.US, "%.6f, %.6f", target.config.origGcjLat, target.config.origGcjLon)})",
                        color = Color(0xFF38BDF8),
                        fontSize = 12.sp
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = editAltitude,
                        onValueChange = { editAltitude = it },
                        label = { Text("海拔(m)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = editAzimuthDeg,
                        onValueChange = { editAzimuthDeg = it },
                        label = { Text("方位角(°)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = editAccuracy,
                        onValueChange = { editAccuracy = it },
                        label = { Text("精度(±m)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = editTitle,
                    onValueChange = { editTitle = it },
                    label = { Text("卡片标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = editPoi,
                    onValueChange = { editPoi = it },
                    label = { Text("地点名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = editAddr,
                    onValueChange = { editAddr = it },
                    label = { Text("完整地址描述") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = editNote,
                    onValueChange = { editNote = it },
                    label = { Text("现场备注内容") },
                    modifier = Modifier.fillMaxWidth()
                )

                Text("点缀色、字号与卡片透明度", color = Color(0xFF94A3B8), fontSize = 11.5.sp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val colors = listOf(Color(0xFF38BDF8), Color(0xFF34D399), Color(0xFFFBBF24), Color(0xFFF87171))
                        colors.forEachIndexed { idx, c ->
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(c)
                                    .border(if (editAccentColorIndex == idx) 2.5.dp else 0.dp, Color.White, CircleShape)
                                    .clickable { editAccentColorIndex = idx }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(0.85f to "小字", 1.0f to "标准", 1.15f to "大字").forEach { (sc, lbl) ->
                            CompactToggleChip(lbl, abs(editFontScale - sc) < 0.05f) { editFontScale = sc }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(120 to "通透", 185 to "标准", 230 to "深邃").forEach { (al, lbl) ->
                            CompactToggleChip(lbl, abs(editBgAlpha - al) < 20) { editBgAlpha = al }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    CompactToggleChip("时间", editShowTime, Modifier.weight(1f)) { editShowTime = !editShowTime }
                    CompactToggleChip("地点", editShowLoc, Modifier.weight(1f)) { editShowLoc = !editShowLoc }
                    CompactToggleChip("经纬", editShowCoords, Modifier.weight(1f)) { editShowCoords = !editShowCoords }
                    CompactToggleChip("定位码", editShowQr, Modifier.weight(1f)) { editShowQr = !editShowQr }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    CompactToggleChip("海拔", editShowAltitude, Modifier.weight(1f)) { editShowAltitude = !editShowAltitude }
                    CompactToggleChip("方位", editShowAzimuth, Modifier.weight(1f)) { editShowAzimuth = !editShowAzimuth }
                    CompactToggleChip("标签", editShowCoordTag, Modifier.weight(1f)) { editShowCoordTag = !editShowCoordTag }
                    CompactToggleChip("精度", editShowAccuracy, Modifier.weight(1f)) { editShowAccuracy = !editShowAccuracy }
                }
                if (editTemplate == 0) {
                    CompactToggleChip(
                        label = if (editShowBottomScrim) "底部托底暗影：开" else "底部托底暗影：关",
                        selected = editShowBottomScrim,
                        modifier = Modifier.fillMaxWidth()
                    ) { editShowBottomScrim = !editShowBottomScrim }
                }
                if (editTemplate in listOf(1, 2)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        CompactToggleChip("高德小图", editShowMiniMap, Modifier.weight(1f)) { editShowMiniMap = !editShowMiniMap }
                        CompactToggleChip(
                            label = if (editMiniMapPos == 0) "小图: 卡片内" else "小图: 左上角",
                            selected = editShowMiniMap,
                            enabled = editShowMiniMap,
                            modifier = Modifier.weight(1f)
                        ) { editMiniMapPos = if (editMiniMapPos == 0) 1 else 0 }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    CompactToggleChip("地址", editShowAddr, Modifier.weight(1f)) { editShowAddr = !editShowAddr }
                    CompactToggleChip("备注", editShowNote, Modifier.weight(1f)) { editShowNote = !editShowNote }
                    if (editTemplate in listOf(1, 2, 4)) {
                        CompactToggleChip("靠右下", editBottomRight, Modifier.weight(1f)) { editBottomRight = !editBottomRight }
                    }
                }
            }
        }
    }
}
