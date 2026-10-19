package com.fast.amapwatermark

import android.Manifest
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fast.amapwatermark.util.*
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class PhotoEntry(
    val id: String,
    val cleanFile: File,
    val watermarkedFile: File,
    var config: WatermarkConfig
)

class MainActivity : ComponentActivity() {
    private lateinit var fastLocationManager: FastLocationManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fastLocationManager = FastLocationManager(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                WatermarkCameraApp(fastLocationManager)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        fastLocationManager.stopUpdates()
    }
}

@Composable
fun WatermarkCameraApp(locationManager: FastLocationManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentTab by remember { mutableStateOf(0) } // 0: Camera, 1: Gallery, 2: Settings
    var defaultCoordSys by remember { mutableStateOf("GCJ-02") }
    var useDms by remember { mutableStateOf(false) }
    var asyncConvergence by remember { mutableStateOf(true) }
    var liveNote by remember { mutableStateOf("现场点位核验 · 高德零偏差") }
    var liveAddress by remember { mutableStateOf("北京市东城区东长安街天安门广场东侧") }
    var photos by remember { mutableStateOf(listOf<PhotoEntry>()) }
    var editingPhoto by remember { mutableStateOf<PhotoEntry?>(null) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        locationManager.startHighFrequencyUpdates()
    }

    LaunchedEffect(Unit) {
        permLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    val loc = locationManager.latestLocation

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F172A))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("极速高德经纬水印相机", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("當前坐标系: $defaultCoordSys · 精度 ±${loc.accuracyMeters}m", color = Color(0xFF38BDF8), fontSize = 12.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { currentTab = 0 }) { Text("拍摄", color = if (currentTab == 0) Color(0xFF38BDF8) else Color.Gray) }
                    TextButton(onClick = { currentTab = 1 }) { Text("相册(${photos.size})", color = if (currentTab == 1) Color(0xFF38BDF8) else Color.Gray) }
                    TextButton(onClick = { currentTab = 2 }) { Text("设置", color = if (currentTab == 2) Color(0xFF38BDF8) else Color.Gray) }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize().background(Color(0xFF090D16))) {
            when (currentTab) {
                0 -> Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    // Live Watermark Overlay Preview Card (Click to quick-customize)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xCC0F172A)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("高德精准经纬水印 [$defaultCoordSys]", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Spacer(modifier = Modifier.height(6.dp))
                            val shownLat = if (defaultCoordSys == "WGS-84") loc.wgsLat else loc.gcjLat
                            val shownLon = if (defaultCoordSys == "WGS-84") loc.wgsLon else loc.gcjLon
                            Text(
                                "经纬：${CoordinateConverter.formatCoordinate(shownLat, shownLon, useDms)}",
                                color = Color.White,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            )
                            Text("地点：$liveAddress", color = Color.White, fontSize = 13.sp)
                            Text("备注：$liveNote", color = Color(0xFFFDE68A), fontSize = 13.sp)
                        }
                    }

                    // Quick Customizer Inputs
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = liveAddress,
                            onValueChange = { liveAddress = it },
                            label = { Text("水印地址描述") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = liveNote,
                            onValueChange = { liveNote = it },
                            label = { Text("现场备注说明") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    defaultCoordSys = if (defaultCoordSys == "GCJ-02") "WGS-84" else "GCJ-02"
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("切换坐标系 ($defaultCoordSys)")
                            }
                            Button(
                                onClick = {
                                    AmapLauncher.jumpToAmapMarker(context, loc.gcjLat, loc.gcjLon, liveNote)
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("当前位置跳高德")
                            }
                        }
                    }
                }

                1 -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(photos) { item ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                val bmp = remember(item.watermarkedFile.lastModified()) {
                                    BitmapFactory.decodeFile(item.watermarkedFile.absolutePath)
                                }
                                if (bmp != null) {
                                    Image(bitmap = bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth().height(200.dp))
                                }
                                Text(
                                    "高德 GCJ-02: ${item.config.gcjLat}, ${item.config.gcjLon}",
                                    color = Color(0xFF38BDF8),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = {
                                        AmapLauncher.jumpToAmapMarker(context, item.config.gcjLat, item.config.gcjLon, item.config.note)
                                    }) { Text("📍 跳高德打点") }
                                    OutlinedButton(onClick = {
                                        AmapLauncher.jumpToAmapNavigation(context, item.config.gcjLat, item.config.gcjLon, item.config.note)
                                    }) { Text("🧭 导航") }
                                    OutlinedButton(onClick = {
                                        editingPhoto = item
                                    }) { Text("✎ 改水印") }
                                }
                            }
                        }
                    }
                }

                2 -> Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("默认使用高德 GCJ-02 坐标系", color = Color.White)
                        Switch(checked = defaultCoordSys == "GCJ-02", onCheckedChange = { defaultCoordSys = if (it) "GCJ-02" else "WGS-84" })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("经纬度使用度分秒 (DMS) 格式", color = Color.White)
                        Switch(checked = useDms, onCheckedChange = { useDms = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("秒拍不阻塞 + 后台异步寻优合成", color = Color.White)
                        Switch(checked = asyncConvergence, onCheckedChange = { asyncConvergence = it })
                    }
                }
            }

            // Non-Destructive Watermark Editor Dialog (Q10 & Q11)
            editingPhoto?.let { target ->
                var editNote by remember(target) { mutableStateOf(target.config.note) }
                var editAddr by remember(target) { mutableStateOf(target.config.address) }
                var editSys by remember(target) { mutableStateOf(target.config.coordSystem) }
                var editLat by remember(target) { mutableStateOf(target.config.gcjLat.toString()) }
                var editLon by remember(target) { mutableStateOf(target.config.gcjLon.toString()) }

                AlertDialog(
                    onDismissRequest = { editingPhoto = null },
                    title = { Text("无损重绘修改水印 (图层分离)") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = editLat, onValueChange = { editLat = it }, label = { Text("高德纬度 GCJ-02") })
                            OutlinedTextField(value = editLon, onValueChange = { editLon = it }, label = { Text("高德经度 GCJ-02") })
                            OutlinedTextField(value = editAddr, onValueChange = { editAddr = it }, label = { Text("详细地址") })
                            OutlinedTextField(value = editNote, onValueChange = { editNote = it }, label = { Text("备注内容") })
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    editSys = if (editSys == "GCJ-02") "WGS-84" else "GCJ-02"
                                }) { Text("坐标系: $editSys") }
                                TextButton(onClick = {
                                    editLat = target.config.origGcjLat.toString()
                                    editLon = target.config.origGcjLon.toString()
                                }) { Text("恢复原始实拍坐标") }
                            }
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            val newGcjLat = editLat.toDoubleOrNull() ?: target.config.origGcjLat
                            val newGcjLon = editLon.toDoubleOrNull() ?: target.config.origGcjLon
                            val newWgs = CoordinateConverter.gcj02ToWgs84(newGcjLat, newGcjLon)
                            val updatedCfg = target.config.copy(
                                coordSystem = editSys,
                                gcjLat = newGcjLat,
                                gcjLon = newGcjLon,
                                wgsLat = newWgs.lat,
                                wgsLon = newWgs.lon,
                                address = editAddr,
                                note = editNote
                            )
                            // Re-render onto clean base image without quality loss
                            val cleanBmp = BitmapFactory.decodeFile(target.cleanFile.absolutePath)
                            if (cleanBmp != null) {
                                val recomposited = WatermarkRenderer.renderWatermarkedBitmap(cleanBmp, updatedCfg)
                                WatermarkRenderer.saveJpegWithDualTrackExif(recomposited, target.watermarkedFile, updatedCfg)
                                target.config = updatedCfg
                            }
                            editingPhoto = null
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
    }
}
