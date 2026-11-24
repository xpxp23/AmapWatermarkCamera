package com.fast.amapwatermark.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fast.amapwatermark.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Two-Layer iOS-Style Gallery Screen (v9.0.0):
 * - Layer 1: High-density 4-column date-grouped grid browser (Watermark Album / System Album) with multi-select.
 * - Layer 2: Immersive full-screen HorizontalPager with pinch-to-zoom, minimal 4-button floating bar, and Detail ModalBottomSheet.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun GalleryAndSystemPhotosScreen(
    appPhotos: List<PhotoEntry>,
    onBackToCamera: () -> Unit,
    onLaunchSystemBatchPicker: () -> Unit,
    onBatchWatermarkUris: (List<Uri>) -> Unit,
    onEditWatermark: (PhotoEntry) -> Unit,
    onVerifyPhoto: (QrCodeGenerator.VerifiedLocationPayload) -> Unit,
    onRequestDeletePhotos: (List<PhotoEntry>) -> Unit,
    onExportCsvLedger: (List<PhotoEntry>) -> Unit
) {
    val context = LocalContext.current

    var galleryTab by remember { mutableStateOf(0) } // 0: 水印相册, 1: 系统相册
    var systemPhotos by remember { mutableStateOf<List<SystemCameraPhoto>>(emptyList()) }
    var isScanningSys by remember { mutableStateOf(false) }

    // Layer 2 detail view index (null = grid view)
    var detailAppIndex by remember { mutableStateOf<Int?>(null) }
    var detailSysIndex by remember { mutableStateOf<Int?>(null) }

    // Multi-select mode
    var isMultiSelectMode by remember { mutableStateOf(false) }
    var selectedAppIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedSysIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

    // Keep detail index safe if photos are deleted
    LaunchedEffect(appPhotos.size) {
        val idx = detailAppIndex
        if (idx != null) {
            if (appPhotos.isEmpty()) {
                detailAppIndex = null
            } else if (idx >= appPhotos.size) {
                detailAppIndex = appPhotos.lastIndex
            }
        }
        selectedAppIds = selectedAppIds.intersect(appPhotos.map { it.id }.toSet())
    }

    LaunchedEffect(galleryTab) {
        isMultiSelectMode = false
        selectedAppIds = emptySet()
        selectedSysIds = emptySet()
        if (galleryTab == 1 && systemPhotos.isEmpty()) {
            isScanningSys = true
            val scanned = withContext(Dispatchers.IO) {
                WatermarkRenderer.scanSystemCameraPhotos(context, maxCount = 120)
            }
            systemPhotos = scanned
            isScanningSys = false
        }
    }

    // Back handling: close detail -> exit multi-select -> back to camera
    BackHandler {
        when {
            detailAppIndex != null -> detailAppIndex = null
            detailSysIndex != null -> detailSysIndex = null
            isMultiSelectMode -> {
                isMultiSelectMode = false
                selectedAppIds = emptySet()
                selectedSysIds = emptySet()
            }
            else -> onBackToCamera()
        }
    }

    // ==================== LAYER 2: IMMERSIVE DETAIL SCREEN (APP PHOTOS) ====================
    if (detailAppIndex != null && appPhotos.isNotEmpty()) {
        val safeInitial = detailAppIndex!!.coerceIn(0, appPhotos.lastIndex)
        AppPhotoImmersiveDetailScreen(
            photos = appPhotos,
            initialPage = safeInitial,
            onBack = { detailAppIndex = null },
            onEditWatermark = onEditWatermark,
            onVerifyPhoto = onVerifyPhoto,
            onDeletePhoto = { target -> onRequestDeletePhotos(listOf(target)) }
        )
        return
    }

    // ==================== LAYER 2: IMMERSIVE DETAIL SCREEN (SYSTEM PHOTOS) ====================
    if (detailSysIndex != null && systemPhotos.isNotEmpty()) {
        val safeInitial = detailSysIndex!!.coerceIn(0, systemPhotos.lastIndex)
        SystemPhotoImmersiveDetailScreen(
            photos = systemPhotos,
            initialPage = safeInitial,
            onBack = { detailSysIndex = null },
            onUpdatePhotoExif = { updated ->
                systemPhotos = systemPhotos.map { if (it.mediaId == updated.mediaId) updated else it }
            },
            onWatermarkSingle = { sysPhoto ->
                detailSysIndex = null
                onBatchWatermarkUris(listOf(sysPhoto.contentUri))
            }
        )
        return
    }

    // ==================== LAYER 1: iOS-STYLE 4-COLUMN DATE-GROUPED GRID ====================
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090D16))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Top Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black)
                .padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = {
                    if (isMultiSelectMode) {
                        isMultiSelectMode = false
                        selectedAppIds = emptySet()
                        selectedSysIds = emptySet()
                    } else {
                        onBackToCamera()
                    }
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (isMultiSelectMode) "取消" else "← 相机",
                    color = Color.White,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Dual Tab Switcher
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF1E293B))
                    .padding(2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (galleryTab == 0) Color(0xFF0284C7) else Color.Transparent)
                        .clickable { galleryTab = 0 }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("水印相册(${appPhotos.size})", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (galleryTab == 1) Color(0xFF0284C7) else Color.Transparent)
                        .clickable { galleryTab = 1 }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("系统相册", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Right actions: Multi-select & Batch Watermark
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        isMultiSelectMode = !isMultiSelectMode
                        if (!isMultiSelectMode) {
                            selectedAppIds = emptySet()
                            selectedSysIds = emptySet()
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isMultiSelectMode) "完成" else "多选",
                        color = Color(0xFF38BDF8),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (!isMultiSelectMode) {
                    TextButton(
                        onClick = onLaunchSystemBatchPicker,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text("批量", color = Color(0xFF34D399), fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Grid Content
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (galleryTab == 0) {
                if (appPhotos.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("暂无水印实拍照片", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "返回相机拍摄，或点击右上角「批量」从系统相册选取多张照片批量加装高德经纬水印。",
                            color = Color(0xFF94A3B8),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = onBackToCamera) { Text("返回拍摄") }
                            OutlinedButton(onClick = onLaunchSystemBatchPicker) { Text("批量加水印") }
                        }
                    }
                } else {
                    val groupedApp = remember(appPhotos) {
                        appPhotos.groupBy { entry ->
                            entry.config.timestamp.substringBefore(" ").ifBlank {
                                entry.config.dateWeek.substringBefore(" ").ifBlank { "最近拍摄" }
                            }
                        }
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 3.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        groupedApp.forEach { (dateKey, dayList) ->
                            item(key = "hdr_app_$dateKey", span = { GridItemSpan(4) }) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "$dateKey (${dayList.size})",
                                        color = Color(0xFFE2E8F0),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (!isMultiSelectMode && dateKey == groupedApp.keys.first()) {
                                        Row(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(Color(0xFF1E293B))
                                                .clickable { onExportCsvLedger(appPhotos) }
                                                .padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            ExportCsvVectorIcon(modifier = Modifier.size(14.dp))
                                            Text("导出台账CSV", color = Color(0xFF38BDF8), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                            }
                            items(dayList, key = { it.id }) { item ->
                                val isSelected = selectedAppIds.contains(item.id)
                                AppPhotoGridCell(
                                    item = item,
                                    isMultiSelectMode = isMultiSelectMode,
                                    isSelected = isSelected,
                                    onClick = {
                                        if (isMultiSelectMode) {
                                            selectedAppIds = if (isSelected) selectedAppIds - item.id else selectedAppIds + item.id
                                        } else {
                                            val globalIdx = appPhotos.indexOfFirst { it.id == item.id }
                                            if (globalIdx >= 0) detailAppIndex = globalIdx
                                        }
                                    },
                                    onLongClick = {
                                        isMultiSelectMode = true
                                        selectedAppIds = selectedAppIds + item.id
                                    }
                                )
                            }
                        }
                    }
                }
            } else {
                if (isScanningSys) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(color = Color(0xFF38BDF8))
                            Text("正在加载系统相册...", color = Color(0xFFCBD5E1), fontSize = 13.sp)
                        }
                    }
                } else if (systemPhotos.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("未读取到系统相册照片", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(onClick = onLaunchSystemBatchPicker) {
                            Text("调用系统相册多选加水印")
                        }
                    }
                } else {
                    val groupedSys = remember(systemPhotos) {
                        systemPhotos.groupBy { it.dateGroupKey.ifBlank { "系统照片" } }
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 3.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        groupedSys.forEach { (dateKey, dayList) ->
                            item(key = "hdr_sys_$dateKey", span = { GridItemSpan(4) }) {
                                Text(
                                    text = "$dateKey (${dayList.size})",
                                    color = Color(0xFFE2E8F0),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                                )
                            }
                            items(dayList, key = { it.mediaId }) { item ->
                                val isSelected = selectedSysIds.contains(item.mediaId)
                                SystemPhotoGridCell(
                                    item = item,
                                    isMultiSelectMode = isMultiSelectMode,
                                    isSelected = isSelected,
                                    onClick = {
                                        if (isMultiSelectMode) {
                                            selectedSysIds = if (isSelected) selectedSysIds - item.mediaId else selectedSysIds + item.mediaId
                                        } else {
                                            val globalIdx = systemPhotos.indexOfFirst { it.mediaId == item.mediaId }
                                            if (globalIdx >= 0) detailSysIndex = globalIdx
                                        }
                                    },
                                    onLongClick = {
                                        isMultiSelectMode = true
                                        selectedSysIds = selectedSysIds + item.mediaId
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Bottom Multi-Select Action Bar
        if (isMultiSelectMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F172A))
                    .border(0.5.dp, Color(0xFF1E293B))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (galleryTab == 0) {
                    val chosen = appPhotos.filter { selectedAppIds.contains(it.id) }
                    Text("已选 ${chosen.size} 张", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = chosen.isNotEmpty(),
                            onClick = {
                                shareMultipleAppPhotos(context, chosen)
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("分享", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            enabled = chosen.isNotEmpty(),
                            onClick = { onExportCsvLedger(chosen) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("台账CSV", fontSize = 12.sp, color = Color(0xFF38BDF8))
                        }
                        Button(
                            enabled = chosen.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                            onClick = {
                                onRequestDeletePhotos(chosen)
                                isMultiSelectMode = false
                                selectedAppIds = emptySet()
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("删除", fontSize = 12.sp, color = Color.White)
                        }
                    }
                } else {
                    val chosenUris = systemPhotos.filter { selectedSysIds.contains(it.mediaId) }.map { it.contentUri }
                    Text("已选 ${chosenUris.size} 张", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Button(
                        enabled = chosenUris.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        onClick = {
                            isMultiSelectMode = false
                            selectedSysIds = emptySet()
                            onBatchWatermarkUris(chosenUris)
                        }
                    ) {
                        Text("批量加水印 (${chosenUris.size})", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppPhotoGridCell(
    item: PhotoEntry,
    isMultiSelectMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    var thumb by remember(item.id, item.watermarkedFile.lastModified()) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.id, item.watermarkedFile.lastModified()) {
        thumb = withContext(Dispatchers.IO) {
            BitmapCacheManager.loadThumbnail(item.watermarkedFile, 240, 240)
        }
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF1E293B))
            .border(
                width = if (isSelected) 2.5.dp else 0.dp,
                color = if (isSelected) Color(0xFF38BDF8) else Color.Transparent,
                shape = RoundedCornerShape(4.dp)
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        thumb?.let { bmp ->
            if (!bmp.isRecycled) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Bottom-left minimal badge: Anti-tamper shield OR Editable indicator
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (item.config.antiTamperMode) Color(0xCC065F46) else Color(0xAA0F172A))
                .padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            if (item.config.antiTamperMode) {
                ShieldVerifyVectorIcon(tint = Color(0xFF34D399), modifier = Modifier.size(11.dp))
            } else {
                Text("可改", color = Color(0xFFFDE68A), fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Bottom-right minimal time badge
        if (item.config.timeHm.isNotBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xAA000000))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(item.config.timeHm, color = Color.White, fontSize = 8.5.sp, fontFamily = FontFamily.Monospace)
            }
        }

        if (isMultiSelectMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) Color(0xFF0284C7) else Color(0x88000000))
                    .border(1.5.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Text("✓", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SystemPhotoGridCell(
    item: SystemCameraPhoto,
    isMultiSelectMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    var thumb by remember(item.mediaId) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.contentUri) {
        thumb = withContext(Dispatchers.IO) {
            BitmapCacheManager.loadUriThumbnail(context, item.contentUri, 240)
        }
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF1E293B))
            .border(
                width = if (isSelected) 2.5.dp else 0.dp,
                color = if (isSelected) Color(0xFF38BDF8) else Color.Transparent,
                shape = RoundedCornerShape(4.dp)
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        thumb?.let { bmp ->
            if (!bmp.isRecycled) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        val timePart = item.dateTakenStr.substringAfter(" ", "")
        if (timePart.isNotBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xAA000000))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(timePart, color = Color.White, fontSize = 8.5.sp, fontFamily = FontFamily.Monospace)
            }
        }

        if (isMultiSelectMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) Color(0xFF0284C7) else Color(0x88000000))
                    .border(1.5.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Text("✓", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun AppPhotoImmersiveDetailScreen(
    photos: List<PhotoEntry>,
    initialPage: Int,
    onBack: () -> Unit,
    onEditWatermark: (PhotoEntry) -> Unit,
    onVerifyPhoto: (QrCodeGenerator.VerifiedLocationPayload) -> Unit,
    onDeletePhoto: (PhotoEntry) -> Unit
) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = initialPage) { photos.size }
    var showInfoSheet by remember { mutableStateOf(false) }

    val currentPhoto = photos.getOrNull(pagerState.currentPage) ?: return
    val cfg = currentPhoto.config

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val photo = photos[page]
            var bmp by remember(photo.id, photo.watermarkedFile.lastModified()) { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(photo.id, photo.watermarkedFile.lastModified()) {
                bmp = withContext(Dispatchers.IO) {
                    BitmapCacheManager.loadDisplayBitmap(photo.watermarkedFile, 1600)
                }
            }
            ZoomablePhotoPage(bitmap = bmp)
        }

        // Top Floating Overlay Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Color(0x99000000))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                Text("← 返回网格", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = cfg.shortPoi.ifBlank { "实拍点位" },
                    color = Color.White,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${pagerState.currentPage + 1} / ${photos.size} · ${cfg.timestamp}",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp
                )
            }
            Spacer(modifier = Modifier.width(64.dp))
        }

        // Bottom Floating 4-Button Minimal Action Bar: 导航 | 分享 | 改水印/已锁 | 详情
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 18.dp, vertical = 14.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xE60F172A))
                .border(0.8.dp, Color(0x4438BDF8), RoundedCornerShape(24.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ImmersiveActionItem(
                label = "导航",
                onClick = {
                    if (cfg.hasValidCoords()) {
                        AmapLauncher.jumpToAmapNavigation(context, cfg.gcjLat, cfg.gcjLon, cfg.shortPoi.ifBlank { cfg.address })
                    } else {
                        Toast.makeText(context, "该照片未包含有效定位坐标", Toast.LENGTH_SHORT).show()
                    }
                }
            ) { NavigationVectorIcon() }

            ImmersiveActionItem(
                label = "分享",
                onClick = { shareSingleAppPhoto(context, currentPhoto) }
            ) { ShareVectorIcon() }

            ImmersiveActionItem(
                label = if (cfg.antiTamperMode) "已锁" else "改水印",
                labelColor = if (cfg.antiTamperMode) Color(0xFF34D399) else Color(0xFFFDE68A),
                onClick = { onEditWatermark(currentPhoto) }
            ) {
                if (cfg.antiTamperMode) LockVectorIcon() else EditPenVectorIcon()
            }

            ImmersiveActionItem(
                label = "详情",
                onClick = { showInfoSheet = true }
            ) { InfoCircleVectorIcon() }
        }
    }

    if (showInfoSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showInfoSheet = false },
            sheetState = sheetState,
            containerColor = Color(0xFF0F172A)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = cfg.shortPoi.ifBlank { "实拍点位详情" },
                        color = Color.White,
                        fontSize = 16.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (cfg.antiTamperMode) {
                        val valid = cfg.isSignatureValid()
                        Text(
                            text = if (valid) "防伪已验真" else "防伪异常",
                            color = if (valid) Color(0xFF34D399) else Color(0xFFF87171),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (cfg.hasValidCoords()) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "高德 GCJ-02: ${CoordinateConverter.formatDecimalHemisphere(cfg.gcjLat, cfg.gcjLon, 6)}",
                                    color = Color(0xFF38BDF8),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "复制",
                                    color = Color(0xFF38BDF8),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val txt = String.format(Locale.US, "%.6f,%.6f", cfg.gcjLon, cfg.gcjLat)
                                        cm.setPrimaryClip(ClipData.newPlainText("GCJ-02", txt))
                                        Toast.makeText(context, "已复制高德坐标: $txt", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                            Text(
                                "原始 WGS-84: ${CoordinateConverter.formatDecimalHemisphere(cfg.wgsLat, cfg.wgsLon, 6)}",
                                color = Color(0xFFCBD5E1),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.5.sp
                            )
                            Text(
                                "时间：${cfg.timestamp} · 海拔 ${String.format(Locale.US, "%.1f", cfg.altitudeMeters)}m · ${cfg.azimuthText}",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.5.sp
                            )
                            if (cfg.address.isNotBlank()) {
                                Text("地址：${cfg.address}", color = Color(0xFFCBD5E1), fontSize = 11.5.sp)
                            }
                            if (cfg.note.isNotBlank()) {
                                Text("备注：${cfg.note}", color = Color(0xFFFDE68A), fontSize = 11.5.sp)
                            }
                            if (cfg.imageHash.isNotBlank()) {
                                Text("图像指纹：${cfg.imageHash}", color = Color(0xFF34D399), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            }
                        }
                    }
                } else {
                    Text("该照片未包含定位坐标（已隐藏坐标或无 GPS）", color = Color(0xFFFBBF24), fontSize = 12.5.sp)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = cfg.hasValidCoords(),
                        onClick = {
                            showInfoSheet = false
                            AmapLauncher.jumpToAmapMarker(context, cfg.gcjLat, cfg.gcjLon, cfg.shortPoi.ifBlank { cfg.address })
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("高德地图打点", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = {
                            showInfoSheet = false
                            val exp = QrCodeGenerator.computeAntiTamperHash(cfg.gcjLat, cfg.gcjLon, cfg.timestamp, cfg.shortPoi, cfg.note, cfg.imageHash).take(12)
                            onVerifyPhoto(
                                QrCodeGenerator.VerifiedLocationPayload(
                                    gcjLat = cfg.gcjLat,
                                    gcjLon = cfg.gcjLon,
                                    wgsLat = cfg.wgsLat,
                                    wgsLon = cfg.wgsLon,
                                    shortPoi = cfg.shortPoi,
                                    timestamp = cfg.timestamp,
                                    note = cfg.note,
                                    imageHash = cfg.imageHash,
                                    signature = cfg.antiTamperHash.take(12),
                                    expectedSignature = exp,
                                    ntpSynced = cfg.ntpSynced,
                                    hasAntiTamper = cfg.antiTamperMode,
                                    verified = cfg.isSignatureValid(),
                                    sourceLabel = "水印相册原片核验"
                                )
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("SHA-256 验真报告", color = Color(0xFF34D399), fontSize = 12.5.sp)
                    }
                }

                OutlinedButton(
                    onClick = {
                        showInfoSheet = false
                        onDeletePhoto(currentPhoto)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TrashVectorIcon(modifier = Modifier.size(16.dp))
                        Text("删除此照片（同步清理系统相册）", color = Color(0xFFF87171), fontSize = 12.5.sp)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SystemPhotoImmersiveDetailScreen(
    photos: List<SystemCameraPhoto>,
    initialPage: Int,
    onBack: () -> Unit,
    onUpdatePhotoExif: (SystemCameraPhoto) -> Unit,
    onWatermarkSingle: (SystemCameraPhoto) -> Unit
) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = initialPage) { photos.size }
    var showInfoSheet by remember { mutableStateOf(false) }

    val currentPhoto = photos.getOrNull(pagerState.currentPage) ?: return

    // Lazy on-demand EXIF loading for the active page
    LaunchedEffect(currentPhoto.mediaId, currentPhoto.exifLoaded) {
        if (!currentPhoto.exifLoaded) {
            val loaded = withContext(Dispatchers.IO) {
                WatermarkRenderer.loadSystemPhotoExif(context, currentPhoto)
            }
            onUpdatePhotoExif(loaded)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val photo = photos[page]
            var bmp by remember(photo.mediaId) { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(photo.contentUri) {
                bmp = withContext(Dispatchers.IO) {
                    BitmapCacheManager.loadUriThumbnail(context, photo.contentUri, 1440)
                }
            }
            ZoomablePhotoPage(bitmap = bmp)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Color(0x99000000))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                Text("← 返回网格", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(currentPhoto.displayName, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${pagerState.currentPage + 1} / ${photos.size} · ${currentPhoto.dateTakenStr}", color = Color(0xFF94A3B8), fontSize = 11.sp)
            }
            Spacer(modifier = Modifier.width(64.dp))
        }

        // Bottom Floating 4-Button Minimal Action Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 18.dp, vertical = 14.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xE60F172A))
                .border(0.8.dp, Color(0x4438BDF8), RoundedCornerShape(24.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ImmersiveActionItem(
                label = "导航",
                labelColor = if (currentPhoto.hasGps) Color.White else Color(0xFF64748B),
                onClick = {
                    if (currentPhoto.hasGps) {
                        AmapLauncher.jumpToAmapNavigation(context, currentPhoto.gcjLat, currentPhoto.gcjLon, currentPhoto.displayName)
                    } else {
                        Toast.makeText(context, "该系统照片未包含 EXIF GPS 坐标", Toast.LENGTH_SHORT).show()
                    }
                }
            ) { NavigationVectorIcon(tint = if (currentPhoto.hasGps) Color(0xFF38BDF8) else Color(0xFF64748B)) }

            ImmersiveActionItem(
                label = "分享",
                onClick = {
                    try {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "image/jpeg"
                            putExtra(Intent.EXTRA_STREAM, currentPhoto.contentUri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "分享照片"))
                    } catch (e: Exception) {
                        Toast.makeText(context, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            ) { ShareVectorIcon() }

            ImmersiveActionItem(
                label = "加水印",
                labelColor = Color(0xFF34D399),
                onClick = { onWatermarkSingle(currentPhoto) }
            ) { BatchWatermarkVectorIcon(tint = Color(0xFF34D399), modifier = Modifier.size(22.dp)) }

            ImmersiveActionItem(
                label = "详情",
                onClick = { showInfoSheet = true }
            ) { InfoCircleVectorIcon() }
        }
    }

    if (showInfoSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showInfoSheet = false },
            sheetState = sheetState,
            containerColor = Color(0xFF0F172A)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(currentPhoto.displayName, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("拍摄/修改时间：${currentPhoto.dateTakenStr}", color = Color(0xFF94A3B8), fontSize = 12.sp)

                if (!currentPhoto.exifLoaded) {
                    Text("正在读取 EXIF 地理位置信息...", color = Color(0xFF38BDF8), fontSize = 12.5.sp)
                } else if (currentPhoto.hasGps) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(
                                "高德 GCJ-02: ${CoordinateConverter.formatDecimalHemisphere(currentPhoto.gcjLat, currentPhoto.gcjLon, 6)}",
                                color = Color(0xFF38BDF8),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "原始 WGS-84: ${CoordinateConverter.formatDecimalHemisphere(currentPhoto.wgsLat, currentPhoto.wgsLon, 6)}",
                                color = Color(0xFFCBD5E1),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.5.sp
                            )
                            if (currentPhoto.antiTamperHash.isNotBlank()) {
                                Text(
                                    if (currentPhoto.antiTamperVerified) "EXIF 防伪验真通过 (${currentPhoto.antiTamperHash.take(12)})"
                                    else "EXIF 防伪签名异常",
                                    color = if (currentPhoto.antiTamperVerified) Color(0xFF34D399) else Color(0xFFF87171),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Button(
                        onClick = {
                            showInfoSheet = false
                            AmapLauncher.jumpToAmapMarker(context, currentPhoto.gcjLat, currentPhoto.gcjLon, currentPhoto.displayName)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("在高德地图中查看打点")
                    }
                } else {
                    Text("该照片未包含 EXIF GPS 坐标信息，可直接点击“加水印”选择当前位置或隐藏坐标。", color = Color(0xFFFBBF24), fontSize = 12.5.sp)
                }
            }
        }
    }
}

@Composable
private fun ZoomablePhotoPage(bitmap: Bitmap?) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1.2f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val nextScale = (scale * zoom).coerceIn(1f, 4f)
                    scale = nextScale
                    offset = if (nextScale > 1.01f) {
                        Offset(offset.x + pan.x, offset.y + pan.y)
                    } else {
                        Offset.Zero
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null && !bitmap.isRecycled) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
            )
        } else {
            CircularProgressIndicator(color = Color(0xFF38BDF8))
        }
    }
}

@Composable
private fun ImmersiveActionItem(
    label: String,
    labelColor: Color = Color.White,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        icon()
        Spacer(modifier = Modifier.height(3.dp))
        Text(label, color = labelColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

private fun shareSingleAppPhoto(context: Context, entry: PhotoEntry) {
    try {
        val shareUri = WatermarkRenderer.getShareUriForFile(context, entry.watermarkedFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享高德水印照片"))
    } catch (e: Exception) {
        Toast.makeText(context, "调起系统分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

private fun shareMultipleAppPhotos(context: Context, entries: List<PhotoEntry>) {
    try {
        val uris = ArrayList(entries.map { WatermarkRenderer.getShareUriForFile(context, it.watermarkedFile) })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/jpeg"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "批量分享 ${entries.size} 张照片"))
    } catch (e: Exception) {
        Toast.makeText(context, "批量分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
