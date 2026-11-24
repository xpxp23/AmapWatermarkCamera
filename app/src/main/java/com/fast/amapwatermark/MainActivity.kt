package com.fast.amapwatermark

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import com.fast.amapwatermark.ui.*
import com.fast.amapwatermark.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var fastLocationManager: FastLocationManager
    var volumeShutterAction: (() -> Boolean)? = null

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            val handled = volumeShutterAction?.invoke() ?: false
            if (handled) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        fastLocationManager = FastLocationManager(applicationContext)

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

    override fun onDestroy() {
        super.onDestroy()
        fastLocationManager.release()
    }
}

private val QUICK_TITLE_TAGS = listOf("现场点位核验", "工程巡检记录", "隐蔽工程验收", "外业测绘定桩", "安全隐患排查", "物资进场取证")
private val QUICK_NOTE_TAGS = listOf("核验合格", "定位已校准", "施工进行中", "待整改复核", "初检通过", "已闭环归档")

@Composable
fun ProWatermarkCameraScreen(locationManager: FastLocationManager) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("amap_wm_prefs_v6", Context.MODE_PRIVATE) }

    var deviceOrientation by remember { mutableStateOf(0) }
    var continuousUiRotation by remember { mutableStateOf(0f) }

    DisposableEffect(context) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
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

    val uiIconRotationState = animateFloatAsState(
        targetValue = continuousUiRotation,
        animationSpec = tween(durationMillis = 280),
        label = "uiIconRotation"
    )
    val rotationProvider: () -> Float = { uiIconRotationState.value }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    // Camera hardware & capture mode states
    var captureMode by remember { mutableStateOf(prefs.getInt("captureMode", 1)) }
    var isFrontCamera by remember { mutableStateOf(false) }
    var flashMode by remember { mutableStateOf(0) } // 0: 关, 1: 开, 2: 自动, 3: 常亮
    var timerSeconds by remember { mutableStateOf(0) }
    var activeTopInlineMenu by remember { mutableStateOf(0) }
    var countdownRemaining by remember { mutableStateOf(0) }
    var countdownJob by remember { mutableStateOf<Job?>(null) }
    var isCapturing by remember { mutableStateOf(false) }
    var zoomRatio by remember { mutableStateOf(1.0f) }
    var maxZoomRatio by remember { mutableStateOf(4.0f) }
    var shutterFlashVisible by remember { mutableStateOf(false) }
    var asyncSamplingBanner by remember { mutableStateOf<String?>(null) }

    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusTriggerId by remember { mutableStateOf(0) }
    var exposureIndex by remember { mutableStateOf(0) }
    var exposureRange by remember { mutableStateOf(-4..4) }

    // Persisted Watermark configuration (v9.0.0: zero filter state, showBottomScrim default false, asyncOptimal default false)
    var styleTemplate by remember { mutableStateOf(prefs.getInt("styleTemplate", 0)) }
    var customTitle by remember { mutableStateOf(prefs.getString("customTitle", "现场点位核验") ?: "现场点位核验") }
    var coordSystem by remember { mutableStateOf(prefs.getString("coordSystem", "WGS-84") ?: "WGS-84") }
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
    var showAltitude by remember { mutableStateOf(prefs.getBoolean("showAltitude", false)) }
    var showAzimuth by remember { mutableStateOf(prefs.getBoolean("showAzimuth", false)) }
    var showQrCode by remember { mutableStateOf(prefs.getBoolean("showQrCode", true)) }
    var showAddress by remember { mutableStateOf(prefs.getBoolean("showAddress", false)) }
    var showNote by remember { mutableStateOf(prefs.getBoolean("showNote", true)) }
    var showMiniMap by remember { mutableStateOf(prefs.getBoolean("showMiniMap", true)) }
    var miniMapPosition by remember { mutableStateOf(prefs.getInt("miniMapPosition", 0)) }
    var showBottomScrim by remember { mutableStateOf(prefs.getBoolean("showBottomScrim", false)) }
    var antiTamperMode by remember { mutableStateOf(prefs.getBoolean("antiTamperMode", false)) }
    var bottomRight by remember { mutableStateOf(prefs.getBoolean("bottomRight", false)) }

    var asyncOptimal by remember { mutableStateOf(prefs.getBoolean("asyncOptimal", false)) }
    var accuracyThreshold by remember { mutableStateOf(prefs.getInt("accuracyThreshold", 10)) }
    var keepCleanOriginal by remember { mutableStateOf(prefs.getBoolean("keepCleanOriginal", false)) }
    var showGridLines by remember { mutableStateOf(prefs.getBoolean("showGridLines", false)) }

    var templateSwitchBanner by remember { mutableStateOf<String?>(null) }
    var templateBannerTrigger by remember { mutableStateOf(0) }

    fun switchWatermarkTemplate(newIndex: Int) {
        val total = WatermarkRenderer.STYLE_NAMES.size
        val normalized = ((newIndex % total) + total) % total
        styleTemplate = normalized
        templateSwitchBanner = "模板 ${normalized + 1}/$total · ${WatermarkRenderer.STYLE_NAMES[normalized]}"
        templateBannerTrigger++
    }

    LaunchedEffect(templateBannerTrigger) {
        if (templateSwitchBanner != null) {
            delay(1500L)
            templateSwitchBanner = null
        }
    }

    LaunchedEffect(
        captureMode, styleTemplate, customTitle, coordSystem, useDms, customNote,
        fontScale, accentColorIndex, bgAlpha, showTime, showLocation,
        showCoords, showCoordTag, showAccuracy, showAltitude, showAzimuth,
        showQrCode, showAddress, showNote, showMiniMap, miniMapPosition, showBottomScrim,
        antiTamperMode, bottomRight, asyncOptimal, accuracyThreshold, keepCleanOriginal, showGridLines
    ) {
        delay(250L)
        prefs.edit()
            .putInt("captureMode", captureMode)
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
            .putBoolean("showAltitude", showAltitude)
            .putBoolean("showAzimuth", showAzimuth)
            .putBoolean("showQrCode", showQrCode)
            .putBoolean("showAddress", showAddress)
            .putBoolean("showNote", showNote)
            .putBoolean("showMiniMap", showMiniMap)
            .putInt("miniMapPosition", miniMapPosition)
            .putBoolean("showBottomScrim", showBottomScrim)
            .putBoolean("antiTamperMode", antiTamperMode)
            .putBoolean("bottomRight", bottomRight)
            .putBoolean("asyncOptimal", asyncOptimal)
            .putInt("accuracyThreshold", accuracyThreshold)
            .putBoolean("keepCleanOriginal", keepCleanOriginal)
            .putBoolean("showGridLines", showGridLines)
            .apply()
    }

    // Dialog / Screen States
    var showLocationSheet by remember { mutableStateOf(false) }
    var showWatermarkSheet by remember { mutableStateOf(false) }
    var watermarkSheetTab by remember { mutableStateOf(0) }
    var showResetConfirmDialog by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showGalleryScreen by remember { mutableStateOf(false) }
    var editingPhoto by remember { mutableStateOf<PhotoEntry?>(null) }
    var pendingDeletePhotos by remember { mutableStateOf<List<PhotoEntry>>(emptyList()) }
    var showVerifyDialog by remember { mutableStateOf(false) }
    var verifyPayload by remember { mutableStateOf<QrCodeGenerator.VerifiedLocationPayload?>(null) }
    var justCapturedPhoto by remember { mutableStateOf<PhotoEntry?>(null) }

    // Batch Watermark States
    var pendingBatchUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var batchProgressState by remember { mutableStateOf<BatchProgressState?>(null) }
    var batchJob by remember { mutableStateOf<Job?>(null) }

    var photos by remember { mutableStateOf<List<PhotoEntry>>(emptyList()) }
    var latestThumbBitmap by remember { mutableStateOf<Bitmap?>(null) }

    fun buildCurrentTemplateBaseConfig(): WatermarkConfig {
        return WatermarkConfig(
            styleTemplate = styleTemplate,
            customTitle = customTitle,
            coordSystem = coordSystem,
            useDms = useDms,
            note = if (antiTamperMode) "" else customNote,
            showTime = showTime,
            showLocation = showLocation,
            showCoords = showCoords,
            showCoordTag = showCoordTag,
            showAccuracy = showAccuracy,
            showAltitude = showAltitude,
            showAzimuth = showAzimuth,
            showQrCode = showQrCode,
            showAddress = showAddress,
            showNote = showNote,
            showMiniMap = showMiniMap,
            miniMapPosition = miniMapPosition,
            showBottomScrim = showBottomScrim,
            bottomRight = bottomRight,
            bgAlpha = bgAlpha,
            fontScale = fontScale,
            accentColorIndex = accentColorIndex
        )
    }

    fun exportAndShareCsv(targetList: List<PhotoEntry>) {
        if (targetList.isEmpty()) {
            Toast.makeText(context, "暂无可导出的照片记录", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            val csvFile = withContext(Dispatchers.IO) {
                WatermarkRenderer.exportPhotosLedgerCsv(context, targetList)
            }
            if (csvFile != null) {
                try {
                    val uri = WatermarkRenderer.getShareUriForFile(context, csvFile)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "高德水印相机工程台账")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "导出工程台账 CSV"))
                } catch (e: Exception) {
                    Toast.makeText(context, "分享 CSV 失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, "导出台账 CSV 失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val loaded = WatermarkRenderer.loadSavedPhotos(context)
            val firstThumb = loaded.firstOrNull()?.watermarkedFile?.let {
                BitmapCacheManager.loadThumbnail(it, 160, 160)
            }
            withContext(Dispatchers.Main) {
                photos = loaded
                latestThumbBitmap = firstThumb
            }
        }
    }

    LaunchedEffect(photos.firstOrNull()?.id, photos.firstOrNull()?.watermarkedFile?.lastModified()) {
        val firstFile = photos.firstOrNull()?.watermarkedFile
        latestThumbBitmap = if (firstFile != null) {
            withContext(Dispatchers.IO) { BitmapCacheManager.loadThumbnail(firstFile, 160, 160) }
        } else null
    }

    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var cameraRef by remember { mutableStateOf<Camera?>(null) }
    var imageCaptureRef by remember { mutableStateOf<ImageCapture?>(null) }

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            perms.add(Manifest.permission.READ_MEDIA_IMAGES)
            perms.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            perms.add(Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    // Batch Photo Picker (up to 50 photos)
    val batchPhotoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 50)
    ) { uris ->
        if (uris.isNotEmpty()) {
            pendingBatchUris = uris
        }
    }

    fun launchBatchPicker() {
        batchPhotoPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    // Bind CameraX with lifecycle check, zoom reset, and front-camera torch reset
    LaunchedEffect(hasCameraPermission, isFrontCamera, previewViewRef) {
        val pv = previewViewRef ?: return@LaunchedEffect
        if (!hasCameraPermission) return@LaunchedEffect
        if (isFrontCamera && flashMode == 3) {
            flashMode = 0
        }
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.INITIALIZED)) {
                return@addListener
            }
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
                val viewPort = pv.viewPort
                val cam = if (viewPort != null) {
                    val useCaseGroup = UseCaseGroup.Builder()
                        .addUseCase(preview)
                        .addUseCase(imgCapture)
                        .setViewPort(viewPort)
                        .build()
                    cameraProvider.bindToLifecycle(lifecycleOwner, selector, useCaseGroup)
                } else {
                    cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, imgCapture)
                }
                cameraRef = cam
                imageCaptureRef = imgCapture

                val zoomState = cam.cameraInfo.zoomState.value
                maxZoomRatio = (zoomState?.maxZoomRatio ?: 4.0f).coerceAtLeast(2.0f)
                zoomRatio = 1.0f
                try {
                    cam.cameraControl.setZoomRatio(1.0f)
                } catch (_: Exception) {}

                val expState = cam.cameraInfo.exposureState
                val range = expState.exposureCompensationRange
                exposureRange = range.lower..range.upper
                exposureIndex = expState.exposureCompensationIndex
            } catch (e: Exception) {
                Toast.makeText(context, "相机启动异常: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // Keep ImageCapture targetRotation aligned with physical deviceOrientation
    LaunchedEffect(deviceOrientation, imageCaptureRef) {
        val cap = imageCaptureRef ?: return@LaunchedEffect
        cap.targetRotation = when (deviceOrientation) {
            90 -> Surface.ROTATION_270
            180 -> Surface.ROTATION_180
            270 -> Surface.ROTATION_90
            else -> Surface.ROTATION_0
        }
    }

    // Sync Flash / Torch state
    LaunchedEffect(flashMode, isFrontCamera, cameraRef, imageCaptureRef) {
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
                    if (!isFrontCamera) {
                        cam.cameraControl.enableTorch(true)
                    } else {
                        cam.cameraControl.enableTorch(false)
                    }
                    cap.flashMode = ImageCapture.FLASH_MODE_OFF
                }
            }
        } catch (_: Exception) {}
    }

    LaunchedEffect(focusTriggerId) {
        if (focusPoint != null) {
            delay(2200L)
            focusPoint = null
        }
    }

    // Dual-Mode Capture Execution
    fun performCapture() {
        if (countdownRemaining > 0) {
            countdownJob?.cancel()
            countdownJob = null
            countdownRemaining = 0
            isCapturing = false
            Toast.makeText(context, "已取消倒计时拍摄", Toast.LENGTH_SHORT).show()
            return
        }
        if (isCapturing) return
        countdownJob = scope.launch {
            isCapturing = true
            try {
                activeTopInlineMenu = 0
                if (timerSeconds > 0) {
                    for (sec in timerSeconds downTo 1) {
                        countdownRemaining = sec
                        delay(1000L)
                    }
                    countdownRemaining = 0
                }

                val currentLoc = locationManager.locationState.value
                if (antiTamperMode && !currentLoc.hasValidCoordinates()) {
                    Toast.makeText(context, "防伪模式需等待真实 GPS 定位锁定后方可拍摄签发", Toast.LENGTH_LONG).show()
                    return@launch
                }

                val captureOrientation = deviceOrientation
                val (hm, dateWeek, fullTs) = WatermarkConfig.currentTimeStrings(locationManager.getTrustedDate())
                val useHardwareCapture = (captureMode == 1 && imageCaptureRef != null && hasCameraPermission)

                val needFrontScreenFlash = ((flashMode == 1 || flashMode == 2) && isFrontCamera)
                if (needFrontScreenFlash) {
                    shutterFlashVisible = true
                    delay(180L)
                }

                val uprightCleanBmp: Bitmap = if (useHardwareCapture) {
                    shutterFlashVisible = true
                    launch {
                        delay(150L)
                        shutterFlashVisible = false
                    }
                    val hwBmp = captureHardwareOpticalBitmap(
                        context = context,
                        imageCapture = imageCaptureRef!!,
                        maxLongEdgePx = 2560
                    )
                    if (hwBmp != null) {
                        hwBmp
                    } else {
                        val fallbackRaw = previewViewRef?.bitmap ?: createFallbackBitmap()
                        val rotDeg = when (captureOrientation) {
                            270 -> -90
                            90 -> 90
                            180 -> 180
                            else -> 0
                        }
                        withContext(Dispatchers.IO) {
                            WatermarkRenderer.rotateBitmapIfNeeded(fallbackRaw, rotDeg).also {
                                if (it !== fallbackRaw && !fallbackRaw.isRecycled) fallbackRaw.recycle()
                            }
                        }
                    }
                } else {
                    val needRearTorchPulse = ((flashMode == 1 || flashMode == 2) && !isFrontCamera)
                    if (needRearTorchPulse) {
                        try {
                            cameraRef?.cameraControl?.enableTorch(true)
                            delay(200L)
                        } catch (_: Exception) {}
                    }
                    shutterFlashVisible = true
                    val rawViewfinderBmp: Bitmap = previewViewRef?.bitmap ?: createFallbackBitmap()
                    if (needRearTorchPulse) {
                        try { cameraRef?.cameraControl?.enableTorch(false) } catch (_: Exception) {}
                    }
                    launch {
                        delay(140L)
                        shutterFlashVisible = false
                    }
                    val rotDeg = when (captureOrientation) {
                        270 -> -90
                        90 -> 90
                        180 -> 180
                        else -> 0
                    }
                    withContext(Dispatchers.IO) {
                        WatermarkRenderer.rotateBitmapIfNeeded(rawViewfinderBmp, rotDeg).also {
                            if (it !== rawViewfinderBmp && !rawViewfinderBmp.isRecycled) rawViewfinderBmp.recycle()
                        }
                    }
                }

                val finalLoc = if (asyncOptimal && (!currentLoc.hasRealFix || currentLoc.accuracyMeters > accuracyThreshold)) {
                    asyncSamplingBanner = "正在锁定高精高德坐标..."
                    val best = locationManager.awaitOptimalLocation(accuracyThreshold.toFloat(), 1100L)
                    asyncSamplingBanner = null
                    best
                } else {
                    currentLoc
                }

                val activeGcjLat = if (antiTamperMode) finalLoc.rawGcjLat else finalLoc.gcjLat
                val activeGcjLon = if (antiTamperMode) finalLoc.rawGcjLon else finalLoc.gcjLon
                val activeWgsLat = if (antiTamperMode) finalLoc.rawWgsLat else finalLoc.wgsLat
                val activeWgsLon = if (antiTamperMode) finalLoc.rawWgsLon else finalLoc.wgsLon
                val effectiveNote = if (antiTamperMode) "" else customNote

                val savedEntry = withContext(Dispatchers.IO) {
                    val imgHash = if (antiTamperMode && (activeGcjLat != 0.0 || activeGcjLon != 0.0)) {
                        QrCodeGenerator.computeImagePerceptualHash(uprightCleanBmp)
                    } else ""

                    val snapshotCfg = WatermarkConfig(
                        styleTemplate = styleTemplate,
                        customTitle = customTitle,
                        coordSystem = coordSystem,
                        useDms = useDms,
                        gcjLat = activeGcjLat,
                        gcjLon = activeGcjLon,
                        wgsLat = activeWgsLat,
                        wgsLon = activeWgsLon,
                        origGcjLat = activeGcjLat,
                        origGcjLon = activeGcjLon,
                        accuracyMeters = finalLoc.accuracyMeters,
                        altitudeMeters = finalLoc.altitudeMeters,
                        azimuthDeg = finalLoc.azimuthDeg,
                        azimuthText = finalLoc.azimuthText,
                        shortPoi = finalLoc.shortPoi,
                        address = finalLoc.fullAddress,
                        timeHm = hm,
                        dateWeek = dateWeek,
                        timestamp = fullTs,
                        note = effectiveNote,
                        showTime = showTime,
                        showLocation = showLocation,
                        showCoords = showCoords,
                        showCoordTag = showCoordTag,
                        showAccuracy = showAccuracy,
                        showAltitude = showAltitude,
                        showAzimuth = showAzimuth,
                        showQrCode = showQrCode,
                        showAddress = showAddress,
                        showNote = showNote && !antiTamperMode,
                        showMiniMap = showMiniMap,
                        miniMapPosition = miniMapPosition,
                        showBottomScrim = showBottomScrim,
                        antiTamperMode = antiTamperMode && (activeGcjLat != 0.0 || activeGcjLon != 0.0),
                        ntpSynced = finalLoc.ntpSynced,
                        imageHash = imgHash,
                        antiTamperHash = if (antiTamperMode && (activeGcjLat != 0.0 || activeGcjLon != 0.0)) {
                            QrCodeGenerator.computeAntiTamperHash(activeGcjLat, activeGcjLon, fullTs, finalLoc.shortPoi, effectiveNote, imgHash)
                        } else "",
                        bottomRight = bottomRight,
                        bgAlpha = bgAlpha,
                        fontScale = fontScale,
                        accentColorIndex = accentColorIndex
                    )

                    try {
                        WatermarkRenderer.saveNewPhotoEntry(
                            context = context,
                            cleanBitmap = uprightCleanBmp,
                            cfg = snapshotCfg,
                            alsoExportOriginal = keepCleanOriginal
                        )
                    } finally {
                        if (!uprightCleanBmp.isRecycled) uprightCleanBmp.recycle()
                    }
                }
                photos = listOf(savedEntry) + photos
                justCapturedPhoto = savedEntry
                launch {
                    delay(4500L)
                    if (justCapturedPhoto?.id == savedEntry.id) {
                        justCapturedPhoto = null
                    }
                }
            } finally {
                countdownRemaining = 0
                isCapturing = false
            }
        }
    }

    DisposableEffect(showGalleryScreen, showLocationSheet, showWatermarkSheet, showSettingsSheet, showVerifyDialog, editingPhoto) {
        val activity = context as? MainActivity
        activity?.volumeShutterAction = {
            val onMainCamera = !showGalleryScreen && !showLocationSheet && !showWatermarkSheet && !showSettingsSheet && !showVerifyDialog && editingPhoto == null
            if (onMainCamera) {
                performCapture()
                true
            } else {
                false
            }
        }
        onDispose {
            activity?.volumeShutterAction = null
        }
    }

    if (showGalleryScreen) {
        GalleryAndSystemPhotosScreen(
            appPhotos = photos,
            onBackToCamera = { showGalleryScreen = false },
            onLaunchSystemBatchPicker = { launchBatchPicker() },
            onBatchWatermarkUris = { uris -> pendingBatchUris = uris },
            onEditWatermark = { target ->
                if (target.config.antiTamperMode) {
                    Toast.makeText(context, "防篡改验真原片已由密码学签名锁定，禁止后期修改", Toast.LENGTH_LONG).show()
                } else {
                    editingPhoto = target
                }
            },
            onVerifyPhoto = { payload ->
                verifyPayload = payload
                showVerifyDialog = true
            },
            onRequestDeletePhotos = { targets -> pendingDeletePhotos = targets },
            onExportCsvLedger = { list -> exportAndShareCsv(list) }
        )
    } else {
        // ==================== MAIN CAMERA SCREEN ====================
        BackHandler(enabled = activeTopInlineMenu != 0 || countdownRemaining > 0 || showWatermarkSheet) {
            when {
                countdownRemaining > 0 -> {
                    countdownJob?.cancel()
                    countdownJob = null
                    countdownRemaining = 0
                    isCapturing = false
                    Toast.makeText(context, "已取消倒计时拍摄", Toast.LENGTH_SHORT).show()
                }
                showWatermarkSheet -> showWatermarkSheet = false
                activeTopInlineMenu != 0 -> activeTopInlineMenu = 0
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .statusBarsPadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. STREAMLINED TOP TOOLBAR (statusBarsPadding applied on root)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    .background(Color.Black)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                when (activeTopInlineMenu) {
                    1 -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(24.dp))
                                .background(Color(0xFF141D2E))
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(36.dp).graphicsLayer { rotationZ = rotationProvider() },
                                contentAlignment = Alignment.Center
                            ) {
                                TimerVectorIcon(seconds = timerSeconds)
                            }
                            listOf(0 to "关", 3 to "3s", 5 to "5s", 10 to "10s").forEach { (sec, shortLbl) ->
                                val isSel = (timerSeconds == sec)
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .graphicsLayer { rotationZ = rotationProvider() }
                                        .clip(CircleShape)
                                        .background(if (isSel) Color(0xFF0284C7) else Color(0xFF1E293B))
                                        .clickable {
                                            timerSeconds = sec
                                            activeTopInlineMenu = 0
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(shortLbl, color = Color.White, fontSize = 11.5.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium)
                                }
                            }
                        }
                    }

                    2 -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(24.dp))
                                .background(Color(0xFF141D2E))
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(36.dp).graphicsLayer { rotationZ = rotationProvider() },
                                contentAlignment = Alignment.Center
                            ) {
                                FlashVectorIcon(statusText = "", active = flashMode != 0)
                            }
                            listOf(0 to "关", 1 to "开", 2 to "自动", 3 to "常亮").forEach { (mode, shortLbl) ->
                                val isSel = (flashMode == mode)
                                val enabled = !(isFrontCamera && mode == 3)
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .graphicsLayer { rotationZ = rotationProvider() }
                                        .clip(CircleShape)
                                        .background(if (isSel) Color(0xFFF59E0B) else if (enabled) Color(0xFF1E293B) else Color(0xFF0F172A))
                                        .clickable(enabled = enabled) {
                                            flashMode = mode
                                            activeTopInlineMenu = 0
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = shortLbl,
                                        color = if (isSel) Color.Black else if (enabled) Color.White else Color(0xFF475569),
                                        fontSize = 11.sp,
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
                            CameraTopBarItem("设置", rotationProvider, onClick = { showSettingsSheet = true }) {
                                GearVectorIcon()
                            }

                            CameraTopBarItem(
                                label = "验真",
                                rotationProvider = rotationProvider,
                                onClick = {
                                    // Always clear stale verifyPayload before opening fresh
                                    verifyPayload = null
                                    val latest = photos.firstOrNull()
                                    if (latest != null) {
                                        val c = latest.config
                                        val exp = QrCodeGenerator.computeAntiTamperHash(c.gcjLat, c.gcjLon, c.timestamp, c.shortPoi, c.note, c.imageHash).take(12)
                                        verifyPayload = QrCodeGenerator.VerifiedLocationPayload(
                                            gcjLat = c.gcjLat,
                                            gcjLon = c.gcjLon,
                                            wgsLat = c.wgsLat,
                                            wgsLon = c.wgsLon,
                                            shortPoi = c.shortPoi,
                                            timestamp = c.timestamp,
                                            note = c.note,
                                            imageHash = c.imageHash,
                                            signature = c.antiTamperHash.take(12),
                                            expectedSignature = exp,
                                            ntpSynced = c.ntpSynced,
                                            hasAntiTamper = c.antiTamperMode,
                                            verified = c.isSignatureValid(),
                                            sourceLabel = "本机最近实拍原片"
                                        )
                                    }
                                    showVerifyDialog = true
                                }
                            ) { ShieldVerifyVectorIcon() }

                            CameraTopBarItem(
                                label = if (captureMode == 1) "光学" else "极速",
                                rotationProvider = rotationProvider,
                                onClick = {
                                    captureMode = if (captureMode == 1) 0 else 1
                                    Toast.makeText(
                                        context,
                                        if (captureMode == 1) "已切换至：光学拍照模式 (硬件ISP高清)" else "已切换至：极速截屏模式 (0ms抓拍)",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            ) { CaptureModeVectorIcon(isOptical = captureMode == 1) }

                            CameraTopBarItem(
                                label = if (timerSeconds == 0) "倒计时" else "${timerSeconds}s",
                                rotationProvider = rotationProvider,
                                onClick = { activeTopInlineMenu = 1 }
                            ) { TimerVectorIcon(seconds = timerSeconds) }

                            val flashSubLabel = when (flashMode) {
                                0 -> "关"
                                1 -> "开"
                                2 -> "自动"
                                else -> "常亮"
                            }
                            CameraTopBarItem(
                                label = "闪光灯",
                                rotationProvider = rotationProvider,
                                onClick = { activeTopInlineMenu = 2 }
                            ) { FlashVectorIcon(statusText = flashSubLabel, active = flashMode != 0) }

                            CameraTopBarItem(
                                label = "翻转",
                                rotationProvider = rotationProvider,
                                onClick = { isFrontCamera = !isFrontCamera }
                            ) { FlipCameraVectorIcon() }
                        }
                    }
                }
            }

            // 2. CENTER LIVE VIEWFINDER AREA (Full-frame tap-to-focus restored; no 42dp accidental swipe)
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
                            .pointerInput(maxZoomRatio) {
                                // Pinch-to-zoom only (removed 42dp accidental horizontal template swipe)
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    do {
                                        val event = awaitPointerEvent()
                                        val activePointers = event.changes.count { it.pressed }
                                        if (activePointers >= 2) {
                                            val zoomChange = event.calculateZoom()
                                            if (zoomChange != 1.0f && event.calculateCentroidSize(useCurrent = false) > 0f) {
                                                val nextZoom = (zoomRatio * zoomChange).coerceIn(1.0f, maxZoomRatio)
                                                zoomRatio = nextZoom
                                                try {
                                                    cameraRef?.cameraControl?.setZoomRatio(nextZoom)
                                                } catch (_: Exception) {}
                                            }
                                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                                        }
                                    } while (event.changes.any { it.pressed })
                                }
                            }
                            .pointerInput(maxZoomRatio) {
                                detectTapGestures(
                                    onDoubleTap = {
                                        val nextZoom = if (zoomRatio < 1.8f) 2.0f.coerceAtMost(maxZoomRatio) else 1.0f
                                        zoomRatio = nextZoom
                                        try {
                                            cameraRef?.cameraControl?.setZoomRatio(nextZoom)
                                        } catch (_: Exception) {}
                                    },
                                    onTap = { tapOffset ->
                                        activeTopInlineMenu = 0
                                        if (showWatermarkSheet) {
                                            showWatermarkSheet = false
                                            return@detectTapGestures
                                        }
                                        // Full-frame tap-to-focus (removed bottom 25% accidental hotzone)
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
                                )
                            }
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("需要相机与定位权限以启用实景水印拍摄", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = {
                                permissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.CAMERA,
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            }) {
                                Text("申请权限")
                            }
                            OutlinedButton(onClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
                            }) {
                                Text("前往系统设置")
                            }
                        }
                    }
                }

                if (showGridLines) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        val gridColor = Color(0x40FFFFFF)
                        val strokePx = 1.dp.toPx()
                        drawLine(gridColor, Offset(w / 3f, 0f), Offset(w / 3f, h), strokeWidth = strokePx)
                        drawLine(gridColor, Offset(w * 2f / 3f, 0f), Offset(w * 2f / 3f, h), strokeWidth = strokePx)
                        drawLine(gridColor, Offset(0f, h / 3f), Offset(w, h / 3f), strokeWidth = strokePx)
                        drawLine(gridColor, Offset(0f, h * 2f / 3f), Offset(w, h * 2f / 3f), strokeWidth = strokePx)
                    }
                }

                // 100% WYSIWYG LIVE WATERMARK OVERLAY (isolated loc subscription to avoid root recomposition)
                WysiwygLiveWatermarkCanvas(
                    locationManager = locationManager,
                    deviceOrientation = deviceOrientation,
                    styleTemplate = styleTemplate,
                    customTitle = customTitle,
                    coordSystem = coordSystem,
                    useDms = useDms,
                    customNote = if (antiTamperMode) "" else customNote,
                    showTime = showTime,
                    showLocation = showLocation,
                    showCoords = showCoords,
                    showCoordTag = showCoordTag,
                    showAccuracy = showAccuracy,
                    showAltitude = showAltitude,
                    showAzimuth = showAzimuth,
                    showQrCode = showQrCode,
                    showAddress = showAddress,
                    showNote = showNote && !antiTamperMode,
                    showMiniMap = showMiniMap,
                    miniMapPosition = miniMapPosition,
                    showBottomScrim = showBottomScrim,
                    antiTamperMode = antiTamperMode,
                    bottomRight = bottomRight,
                    bgAlpha = bgAlpha,
                    fontScale = fontScale,
                    accentColorIndex = accentColorIndex
                )

                // Isolated GPS Status Pill (recomputes only this pill on GPS updates)
                GpsStatusPill(
                    locationManager = locationManager,
                    deviceOrientation = deviceOrientation,
                    coordSystem = coordSystem,
                    antiTamperMode = antiTamperMode,
                    captureMode = captureMode,
                    modifier = Modifier
                        .align(if (deviceOrientation == 270) Alignment.TopEnd else Alignment.TopStart)
                        .padding(12.dp),
                    onClick = { showLocationSheet = true }
                )

                justCapturedPhoto?.let { latest ->
                    if (latest.config.hasValidCoords()) {
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
                            Text("已存DCIM · 跳高德 →", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = templateSwitchBanner != null,
                    enter = fadeIn(tween(150)),
                    exit = fadeOut(tween(250)),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 48.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .graphicsLayer { rotationZ = rotationProvider() }
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xD90F172A))
                            .border(1.dp, Color(0xFF38BDF8), RoundedCornerShape(20.dp))
                            .padding(horizontal = 16.dp, vertical = 7.dp)
                    ) {
                        Text(templateSwitchBanner ?: "", color = Color(0xFF38BDF8), fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    }
                }

                asyncSamplingBanner?.let { msg ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .graphicsLayer { rotationZ = rotationProvider() }
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xE6F59E0B))
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(msg, color = Color.Black, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    }
                }

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
                            .graphicsLayer { rotationZ = rotationProvider() }
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

                // Zoom Ratio Pill
                Box(
                    modifier = Modifier
                        .align(if (deviceOrientation == 90) Alignment.CenterStart else Alignment.CenterEnd)
                        .padding(horizontal = 14.dp)
                        .size(44.dp)
                        .graphicsLayer { rotationZ = rotationProvider() }
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
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (countdownRemaining > 0) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = countdownRemaining.toString(),
                            color = Color.White,
                            fontSize = 96.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.graphicsLayer { rotationZ = rotationProvider() },
                            style = TextStyle(shadow = Shadow(Color.Black, Offset(0f, 4f), 12f))
                        )
                    }
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = shutterFlashVisible,
                    enter = fadeIn(tween(60)),
                    exit = fadeOut(tween(150)),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.White))
                }
            }

            // Direct 5-Template Quick Switcher Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF060911))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                WatermarkRenderer.STYLE_SHORT_NAMES.forEachIndexed { idx, shortName ->
                    val isSel = (styleTemplate == idx)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 2.5.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isSel) Color(0xFF0284C7) else Color(0xFF141D2E))
                            .border(
                                width = if (isSel) 1.dp else 0.dp,
                                color = if (isSel) Color(0xFF38BDF8) else Color.Transparent,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable { switchWatermarkTemplate(idx) }
                            .padding(vertical = 5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = shortName,
                            color = if (isSel) Color.White else Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }
            }

            // 3. BOTTOM BLACK CONTROL BAR (Symmetric 5-slot: 相册 | 地点校准 | 快门 | 批量水印 | 水印设置)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(114.dp)
                    .background(Color.Black)
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .graphicsLayer { rotationZ = rotationProvider() }
                        .clickable { showGalleryScreen = true }
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E293B))
                            .border(1.5.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        val bmp = latestThumbBitmap
                        if (bmp != null && !bmp.isRecycled) {
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
                    Spacer(modifier = Modifier.height(5.dp))
                    Text("相册", color = Color.White, fontSize = 11.5.sp)
                }

                CameraBottomBarItem("地点校准", rotationProvider = rotationProvider, onClick = { showLocationSheet = true }) {
                    PaperPlaneLocationIcon()
                }

                val isCountdownActive = countdownRemaining > 0
                Box(
                    modifier = Modifier
                        .size(74.dp)
                        .clip(CircleShape)
                        .border(
                            width = 4.dp,
                            color = if (isCountdownActive) Color(0xFFF59E0B) else if (captureMode == 1) Color(0xFF38BDF8) else Color.White,
                            shape = CircleShape
                        )
                        .clickable { performCapture() }
                        .padding(6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(if (isCountdownActive) Color(0x66F59E0B) else Color(0x28FFFFFF)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isCountdownActive) {
                            Text("停止", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                CameraBottomBarItem("批量水印", rotationProvider = rotationProvider, onClick = { launchBatchPicker() }) {
                    BatchWatermarkVectorIcon()
                }

                CameraBottomBarItem(
                    label = "水印设置",
                    active = showWatermarkSheet,
                    rotationProvider = rotationProvider,
                    onClick = { showWatermarkSheet = !showWatermarkSheet }
                ) {
                    WatermarkStampVectorIcon()
                }
            }
        }
    }

    // ==================== HALF-SCREEN WATERMARK SETTINGS SHEET ====================
    if (showWatermarkSheet) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x33000000))
                .clickable { showWatermarkSheet = false },
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .heightIn(max = 480.dp)
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(Color(0xF50B132B))
                    .border(1.dp, Color(0x3338BDF8), RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .clickable(enabled = false) {}
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("水印快捷设置", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.5.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { showResetConfirmDialog = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("重置", color = Color(0xFF94A3B8), fontSize = 12.sp)
                        }
                        Button(
                            onClick = { showWatermarkSheet = false },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("完成", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF141D2E))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    listOf("预设与开关", "外观与暗影", "标题与备注").forEachIndexed { idx, title ->
                        val sel = (watermarkSheetTab == idx)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (sel) Color(0xFF0284C7) else Color.Transparent)
                                .clickable { watermarkSheetTab = idx }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(title, color = if (sel) Color.White else Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium)
                        }
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    when (watermarkSheetTab) {
                        0 -> {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                QuickPresetButton("极简打卡", Modifier.weight(1f)) {
                                    styleTemplate = 0; showTime = true; showLocation = true; showCoords = true
                                    showCoordTag = true; showAccuracy = true; showAddress = false; showNote = false
                                    showAltitude = false; showAzimuth = false; showQrCode = true; showBottomScrim = false
                                }
                                QuickPresetButton("工程全要素", Modifier.weight(1f)) {
                                    styleTemplate = 1; showTime = true; showLocation = true; showCoords = true
                                    showCoordTag = true; showAccuracy = true; showAddress = true; showNote = true
                                    showAltitude = true; showAzimuth = true; showQrCode = true
                                }
                                QuickPresetButton("GIS测绘", Modifier.weight(1f)) {
                                    styleTemplate = 2; useDms = true; showTime = true; showLocation = true
                                    showCoords = true; showCoordTag = true; showAccuracy = true; showAddress = true
                                    showAltitude = true; showAzimuth = true; showQrCode = true
                                }
                                QuickPresetButton("标准台账", Modifier.weight(1f)) {
                                    styleTemplate = 4; showTime = true; showLocation = true; showCoords = true
                                    showCoordTag = true; showAccuracy = true; showAddress = true; showNote = true
                                    showAltitude = false; showAzimuth = false; showQrCode = true
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                CompactToggleChip("WGS-84 国际", coordSystem == "WGS-84", Modifier.weight(1f)) { coordSystem = "WGS-84" }
                                CompactToggleChip("CGCS2000 国标", coordSystem == "CGCS2000", Modifier.weight(1f)) { coordSystem = "CGCS2000" }
                                CompactToggleChip("GCJ-02 高德", coordSystem == "GCJ-02", Modifier.weight(1f)) { coordSystem = "GCJ-02" }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                CompactToggleChip("十进制坐标", !useDms, Modifier.weight(1f)) { useDms = false }
                                CompactToggleChip("度分秒 (DMS)", useDms, Modifier.weight(1f)) { useDms = true }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                val supportsMiniMap = styleTemplate in listOf(1, 2)
                                CompactToggleChip(
                                    label = if (supportsMiniMap) "高德缩略图(160px)" else "缩略图(仅模板2/3)",
                                    selected = showMiniMap && supportsMiniMap,
                                    enabled = supportsMiniMap,
                                    modifier = Modifier.weight(1f)
                                ) { showMiniMap = !showMiniMap }
                                CompactToggleChip("防篡改SHA256验真", antiTamperMode, Modifier.weight(1f)) {
                                    antiTamperMode = !antiTamperMode
                                    if (antiTamperMode) {
                                        locationManager.restoreRealGpsCoordinates(clearCustomText = true)
                                        locationManager.syncTrustedNetworkTime()
                                        Toast.makeText(context, "已开启防伪验真，坐标、地点与备注已锁定", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            if (showMiniMap && styleTemplate in listOf(1, 2)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    CompactToggleChip("缩略图: 卡片左侧", miniMapPosition == 0, Modifier.weight(1f)) { miniMapPosition = 0 }
                                    CompactToggleChip("缩略图: 左上角悬浮", miniMapPosition == 1, Modifier.weight(1f)) { miniMapPosition = 1 }
                                }
                            }
                            if (styleTemplate == 0) {
                                CompactToggleChip(
                                    label = if (showBottomScrim) "底部托底暗影：开（柔和渐变）" else "底部托底暗影：关（不压暗画面）",
                                    selected = showBottomScrim,
                                    modifier = Modifier.fillMaxWidth()
                                ) { showBottomScrim = !showBottomScrim }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                CompactToggleChip("扫码跳高德角标", showQrCode, Modifier.weight(1f)) { showQrCode = !showQrCode }
                                CompactToggleChip("地点名称", showLocation, Modifier.weight(1f)) { showLocation = !showLocation }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                CompactToggleChip("时间与星期", showTime, Modifier.weight(1f)) { showTime = !showTime }
                                CompactToggleChip("经纬度坐标", showCoords, Modifier.weight(1f)) { showCoords = !showCoords }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                CompactToggleChip("海拔高程(m)", showAltitude, Modifier.weight(1f)) { showAltitude = !showAltitude }
                                CompactToggleChip("罗盘方位角", showAzimuth, Modifier.weight(1f)) { showAzimuth = !showAzimuth }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                CompactToggleChip("坐标系标签", showCoordTag, Modifier.weight(1f)) { showCoordTag = !showCoordTag }
                                CompactToggleChip("精度误差(±m)", showAccuracy, Modifier.weight(1f)) { showAccuracy = !showAccuracy }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                CompactToggleChip("完整街道地址", showAddress, Modifier.weight(1f)) { showAddress = !showAddress }
                                CompactToggleChip("自定义备注", showNote && !antiTamperMode, Modifier.weight(1f), enabled = !antiTamperMode) { showNote = !showNote }
                            }
                        }

                        1 -> {
                            Text("5 款专业水印模板", color = Color(0xFF94A3B8), fontSize = 11.5.sp)
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                WatermarkRenderer.STYLE_NAMES.forEachIndexed { idx, title ->
                                    FilterChip(
                                        selected = styleTemplate == idx,
                                        onClick = { styleTemplate = idx },
                                        label = { Text("${idx + 1}. $title", fontSize = 12.sp) }
                                    )
                                }
                            }

                            WatermarkToggleRow(
                                label = "底部托底暗影（仅模板 1 大字时间生效，默认关闭）",
                                checked = showBottomScrim,
                                enabled = styleTemplate == 0,
                                onCheckedChange = { showBottomScrim = it }
                            )

                            Text("点缀主题色与字号缩放", color = Color(0xFF94A3B8), fontSize = 11.5.sp)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    listOf(Color(0xFF38BDF8), Color(0xFF34D399), Color(0xFFFBBF24), Color(0xFFF87171)).forEachIndexed { idx, c ->
                                        Box(
                                            modifier = Modifier
                                                .size(30.dp)
                                                .clip(CircleShape)
                                                .background(c)
                                                .border(if (accentColorIndex == idx) 2.5.dp else 0.dp, Color.White, CircleShape)
                                                .clickable { accentColorIndex = idx }
                                        )
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf(0.85f to "小号", 1.0f to "标准", 1.15f to "大号").forEach { (sc, lbl) ->
                                        CompactToggleChip(lbl, abs(fontScale - sc) < 0.05f) { fontScale = sc }
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("卡片底色不透明度: ${(bgAlpha * 100 / 255)}%", color = Color(0xFF94A3B8), fontSize = 11.5.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf(120 to "通透", 185 to "标准", 230 to "深色").forEach { (alphaVal, lbl) ->
                                        CompactToggleChip(lbl, abs(bgAlpha - alphaVal) < 20) { bgAlpha = alphaVal }
                                    }
                                }
                            }
                            Slider(
                                value = bgAlpha.toFloat(),
                                onValueChange = { bgAlpha = it.roundToInt().coerceIn(95, 240) },
                                valueRange = 95f..240f,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        2 -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("卡片标题", color = Color(0xFF94A3B8), fontSize = 11.5.sp)
                                if (customTitle.isNotEmpty()) {
                                    Text("清空", color = Color(0xFF38BDF8), fontSize = 11.5.sp, modifier = Modifier.clickable { customTitle = "" })
                                }
                            }
                            OutlinedTextField(
                                value = customTitle,
                                onValueChange = { customTitle = it },
                                placeholder = { Text("输入自定义卡片标题...") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                QUICK_TITLE_TAGS.forEach { tag ->
                                    SuggestionChip(onClick = { customTitle = tag }, label = { Text(tag, fontSize = 11.sp) })
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    if (antiTamperMode) "现场备注（防伪模式下已锁定禁止自定义）" else "现场备注",
                                    color = if (antiTamperMode) Color(0xFF34D399) else Color(0xFF94A3B8),
                                    fontSize = 11.5.sp
                                )
                                if (customNote.isNotEmpty() && !antiTamperMode) {
                                    Text("清空", color = Color(0xFF38BDF8), fontSize = 11.5.sp, modifier = Modifier.clickable { customNote = "" })
                                }
                            }
                            OutlinedTextField(
                                value = if (antiTamperMode) "" else customNote,
                                onValueChange = { customNote = it },
                                enabled = !antiTamperMode,
                                placeholder = { Text(if (antiTamperMode) "防伪模式下备注已锁定" else "例如：1号点位核验完成") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (!antiTamperMode) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    QUICK_NOTE_TAGS.forEach { tag ->
                                        SuggestionChip(
                                            onClick = {
                                                customNote = if (customNote.isBlank()) tag else "$customNote · $tag"
                                                showNote = true
                                            },
                                            label = { Text("+ $tag", fontSize = 11.sp) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ==================== MODULAR DIALOGS & SHEETS ====================
    if (showLocationSheet) {
        LocationCalibrationDialog(
            loc = locationManager.locationState.value,
            antiTamperMode = antiTamperMode,
            locationManager = locationManager,
            onDismiss = { showLocationSheet = false }
        )
    }

    if (showSettingsSheet) {
        CameraSettingsDialog(
            captureMode = captureMode,
            onCaptureModeChange = { captureMode = it },
            styleTemplate = styleTemplate,
            showMiniMap = showMiniMap,
            onShowMiniMapChange = { showMiniMap = it },
            antiTamperMode = antiTamperMode,
            onAntiTamperModeChange = { enabled ->
                antiTamperMode = enabled
                if (enabled) {
                    locationManager.restoreRealGpsCoordinates(clearCustomText = true)
                    locationManager.syncTrustedNetworkTime()
                }
            },
            showQrCode = showQrCode,
            onShowQrCodeChange = { showQrCode = it },
            coordSystem = coordSystem,
            onCoordSystemChange = { coordSystem = it },
            asyncOptimal = asyncOptimal,
            onAsyncOptimalChange = { asyncOptimal = it },
            accuracyThreshold = accuracyThreshold,
            onAccuracyThresholdChange = { accuracyThreshold = it },
            keepCleanOriginal = keepCleanOriginal,
            onKeepCleanOriginalChange = { keepCleanOriginal = it },
            showGridLines = showGridLines,
            onShowGridLinesChange = { showGridLines = it },
            bottomRight = bottomRight,
            onBottomRightChange = { bottomRight = it },
            photoCount = photos.size,
            onExportCsvLedger = {
                showSettingsSheet = false
                exportAndShareCsv(photos)
            },
            onDismiss = { showSettingsSheet = false }
        )
    }

    editingPhoto?.let { target ->
        WatermarkEditorBottomSheet(
            target = target,
            locationManager = locationManager,
            onDismiss = { editingPhoto = null },
            onSaved = { reloaded, _ ->
                photos = reloaded
                editingPhoto = null
            }
        )
    }

    if (pendingDeletePhotos.isNotEmpty()) {
        val targets = pendingDeletePhotos
        DeletePhotosConfirmDialog(
            count = targets.size,
            onDismiss = { pendingDeletePhotos = emptyList() },
            onConfirm = {
                pendingDeletePhotos = emptyList()
                scope.launch {
                    withContext(Dispatchers.IO) {
                        for (t in targets) {
                            WatermarkRenderer.deletePhotoEntryAndMediaStore(context, t)
                        }
                    }
                    val deletedIds = targets.map { it.id }.toSet()
                    photos = photos.filterNot { deletedIds.contains(it.id) }
                    if (justCapturedPhoto != null && deletedIds.contains(justCapturedPhoto?.id)) {
                        justCapturedPhoto = null
                    }
                    Toast.makeText(context, "已同步删除 ${targets.size} 张照片", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    if (showResetConfirmDialog) {
        ResetWatermarkConfirmDialog(
            onDismiss = { showResetConfirmDialog = false },
            onConfirm = {
                showResetConfirmDialog = false
                styleTemplate = 0; coordSystem = "GCJ-02"; useDms = false
                fontScale = 1.0f; accentColorIndex = 0; bgAlpha = 185
                showTime = true; showLocation = true; showCoords = true
                showCoordTag = true; showAccuracy = true; showAltitude = false
                showAzimuth = false; showQrCode = true; showAddress = false
                showNote = true; showBottomScrim = false; bottomRight = false
                Toast.makeText(context, "已恢复默认水印配置", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showVerifyDialog) {
        VerifyCenterDialog(
            verifyPayload = verifyPayload,
            onUpdatePayload = { verifyPayload = it },
            onOpenGallery = { showGalleryScreen = true },
            onDismiss = { showVerifyDialog = false }
        )
    }

    if (pendingBatchUris.isNotEmpty()) {
        val urisToProcess = pendingBatchUris
        BatchWatermarkConfigDialog(
            selectedCount = urisToProcess.size,
            currentLoc = locationManager.locationState.value,
            onDismiss = { pendingBatchUris = emptyList() },
            onConfirm = { options ->
                pendingBatchUris = emptyList()
                val baseCfg = buildCurrentTemplateBaseConfig()
                val locSnap = locationManager.locationState.value
                batchJob?.cancel()
                batchJob = scope.launch {
                    runBatchWatermarkPipeline(
                        context = context,
                        uris = urisToProcess,
                        locationManager = locationManager,
                        fallbackLoc = locSnap,
                        defaultCoordSystem = coordSystem,
                        templateBase = baseCfg,
                        options = options,
                        onProgress = { state -> batchProgressState = state },
                        onItemCreated = { newEntry ->
                            photos = listOf(newEntry) + photos
                        }
                    )
                }
            }
        )
    }

    batchProgressState?.let { prog ->
        BatchWatermarkProgressDialog(
            state = prog,
            onCancel = { batchJob?.cancel() },
            onDismissFinished = { batchProgressState = null }
        )
    }
}

@Composable
private fun GpsStatusPill(
    locationManager: FastLocationManager,
    deviceOrientation: Int,
    coordSystem: String,
    antiTamperMode: Boolean,
    captureMode: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val loc by locationManager.locationState
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xA60B132B))
            .border(0.8.dp, Color(0x4038BDF8), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    when {
                        loc.isManualCoord -> Color(0xFFF59E0B)
                        loc.hasValidCoordinates() -> Color(0xFF34D399)
                        else -> Color(0xFFFBBF24)
                    }
                )
        )
        val modeTag = when (deviceOrientation) {
            270 -> "左横"
            90 -> "右横"
            180 -> "倒置"
            else -> "竖屏"
        }
        val engineTag = if (antiTamperMode) (if (loc.ntpSynced) "NTP防伪" else "防伪锁") else if (captureMode == 1) "光学" else "极速"
        val accOrManual = if (loc.isManualCoord) "手动选点" else "±${String.format(Locale.US, "%.1fm", loc.accuracyMeters)}"
        Text(
            text = if (loc.hasValidCoordinates()) "$coordSystem · $accOrManual · $engineTag · $modeTag" else "定位中... · $engineTag",
            color = Color.White,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

/**
 * Hardware Optical Capture via CameraX ImageCapture with cancellation cleanup, EXIF rotation alignment, and 2560px cap.
 */
private suspend fun captureHardwareOpticalBitmap(
    context: Context,
    imageCapture: ImageCapture,
    maxLongEdgePx: Int = 2560
): Bitmap? = withContext(Dispatchers.IO) {
    val tempFile = File(context.cacheDir, "opt_cap_${System.currentTimeMillis()}.jpg")
    val savedOk = suspendCancellableCoroutine<Boolean> { cont ->
        cont.invokeOnCancellation {
            if (tempFile.exists()) tempFile.delete()
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    if (cont.isActive) cont.resume(true)
                }
                override fun onError(exception: ImageCaptureException) {
                    if (cont.isActive) cont.resume(false)
                }
            }
        )
    }
    if (!savedOk || !tempFile.exists()) {
        tempFile.delete()
        return@withContext null
    }
    try {
        val exifRot = WatermarkRenderer.readExifRotationDegrees(tempFile)
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(tempFile.absolutePath, boundsOpts)
        var sample = 1
        while (max(boundsOpts.outWidth, boundsOpts.outHeight) / sample > maxLongEdgePx * 1.4f) {
            sample *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val rawBmp = BitmapFactory.decodeFile(tempFile.absolutePath, decodeOpts)
        if (rawBmp == null) return@withContext null

        val uprightBmp = WatermarkRenderer.rotateBitmapIfNeeded(rawBmp, exifRot)
        if (uprightBmp !== rawBmp && !rawBmp.isRecycled) {
            rawBmp.recycle()
        }
        val curLongEdge = max(uprightBmp.width, uprightBmp.height)
        if (curLongEdge > maxLongEdgePx) {
            val scale = maxLongEdgePx.toFloat() / curLongEdge.toFloat()
            val targetW = (uprightBmp.width * scale).roundToInt().coerceAtLeast(1)
            val targetH = (uprightBmp.height * scale).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(uprightBmp, targetW, targetH, true).also {
                if (it !== uprightBmp && !uprightBmp.isRecycled) uprightBmp.recycle()
            }
        } else {
            uprightBmp
        }
    } catch (_: Exception) {
        null
    } finally {
        if (tempFile.exists()) tempFile.delete()
    }
}

@Composable
fun WysiwygLiveWatermarkCanvas(
    locationManager: FastLocationManager,
    deviceOrientation: Int,
    styleTemplate: Int,
    customTitle: String,
    coordSystem: String,
    useDms: Boolean,
    customNote: String,
    showTime: Boolean,
    showLocation: Boolean,
    showCoords: Boolean,
    showCoordTag: Boolean,
    showAccuracy: Boolean,
    showAltitude: Boolean,
    showAzimuth: Boolean,
    showQrCode: Boolean,
    showAddress: Boolean,
    showNote: Boolean,
    showMiniMap: Boolean,
    miniMapPosition: Int,
    showBottomScrim: Boolean,
    antiTamperMode: Boolean,
    bottomRight: Boolean,
    bgAlpha: Int,
    fontScale: Float,
    accentColorIndex: Int
) {
    val loc by locationManager.locationState
    var timeTick by remember { mutableStateOf(WatermarkConfig.currentTimeStrings(Date(loc.trustedCurrentTimeMillis()))) }
    LaunchedEffect(styleTemplate, showTime, antiTamperMode, loc.ntpOffsetMs) {
        if (!showTime && !antiTamperMode) return@LaunchedEffect
        while (true) {
            val next = WatermarkConfig.currentTimeStrings(Date(loc.trustedCurrentTimeMillis()))
            if (styleTemplate != 0 || antiTamperMode || next.first != timeTick.first || next.second != timeTick.second) {
                timeTick = next
            }
            delay(1000L)
        }
    }

    val (liveHm, liveDateWeek, liveFullTs) = timeTick
    val liveCfg = remember(
        styleTemplate, customTitle, coordSystem, useDms,
        loc, customNote, liveHm, liveDateWeek, liveFullTs,
        showTime, showLocation, showCoords, showCoordTag,
        showAccuracy, showAltitude, showAzimuth, showQrCode,
        showAddress, showNote, showMiniMap, miniMapPosition, showBottomScrim,
        antiTamperMode, bottomRight, bgAlpha, fontScale, accentColorIndex
    ) {
        val activeGcjLat = if (antiTamperMode) loc.rawGcjLat else loc.gcjLat
        val activeGcjLon = if (antiTamperMode) loc.rawGcjLon else loc.gcjLon
        val activeWgsLat = if (antiTamperMode) loc.rawWgsLat else loc.wgsLat
        val activeWgsLon = if (antiTamperMode) loc.rawWgsLon else loc.wgsLon
        val hasFix = (activeGcjLat != 0.0 || activeGcjLon != 0.0)
        WatermarkConfig(
            styleTemplate = styleTemplate,
            customTitle = customTitle,
            coordSystem = coordSystem,
            useDms = useDms,
            gcjLat = activeGcjLat,
            gcjLon = activeGcjLon,
            wgsLat = activeWgsLat,
            wgsLon = activeWgsLon,
            origGcjLat = activeGcjLat,
            origGcjLon = activeGcjLon,
            accuracyMeters = loc.accuracyMeters,
            altitudeMeters = loc.altitudeMeters,
            azimuthDeg = loc.azimuthDeg,
            azimuthText = loc.azimuthText,
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
            showAltitude = showAltitude,
            showAzimuth = showAzimuth,
            showQrCode = showQrCode,
            showAddress = showAddress,
            showNote = showNote,
            showMiniMap = showMiniMap,
            miniMapPosition = miniMapPosition,
            showBottomScrim = showBottomScrim,
            antiTamperMode = antiTamperMode,
            ntpSynced = loc.ntpSynced,
            antiTamperHash = if (antiTamperMode && hasFix) {
                QrCodeGenerator.computeAntiTamperHash(activeGcjLat, activeGcjLon, liveFullTs, loc.shortPoi, customNote)
            } else "",
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
                    nativeCanvas.translate(viewW.toFloat(), 0f)
                    nativeCanvas.rotate(90f)
                    WatermarkRenderer.drawWatermarkOverlay(nativeCanvas, viewH, viewW, liveCfg, isLivePreview = true)
                }
                90 -> {
                    nativeCanvas.translate(0f, viewH.toFloat())
                    nativeCanvas.rotate(-90f)
                    WatermarkRenderer.drawWatermarkOverlay(nativeCanvas, viewH, viewW, liveCfg, isLivePreview = true)
                }
                180 -> {
                    nativeCanvas.translate(viewW.toFloat(), viewH.toFloat())
                    nativeCanvas.rotate(180f)
                    WatermarkRenderer.drawWatermarkOverlay(nativeCanvas, viewW, viewH, liveCfg, isLivePreview = true)
                }
                else -> {
                    WatermarkRenderer.drawWatermarkOverlay(nativeCanvas, viewW, viewH, liveCfg, isLivePreview = true)
                }
            }
            nativeCanvas.restore()
        }
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
