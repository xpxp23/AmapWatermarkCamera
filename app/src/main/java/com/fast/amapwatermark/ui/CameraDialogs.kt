package com.fast.amapwatermark.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fast.amapwatermark.util.*
import java.util.Locale

private val QUICK_POI_TAGS = listOf("1号点位", "施工现场", "巡检点A", "北门入口", "主楼基坑")

/**
 * Location Calibration Dialog (v9.0.0):
 * - Uses independent draft state (initialized once on open) so live GPS ticks never overwrite user typing!
 * - When antiTamperMode == true, locks POI name, address, AND coordinates to prevent spoofing.
 */
@Composable
fun LocationCalibrationDialog(
    loc: LocationSnapshot,
    antiTamperMode: Boolean,
    locationManager: FastLocationManager,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    // Independent draft states initialized once when dialog enters composition
    var poiInput by remember { mutableStateOf(if (loc.hasValidCoordinates()) loc.shortPoi else "") }
    var addrInput by remember { mutableStateOf(if (loc.hasValidCoordinates()) loc.fullAddress else "") }
    var manualLatInput by remember {
        mutableStateOf(if (loc.hasValidCoordinates()) String.format(Locale.US, "%.6f", loc.gcjLat) else "")
    }
    var manualLonInput by remember {
        mutableStateOf(if (loc.hasValidCoordinates()) String.format(Locale.US, "%.6f", loc.gcjLon) else "")
    }
    var validationError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0F172A),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RedPinVectorIcon(modifier = Modifier.size(18.dp))
                Text("地点与高德坐标校准", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (loc.hasValidCoordinates()) {
                            val offsetDist = CoordinateConverter.distanceMeters(loc.gcjLat, loc.gcjLon, loc.wgsLat, loc.wgsLon)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "高德 GCJ-02: ${CoordinateConverter.formatDecimalHemisphere(loc.gcjLat, loc.gcjLon, 6)}",
                                    color = Color(0xFF38BDF8),
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = "复制",
                                    color = Color(0xFF38BDF8),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val txt = String.format(Locale.US, "%.6f,%.6f", loc.gcjLon, loc.gcjLat)
                                        cm.setPrimaryClip(ClipData.newPlainText("GCJ-02", txt))
                                        Toast.makeText(context, "已复制高德坐标: $txt", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                            Text(
                                "原始 WGS-84: ${CoordinateConverter.formatDecimalHemisphere(loc.wgsLat, loc.wgsLon, 6)}",
                                color = Color(0xFF94A3B8),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                            Text(
                                "偏移纠偏 ${String.format(Locale.US, "%.1f", offsetDist)}m · 海拔 ${String.format(Locale.US, "%.1f", loc.altitudeMeters)}m · ${loc.azimuthText}",
                                color = Color(0xFF34D399),
                                fontSize = 11.sp
                            )
                        } else {
                            Text(
                                "正在获取卫星/网络真实定位（冷启动不填充虚假坐标）",
                                color = Color(0xFFFBBF24),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                if (antiTamperMode) {
                    Text(
                        "当前处于「外业防篡改验真模式」，真实授时 (${loc.ntpSource})、实测坐标、地点名称与详细地址已全部锁定禁止手改。",
                        color = Color(0xFF34D399),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                OutlinedTextField(
                    value = poiInput,
                    onValueChange = { poiInput = it },
                    enabled = !antiTamperMode,
                    label = { Text("地点简称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (!antiTamperMode) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val combinedTags = (loc.nearbyPois + QUICK_POI_TAGS).distinct()
                        combinedTags.forEach { tag ->
                            SuggestionChip(
                                onClick = { poiInput = tag },
                                label = { Text(tag, fontSize = 11.sp) }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = addrInput,
                    onValueChange = { addrInput = it },
                    enabled = !antiTamperMode,
                    label = { Text("详细结构化地址") },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = manualLatInput,
                        onValueChange = { manualLatInput = it; validationError = null },
                        enabled = !antiTamperMode,
                        label = { Text("高德纬度 GCJ-02") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = manualLonInput,
                        onValueChange = { manualLonInput = it; validationError = null },
                        enabled = !antiTamperMode,
                        label = { Text("高德经度 GCJ-02") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                validationError?.let { err ->
                    Text(err, color = Color(0xFFF87171), fontSize = 11.5.sp)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            locationManager.setCustomLocationText(null, null)
                            locationManager.restoreRealGpsCoordinates(clearCustomText = true)
                            locationManager.startHighFrequencyUpdates(forceRestart = true)
                            if (loc.rawGcjLat != 0.0 || loc.rawGcjLon != 0.0) {
                                manualLatInput = String.format(Locale.US, "%.6f", loc.rawGcjLat)
                                manualLonInput = String.format(Locale.US, "%.6f", loc.rawGcjLon)
                            }
                            Toast.makeText(context, "已恢复手机硬件真实 GPS 与自动逆地理", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (loc.isManualCoord) "恢复真GPS" else "刷新真GPS", fontSize = 12.sp)
                    }
                    Button(
                        enabled = loc.hasValidCoordinates() || (manualLatInput.toDoubleOrNull() != null && manualLonInput.toDoubleOrNull() != null),
                        onClick = {
                            val targetLat = manualLatInput.trim().toDoubleOrNull() ?: loc.gcjLat
                            val targetLon = manualLonInput.trim().toDoubleOrNull() ?: loc.gcjLon
                            AmapLauncher.jumpToAmapMarker(context, targetLat, targetLon, poiInput.ifBlank { loc.shortPoi })
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("跳高德核验", fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            if (!antiTamperMode) {
                Button(onClick = {
                    val latTxt = manualLatInput.trim()
                    val lonTxt = manualLonInput.trim()
                    if (latTxt.isNotEmpty() || lonTxt.isNotEmpty()) {
                        val newLat = latTxt.toDoubleOrNull()
                        val newLon = lonTxt.toDoubleOrNull()
                        if (newLat == null || newLon == null || newLat !in -90.0..90.0 || newLon !in -180.0..180.0) {
                            validationError = "请输入有效的经纬度数值（纬度 -90~90，经度 -180~180）"
                            return@Button
                        }
                        if (newLat != loc.gcjLat || newLon != loc.gcjLon) {
                            locationManager.updateManualCoordinates(newLat, newLon)
                        }
                    }
                    locationManager.setCustomLocationText(poiInput.trim().ifBlank { null }, addrInput.trim().ifBlank { null })
                    onDismiss()
                }) {
                    Text("应用修改")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/**
 * Camera Engine & Settings Dialog (v9.0.0)
 */
@Composable
fun CameraSettingsDialog(
    captureMode: Int,
    onCaptureModeChange: (Int) -> Unit,
    styleTemplate: Int,
    showMiniMap: Boolean,
    onShowMiniMapChange: (Boolean) -> Unit,
    antiTamperMode: Boolean,
    onAntiTamperModeChange: (Boolean) -> Unit,
    showQrCode: Boolean,
    onShowQrCodeChange: (Boolean) -> Unit,
    coordSystem: String,
    onCoordSystemChange: (String) -> Unit,
    asyncOptimal: Boolean,
    onAsyncOptimalChange: (Boolean) -> Unit,
    accuracyThreshold: Int,
    onAccuracyThresholdChange: (Int) -> Unit,
    keepCleanOriginal: Boolean,
    onKeepCleanOriginalChange: (Boolean) -> Unit,
    showGridLines: Boolean,
    onShowGridLinesChange: (Boolean) -> Unit,
    bottomRight: Boolean,
    onBottomRightChange: (Boolean) -> Unit,
    photoCount: Int,
    onExportCsvLedger: () -> Unit,
    onDismiss: () -> Unit
) {
    val supportsMiniMap = styleTemplate in listOf(1, 2)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0F172A),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GearVectorIcon()
                Text("相机引擎与定位设置 (v9.0.0)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.5.sp)
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("核心拍摄引擎模式", color = Color(0xFF38BDF8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    CompactToggleChip("光学拍照 (ISP高清)", captureMode == 1, Modifier.weight(1f)) { onCaptureModeChange(1) }
                    CompactToggleChip("极速截屏 (0ms抓拍)", captureMode == 0, Modifier.weight(1f)) { onCaptureModeChange(0) }
                }
                Text(
                    text = if (captureMode == 1) {
                        "调用硬件 ImageCapture 拍照（2560px 高清、自动测光与多帧降噪）"
                    } else {
                        "直接定格取景框画面（0ms 零快门延迟、文件轻量，适合移动抓拍）"
                    },
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp
                )

                WatermarkToggleRow(
                    label = if (supportsMiniMap) "160×160 高德矢量缩略地图" else "高德矢量缩略地图（仅模板 2/3 支持）",
                    checked = showMiniMap && supportsMiniMap,
                    enabled = supportsMiniMap,
                    onCheckedChange = onShowMiniMapChange
                )
                WatermarkToggleRow(
                    label = "外业防篡改验真模式 (NTP真时 + SHA-256 + 图像指纹)",
                    checked = antiTamperMode,
                    onCheckedChange = onAntiTamperModeChange
                )
                WatermarkToggleRow("水印内嵌高德定位二维码", showQrCode, onCheckedChange = onShowQrCodeChange)

                Text("水印显示坐标系（扫码跳高德恒定零偏差）", color = Color(0xFF38BDF8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    CompactToggleChip("WGS-84", coordSystem == "WGS-84", Modifier.weight(1f)) { onCoordSystemChange("WGS-84") }
                    CompactToggleChip("CGCS2000", coordSystem == "CGCS2000", Modifier.weight(1f)) { onCoordSystemChange("CGCS2000") }
                    CompactToggleChip("GCJ-02", coordSystem == "GCJ-02", Modifier.weight(1f)) { onCoordSystemChange("GCJ-02") }
                }

                WatermarkToggleRow("弱信号拍照等待收敛（最多 1.1s，默认关闭）", asyncOptimal, onCheckedChange = onAsyncOptimalChange)
                if (asyncOptimal) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("收敛精度阈值", color = Color(0xFF94A3B8), fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(5, 10, 20).forEach { th ->
                                CompactToggleChip("≤${th}m", accuracyThreshold == th) { onAccuracyThresholdChange(th) }
                            }
                        }
                    }
                }
                WatermarkToggleRow("同时保存无水印纯净原图到系统相册", keepCleanOriginal, onCheckedChange = onKeepCleanOriginalChange)
                WatermarkToggleRow("取景框显示九宫格构图辅助线", showGridLines, onCheckedChange = onShowGridLinesChange)
                if (styleTemplate in listOf(1, 2, 4)) {
                    WatermarkToggleRow("卡片水印靠右下角对齐", bottomRight, onCheckedChange = onBottomRightChange)
                }

                if (photoCount > 0) {
                    OutlinedButton(
                        onClick = onExportCsvLedger,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ExportCsvVectorIcon(modifier = Modifier.size(16.dp))
                            Text("导出工程台账 CSV（共 $photoCount 条记录）", color = Color(0xFF38BDF8), fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("确定") }
        }
    )
}

/**
 * Independent Anti-Tamper Verification Center Dialog (v9.0.0)
 */
@Composable
fun VerifyCenterDialog(
    verifyPayload: QrCodeGenerator.VerifiedLocationPayload?,
    onUpdatePayload: (QrCodeGenerator.VerifiedLocationPayload?) -> Unit,
    onOpenGallery: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var manualVerifyText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0F172A),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShieldVerifyVectorIcon(modifier = Modifier.size(20.dp))
                Text("高德水印识图验真中心", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.5.sp)
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            onDismiss()
                            onOpenGallery()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("相册选图验真", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clipTxt = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                            if (clipTxt.isNotBlank()) {
                                manualVerifyText = clipTxt
                                val parsed = QrCodeGenerator.parseAndVerifyPayload(clipTxt, "剪贴板链接核验")
                                if (parsed != null) {
                                    onUpdatePayload(parsed)
                                } else {
                                    Toast.makeText(context, "剪贴板内容未包含有效定位码参数", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(context, "剪贴板为空", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("粘贴剪贴板核验", fontSize = 12.sp)
                    }
                }

                val rep = verifyPayload
                if (rep != null) {
                    val borderCol = when {
                        rep.hasAntiTamper && rep.verified -> Color(0xFF34D399)
                        rep.hasAntiTamper && !rep.verified -> Color(0xFFF87171)
                        else -> Color(0xFF38BDF8)
                    }
                    val statusBadge = when {
                        rep.hasAntiTamper && rep.verified -> "SHA-256 防篡改核验通过 · 真实原片"
                        rep.hasAntiTamper && !rep.verified -> "警告：签名不匹配 · 疑似被篡改"
                        else -> "普通高德定位照片 (未开启防伪签名)"
                    }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF090D16)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().border(1.5.dp, borderCol, RoundedCornerShape(12.dp))
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(statusBadge, color = borderCol, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("核验来源: ${rep.sourceLabel}", color = Color(0xFF94A3B8), fontSize = 11.sp)
                            Text("实拍点位: ${rep.shortPoi}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(
                                "高德 GCJ-02: ${CoordinateConverter.formatDecimalHemisphere(rep.gcjLat, rep.gcjLon, 6)}",
                                color = Color(0xFF38BDF8),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.5.sp
                            )
                            Text(
                                "大地 WGS-84: ${CoordinateConverter.formatDecimalHemisphere(rep.wgsLat, rep.wgsLon, 6)}",
                                color = Color(0xFFCBD5E1),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.5.sp
                            )
                            if (rep.timestamp.isNotBlank()) {
                                Text("记录时间: ${rep.timestamp} (${if (rep.ntpSynced) "NTP真时已锁" else "标准授时"})", color = Color.White, fontSize = 11.5.sp)
                            }
                            if (rep.note.isNotBlank()) {
                                Text("内嵌备注: ${rep.note}", color = Color(0xFFFDE68A), fontSize = 11.5.sp)
                            }
                            if (rep.imageHash.isNotBlank()) {
                                Text("图像指纹: ${rep.imageHash}", color = Color(0xFF34D399), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            }
                            if (rep.hasAntiTamper) {
                                Text("内嵌签名: ${rep.signature}", color = Color(0xFF94A3B8), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                Text("重算签名: ${rep.expectedSignature}", color = borderCol, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { AmapLauncher.jumpToAmapMarker(context, rep.gcjLat, rep.gcjLon, rep.shortPoi) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("跳高德导航", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val reportStr = buildString {
                                    append("【高德水印相机 v9.0.0 · 验真报告】\n")
                                    append("结论：$statusBadge\n")
                                    append("点位：${rep.shortPoi}\n")
                                    append("高德GCJ-02：${CoordinateConverter.formatDecimalHemisphere(rep.gcjLat, rep.gcjLon, 6)}\n")
                                    append("时间：${rep.timestamp}\n")
                                    if (rep.imageHash.isNotBlank()) append("图像指纹：${rep.imageHash}\n")
                                    append("签名：${rep.signature}")
                                }
                                cm.setPrimaryClip(ClipData.newPlainText("VerifyReport", reportStr))
                                Toast.makeText(context, "已复制完整验真报告", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("复制验真报告", fontSize = 12.sp)
                        }
                    }
                }

                OutlinedTextField(
                    value = manualVerifyText,
                    onValueChange = { manualVerifyText = it },
                    label = { Text("粘贴二维码链接或含 sig= 的防伪串核验") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        val parsed = QrCodeGenerator.parseAndVerifyPayload(manualVerifyText, "手动输入链接核验")
                        if (parsed != null) {
                            onUpdatePayload(parsed)
                        } else {
                            Toast.makeText(context, "未能从文本中解析出高德坐标或防伪签名", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("立即解析并核验真伪")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
fun DeletePhotosConfirmDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0F172A),
        title = {
            Text(
                if (count > 1) "确认批量删除 $count 张照片" else "确认删除照片",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        },
        text = {
            Text(
                "删除后将从应用相册与系统相册 (DCIM/Camera，含纯净原图) 中同步移除且不可恢复，是否确认？",
                color = Color(0xFFCBD5E1),
                fontSize = 13.sp
            )
        },
        confirmButton = {
            Button(
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                onClick = onConfirm
            ) {
                Text("确认删除", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
fun ResetWatermarkConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0F172A),
        title = { Text("确认重置水印设置", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Text("将恢复默认水印模板、主题色、字体大小与显示开关，是否确认重置？", color = Color(0xFFCBD5E1), fontSize = 13.sp)
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text("确认重置") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
