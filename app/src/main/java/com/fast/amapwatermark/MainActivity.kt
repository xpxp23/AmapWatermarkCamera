package com.fast.amapwatermark

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.OrientationEventListener
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
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
import kotlin.math.abs
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
    val prefs = remember { context.getSharedPreferences("amap_wm_prefs_v4", Context.MODE_PRIVATE) }

    // Live reactive GPS state
    val loc by locationManager.locationState

    // Gravity sensor orientation listener with 15-degree hysteresis deadband (prevents jitter at diagonal angles!)
    // 0 = Portrait, 90 = Clockwise Right-Landscape (left side at top), 180 = UpsideDown, 270 = Counter-Clockwise Left-Landscape (right side at top)
    var deviceOrientation by remember { mutableStateOf(0) }
    var continuousUiRotation by remember { mutableStateOf(0f) }

    DisposableEffect(context) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                // Hysteresis deadband of 15 degrees around 45, 135, 225, 315
                val current = deviceOrientation
                val nextOri = when (current) {
                    0 -> when {
                        orientation in 60..120 -> 90
                        orientation in 150..210 -> 180
                        orientation in 240..300 -> 270
                        else -> 0
                    }
                    90 -> when {
                        orientation >= 330 || orientation <= 30 -> 0
                        orientation in 150..210 -> 180
                        orientation in 240..300 -> 270
                        else -> 90
                    }
                    180 -> when {
                        orientation >= 330 || orientation <= 30 -> 0
                        orientation in 60..120 -> 90
                        orientation in 240..300 -> 270
                        else -> 180
                    }
                    else -> when {
                        orientation >= 330 || orientation <= 30 -> 0
                        orientation in 60..120 -> 90
                        orientation in 150..210 -> 180
                        else -> 270
                    }
                }
                if (nextOri != current) {
                    deviceOrientation = nextOri
                    // Compute shortest-arc target angle for UI icons:
                    // When deviceOrientation == 270 (phone rotated CCW), icons on portrait screen must rotate +90f CW to stand upright!
                    // When deviceOrientation == 90 (phone rotated CW), icons on portrait screen must rotate -90f CCW to stand upright!
                    val desiredMod = when (nextOri) {
                        90 -> -90f
                        270 -> 90f
                        180 -> 180f
                        else -> 0f
                    }
                    var delta = (desiredMod - continuousUiRotation) % 360f
                    if (delta > 180f) delta -= 360f
                    if (delta < -180f) delta += 360f
                    continuousUiRotation += delta
                }
            }
        }
        if (listener.canDetectOrientation()) {
            listener.enable()
        }
        onDispose {
            listener.disable()
        }
    }

    val uiIconRotation by animateFloatAsState(
        targetValue = continuousUiRotation,
        animationSpec = tween(durationMillis = 280),
        label = "uiIconRotation"
    )
    val isLandscapeHold = (deviceOrientation == 90 || deviceOrientation == 270)

    // Permission states
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    // Camera hardware states
    var isFrontCamera by remember { mutableStateOf(false) }
    var flashMode by remember { mutableStateOf(0) } // 0: 关, 1: 开, 2: 自动, 3: 常亮
    var timerSeconds by remember { mutableStateOf(0) } // 0: 关, 3: 3s, 5: 5s, 10: 10s
    var activeTopInlineMenu by remember { mutableStateOf(0) } // 0: None, 1: Timer inline menu, 2: Flash inline menu
    var countdownRemaining by remember { mutableStateOf(0) }
    var zoomRatio by remember { mutableStateOf(1.0f) }
    var maxZoomRatio by remember { mutableStateOf(4.0f) }
    var shutterFlashVisible by remember { mutableStateOf(false) }
    var asyncSamplingBanner by remember { mutableStateOf<String?>(null) }

    // Tap-to-focus & exposure compensation states
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusTriggerId by remember { mutableStateOf(0) }
    var exposureIndex by remember { mutableStateOf(0) }
    var exposureRange by remember { mutableStateOf(-4..4) }

    // Persisted Watermark & Filter configuration
    var styleTemplate by remember { mutableStateOf(prefs.getInt("styleTemplate", 0)) }
    var customTitle by remember { mutableStateOf(prefs.getString("customTitle", "现场点位核验") ?: "现场点位核验") }
    var coordSystem by remember { mutableStateOf(prefs.getString("coordSystem", "GCJ-02") ?: "GCJ-02") }
    var useDms by remember { mutableStateOf(prefs.getBoolean("useDms", false)) }
    var customNote by remember { mutableStateOf(prefs.getString("customNote", "") ?: "") }
    var fontScale by remember { mutableStateOf(prefs.getFloat("fontScale", 1.0f)) }
    var accentColorIndex by remember { mutableStateOf(prefs.getInt("accentColorIndex", 0)) }
    var bgAlpha by remember { mutableStateOf(prefs.getInt("bgAlpha", 185)) }

    var showTime by remember { mutableStateOf(prefs.getBoolean("showTime", true)) }
    var showLocation by remember { mutableStateOf(prefs.getBoolean("showLocation", true)) }
    var showCoords by remember { mutableStateOf(prefs.getBoolean("showCoords", true)) }
    var showCoordTag by remember { mutableStateOf(prefs.getBoolean("showCoordTag", true)) }
    var showAccuracy by remember { mutableStateOf(prefs.getBoolean("showAccuracy", true)) }
    var showAddress by remember { mutableStateOf(prefs.getBoolean("showAddress", false)) }
    var showNote by remember { mutableStateOf(prefs.getBoolean("showNote", true)) }
    var bottomRight by remember { mutableStateOf(prefs.getBoolean("bottomRight", false)) }
    var filterIndex by remember { mutableStateOf(0) }

    // App settings
    var asyncOptimal by remember { mutableStateOf(prefs.getBoolean("asyncOptimal", true)) }
    var accuracyThreshold by remember { mutableStateOf(10) }
    var keepCleanOriginal by remember { mutableStateOf(prefs.getBoolean("keepCleanOriginal", false)) }

    LaunchedEffect(
        styleTemplate, customTitle, coordSystem, useDms, customNote,
        fontScale, accentColorIndex, bgAlpha, showTime, showLocation,
        showCoords, showCoordTag, showAccuracy, showAddress, showNote,
        bottomRight, asyncOptimal, keepCleanOriginal
    ) {
        prefs.edit()
            .putInt("styleTemplate", styleTemplate)
            .putString("customTitle", customTitle)
            .putString("coordSystem", coordSystem)
            .putBoolean("useDms", useDms)
            .putString("customNote", customNote)
            .putFloat("fontScale", fontScale)
            .putInt("accentColorIndex", accentColorIndex)
            .putInt("bgAlpha", bgAlpha)
            .putBoolean("showTime", showTime)
            .putBoolean("showLocation", showLocation)
            .putBoolean("showCoords", showCoords)
            .putBoolean("showCoordTag", showCoordTag)
            .putBoolean("showAccuracy", showAccuracy)
            .putBoolean("showAddress", showAddress)
            .putBoolean("showNote", showNote)
            .putBoolean("bottomRight", bottomRight)
            .putBoolean("asyncOptimal", asyncOptimal)
            .putBoolean("keepCleanOriginal", keepCleanOriginal)
            .apply()
    }

    // UI overlays & sheets
    var showFilterStrip by remember { mutableStateOf(false) }
    var showLocationSheet by remember { mutableStateOf(false) }
    var showWatermarkSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showGalleryScreen by remember { mutableStateOf(false) }
    var selectedGalleryPhoto by remember { mutableStateOf<PhotoEntry?>(null) }
    var editingPhoto by remember { mutableStateOf<PhotoEntry?>(null) }
    var justCapturedPhoto by remember { mutableStateOf<PhotoEntry?>(null) }

    // Persisted captured photos
    var photos by remember { mutableStateOf<List<PhotoEntry>>(emptyList()) }
    var latestThumbBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val loaded = WatermarkRenderer.loadSavedPhotos(context)
            val firstThumb = loaded.firstOrNull()?.watermarkedFile?.let {
                BitmapCacheManager.loadThumbnail(it, 160, 160)
            }
            withContext(Dispatchers.Main) {
                photos = loaded
                latestThumbBitmap = firstThumb
                if (loaded.isNotEmpty() && selectedGalleryPhoto == null) {
                    selectedGalleryPhoto = loaded.first()
                }
            }
        }
    }

    LaunchedEffect(photos.firstOrNull()?.id, photos.firstOrNull()?.watermarkedFile?.lastModified()) {
        val firstFile = photos.firstOrNull()?.watermarkedFile
        if (firstFile != null) {
            val bmp = withContext(Dispatchers.IO) {
                BitmapCacheManager.loadThumbnail(firstFile, 160, 160)
            }
            latestThumbBitmap = bmp
        } else {
            latestThumbBitmap = null
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            perms.add(Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    // System gallery photo importer
    val importPhotoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                Toast.makeText(context, "正在提取照片EXIF地理位置并对齐高德...", Toast.LENGTH_SHORT).show()
                val imported = withContext(Dispatchers.IO) {
                    WatermarkRenderer.importExternalPhotoUri(context, uri, loc, coordSystem)
                }
                if (imported != null) {
                    photos = listOf(imported) + photos
                    selectedGalleryPhoto = imported
                    Toast.makeText(context, "已生成经纬水印图并对齐高德GCJ-02坐标！", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "无法读取该图片，请重试", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Bind CameraX
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
                Toast.makeText(context, "相机启动异常: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // Sync Flash / Torch state
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

    // Auto-hide focus box
    LaunchedEffect(focusTriggerId) {
        if (focusPoint != null) {
            delay(2200L)
            focusPoint = null
        }
    }

    // Capture execution logic (with exact optical rotation for 0°, 90°, 180°, 270°!)
    fun performCapture() {
        scope.launch {
            activeTopInlineMenu = 0
            if (countdownRemaining > 0) return@launch
            if (timerSeconds > 0) {
                for (sec in timerSeconds downTo 1) {
                    countdownRemaining = sec
                    delay(1000L)
                }
                countdownRemaining = 0
            }

            val needRearTorchPulse = (flashMode == 1 && !isFrontCamera)
            val needFrontScreenFlash = (flashMode == 1 && isFrontCamera)

            if (needRearTorchPulse) {
                try {
                    cameraRef?.cameraControl?.enableTorch(true)
                    delay(200L)
                } catch (_: Exception) {}
            } else if (needFrontScreenFlash) {
                shutterFlashVisible = true
                delay(180L)
            }

            shutterFlashVisible = true
            val rawViewfinderBmp: Bitmap = previewViewRef?.bitmap ?: createFallbackBitmap()
            val captureOrientation = deviceOrientation
            val (hm, dateWeek, fullTs) = WatermarkConfig.currentTimeStrings()

            if (needRearTorchPulse) {
                try { cameraRef?.cameraControl?.enableTorch(false) } catch (_: Exception) {}
            }
            launch {
                delay(140L)
                shutterFlashVisible = false
            }

            val finalLoc = if (asyncOptimal && (!loc.hasRealFix || loc.accuracyMeters > accuracyThreshold)) {
                asyncSamplingBanner = "正在锁定高精高德坐标..."
                val best = locationManager.awaitOptimalLocation(accuracyThreshold.toFloat(), 450L)
                asyncSamplingBanner = null
                best
            } else {
                loc
            }

            val snapshotCfg = WatermarkConfig(
                styleTemplate = styleTemplate,
                customTitle = customTitle,
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
                filterIndex = filterIndex,
                bgAlpha = bgAlpha,
                fontScale = fontScale,
                accentColorIndex = accentColorIndex
            )

            // CRITICAL OPTICAL ROTATION FIX:
            // - When deviceOrientation == 270 (phone rotated CCW, top points left), the real-world Sky is on the RIGHT edge (+X) of rawViewfinderBmp.
            //   To move the Right edge (+X) to the Top (-Y) of the output photo, we MUST rotate by -90 degrees (CCW)!
            // - When deviceOrientation == 90 (phone rotated CW, top points right), the real-world Sky is on the LEFT edge (-X) of rawViewfinderBmp.
            //   To move the Left edge (-X) to the Top (-Y) of the output photo, we MUST rotate by +90 degrees (CW)!
            // - When deviceOrientation == 180 (upside down), rotate by 180 degrees!
            val savedEntry = withContext(Dispatchers.IO) {
                val bitmapRotDeg = when (captureOrientation) {
                    270 -> -90
                    90 -> 90
                    180 -> 180
                    else -> 0
                }
                val uprightCleanBmp = WatermarkRenderer.rotateBitmapIfNeeded(rawViewfinderBmp, bitmapRotDeg)
                WatermarkRenderer.saveNewPhotoEntry(
                    context = context,
                    cleanBitmap = uprightCleanBmp,
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

    // ==================== FULL-SCREEN GALLERY & SYSTEM CAMERA LOCATION SCANNER ====================
    if (showGalleryScreen) {
        BackHandler { showGalleryScreen = false }
        GalleryAndSystemPhotosScreen(
            appPhotos = photos,
            selectedPhoto = selectedGalleryPhoto ?: photos.firstOrNull(),
            onSelectPhoto = { selectedGalleryPhoto = it },
            onBackToCamera = { showGalleryScreen = false },
            onImportArbitraryPhoto = { importPhotoLauncher.launch("image/*") },
            onImportSystemPhotoUri = { sysPhoto ->
                scope.launch {
                    Toast.makeText(context, "正在为原相机照片生成经纬水印...", Toast.LENGTH_SHORT).show()
                    val imported = withContext(Dispatchers.IO) {
                        WatermarkRenderer.importExternalPhotoUri(context, sysPhoto.contentUri, loc, coordSystem)
                    }
                    if (imported != null) {
                        photos = listOf(imported) + photos
                        selectedGalleryPhoto = imported
                        Toast.makeText(context, "已生成带水印照片并存入DCIM/Camera！", Toast.LENGTH_SHORT).show()
                    }
                }
            },
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
        // ==================== MAIN CAMERA SCREEN ====================
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. TOP BLACK TOOLBAR WITH SYMMETRIC CIRCULAR INLINE MENUS (Zero clipping in Landscape!)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(84.dp)
                    .background(Color.Black)
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center
            ) {
                when (activeTopInlineMenu) {
                    1 -> {
                        // Symmetric Circular Inline Menu for Countdown Timer
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(28.dp))
                                .background(Color(0xFF141D2E))
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(40.dp).rotate(uiIconRotation),
                                contentAlignment = Alignment.Center
                            ) {
                                TimerVectorIcon(seconds = timerSeconds)
                            }
                            val timerOptions = listOf(0 to "关", 3 to "3s", 5 to "5s", 10 to "10s")
                            timerOptions.forEach { (sec, shortLbl) ->
                                val isSel = (timerSeconds == sec)
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .rotate(uiIconRotation)
                                        .clip(CircleShape)
                                        .background(if (isSel) Color(0xFF0284C7) else Color(0xFF1E293B))
                                        .clickable {
                                            timerSeconds = sec
                                            activeTopInlineMenu = 0
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = shortLbl,
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    2 -> {
                        // Symmetric Circular Inline Menu for Flash
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(28.dp))
                                .background(Color(0xFF141D2E))
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(40.dp).rotate(uiIconRotation),
                                contentAlignment = Alignment.Center
                            ) {
                                FlashVectorIcon(statusText = "", active = flashMode != 0)
                            }
                            val flashOptions = listOf(0 to "关", 1 to "开", 2 to "自动", 3 to "常亮")
                            flashOptions.forEach { (mode, shortLbl) ->
                                val isSel = (flashMode == mode)
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .rotate(uiIconRotation)
                                        .clip(CircleShape)
                                        .background(if (isSel) Color(0xFFF59E0B) else Color(0xFF1E293B))
                                        .clickable {
                                            flashMode = mode
                                            activeTopInlineMenu = 0
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = shortLbl,
                                        color = if (isSel) Color.Black else Color.White,
                                        fontSize = 11.5.sp,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    else -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CameraTopBarItem(
                                label = "设置",
                                rotationDegrees = uiIconRotation,
                                onClick = { showSettingsSheet = true }
                            ) {
                                GearVectorIcon()
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(26.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CameraTopBarItem(
                                    label = if (timerSeconds == 0) "倒计时" else "${timerSeconds}秒",
                                    rotationDegrees = uiIconRotation,
                                    onClick = { activeTopInlineMenu = 1 }
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
                                    rotationDegrees = uiIconRotation,
                                    onClick = { activeTopInlineMenu = 2 }
                                ) {
                                    FlashVectorIcon(statusText = flashSubLabel, active = flashMode != 0)
                                }

                                CameraTopBarItem(
                                    label = "翻转",
                                    rotationDegrees = uiIconRotation,
                                    onClick = { isFrontCamera = !isFrontCamera }
                                ) {
                                    FlipCameraVectorIcon()
                                }
                            }
                        }
                    }
                }
            }

            // 2. CENTER LIVE VIEWFINDER AREA
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
                            .pointerInput(deviceOrientation) {
                                detectTapGestures { tapOffset ->
                                    activeTopInlineMenu = 0
                                    val w = size.width.toFloat()
                                    val h = size.height.toFloat()

                                    // Check if user tapped on the live watermark region in the current orientation:
                                    val tappedWatermarkZone = when (deviceOrientation) {
                                        270 -> tapOffset.x < w * 0.28f // Physical bottom is Left edge
                                        90 -> tapOffset.x > w * 0.72f  // Physical bottom is Right edge
                                        180 -> tapOffset.y < h * 0.25f // Physical bottom is Top edge
                                        else -> tapOffset.y > h * 0.75f // Physical bottom is Bottom edge
                                    }
                                    if (tappedWatermarkZone) {
                                        showWatermarkSheet = true
                                        return@detectTapGestures
                                    }

                                    // Otherwise trigger CameraX Tap-to-Focus & Exposure
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

                // Live Filter Tint Overlay
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

                // 100% WYSIWYG LIVE WATERMARK OVERLAY (Uses the EXACT same Canvas renderer & optical matrix as the captured photo!)
                WysiwygLiveWatermarkCanvas(
                    deviceOrientation = deviceOrientation,
                    styleTemplate = styleTemplate,
                    customTitle = customTitle,
                    coordSystem = coordSystem,
                    useDms = useDms,
                    loc = loc,
                    customNote = customNote,
                    showTime = showTime,
                    showLocation = showLocation,
                    showCoords = showCoords,
                    showCoordTag = showCoordTag,
                    showAccuracy = showAccuracy,
                    showAddress = showAddress,
                    showNote = showNote,
                    bottomRight = bottomRight,
                    bgAlpha = bgAlpha,
                    fontScale = fontScale,
                    accentColorIndex = accentColorIndex
                )

                // Compact GPS & Orientation Pill (docked cleanly at top-center in portrait, or top-right in landscape)
                Row(
                    modifier = Modifier
                        .align(if (deviceOrientation == 270) Alignment.TopEnd else Alignment.TopStart)
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
                    val modeTag = when (deviceOrientation) {
                        270 -> "左横屏"
                        90 -> "右横屏"
                        180 -> "倒置竖屏"
                        else -> "竖屏"
                    }
                    Text(
                        text = if (loc.hasRealFix) "$coordSystem · ±${String.format(Locale.US, "%.1fm", loc.accuracyMeters)} · $modeTag" else "GPS锁定中 · $modeTag",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Quick 1-Tap Jump to Amap pill right after taking a photo
                justCapturedPhoto?.let { latest ->
                    Row(
                        modifier = Modifier
                            .align(if (deviceOrientation == 270) Alignment.BottomEnd else Alignment.TopEnd)
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
                        Text("已存DCIM · 一键跳高德 →", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Async optimal GPS sampling banner
                asyncSamplingBanner?.let { msg ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .rotate(uiIconRotation)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xE6F59E0B))
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(msg, color = Color.Black, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Tap-to-Focus Square Box + Exposure Sun Icon (rotates symmetrically with orientation!)
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
                            .rotate(uiIconRotation)
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

                // Floating Circular Zoom Button ("1.0X" - moves to top-right when in 90° Right-Landscape so it never overlaps the right-edge watermark!)
                Box(
                    modifier = Modifier
                        .align(if (deviceOrientation == 90) Alignment.CenterStart else Alignment.CenterEnd)
                        .padding(horizontal = 14.dp)
                        .size(46.dp)
                        .rotate(uiIconRotation)
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
                            modifier = Modifier.rotate(uiIconRotation),
                            style = TextStyle(shadow = Shadow(Color.Black, Offset(0f, 4f), 12f))
                        )
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

            // Optional Live Filter Selector Bar (Circular buttons so rotating in landscape never clips!)
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
                                .size(44.dp)
                                .rotate(uiIconRotation)
                                .clip(CircleShape)
                                .background(if (selected) Color(0xFF0284C7) else Color(0xFF1E293B))
                                .clickable { filterIndex = idx },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(name, color = Color.White, fontSize = 11.5.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }

            // 3. BOTTOM BLACK CONTROL BAR (相册 | 地点 | 大圆环快门 | 滤镜 | 水印)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(128.dp)
                    .background(Color.Black)
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .rotate(uiIconRotation)
                        .clickable {
                            if (photos.isNotEmpty() && selectedGalleryPhoto == null) {
                                selectedGalleryPhoto = photos.first()
                            }
                            showGalleryScreen = true
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E293B))
                            .border(1.5.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        val bmp = latestThumbBitmap
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
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

                CameraBottomBarItem(
                    label = "地点",
                    rotationDegrees = uiIconRotation,
                    onClick = { showLocationSheet = true }
                ) {
                    PaperPlaneLocationIcon()
                }

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

                CameraBottomBarItem(
                    label = "滤镜",
                    active = showFilterStrip || filterIndex != 0,
                    rotationDegrees = uiIconRotation,
                    onClick = { showFilterStrip = !showFilterStrip }
                ) {
                    FilterRingsVectorIcon(active = filterIndex != 0)
                }

                CameraBottomBarItem(
                    label = "水印",
                    rotationDegrees = uiIconRotation,
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
                        label = { Text("地点简称 (例如：小庄村 / 1号施工点)") },
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

    // ==================== DIALOG 2: 5套专业水印样式与深度自定义面板 ====================
    if (showWatermarkSheet) {
        AlertDialog(
            onDismissRequest = { showWatermarkSheet = false },
            containerColor = Color(0xFF0F172A),
            title = {
                Text("🎨 水印样式库与深度自定义", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("选择水印样式模板 (预览与实拍 100% 同步)", color = Color(0xFF94A3B8), fontSize = 12.sp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        WatermarkRenderer.STYLE_NAMES.forEachIndexed { idx, title ->
                            FilterChip(
                                selected = styleTemplate == idx,
                                onClick = { styleTemplate = idx },
                                label = { Text(title, fontSize = 12.sp) }
                            )
                        }
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

                    Text("主题点缀色与字号大小", color = Color(0xFF94A3B8), fontSize = 12.sp)
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
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(c)
                                        .border(
                                            width = if (accentColorIndex == idx) 2.5.dp else 0.dp,
                                            color = Color.White,
                                            shape = CircleShape
                                        )
                                        .clickable { accentColorIndex = idx }
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(0.85f to "小", 1.0f to "标准", 1.15f to "大").forEach { (sc, lbl) ->
                                FilterChip(
                                    selected = abs(fontScale - sc) < 0.05f,
                                    onClick = { fontScale = sc },
                                    label = { Text(lbl, fontSize = 11.sp) }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = customTitle,
                        onValueChange = { customTitle = it },
                        label = { Text("水印卡片自定义标题") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = customNote,
                        onValueChange = { customNote = it },
                        label = { Text("自定义现场备注 (选填)") },
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
                    if (styleTemplate in listOf(1, 2, 4)) {
                        WatermarkToggleRow("卡片靠右下角对齐", bottomRight) { bottomRight = it }
                    }
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
                    if (styleTemplate in listOf(1, 2, 4)) {
                        WatermarkToggleRow("卡片水印靠右下角对齐", bottomRight) {
                            bottomRight = it
                        }
                    }
                    Text(
                        "存储路径：与系统原相机完全一致（DCIM/Camera/IMG_yyyyMMdd_HHmmss.jpg）。\n四向重力自适应：支持竖屏、左横屏、右横屏、倒立竖屏四向光学对齐，预览与实拍 100% 一致。",
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

    // ==================== DIALOG 4: 已拍照片图层分离无损改水印 ====================
    editingPhoto?.let { target ->
        var editTemplate by remember(target) { mutableStateOf(target.config.styleTemplate) }
        var editTitle by remember(target) { mutableStateOf(target.config.customTitle) }
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
        var editBottomRight by remember(target) { mutableStateOf(target.config.bottomRight) }

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
                        "基于底层保存的无水印原图重新渲染，并自动覆盖更新 DCIM/Camera 中的系统相册照片。",
                        color = Color(0xFF34D399),
                        fontSize = 11.5.sp
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
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
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        label = { Text("卡片标题") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
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
                    if (editTemplate in listOf(1, 2, 4)) {
                        WatermarkToggleRow("靠右下角对齐", editBottomRight) { editBottomRight = it }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val newGcjLat = editLat.toDoubleOrNull() ?: target.config.origGcjLat
                    val newGcjLon = editLon.toDoubleOrNull() ?: target.config.origGcjLon
                    val newWgs = CoordinateConverter.gcj02ToWgs84(newGcjLat, newGcjLon)
                    val updatedCfg = target.config.copy(
                        styleTemplate = editTemplate,
                        customTitle = editTitle,
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
                        showNote = editShowNote,
                        bottomRight = editBottomRight
                    )
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val cleanBmp = BitmapCacheManager.loadDisplayBitmap(target.cleanFile, 2400)
                            if (cleanBmp != null) {
                                val recomposited = WatermarkRenderer.renderWatermarkedBitmap(cleanBmp, updatedCfg)
                                WatermarkRenderer.saveJpegWithDualTrackExif(recomposited, target.watermarkedFile, updatedCfg)
                                val newUri = WatermarkRenderer.exportOrUpdateSystemCameraGallery(context, target.watermarkedFile, updatedCfg)
                                updatedCfg.mediaStoreUri = newUri
                                target.metaFile.writeText(updatedCfg.toJson())
                                BitmapCacheManager.invalidate(target.watermarkedFile)
                            }
                        }
                        target.config = updatedCfg
                        photos = WatermarkRenderer.loadSavedPhotos(context)
                        selectedGalleryPhoto = photos.find { it.id == target.id } ?: photos.firstOrNull()
                        editingPhoto = null
                        Toast.makeText(context, "水印已无损重绘并同步更新至系统相册 DCIM/Camera！", Toast.LENGTH_SHORT).show()
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
// 100% WYSIWYG LIVE WATERMARK OVERLAY CANVAS
// Calls WatermarkRenderer.drawWatermarkOverlay() with the exact inverse optical
// matrix of performCapture(), guaranteeing 0-pixel discrepancy in all 4 orientations!
// ============================================================================
@Composable
fun WysiwygLiveWatermarkCanvas(
    deviceOrientation: Int,
    styleTemplate: Int,
    customTitle: String,
    coordSystem: String,
    useDms: Boolean,
    loc: LocationSnapshot,
    customNote: String,
    showTime: Boolean,
    showLocation: Boolean,
    showCoords: Boolean,
    showCoordTag: Boolean,
    showAccuracy: Boolean,
    showAddress: Boolean,
    showNote: Boolean,
    bottomRight: Boolean,
    bgAlpha: Int,
    fontScale: Float,
    accentColorIndex: Int
) {
    var timeTick by remember { mutableStateOf(WatermarkConfig.currentTimeStrings()) }
    LaunchedEffect(Unit) {
        while (true) {
            timeTick = WatermarkConfig.currentTimeStrings()
            delay(1000L)
        }
    }

    val (liveHm, liveDateWeek, liveFullTs) = timeTick
    val liveCfg = remember(
        styleTemplate, customTitle, coordSystem, useDms,
        loc, customNote, liveHm, liveDateWeek, liveFullTs,
        showTime, showLocation, showCoords, showCoordTag,
        showAccuracy, showAddress, showNote, bottomRight,
        bgAlpha, fontScale, accentColorIndex
    ) {
        WatermarkConfig(
            styleTemplate = styleTemplate,
            customTitle = customTitle,
            coordSystem = coordSystem,
            useDms = useDms,
            gcjLat = loc.gcjLat,
            gcjLon = loc.gcjLon,
            wgsLat = loc.wgsLat,
            wgsLon = loc.wgsLon,
            origGcjLat = loc.gcjLat,
            origGcjLon = loc.gcjLon,
            accuracyMeters = loc.accuracyMeters,
            shortPoi = loc.shortPoi,
            address = loc.fullAddress,
            timeHm = liveHm,
            dateWeek = liveDateWeek,
            timestamp = liveFullTs,
            note = customNote,
            showTime = showTime,
            showLocation = showLocation,
            showCoords = showCoords,
            showCoordTag = showCoordTag,
            showAccuracy = showAccuracy,
            showAddress = showAddress,
            showNote = showNote,
            bottomRight = bottomRight,
            bgAlpha = bgAlpha,
            fontScale = fontScale,
            accentColorIndex = accentColorIndex
        )
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val viewW = size.width.roundToInt()
        val viewH = size.height.roundToInt()
        if (viewW <= 0 || viewH <= 0) return@Canvas

        drawIntoCanvas { composeCanvas ->
            val nativeCanvas = composeCanvas.nativeCanvas
            nativeCanvas.save()
            when (deviceOrientation) {
                270 -> {
                    // Phone rotated 90° CCW (Left-Landscape: top of phone is on the left, shutter bar is on the right).
                    // Real-world Sky is at x = viewW (Right edge), Ground is at x = 0 (Left edge).
                    // Virtual upright landscape canvas has width = viewH, height = viewW.
                    nativeCanvas.translate(viewW.toFloat(), 0f)
                    nativeCanvas.rotate(90f)
                    WatermarkRenderer.drawWatermarkOverlay(nativeCanvas, viewH, viewW, liveCfg)
                }
                90 -> {
                    // Phone rotated 90° CW (Right-Landscape: top of phone is on the right, shutter bar is on the left).
                    // Real-world Sky is at x = 0 (Left edge), Ground is at x = viewW (Right edge).
                    // Virtual upright landscape canvas has width = viewH, height = viewW.
                    nativeCanvas.translate(0f, viewH.toFloat())
                    nativeCanvas.rotate(-90f)
                    WatermarkRenderer.drawWatermarkOverlay(nativeCanvas, viewH, viewW, liveCfg)
                }
                180 -> {
                    // Upside-down portrait
                    nativeCanvas.translate(viewW.toFloat(), viewH.toFloat())
                    nativeCanvas.rotate(180f)
                    WatermarkRenderer.drawWatermarkOverlay(nativeCanvas, viewW, viewH, liveCfg)
                }
                else -> {
                    // Normal portrait
                    WatermarkRenderer.drawWatermarkOverlay(nativeCanvas, viewW, viewH, liveCfg)
                }
            }
            nativeCanvas.restore()
        }
    }
}

// ============================================================================
// DUAL-MODE GALLERY: APP WATERMARK PHOTOS + SYSTEM CAMERA EXIF SCANNER
// ============================================================================
@Composable
fun GalleryAndSystemPhotosScreen(
    appPhotos: List<PhotoEntry>,
    selectedPhoto: PhotoEntry?,
    onSelectPhoto: (PhotoEntry) -> Unit,
    onBackToCamera: () -> Unit,
    onImportArbitraryPhoto: () -> Unit,
    onImportSystemPhotoUri: (SystemCameraPhoto) -> Unit,
    onEditWatermark: (PhotoEntry) -> Unit,
    onDeletePhoto: (PhotoEntry) -> Unit
) {
    val context = LocalContext.current
    var galleryTab by remember { mutableStateOf(0) }
    var systemPhotos by remember { mutableStateOf<List<SystemCameraPhoto>>(emptyList()) }
    var selectedSysPhoto by remember { mutableStateOf<SystemCameraPhoto?>(null) }
    var isScanningSys by remember { mutableStateOf(false) }

    LaunchedEffect(galleryTab) {
        if (galleryTab == 1) {
            isScanningSys = true
            val scanned = withContext(Dispatchers.IO) {
                WatermarkRenderer.scanSystemCameraPhotos(context, maxCount = 60)
            }
            systemPhotos = scanned
            if (selectedSysPhoto == null && scanned.isNotEmpty()) {
                selectedSysPhoto = scanned.firstOrNull { it.hasGps } ?: scanned.first()
            }
            isScanningSys = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090D16))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBackToCamera) {
                Text("← 返回相机", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
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
                    Text("系统原相机识别", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            TextButton(onClick = onImportArbitraryPhoto) {
                Text("+选图", color = Color(0xFF38BDF8), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (galleryTab == 0) {
            if (appPhotos.isEmpty() || selectedPhoto == null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("暂无水印实拍照片", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "你可以返回相机实拍，或切换到顶部「系统原相机识别」直接读取手机自带相机拍过的照片并一键跳高德地图。",
                        color = Color(0xFF94A3B8),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onBackToCamera) { Text("返回拍摄") }
                        OutlinedButton(onClick = { galleryTab = 1 }) { Text("扫描系统原相机照片") }
                    }
                }
                return@Column
            }

            var mainBitmap by remember { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(selectedPhoto.id, selectedPhoto.watermarkedFile.lastModified()) {
                mainBitmap = withContext(Dispatchers.IO) {
                    BitmapCacheManager.loadDisplayBitmap(selectedPhoto.watermarkedFile, 1440)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black)
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                mainBitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "已拍水印照片预览",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

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
                            text = "📍 ${cfg.shortPoi.ifBlank { "实拍点位" }} (已存 DCIM/Camera)",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "高德 GCJ-02: ${String.format(Locale.US, "%.6f°N, %.6f°E", cfg.gcjLat, cfg.gcjLon)}",
                            color = Color(0xFF38BDF8),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
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
                        Text("📍 一键跳高德地图 (丝毫不差)", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
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

            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(appPhotos, key = { it.id }) { item ->
                    val isSelected = item.id == selectedPhoto.id
                    var thumb by remember(item.id) { mutableStateOf<Bitmap?>(null) }
                    LaunchedEffect(item.id, item.watermarkedFile.lastModified()) {
                        thumb = withContext(Dispatchers.IO) {
                            BitmapCacheManager.loadThumbnail(item.watermarkedFile, 160, 160)
                        }
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
                        thumb?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        } else {
            if (isScanningSys) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("正在扫描手机系统相册照片并提取EXIF经纬度...", color = Color.White, fontSize = 14.sp)
                }
                return@Column
            }

            if (systemPhotos.isEmpty() || selectedSysPhoto == null) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("未扫描到系统相册照片或未授予相册读取权限", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = onImportArbitraryPhoto) {
                        Text("手动选择系统相册图片")
                    }
                }
                return@Column
            }

            val curSys = selectedSysPhoto!!
            var sysDisplayBmp by remember(curSys.contentUri) { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(curSys.contentUri) {
                sysDisplayBmp = withContext(Dispatchers.IO) {
                    BitmapCacheManager.loadUriThumbnail(context, curSys.contentUri, 1080)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black)
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                sysDisplayBmp?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F172A))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "${curSys.displayName} · ${curSys.dateTakenStr}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                if (curSys.hasGps) {
                    Text(
                        text = "✅ 已识别照片地理位置 (已自动由 WGS-84 纠偏为高德 GCJ-02)",
                        color = Color(0xFF34D399),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "高德 GCJ-02: ${String.format(Locale.US, "%.6f°N, %.6f°E", curSys.gcjLat, curSys.gcjLon)}",
                        color = Color(0xFF38BDF8),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "原始 EXIF (WGS-84): ${String.format(Locale.US, "%.6f°N, %.6f°E", curSys.wgsLat, curSys.wgsLon)}",
                        color = Color(0xFF94A3B8),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                } else {
                    Text(
                        text = "⚠️ 该照片拍摄时未开启系统相机“保存地理位置”开关 (无EXIF GPS标签)",
                        color = Color(0xFFFBBF24),
                        fontSize = 12.sp
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        enabled = curSys.hasGps,
                        onClick = {
                            AmapLauncher.jumpToAmapMarker(
                                context = context,
                                gcjLat = curSys.gcjLat,
                                gcjLon = curSys.gcjLon,
                                poiName = curSys.displayName
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                        modifier = Modifier.weight(1.3f)
                    ) {
                        Text("📍 一键跳高德地图", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }

                    Button(
                        enabled = curSys.hasGps,
                        onClick = {
                            AmapLauncher.jumpToAmapNavigation(
                                context = context,
                                gcjLat = curSys.gcjLat,
                                gcjLon = curSys.gcjLon,
                                poiName = curSys.displayName
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("🧭 高德导航", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }

                OutlinedButton(
                    onClick = { onImportSystemPhotoUri(curSys) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("✨ 为这张原相机照片加装高德经纬水印", color = Color(0xFF38BDF8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(systemPhotos, key = { it.mediaId }) { item ->
                    val isSel = (item.mediaId == curSys.mediaId)
                    var thumb by remember(item.mediaId) { mutableStateOf<Bitmap?>(null) }
                    LaunchedEffect(item.contentUri) {
                        thumb = withContext(Dispatchers.IO) {
                            BitmapCacheManager.loadUriThumbnail(context, item.contentUri, 160)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(
                                width = if (isSel) 2.5.dp else 1.dp,
                                color = if (isSel) Color(0xFF38BDF8) else if (item.hasGps) Color(0xFF34D399) else Color(0xFF334155),
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable { selectedSysPhoto = item }
                    ) {
                        thumb?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        if (item.hasGps) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(3.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xCC059669))
                                    .padding(horizontal = 3.dp, vertical = 1.dp)
                            ) {
                                Text("GPS", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// CUSTOM VECTOR ICONS & REUSABLE UI COMPONENTS
// ============================================================================
@Composable
fun CameraTopBarItem(
    label: String,
    rotationDegrees: Float = 0f,
    onClick: () -> Unit,
    iconContent: @Composable () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .rotate(rotationDegrees)
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
    rotationDegrees: Float = 0f,
    onClick: () -> Unit,
    iconContent: @Composable () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .rotate(rotationDegrees)
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
        if (statusText.isNotEmpty()) {
            Text(
                text = statusText,
                color = if (active) Color(0xFFFACC15) else Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
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
        drawCircle(color = Color.White, radius = 1.6.dp.toPx(), center = Offset(w * 0.50f, h * 0.60f))
        drawCircle(color = Color.White, radius = 1.6.dp.toPx(), center = Offset(w * 0.68f, h * 0.60f))
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
