package com.fast.amapwatermark.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fast.amapwatermark.util.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Locale

data class BatchProgressState(
    val isRunning: Boolean = false,
    val total: Int = 0,
    val processed: Int = 0,
    val succeeded: Int = 0,
    val failed: Int = 0,
    val currentStatus: String = "",
    val finished: Boolean = false,
    val cancelled: Boolean = false
)

/**
 * Batch Watermark parameter confirmation dialog before starting the serial IO pipeline.
 */
@Composable
fun BatchWatermarkConfigDialog(
    selectedCount: Int,
    currentLoc: LocationSnapshot,
    onDismiss: () -> Unit,
    onConfirm: (BatchWatermarkOptions) -> Unit
) {
    var timeStrategy by remember { mutableStateOf(BatchTimeStrategy.PREFER_EXIF) }
    var missingGpsStrategy by remember {
        mutableStateOf(
            if (currentLoc.hasValidCoordinates()) BatchMissingGpsStrategy.USE_CURRENT_LOCATION
            else BatchMissingGpsStrategy.HIDE_COORDS
        )
    }
    var manualLat by remember {
        mutableStateOf(
            if (currentLoc.hasValidCoordinates()) String.format(Locale.US, "%.6f", currentLoc.gcjLat) else ""
        )
    }
    var manualLon by remember {
        mutableStateOf(
            if (currentLoc.hasValidCoordinates()) String.format(Locale.US, "%.6f", currentLoc.gcjLon) else ""
        )
    }
    var manualPoi by remember {
        mutableStateOf(if (currentLoc.hasValidCoordinates()) currentLoc.shortPoi else "")
    }
    var validationError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0F172A),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BatchWatermarkVectorIcon(tint = Color(0xFF38BDF8), modifier = Modifier.size(22.dp))
                Text("批量加水印 ($selectedCount 张)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "将按当前水印模板与配色，串行低内存逐张渲染并存入系统相册 DCIM/Camera；同片区 100m 网格自动复用逆地理缓存。",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp
                )

                Text("时间策略", color = Color(0xFF38BDF8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    CompactToggleChip(
                        label = "优先原片 EXIF 时间",
                        selected = timeStrategy == BatchTimeStrategy.PREFER_EXIF,
                        modifier = Modifier.weight(1f)
                    ) { timeStrategy = BatchTimeStrategy.PREFER_EXIF }
                    CompactToggleChip(
                        label = "统一当前时间",
                        selected = timeStrategy == BatchTimeStrategy.UNIFY_CURRENT,
                        modifier = Modifier.weight(1f)
                    ) { timeStrategy = BatchTimeStrategy.UNIFY_CURRENT }
                }

                Text("原片无 GPS 时的兜底策略", color = Color(0xFF38BDF8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    CompactToggleChip(
                        label = "当前位置",
                        selected = missingGpsStrategy == BatchMissingGpsStrategy.USE_CURRENT_LOCATION,
                        modifier = Modifier.weight(1f)
                    ) { missingGpsStrategy = BatchMissingGpsStrategy.USE_CURRENT_LOCATION }
                    CompactToggleChip(
                        label = "手动指定",
                        selected = missingGpsStrategy == BatchMissingGpsStrategy.USE_MANUAL_COORDS,
                        modifier = Modifier.weight(1f)
                    ) { missingGpsStrategy = BatchMissingGpsStrategy.USE_MANUAL_COORDS }
                    CompactToggleChip(
                        label = "隐藏坐标",
                        selected = missingGpsStrategy == BatchMissingGpsStrategy.HIDE_COORDS,
                        modifier = Modifier.weight(1f)
                    ) { missingGpsStrategy = BatchMissingGpsStrategy.HIDE_COORDS }
                }

                if (missingGpsStrategy == BatchMissingGpsStrategy.USE_CURRENT_LOCATION && !currentLoc.hasValidCoordinates()) {
                    Text(
                        "提示：当前尚未获取到真实 GPS 定位，若继续则无 GPS 照片将显示“定位中”。建议切换为“手动指定”或“隐藏坐标”。",
                        color = Color(0xFFFBBF24),
                        fontSize = 11.5.sp
                    )
                }

                if (missingGpsStrategy == BatchMissingGpsStrategy.USE_MANUAL_COORDS) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = manualLat,
                            onValueChange = { manualLat = it; validationError = null },
                            label = { Text("高德纬度") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = manualLon,
                            onValueChange = { manualLon = it; validationError = null },
                            label = { Text("高德经度") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = manualPoi,
                        onValueChange = { manualPoi = it },
                        label = { Text("兜底地点名称 (选填)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                validationError?.let { err ->
                    Text(err, color = Color(0xFFF87171), fontSize = 11.5.sp)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (missingGpsStrategy == BatchMissingGpsStrategy.USE_MANUAL_COORDS) {
                    val lat = manualLat.trim().toDoubleOrNull()
                    val lon = manualLon.trim().toDoubleOrNull()
                    if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                        validationError = "请输入有效的高德经纬度（纬度 -90~90，经度 -180~180）"
                        return@Button
                    }
                    onConfirm(
                        BatchWatermarkOptions(
                            timeStrategy = timeStrategy,
                            missingGpsStrategy = missingGpsStrategy,
                            manualGcjLat = lat,
                            manualGcjLon = lon,
                            manualPoi = manualPoi.trim()
                        )
                    )
                } else {
                    onConfirm(
                        BatchWatermarkOptions(
                            timeStrategy = timeStrategy,
                            missingGpsStrategy = missingGpsStrategy
                        )
                    )
                }
            }) {
                Text("开始批量处理")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * Real-time batch processing progress & completion summary dialog.
 */
@Composable
fun BatchWatermarkProgressDialog(
    state: BatchProgressState,
    onCancel: () -> Unit,
    onDismissFinished: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (state.finished) onDismissFinished() },
        containerColor = Color(0xFF0F172A),
        title = {
            Text(
                text = when {
                    state.cancelled -> "批量处理已取消"
                    state.finished -> "批量加水印完成"
                    else -> "正在批量渲染水印 (${state.processed}/${state.total})"
                },
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 16.5.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                val progress = if (state.total > 0) (state.processed.toFloat() / state.total.toFloat()).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = if (state.finished && !state.cancelled) Color(0xFF34D399) else Color(0xFF38BDF8),
                    trackColor = Color(0xFF1E293B)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "进度：${state.processed} / ${state.total}",
                        color = Color.White,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "成功 ${state.succeeded} · 失败 ${state.failed}",
                        color = if (state.failed > 0) Color(0xFFFBBF24) else Color(0xFF34D399),
                        fontSize = 12.sp
                    )
                }
                if (state.currentStatus.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1E293B))
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Text(state.currentStatus, color = Color(0xFFCBD5E1), fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            if (state.finished) {
                Button(onClick = onDismissFinished) {
                    Text("完成")
                }
            } else {
                OutlinedButton(onClick = onCancel) {
                    Text("取消处理", color = Color(0xFFF87171))
                }
            }
        }
    )
}

/**
 * Strictly serial IO pipeline (limitedParallelism(1)) to cap peak memory during batch watermarking.
 * Uses 100m grid reverse geocoding cache to avoid redundant network requests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
suspend fun runBatchWatermarkPipeline(
    context: Context,
    uris: List<Uri>,
    locationManager: FastLocationManager,
    fallbackLoc: LocationSnapshot,
    defaultCoordSystem: String,
    templateBase: WatermarkConfig,
    options: BatchWatermarkOptions,
    onProgress: (BatchProgressState) -> Unit,
    onItemCreated: (PhotoEntry) -> Unit
) {
    val serialDispatcher = Dispatchers.IO.limitedParallelism(1)
    val total = uris.size
    var processed = 0
    var succeeded = 0
    var failed = 0

    withContext(Dispatchers.Main) {
        onProgress(
            BatchProgressState(
                isRunning = true,
                total = total,
                processed = 0,
                succeeded = 0,
                failed = 0,
                currentStatus = "准备串行处理 $total 张照片..."
            )
        )
    }

    try {
        withContext(serialDispatcher) {
            for ((idx, uri) in uris.withIndex()) {
                if (!isActive) throw CancellationException("User cancelled batch")

                withContext(Dispatchers.Main) {
                    onProgress(
                        BatchProgressState(
                            isRunning = true,
                            total = total,
                            processed = processed,
                            succeeded = succeeded,
                            failed = failed,
                            currentStatus = "正在处理第 ${idx + 1}/$total 张 · 提取 EXIF 与逆地理..."
                        )
                    )
                }

                // Step 1: Extract EXIF coords & resolve POI with 100m grid memory cache
                val exifCoords = WatermarkRenderer.extractExifCoordsFromUri(context, uri)
                val resolvedPoiAddr: Pair<String, String>? = when {
                    exifCoords != null -> {
                        val (wgs, gcj) = exifCoords
                        locationManager.reverseGeocodeWithGridCache(wgs.lat, wgs.lon, gcj.lat, gcj.lon)
                    }
                    options.missingGpsStrategy == BatchMissingGpsStrategy.USE_MANUAL_COORDS &&
                            options.manualGcjLat != null && options.manualGcjLon != null &&
                            options.manualPoi.isBlank() -> {
                        val wgs = CoordinateConverter.gcj02ToWgs84(options.manualGcjLat, options.manualGcjLon)
                        locationManager.reverseGeocodeWithGridCache(wgs.lat, wgs.lon, options.manualGcjLat, options.manualGcjLon)
                    }
                    else -> null
                }

                // Step 2: Two-step sampled decode + render + save + recycle
                val entry = WatermarkRenderer.importExternalPhotoUri(
                    context = context,
                    uri = uri,
                    fallbackLoc = fallbackLoc,
                    defaultSys = defaultCoordSystem,
                    templateBase = templateBase,
                    batchOptions = options,
                    resolvedPoiAddress = resolvedPoiAddr
                )

                processed++
                if (entry != null) {
                    succeeded++
                    withContext(Dispatchers.Main) {
                        onItemCreated(entry)
                    }
                } else {
                    failed++
                }

                withContext(Dispatchers.Main) {
                    onProgress(
                        BatchProgressState(
                            isRunning = true,
                            total = total,
                            processed = processed,
                            succeeded = succeeded,
                            failed = failed,
                            currentStatus = "已完成 $processed/$total 张"
                        )
                    )
                }
            }
        }

        withContext(Dispatchers.Main) {
            onProgress(
                BatchProgressState(
                    isRunning = false,
                    total = total,
                    processed = processed,
                    succeeded = succeeded,
                    failed = failed,
                    currentStatus = "已全部处理完成，成功 $succeeded 张已保存至系统相册 DCIM/Camera。",
                    finished = true,
                    cancelled = false
                )
            )
        }
    } catch (_: CancellationException) {
        withContext(Dispatchers.Main) {
            onProgress(
                BatchProgressState(
                    isRunning = false,
                    total = total,
                    processed = processed,
                    succeeded = succeeded,
                    failed = failed,
                    currentStatus = "已取消剩余任务（已成功保存 $succeeded 张）。",
                    finished = true,
                    cancelled = true
                )
            )
        }
    }
}
