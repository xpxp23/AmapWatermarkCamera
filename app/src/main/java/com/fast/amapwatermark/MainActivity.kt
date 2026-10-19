package com.fast.amapwatermark

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.fast.amapwatermark.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    private lateinit var fastLocationManager: FastLocationManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        fastLocationManager = FastLocationManager(this)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF38BDF8),
                    surface = Color(0xFF0F172A),
                    background = Color.Black
                )
            ) {
                ProWatermarkCameraScreen(fastLocationManager)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        fastLocationManager.startHighFrequencyUpdates()
    }

    override fun onPause() {
        super.onPause()
        fastLocationManager.stopUpdates()
    }
}

@Composable
fun ProWatermarkCameraScreen(locationManager: FastLocationManager) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // Live reactive GPS state
    val loc by locationManager.locationState

    // Live clock ticker (updates every second)
    var timeTick by remember { mutableStateOf(WatermarkConfig.currentTimeStrings()) }
    LaunchedEffect(Unit) {
        while (true) {
            timeTick = WatermarkConfig.currentTimeStrings()
            delay(1000L)
        }
    }

    // Permission states
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    // Camera hardware states
    var isFrontCamera by remember { mutableStateOf(false) }
    var flashMode by remember { mutableStateOf(0) } // 0: 关, 1: 开, 2: 自动, 3: 常亮
    var timerSeconds by remember { mutableStateOf(0) } // 0: 关, 3: 3s, 5: 5s
    var countdownRemaining by remember { mutableStateOf(0) }
    var zoomRatio by remember { mutableStateOf(1.0f) }
    var maxZoomRatio by remember { mutableStateOf(4.0f) }
    var shutterFlashVisible by remember { mutableStateOf(false) }
    var asyncSamplingBanner by remember { mutableStateOf<String?>(null) }

    // Tap-to-focus & exposure compensation states (Figure 3 focus box + sun icon)
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusTriggerId by remember { mutableStateOf(0) }
    var exposureIndex by remember { mutableStateOf(0) }
    var exposureRange by remember { mutableStateOf(-4..4) }

    val prefs = remember { context.getSharedPreferences("amap_wm_prefs", Context.MODE_PRIVATE) }

    // Watermark & filter states (persisted across launches)
    var styleTemplate by remember { mutableStateOf(prefs.getInt("styleTemplate", 0)) }
    var coordSystem by remember { mutableStateOf(prefs.getString("coordSystem", "GCJ-02") ?: "GCJ-02") }
    var useDms by remember { mutableStateOf(prefs.getBoolean("useDms", false)) }
    var customNote by remember { mutableStateOf(prefs.getString("customNote", "") ?: "") }
    var justCapturedPhoto by remember { mutableStateOf<PhotoEntry?>(null) }

    LaunchedEffect(styleTemplate, coordSystem, useDms, customNote) {
        prefs.edit()
            .putInt("styleTemplate", styleTemplate)
            .putString("coordSystem", coordSystem)
            .putBoolean("useDms", useDms)
            .putString("customNote", customNote)
            .apply()
    }
    var showTime by remember { mutableStateOf(true) }
    var showLocation by remember { mutableStateOf(true) }
    var showCoords by remember { mutableStateOf(true) }
    var showCoordTag by remember { mutableStateOf(true) }
    var showAccuracy by remember { mutableStateOf(true) }
    var showAddress by remember { mutableStateOf(false) }
    var showNote by remember { mutableStateOf(true) }
    var bottomRight by remember { mutableStateOf(false) }
    var filterIndex by remember { mutableStateOf(0) }

    // App settings
    var asyncOptimal by remember { mutableStateOf(true) }
    var accuracyThreshold by remember { mutableStateOf(10) }
    var keepCleanOriginal by remember { mutableStateOf(false) }

    // UI overlays & sheets
    var showFilterStrip by remember { mutableStateOf(false) }
    var showLocationSheet by remember { mutableStateOf(false) }
    var showWatermarkSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showGalleryScreen by remember { mutableStateOf(false) }
    var selectedGalleryPhoto by remember { mutableStateOf<PhotoEntry?>(null) }
    var editingPhoto by remember { mutableStateOf<PhotoEntry?>(null) }

    // Persisted captured photos
    var photos by remember { mutableStateOf<List<PhotoEntry>>(emptyList()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val loaded = WatermarkRenderer.loadSavedPhotos(context)
            withContext(Dispatchers.Main) {
                photos = loaded
                if (loaded.isNotEmpty() && selectedGalleryPhoto == null) {
                    selectedGalleryPhoto = loaded.first()
                }
            }
        }
    }

    // CameraX references
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var cameraRef by remember { mutableStateOf<Camera?>(null) }
    var imageCaptureRef by remember { mutableStateOf<ImageCapture?>(null) }

    // Request permissions on launch
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasCameraPermission = result[Manifest.permission.CAMERA] == true ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        locationManager.startHighFrequencyUpdates()
    }

    LaunchedEffect(Unit) {
        val perms = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    // System gallery photo importer (parses EXIF WGS84 and auto-converts to Amap GCJ02)
    val importPhotoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                Toast.makeText(context, "正在解析照片EXIF并纠偏对齐高德...", Toast.LENGTH_SHORT).show()
                val imported = withContext(Dispatchers.IO) {
                    WatermarkRenderer.importExternalPhotoUri(context, uri, loc, coordSystem)
                }
                if (imported != null) {
                    photos = listOf(imported) + photos
                    selectedGalleryPhoto = imported
                    Toast.makeText(context, "导入成功！已自动对齐高德GCJ-02坐标", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "导入照片失败，请重试", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Bind CameraX whenever camera permission, front/back lens, or PreviewView changes
    LaunchedEffect(hasCameraPermission, isFrontCamera, previewViewRef) {
        val pv = previewViewRef ?: return@LaunchedEffect
        if (!hasCameraPermission) return@LaunchedEffect
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val cameraProvider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(pv.surfaceProvider)
                }
                val imgCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val selector = if (isFrontCamera) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                cameraProvider.unbindAll()
                val cam = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, imgCapture)
                cameraRef = cam
                imageCaptureRef = imgCapture

                val zoomState = cam.cameraInfo.zoomState.value
                maxZoomRatio = (zoomState?.maxZoomRatio ?: 4.0f).coerceAtLeast(2.0f)
                zoomRatio = 1.0f

                val expState = cam.cameraInfo.exposureState
                val range = expState.exposureCompensationRange
                exposureRange = range.lower..range.upper
                exposureIndex = expState.exposureCompensationIndex
            } catch (e: Exception) {
                Toast.makeText(context, "相机初始化异常: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // Sync Flash / Torch state to CameraX
    LaunchedEffect(flashMode, cameraRef, imageCaptureRef) {
        val cam = cameraRef ?: return@LaunchedEffect
        val cap = imageCaptureRef ?: return@LaunchedEffect
        try {
            when (flashMode) {
                0 -> {
                    cam.cameraControl.enableTorch(false)
                    cap.flashMode = ImageCapture.FLASH_MODE_OFF
                }
                1 -> {
                    cam.cameraControl.enableTorch(false)
                    cap.flashMode = ImageCapture.FLASH_MODE_ON
                }
                2 -> {
                    cam.cameraControl.enableTorch(false)
                    cap.flashMode = ImageCapture.FLASH_MODE_AUTO
                }
                3 -> {
                    cam.cameraControl.enableTorch(true)
                    cap.flashMode = ImageCapture.FLASH_MODE_OFF
                }
            }
        } catch (_: Exception) {}
    }

    // Auto-hide focus box after 2.2 seconds
    LaunchedEffect(focusTriggerId) {
        if (focusPoint != null) {
            delay(2200L)
            focusPoint = null
        }
    }

    // Capture execution logic (0ms shutter freeze + async optimal GPS + Dual-Track EXIF save)
    fun performCapture() {
        scope.launch {
            if (countdownRemaining > 0) return@launch
            if (timerSeconds > 0) {
                for (sec in timerSeconds downTo 1) {
                    countdownRemaining = sec
                    delay(1000L)
                }
                countdownRemaining = 0
            }

            // If Flash ON is selected on rear camera, pulse torch briefly so previewView.bitmap is lit
            val needTorchPulse = (flashMode == 1 && !isFrontCamera)
            if (needTorchPulse) {
                try {
                    cameraRef?.cameraControl?.enableTorch(true)
                    delay(220L)
                } catch (_: Exception) {}
            }

            // 1. Instant 0ms shutter visual feedback + grab clean WYSIWYG frame
            shutterFlashVisible = true
            val rawFrame: Bitmap = previewViewRef?.bitmap ?: createFallbackBitmap()
            val (hm, dateWeek, fullTs) = WatermarkConfig.currentTimeStrings()

            if (needTorchPulse) {
                try { cameraRef?.cameraControl?.enableTorch(false) } catch (_: Exception) {}
            }
            launch {
                delay(140L)
                shutterFlashVisible = false
            }

            // 2. Async GPS accuracy convergence if accuracy > threshold
            val finalLoc = if (asyncOptimal && (!loc.hasRealFix || loc.accuracyMeters > accuracyThreshold)) {
                asyncSamplingBanner = "底图已定格 · 正在后台高频寻优高德坐标..."
                val best = locationManager.awaitOptimalLocation(accuracyThreshold.toFloat(), 950L)
                asyncSamplingBanner = null
                best
            } else {
                loc
            }

            val snapshotCfg = WatermarkConfig(
                styleTemplate = styleTemplate,
                coordSystem = coordSystem,
                useDms = useDms,
                gcjLat = finalLoc.gcjLat,
                gcjLon = finalLoc.gcjLon,
                wgsLat = finalLoc.wgsLat,
                wgsLon = finalLoc.wgsLon,
                origGcjLat = finalLoc.gcjLat,
                origGcjLon = finalLoc.gcjLon,
                accuracyMeters = finalLoc.accuracyMeters,
                shortPoi = finalLoc.shortPoi,
                address = finalLoc.fullAddress,
                timeHm = hm,
                dateWeek = dateWeek,
                timestamp = fullTs,
                note = customNote,
                showTime = showTime,
                showLocation = showLocation,
                showCoords = showCoords,
                showCoordTag = showCoordTag,
                showAccuracy = showAccuracy,
                showAddress = showAddress,
                showNote = showNote,
                bottomRight = bottomRight,
                filterIndex = filterIndex
            )

            // 3. Save clean base + composited watermark + system gallery export in background
            val savedEntry = withContext(Dispatchers.IO) {
                WatermarkRenderer.saveNewPhotoEntry(
                    context = context,
                    cleanBitmap = rawFrame,
                    cfg = snapshotCfg,
                    alsoExportOriginal = keepCleanOriginal
                )
            }
            photos = listOf(savedEntry) + photos
            selectedGalleryPhoto = savedEntry
            justCapturedPhoto = savedEntry
            launch {
                delay(4500L)
                if (justCapturedPhoto?.id == savedEntry.id) {
                    justCapturedPhoto = null
                }
            }
        }
    }

    // ==================== FULL-SCREEN GALLERY & AMAP JUMP VIEW ====================
    if (showGalleryScreen) {
        BackHandler { showGalleryScreen = false }
        GalleryAndAmapScreen(
            photos = photos,
            selectedPhoto = selectedGalleryPhoto ?: photos.firstOrNull(),
            onSelectPhoto = { selectedGalleryPhoto = it },
            onBackToCamera = { showGalleryScreen = false },
            onImportPhoto = { importPhotoLauncher.launch("image/*") },
            onEditWatermark = { editingPhoto = it },
            onDeletePhoto = { target ->
                target.cleanFile.delete()
                target.watermarkedFile.delete()
                target.metaFile.delete()
                val remaining = photos.filterNot { it.id == target.id }
                photos = remaining
                selectedGalleryPhoto = remaining.firstOrNull()
            }
        )
    } else {
        // ==================== MAIN CAMERA SCREEN (1:1 FIGURE 3 DESIGN) ====================
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. TOP BLACK TOOLBAR (设置 | 倒计时 | 闪光灯 | 翻转)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(84.dp)
                    .background(Color.Black)
                    .padding(horizontal = 22.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: 设置
                CameraTopBarItem(
                    label = "设置",
                    onClick = { showSettingsSheet = true }
                ) {
                    GearVectorIcon()
                }

                // Right: 倒计时 | 闪光灯 | 翻转
                Row(
                    horizontalArrangement = Arrangement.spacedBy(26.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CameraTopBarItem(
                        label = if (timerSeconds == 0) "倒计时" else "${timerSeconds}秒",
                        onClick = {
                            timerSeconds = when (timerSeconds) {
                                0 -> 3
                                3 -> 5
                                else -> 0
                            }
                        }
                    ) {
                        TimerVectorIcon(seconds = timerSeconds)
                    }

                    val flashSubLabel = when (flashMode) {
                        0 -> "关"
                        1 -> "开"
                        2 -> "自动"
                        else -> "常亮"
                    }
                    CameraTopBarItem(
                        label = "闪光灯",
                        onClick = { flashMode = (flashMode + 1) % 4 }
                    ) {
                        FlashVectorIcon(statusText = flashSubLabel, active = flashMode != 0)
                    }

                    CameraTopBarItem(
                        label = "翻转",
                        onClick = { isFrontCamera = !isFrontCamera }
                    ) {
                        FlipCameraVectorIcon()
                    }
                }
            }

            // 2. CENTER 3:4 LIVE VIEWFINDER AREA (Matching Figure 3 proportions)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF0A0E17))
            ) {
                if (hasCameraPermission) {
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                scaleType = PreviewView.ScaleType.FILL_CENTER
                                previewViewRef = this
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures { tapOffset ->
                                    focusPoint = tapOffset
                                    focusTriggerId++
                                    val pv = previewViewRef
                                    val cam = cameraRef
                                    if (pv != null && cam != null) {
                                        try {
                                            val pt = pv.meteringPointFactory.createPoint(tapOffset.x, tapOffset.y)
                                            val action = FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                                                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                                                .build()
                                            cam.cameraControl.startFocusAndMetering(action)
                                        } catch (_: Exception) {}
                                    }
                                }
                            }
                            .pointerInput(maxZoomRatio) {
                                detectTransformGestures { _, _, zoomChange, _ ->
                                    if (zoomChange != 1.0f) {
                                        val nextZoom = (zoomRatio * zoomChange).coerceIn(1.0f, maxZoomRatio)
                                        zoomRatio = nextZoom
                                        try {
                                            cameraRef?.cameraControl?.setZoomRatio(nextZoom)
                                        } catch (_: Exception) {}
                                    }
                                }
                            }
                    )
                } else {
                    // Permission prompt inside viewfinder
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("需要相机与定位权限以启用实景水印拍摄", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.CAMERA,
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        }) {
                            Text("立即授权开启相机")
                        }
                    }
                }

                // Live Filter Tint Overlay Preview when a filter is selected
                if (filterIndex != 0) {
                    val overlayColor = when (filterIndex) {
                        1 -> Color(0x1438BDF8)
                        2 -> Color(0x22F59E0B)
                        3 -> Color(0x2206B6D4)
                        4 -> Color(0x44000000)
                        else -> Color.Transparent
                    }
                    Box(modifier = Modifier.fillMaxSize().background(overlayColor))
                }

                // Top GPS Accuracy & Coordinate System Pill inside Viewfinder
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0x990F172A))
                        .clickable { showLocationSheet = true }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (loc.hasRealFix) Color(0xFF34D399) else Color(0xFFFBBF24))
                    )
                    Text(
                        text = if (loc.hasRealFix) "$coordSystem · ±${String.format(Locale.US, "%.1fm", loc.accuracyMeters)}" else "卫星定位锁定中...",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Quick 1-Tap Jump to Amap pill right after taking a photo
                justCapturedPhoto?.let { latest ->
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xEE2563EB))
                            .clickable {
                                AmapLauncher.jumpToAmapMarker(
                                    context = context,
                                    gcjLat = latest.config.gcjLat,
                                    gcjLon = latest.config.gcjLon,
                                    poiName = latest.config.shortPoi.ifBlank { latest.config.address }
                                )
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        RedPinVectorIcon(modifier = Modifier.size(14.dp))
                        Text("已拍好 · 一键跳高德 →", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Async optimal GPS sampling banner
                asyncSamplingBanner?.let { msg ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 44.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xE6F59E0B))
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(msg, color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Tap-to-Focus Square Box + Exposure Sun Icon (1:1 Figure 3 style)
                focusPoint?.let { pt ->
                    val density = LocalDensity.current
                    val boxSizeDp = 78.dp
                    val boxPx = with(density) { boxSizeDp.toPx() }
                    Box(
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    (pt.x - boxPx / 2f).roundToInt(),
                                    (pt.y - boxPx / 2f).roundToInt()
                                )
                            }
                            .pointerInput(exposureRange) {
                                detectVerticalDragGestures { _, dragAmount ->
                                    val step = if (dragAmount < -4f) 1 else if (dragAmount > 4f) -1 else 0
                                    if (step != 0) {
                                        val nextExp = (exposureIndex + step).coerceIn(exposureRange.first, exposureRange.last)
                                        exposureIndex = nextExp
                                        focusTriggerId++
                                        try {
                                            cameraRef?.cameraControl?.setExposureCompensationIndex(nextExp)
                                        } catch (_: Exception) {}
                                    }
                                }
                            }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FocusReticleBox(modifier = Modifier.size(boxSizeDp))
                            Spacer(modifier = Modifier.width(8.dp))
                            SunExposureIcon(modifier = Modifier.size(26.dp))
                        }
                    }
                }

                // Right-Edge Floating Circular Zoom Button ("1.0X" - 1:1 Figure 3 style)
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 14.dp, top = 48.dp)
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(Color(0x66000000))
                        .border(1.5.dp, Color.White, CircleShape)
                        .clickable {
                            val nextZoom = when {
                                zoomRatio < 1.4f -> 1.5f.coerceAtMost(maxZoomRatio)
                                zoomRatio < 1.9f -> 2.0f.coerceAtMost(maxZoomRatio)
                                zoomRatio < 2.9f && maxZoomRatio >= 3.0f -> 3.0f
                                else -> 1.0f
                            }
                            zoomRatio = nextZoom
                            try {
                                cameraRef?.cameraControl?.setZoomRatio(nextZoom)
                            } catch (_: Exception) {}
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = String.format(Locale.US, "%.1fX", zoomRatio),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Countdown big number overlay
                if (countdownRemaining > 0) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = countdownRemaining.toString(),
                            color = Color.White,
                            fontSize = 96.sp,
                            fontWeight = FontWeight.Bold,
                            style = TextStyle(shadow = Shadow(Color.Black, Offset(0f, 4f), 12f))
                        )
                    }
                }

                // LIVE WATERMARK OVERLAY ON VIEWFINDER (Click to customize)
                val (liveHm, liveDateWeek, liveFullTs) = timeTick
                val liveShownLat = if (coordSystem == "WGS-84") loc.wgsLat else loc.gcjLat
                val liveShownLon = if (coordSystem == "WGS-84") loc.wgsLon else loc.gcjLon
                val liveCoordStr = CoordinateConverter.formatCoordinate(liveShownLat, liveShownLon, useDms)
                val liveSysTag = if (showCoordTag) " [$coordSystem]" else ""
                val liveAccTag = if (showAccuracy) String.format(Locale.US, " ±%.1fm", loc.accuracyMeters) else ""

                if (styleTemplate == 0) {
                    // Style 0: Figure 3 Centered Big Time + Date/Weekday/📍POI + Lat/Lon Pill
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, Color(0x88000000))
                                )
                            )
                            .clickable { showWatermarkSheet = true }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (showTime) {
                            Text(
                                text = liveHm,
                                color = Color.White,
                                fontSize = 54.sp,
                                fontWeight = FontWeight.Light,
                                style = TextStyle(
                                    shadow = Shadow(Color(0xAA000000), Offset(0f, 3f), 8f)
                                )
                            )
                        }
                        if (showTime || showLocation) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                if (showTime) {
                                    Text(
                                        text = "$liveDateWeek ",
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        style = TextStyle(
                                            shadow = Shadow(Color(0xCC000000), Offset(0f, 2f), 6f)
                                        )
                                    )
                                }
                                if (showLocation) {
                                    RedPinVectorIcon(modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text(
                                        text = loc.shortPoi,
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = TextStyle(
                                            shadow = Shadow(Color(0xCC000000), Offset(0f, 2f), 6f)
                                        )
                                    )
                                }
                            }
                        }
                        if (showCoords) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0x880F172A))
                                    .padding(horizontal = 10.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "$liveCoordStr$liveSysTag$liveAccTag",
                                    color = Color(0xFF7DD3FC),
                                    fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        if (showAddress && loc.fullAddress.isNotBlank()) {
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = loc.fullAddress,
                                color = Color(0xFFE2E8F0),
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(shadow = Shadow(Color.Black, Offset(0f, 2f), 4f))
                            )
                        }
                        if (showNote && customNote.isNotBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = customNote,
                                color = Color(0xFFFDE68A),
                                fontSize = 11.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(shadow = Shadow(Color.Black, Offset(0f, 2f), 4f))
                            )
                        }
                    }
                } else {
                    // Style 1: Engineering Left/Right Card
                    Box(
                        modifier = Modifier
                            .align(if (bottomRight) Alignment.BottomEnd else Alignment.BottomStart)
                            .padding(14.dp)
                            .widthIn(max = 320.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xB80F172A))
                            .border(1.dp, Color(0x4494A3B8), RoundedCornerShape(14.dp))
                            .clickable { showWatermarkSheet = true }
                            .padding(12.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(modifier = Modifier.width(4.dp).height(14.dp).background(Color(0xFF38BDF8)))
                                Text("高德精准经纬水印 · $coordSystem", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            if (showCoords) {
                                Text(
                                    text = "经纬：$liveCoordStr$liveAccTag",
                                    color = Color(0xFF7DD3FC),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            if (showLocation) {
                                val addrPart = if (showAddress && loc.fullAddress.isNotBlank()) loc.fullAddress else loc.shortPoi
                                Text("地点：$addrPart", color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (showTime) {
                                Text("时间：$liveFullTs", color = Color(0xFFE2E8F0), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                            if (showNote && customNote.isNotBlank()) {
                                Text("备注：$customNote", color = Color(0xFFFDE68A), fontSize = 11.sp)
                            }
                        }
                    }
                }

                // Shutter White Flash Overlay
                androidx.compose.animation.AnimatedVisibility(
                    visible = shutterFlashVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.White))
                }
            }

            // Optional Live Filter Selector Bar (when 滤镜 is tapped)
            if (showFilterStrip) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF090D16))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    WatermarkRenderer.FILTER_NAMES.forEachIndexed { idx, name ->
                        val selected = (idx == filterIndex)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (selected) Color(0xFF0284C7) else Color(0xFF1E293B))
                                .clickable { filterIndex = idx }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(name, color = Color.White, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }

            // 3. BOTTOM BLACK CONTROL BAR (相册 | 地点 | 大圆环快门 | 滤镜 | 水印 — 1:1 Figure 3)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(128.dp)
                    .background(Color.Black)
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Item 1: 相册 (Circular Thumbnail + Label)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable {
                        if (photos.isNotEmpty() && selectedGalleryPhoto == null) {
                            selectedGalleryPhoto = photos.first()
                        }
                        showGalleryScreen = true
                    }
                ) {
                    val latestEntry = photos.firstOrNull()
                    val thumbBitmap = remember(latestEntry?.id, latestEntry?.watermarkedFile?.lastModified()) {
                        latestEntry?.watermarkedFile?.let { f ->
                            if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E293B))
                            .border(1.5.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (thumbBitmap != null) {
                            Image(
                                bitmap = thumbBitmap.asImageBitmap(),
                                contentDescription = "相册",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            GalleryPlaceholderIcon()
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("相册", color = Color.White, fontSize = 12.sp)
                }

                // Item 2: 地点 (Paper-plane navigation arrow + Label)
                CameraBottomBarItem(
                    label = "地点",
                    onClick = { showLocationSheet = true }
                ) {
                    PaperPlaneLocationIcon()
                }

                // Item 3: Center Big White Ring Shutter Button (Figure 3 exact ring style)
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .border(4.dp, Color.White, CircleShape)
                        .clickable { performCapture() }
                        .padding(6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(Color(0x22FFFFFF))
                    )
                }

                // Item 4: 滤镜 (Three interlocking rings + Label)
                CameraBottomBarItem(
                    label = "滤镜",
                    active = showFilterStrip || filterIndex != 0,
                    onClick = { showFilterStrip = !showFilterStrip }
                ) {
                    FilterRingsVectorIcon(active = filterIndex != 0)
                }

                // Item 5: 水印 (Watermark frame icon + Label)
                CameraBottomBarItem(
                    label = "水印",
                    onClick = { showWatermarkSheet = true }
                ) {
                    WatermarkStampVectorIcon()
                }
            }
        }
    }

    // ==================== DIALOG 1: 地点与高德零偏差校准面板 ====================
    if (showLocationSheet) {
        var poiInput by remember(loc.shortPoi) { mutableStateOf(loc.shortPoi) }
        var addrInput by remember(loc.fullAddress) { mutableStateOf(loc.fullAddress) }
        var manualLatInput by remember(loc.gcjLat) { mutableStateOf(String.format(Locale.US, "%.6f", loc.gcjLat)) }
        var manualLonInput by remember(loc.gcjLon) { mutableStateOf(String.format(Locale.US, "%.6f", loc.gcjLon)) }

        AlertDialog(
            onDismissRequest = { showLocationSheet = false },
            containerColor = Color(0xFF0F172A),
            title = {
                Text("📍 地点与高德坐标校准", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val offsetDist = CoordinateConverter.distanceMeters(loc.gcjLat, loc.gcjLon, loc.wgsLat, loc.wgsLon)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "高德 GCJ-02: ${String.format(Locale.US, "%.6f, %.6f", loc.gcjLat, loc.gcjLon)}",
                                color = Color(0xFF38BDF8),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Text(
                                "原始 WGS-84: ${String.format(Locale.US, "%.6f, %.6f", loc.wgsLat, loc.wgsLon)}",
                                color = Color(0xFF94A3B8),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                            Text(
                                "已自动消除加密偏移 ${String.format(Locale.US, "%.1f", offsetDist)} 米 · 当前精度 ±${String.format(Locale.US, "%.1f", loc.accuracyMeters)}m",
                                color = Color(0xFF34D399),
                                fontSize = 11.sp
                            )
                        }
                    }

                    OutlinedTextField(
                        value = poiInput,
                        onValueChange = { poiInput = it },
                        label = { Text("地点简称 (如图3：小庄村)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = addrInput,
                        onValueChange = { addrInput = it },
                        label = { Text("详细结构化地址") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = manualLatInput,
                            onValueChange = { manualLatInput = it },
                            label = { Text("高德纬度 GCJ-02") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = manualLonInput,
                            onValueChange = { manualLonInput = it },
                            label = { Text("高德经度 GCJ-02") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                locationManager.setCustomLocationText(null, null)
                                locationManager.startHighFrequencyUpdates()
                                Toast.makeText(context, "已重新请求硬件GPS定位", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("🛰 刷新GPS", fontSize = 12.sp)
                        }
                        Button(
                            onClick = {
                                val targetLat = manualLatInput.toDoubleOrNull() ?: loc.gcjLat
                                val targetLon = manualLonInput.toDoubleOrNull() ?: loc.gcjLon
                                AmapLauncher.jumpToAmapMarker(context, targetLat, targetLon, poiInput)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("📍 当前位置跳高德", fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    locationManager.setCustomLocationText(poiInput, addrInput)
                    val newLat = manualLatInput.toDoubleOrNull()
                    val newLon = manualLonInput.toDoubleOrNull()
                    if (newLat != null && newLon != null &&
                        (newLat != loc.gcjLat || newLon != loc.gcjLon)
                    ) {
                        locationManager.updateManualCoordinates(newLat, newLon)
                    }
                    showLocationSheet = false
                }) {
                    Text("应用修改")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLocationSheet = false }) { Text("关闭") }
            }
        )
    }

    // ==================== DIALOG 2: 水印样式与字段自定义面板 ====================
    if (showWatermarkSheet) {
        AlertDialog(
            onDismissRequest = { showWatermarkSheet = false },
            containerColor = Color(0xFF0F172A),
            title = {
                Text("🎨 水印模板与字段定制", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("水印排版样式", color = Color(0xFF94A3B8), fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        FilterChip(
                            selected = styleTemplate == 0,
                            onClick = { styleTemplate = 0 },
                            label = { Text("图3同款·大时间居中") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = styleTemplate == 1,
                            onClick = { styleTemplate = 1 },
                            label = { Text("工程经纬左卡片") },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Text("画面显示坐标系 (高德跳转自动对齐GCJ-02)", color = Color(0xFF94A3B8), fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        FilterChip(
                            selected = coordSystem == "GCJ-02",
                            onClick = { coordSystem = "GCJ-02" },
                            label = { Text("GCJ-02 高德坐标") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = coordSystem == "WGS-84",
                            onClick = { coordSystem = "WGS-84" },
                            label = { Text("WGS-84 原始GPS") },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        FilterChip(
                            selected = !useDms,
                            onClick = { useDms = false },
                            label = { Text("十进制 (6位小数)") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = useDms,
                            onClick = { useDms = true },
                            label = { Text("度分秒 (DMS)") },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    OutlinedTextField(
                        value = customNote,
                        onValueChange = { customNote = it },
                        label = { Text("自定义备注文字 (选填)") },
                        placeholder = { Text("例如：1号点位核验完成") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Divider(color = Color(0xFF1E293B))
                    Text("显示要素勾选开关", color = Color(0xFF94A3B8), fontSize = 12.sp)

                    WatermarkToggleRow("显示时间与日期星期", showTime) { showTime = it }
                    WatermarkToggleRow("显示地点名称 (📍POI)", showLocation) { showLocation = it }
                    WatermarkToggleRow("显示高精度经纬度数值", showCoords) { showCoords = it }
                    WatermarkToggleRow("显示坐标系标识 [GCJ-02/WGS-84]", showCoordTag) { showCoordTag = it }
                    WatermarkToggleRow("显示实时定位误差 (±m)", showAccuracy) { showAccuracy = it }
                    WatermarkToggleRow("显示完整省市区街道地址", showAddress) { showAddress = it }
                    WatermarkToggleRow("显示自定义备注", showNote) { showNote = it }
                }
            },
            confirmButton = {
                Button(onClick = { showWatermarkSheet = false }) { Text("完成") }
            }
        )
    }

    // ==================== DIALOG 3: 顶部设置面板 ====================
    if (showSettingsSheet) {
        AlertDialog(
            onDismissRequest = { showSettingsSheet = false },
            containerColor = Color(0xFF0F172A),
            title = {
                Text("⚙ 相机与定位核心设置", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    WatermarkToggleRow("默认采用高德 GCJ-02 火星坐标", coordSystem == "GCJ-02") {
                        coordSystem = if (it) "GCJ-02" else "WGS-84"
                    }
                    WatermarkToggleRow("秒拍0延迟 + 弱信号后台异步寻优", asyncOptimal) {
                        asyncOptimal = it
                    }
                    WatermarkToggleRow("同时保存无水印纯净原图到系统相册", keepCleanOriginal) {
                        keepCleanOriginal = it
                    }
                    if (styleTemplate == 1) {
                        WatermarkToggleRow("卡片水印靠右下角对齐", bottomRight) {
                            bottomRight = it
                        }
                    }
                    Text(
                        "提示：所有拍摄的照片均在App内部自动保留「纯净底图 + 水印元数据」，可随时在「相册」中无损修改水印内容并一键零偏差跳转高德地图。",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.5.sp
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showSettingsSheet = false }) { Text("确定") }
            }
        )
    }

    // ==================== DIALOG 4: 已拍照片图层分离无损改水印 (Q10 & Q11) ====================
    editingPhoto?.let { target ->
        var editTemplate by remember(target) { mutableStateOf(target.config.styleTemplate) }
        var editSys by remember(target) { mutableStateOf(target.config.coordSystem) }
        var editDms by remember(target) { mutableStateOf(target.config.useDms) }
        var editPoi by remember(target) { mutableStateOf(target.config.shortPoi) }
        var editAddr by remember(target) { mutableStateOf(target.config.address) }
        var editNote by remember(target) { mutableStateOf(target.config.note) }
        var editTimeHm by remember(target) { mutableStateOf(target.config.timeHm) }
        var editDateWeek by remember(target) { mutableStateOf(target.config.dateWeek) }
        var editLat by remember(target) { mutableStateOf(String.format(Locale.US, "%.6f", target.config.gcjLat)) }
        var editLon by remember(target) { mutableStateOf(String.format(Locale.US, "%.6f", target.config.gcjLon)) }
        var editShowCoords by remember(target) { mutableStateOf(target.config.showCoords) }
        var editShowAddr by remember(target) { mutableStateOf(target.config.showAddress) }
        var editShowNote by remember(target) { mutableStateOf(target.config.showNote) }

        AlertDialog(
            onDismissRequest = { editingPhoto = null },
            containerColor = Color(0xFF0F172A),
            title = {
                Text("✎ 无损修改照片水印 (0画质损失)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "基于底层保存的无水印原图重新合成，修改经纬度后「一键跳高德」将自动跳往新坐标。",
                        color = Color(0xFF34D399),
                        fontSize = 11.5.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = editTemplate == 0,
                            onClick = { editTemplate = 0 },
                            label = { Text("图3大时间居中") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = editTemplate == 1,
                            onClick = { editTemplate = 1 },
                            label = { Text("左下角卡片") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = editSys == "GCJ-02",
                            onClick = { editSys = "GCJ-02" },
                            label = { Text("GCJ-02 高德") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = editSys == "WGS-84",
                            onClick = { editSys = "WGS-84" },
                            label = { Text("WGS-84 原始") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = editLat,
                            onValueChange = { editLat = it },
                            label = { Text("高德纬度 GCJ-02") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = editLon,
                            onValueChange = { editLon = it },
                            label = { Text("高德经度 GCJ-02") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    TextButton(
                        onClick = {
                            editLat = String.format(Locale.US, "%.6f", target.config.origGcjLat)
                            editLon = String.format(Locale.US, "%.6f", target.config.origGcjLon)
                        }
                    ) {
                        Text("↺ 恢复拍摄瞬间原始坐标 (${target.config.origGcjLat}, ${target.config.origGcjLon})", color = Color(0xFF38BDF8), fontSize = 12.sp)
                    }

                    OutlinedTextField(
                        value = editPoi,
                        onValueChange = { editPoi = it },
                        label = { Text("地点名称 (📍后文字)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editAddr,
                        onValueChange = { editAddr = it },
                        label = { Text("完整地址描述") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = editTimeHm,
                            onValueChange = { editTimeHm = it },
                            label = { Text("大号时间 (HH:mm)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = editDateWeek,
                            onValueChange = { editDateWeek = it },
                            label = { Text("日期与星期") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = editNote,
                        onValueChange = { editNote = it },
                        label = { Text("现场备注内容") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    WatermarkToggleRow("显示经纬度坐标", editShowCoords) { editShowCoords = it }
                    WatermarkToggleRow("显示完整街道地址", editShowAddr) { editShowAddr = it }
                    WatermarkToggleRow("显示备注文字", editShowNote) { editShowNote = it }
                    WatermarkToggleRow("使用度分秒 (DMS) 格式", editDms) { editDms = it }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val newGcjLat = editLat.toDoubleOrNull() ?: target.config.origGcjLat
                    val newGcjLon = editLon.toDoubleOrNull() ?: target.config.origGcjLon
                    val newWgs = CoordinateConverter.gcj02ToWgs84(newGcjLat, newGcjLon)
                    val updatedCfg = target.config.copy(
                        styleTemplate = editTemplate,
                        coordSystem = editSys,
                        useDms = editDms,
                        gcjLat = newGcjLat,
                        gcjLon = newGcjLon,
                        wgsLat = newWgs.lat,
                        wgsLon = newWgs.lon,
                        shortPoi = editPoi,
                        address = editAddr,
                        timeHm = editTimeHm,
                        dateWeek = editDateWeek,
                        note = editNote,
                        showCoords = editShowCoords,
                        showAddress = editShowAddr,
                        showNote = editShowNote
                    )
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val cleanBmp = BitmapFactory.decodeFile(target.cleanFile.absolutePath)
                            if (cleanBmp != null) {
                                val recomposited = WatermarkRenderer.renderWatermarkedBitmap(cleanBmp, updatedCfg)
                                WatermarkRenderer.saveJpegWithDualTrackExif(recomposited, target.watermarkedFile, updatedCfg)
                                target.metaFile.writeText(updatedCfg.toJson())
                                WatermarkRenderer.exportToSystemGallery(context, target.watermarkedFile, updatedCfg)
                            }
                        }
                        target.config = updatedCfg
                        photos = WatermarkRenderer.loadSavedPhotos(context)
                        selectedGalleryPhoto = photos.find { it.id == target.id } ?: photos.firstOrNull()
                        editingPhoto = null
                        Toast.makeText(context, "水印已无损重绘并同步导出至系统相册！", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text("保存并无损重绘")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingPhoto = null }) { Text("取消") }
            }
        )
    }
}

// ============================================================================
// IN-APP GALLERY & ONE-CLICK ZERO-OFFSET AMAP JUMP SCREEN
// ============================================================================
@Composable
fun GalleryAndAmapScreen(
    photos: List<PhotoEntry>,
    selectedPhoto: PhotoEntry?,
    onSelectPhoto: (PhotoEntry) -> Unit,
    onBackToCamera: () -> Unit,
    onImportPhoto: () -> Unit,
    onEditWatermark: (PhotoEntry) -> Unit,
    onDeletePhoto: (PhotoEntry) -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090D16))
    ) {
        // Top Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBackToCamera) {
                Text("← 返回相机", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Text("已拍相册 (${photos.size})", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Button(
                onClick = onImportPhoto,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("📥 导入相册照片", fontSize = 12.sp)
            }
        }

        if (photos.isEmpty() || selectedPhoto == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("相册暂无照片", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "你可以返回相机实拍，或点击右上角「导入相册照片」自动读取系统照片EXIF定位并一键跳高德地图。",
                    color = Color(0xFF94A3B8),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(20.dp))
                Button(onClick = onBackToCamera) {
                    Text("返回极速拍摄")
                }
            }
            return
        }

        // Selected Photo Large Preview
        val mainBitmap = remember(selectedPhoto.id, selectedPhoto.watermarkedFile.lastModified()) {
            BitmapFactory.decodeFile(selectedPhoto.watermarkedFile.absolutePath)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black)
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            if (mainBitmap != null) {
                Image(
                    bitmap = mainBitmap.asImageBitmap(),
                    contentDescription = "已拍水印照片预览",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Coordinate & Amap Zero-Offset Action Panel
        val cfg = selectedPhoto.config
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0F172A))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "📍 ${cfg.shortPoi.ifBlank { "实拍点位" }}",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "高德 GCJ-02: ${String.format(Locale.US, "%.6f°N, %.6f°E", cfg.gcjLat, cfg.gcjLon)}",
                        color = Color(0xFF38BDF8),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.5.sp
                    )
                    Text(
                        text = "原始 WGS-84: ${String.format(Locale.US, "%.6f°N, %.6f°E", cfg.wgsLat, cfg.wgsLon)}",
                        color = Color(0xFF94A3B8),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }

                OutlinedButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val text = String.format(Locale.US, "%.6f,%.6f", cfg.gcjLon, cfg.gcjLat)
                        cm.setPrimaryClip(ClipData.newPlainText("GCJ-02", text))
                        Toast.makeText(context, "已复制高德坐标: $text", Toast.LENGTH_SHORT).show()
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text("复制坐标", fontSize = 11.5.sp)
                }
            }

            // Primary One-Click Amap Jump Button (Zero Offset!)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        AmapLauncher.jumpToAmapMarker(
                            context = context,
                            gcjLat = cfg.gcjLat,
                            gcjLon = cfg.gcjLon,
                            poiName = cfg.shortPoi.ifBlank { cfg.address }
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                    modifier = Modifier.weight(1.4f)
                ) {
                    Text("📍 一键跳高德地图 (丝毫不差)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }

                Button(
                    onClick = {
                        AmapLauncher.jumpToAmapNavigation(
                            context = context,
                            gcjLat = cfg.gcjLat,
                            gcjLon = cfg.gcjLon,
                            poiName = cfg.shortPoi.ifBlank { cfg.address }
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                    modifier = Modifier.weight(0.9f)
                ) {
                    Text("🧭 导航去这里", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }

            // Secondary Actions: Edit Watermark Non-Destructively & Delete
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { onEditWatermark(selectedPhoto) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("✎ 修改水印内容/样式 (无损重绘)", color = Color(0xFFFDE68A), fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = { onDeletePhoto(selectedPhoto) }
                ) {
                    Text("🗑 删除", color = Color(0xFFF87171), fontSize = 12.sp)
                }
            }
        }

        // Horizontal Thumbnail Strip
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(photos, key = { it.id }) { item ->
                val isSelected = item.id == selectedPhoto.id
                val thumb = remember(item.id, item.watermarkedFile.lastModified()) {
                    BitmapFactory.decodeFile(item.watermarkedFile.absolutePath)
                }
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(
                            width = if (isSelected) 2.5.dp else 1.dp,
                            color = if (isSelected) Color(0xFF38BDF8) else Color(0xFF334155),
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable { onSelectPhoto(item) }
                ) {
                    if (thumb != null) {
                        Image(
                            bitmap = thumb.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// CUSTOM VECTOR ICONS & REUSABLE UI COMPONENTS (1:1 FIGURE 3 MATCH)
// ============================================================================
@Composable
fun CameraTopBarItem(
    label: String,
    onClick: () -> Unit,
    iconContent: @Composable () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            iconContent()
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = label, color = Color.White, fontSize = 11.5.sp)
    }
}

@Composable
fun CameraBottomBarItem(
    label: String,
    active: Boolean = false,
    onClick: () -> Unit,
    iconContent: @Composable () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Box(modifier = Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            iconContent()
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            color = if (active) Color(0xFF38BDF8) else Color.White,
            fontSize = 12.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
fun WatermarkToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontSize = 13.sp)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun GearVectorIcon() {
    Canvas(modifier = Modifier.size(26.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val outerR = size.minDimension * 0.42f
        val innerR = size.minDimension * 0.20f
        drawCircle(color = Color.White, radius = outerR, center = Offset(cx, cy), style = Stroke(width = 2.2.dp.toPx()))
        drawCircle(color = Color.White, radius = innerR, center = Offset(cx, cy), style = Stroke(width = 2.dp.toPx()))
        for (i in 0 until 8) {
            val angle = Math.toRadians((i * 45).toDouble())
            val x1 = cx + (outerR - 1.dp.toPx()) * cos(angle).toFloat()
            val y1 = cy + (outerR - 1.dp.toPx()) * sin(angle).toFloat()
            val x2 = cx + (outerR + 3.2.dp.toPx()) * cos(angle).toFloat()
            val y2 = cy + (outerR + 3.2.dp.toPx()) * sin(angle).toFloat()
            drawLine(color = Color.White, start = Offset(x1, y1), end = Offset(x2, y2), strokeWidth = 2.6.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

@Composable
fun TimerVectorIcon(seconds: Int) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(28.dp)) {
        Canvas(modifier = Modifier.size(25.dp)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = size.minDimension * 0.42f
            drawArc(
                color = if (seconds > 0) Color(0xFF38BDF8) else Color.White,
                startAngle = -60f,
                sweepAngle = 300f,
                useCenter = false,
                style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
            )
            drawLine(
                color = if (seconds > 0) Color(0xFF38BDF8) else Color.White,
                start = Offset(cx, cy),
                end = Offset(cx + r * 0.55f, cy - r * 0.45f),
                strokeWidth = 2.2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
        if (seconds > 0) {
            Text(
                text = seconds.toString(),
                color = Color(0xFF38BDF8),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        }
    }
}

@Composable
fun FlashVectorIcon(statusText: String, active: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(22.dp)) {
            val w = size.width
            val h = size.height
            val path = Path().apply {
                moveTo(w * 0.58f, h * 0.08f)
                lineTo(w * 0.22f, h * 0.54f)
                lineTo(w * 0.50f, h * 0.54f)
                lineTo(w * 0.38f, h * 0.92f)
                lineTo(w * 0.78f, h * 0.44f)
                lineTo(w * 0.52f, h * 0.44f)
                close()
            }
            drawPath(
                path = path,
                color = if (active) Color(0xFFFACC15) else Color.White,
                style = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round)
            )
        }
        Text(
            text = statusText,
            color = if (active) Color(0xFFFACC15) else Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun FlipCameraVectorIcon() {
    Canvas(modifier = Modifier.size(25.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 2.1.dp.toPx()
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(w * 0.12f, h * 0.22f),
            size = Size(w * 0.76f, h * 0.58f),
            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
            style = Stroke(width = stroke)
        )
        // Top-right and bottom-left cycle arrowheads matching Figure 3
        drawLine(Color.White, Offset(w * 0.62f, h * 0.12f), Offset(w * 0.74f, h * 0.22f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(Color.White, Offset(w * 0.38f, h * 0.90f), Offset(w * 0.26f, h * 0.80f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawCircle(color = Color.White, radius = 2.2.dp.toPx(), center = Offset(w * 0.34f, h * 0.51f))
        drawCircle(color = Color.White, radius = 2.2.dp.toPx(), center = Offset(w * 0.66f, h * 0.51f))
    }
}

@Composable
fun FocusReticleBox(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = 1.8.dp.toPx()
        val tick = 8.dp.toPx()
        drawRect(color = Color.White, style = Stroke(width = stroke))
        // Top, bottom, left, right interior ticks matching Figure 3
        drawLine(Color.White, Offset(w / 2f, 0f), Offset(w / 2f, tick), strokeWidth = stroke)
        drawLine(Color.White, Offset(w / 2f, h - tick), Offset(w / 2f, h), strokeWidth = stroke)
        drawLine(Color.White, Offset(0f, h / 2f), Offset(tick, h / 2f), strokeWidth = stroke)
        drawLine(Color.White, Offset(w - tick, h / 2f), Offset(w, h / 2f), strokeWidth = stroke)
    }
}

@Composable
fun SunExposureIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = size.minDimension * 0.22f
        drawCircle(color = Color.White, radius = r, center = Offset(cx, cy), style = Stroke(width = 1.8.dp.toPx()))
        for (i in 0 until 8) {
            val angle = Math.toRadians((i * 45).toDouble())
            val x1 = cx + (r + 3.dp.toPx()) * cos(angle).toFloat()
            val y1 = cy + (r + 3.dp.toPx()) * sin(angle).toFloat()
            val x2 = cx + (r + 6.5.dp.toPx()) * cos(angle).toFloat()
            val y2 = cy + (r + 6.5.dp.toPx()) * sin(angle).toFloat()
            drawLine(Color.White, Offset(x1, y1), Offset(x2, y2), strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

@Composable
fun RedPinVectorIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val headY = size.height * 0.36f
        val headR = size.minDimension * 0.30f
        drawLine(
            color = Color(0xFFDC2626),
            start = Offset(cx, headY + headR * 0.6f),
            end = Offset(cx, size.height * 0.92f),
            strokeWidth = 2.2.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawCircle(color = Color(0xFFEF4444), radius = headR, center = Offset(cx, headY))
        drawCircle(color = Color(0xFFFECACA), radius = headR * 0.32f, center = Offset(cx - headR * 0.25f, headY - headR * 0.25f))
    }
}

@Composable
fun PaperPlaneLocationIcon() {
    Canvas(modifier = Modifier.size(28.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.88f, h * 0.14f)
            lineTo(w * 0.12f, h * 0.46f)
            lineTo(w * 0.46f, h * 0.56f)
            lineTo(w * 0.58f, h * 0.88f)
            close()
        }
        drawPath(path = path, color = Color.White, style = Stroke(width = 2.2.dp.toPx(), join = StrokeJoin.Round))
    }
}

@Composable
fun FilterRingsVectorIcon(active: Boolean) {
    val tint = if (active) Color(0xFF38BDF8) else Color.White
    Canvas(modifier = Modifier.size(28.dp)) {
        val w = size.width
        val h = size.height
        val r = size.minDimension * 0.26f
        val stroke = Stroke(width = 2.1.dp.toPx())
        drawCircle(color = tint, radius = r, center = Offset(w * 0.50f, h * 0.34f), style = stroke)
        drawCircle(color = tint, radius = r, center = Offset(w * 0.34f, h * 0.64f), style = stroke)
        drawCircle(color = tint, radius = r, center = Offset(w * 0.66f, h * 0.64f), style = stroke)
    }
}

@Composable
fun WatermarkStampVectorIcon() {
    Canvas(modifier = Modifier.size(28.dp)) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(w * 0.12f, h * 0.16f),
            size = Size(w * 0.76f, h * 0.68f),
            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
            style = Stroke(width = 2.2.dp.toPx())
        )
        drawLine(Color.White, Offset(w * 0.26f, h * 0.36f), Offset(w * 0.74f, h * 0.36f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(Color.White, radius = 1.6.dp.toPx(), center = Offset(w * 0.32f, h * 0.60f))
        drawCircle(Color.White, radius = 1.6.dp.toPx(), center = Offset(w * 0.50f, h * 0.60f))
        drawCircle(Color.White, radius = 1.6.dp.toPx(), center = Offset(w * 0.68f, h * 0.60f))
    }
}

@Composable
fun GalleryPlaceholderIcon() {
    Canvas(modifier = Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = Color(0xFF94A3B8),
            topLeft = Offset(w * 0.1f, h * 0.15f),
            size = Size(w * 0.8f, h * 0.7f),
            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
            style = Stroke(width = 1.8.dp.toPx())
        )
    }
}

private fun createFallbackBitmap(): Bitmap {
    val bmp = Bitmap.createBitmap(1080, 1440, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val p = android.graphics.Paint().apply {
        shader = android.graphics.LinearGradient(
            0f, 0f, 0f, 1440f,
            android.graphics.Color.rgb(15, 23, 42),
            android.graphics.Color.rgb(30, 58, 95),
            android.graphics.Shader.TileMode.CLAMP
        )
    }
    c.drawRect(0f, 0f, 1080f, 1440f, p)
    return bmp
}
