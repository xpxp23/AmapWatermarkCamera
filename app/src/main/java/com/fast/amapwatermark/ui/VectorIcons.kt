package com.fast.amapwatermark.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun CompactToggleChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val bgColor = when {
        !enabled -> Color(0xFF111827)
        selected -> Color(0xFF0369A1)
        else -> Color(0xFF1E293B)
    }
    val borderColor = when {
        !enabled -> Color(0xFF1F2937)
        selected -> Color(0xFF38BDF8)
        else -> Color(0xFF334155)
    }
    val textColor = when {
        !enabled -> Color(0xFF475569)
        selected -> Color.White
        else -> Color(0xFF94A3B8)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 11.5.sp,
            fontWeight = if (selected && enabled) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun QuickPresetButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1E293B))
            .border(1.dp, Color(0xFF38BDF8), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color(0xFF38BDF8), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
fun WatermarkToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = if (enabled) Color.White else Color(0xFF64748B),
            fontSize = 12.5.sp,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
fun CameraTopBarItem(
    label: String,
    rotationProvider: () -> Float = { 0f },
    onClick: () -> Unit,
    iconContent: @Composable () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .graphicsLayer { rotationZ = rotationProvider() }
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        Box(modifier = Modifier.size(26.dp), contentAlignment = Alignment.Center) {
            iconContent()
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(text = label, color = Color.White, fontSize = 10.5.sp, maxLines = 1)
    }
}

@Composable
fun CameraBottomBarItem(
    label: String,
    active: Boolean = false,
    rotationProvider: () -> Float = { 0f },
    onClick: () -> Unit,
    iconContent: @Composable () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .graphicsLayer { rotationZ = rotationProvider() }
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp)
    ) {
        Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            iconContent()
        }
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = label,
            color = if (active) Color(0xFF38BDF8) else Color.White,
            fontSize = 11.5.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

@Composable
fun ShieldVerifyVectorIcon(tint: Color = Color(0xFF34D399), modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = 1.9.dp.toPx()
        val path = Path().apply {
            moveTo(w * 0.50f, h * 0.10f)
            lineTo(w * 0.84f, h * 0.25f)
            lineTo(w * 0.84f, h * 0.52f)
            quadraticBezierTo(w * 0.84f, h * 0.80f, w * 0.50f, h * 0.92f)
            quadraticBezierTo(w * 0.16f, h * 0.80f, w * 0.16f, h * 0.52f)
            lineTo(w * 0.16f, h * 0.25f)
            close()
        }
        drawPath(path = path, color = tint, style = Stroke(width = stroke, join = StrokeJoin.Round))
        drawLine(tint, Offset(w * 0.36f, h * 0.52f), Offset(w * 0.47f, h * 0.63f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.47f, h * 0.63f), Offset(w * 0.66f, h * 0.40f), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
fun CaptureModeVectorIcon(isOptical: Boolean) {
    val tint = if (isOptical) Color(0xFF38BDF8) else Color(0xFFFBBF24)
    Canvas(modifier = Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.9.dp.toPx()
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.10f, h * 0.22f),
            size = Size(w * 0.80f, h * 0.58f),
            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
            style = Stroke(width = stroke)
        )
        drawCircle(
            color = tint,
            radius = w * 0.18f,
            center = Offset(w * 0.5f, h * 0.51f),
            style = Stroke(width = stroke)
        )
        if (isOptical) {
            drawCircle(color = tint, radius = w * 0.06f, center = Offset(w * 0.5f, h * 0.51f))
        }
    }
}

@Composable
fun GridLinesVectorIcon(active: Boolean) {
    val tint = if (active) Color(0xFF38BDF8) else Color.White
    Canvas(modifier = Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.8.dp.toPx()
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.1f, h * 0.1f),
            size = Size(w * 0.8f, h * 0.8f),
            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
            style = Stroke(width = stroke)
        )
        drawLine(tint, Offset(w * 0.37f, h * 0.1f), Offset(w * 0.37f, h * 0.9f), strokeWidth = stroke)
        drawLine(tint, Offset(w * 0.63f, h * 0.1f), Offset(w * 0.63f, h * 0.9f), strokeWidth = stroke)
        drawLine(tint, Offset(w * 0.1f, h * 0.37f), Offset(w * 0.9f, h * 0.37f), strokeWidth = stroke)
        drawLine(tint, Offset(w * 0.1f, h * 0.63f), Offset(w * 0.9f, h * 0.63f), strokeWidth = stroke)
    }
}

@Composable
fun GearVectorIcon() {
    Canvas(modifier = Modifier.size(24.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val outerR = size.minDimension * 0.42f
        val innerR = size.minDimension * 0.20f
        drawCircle(color = Color.White, radius = outerR, center = Offset(cx, cy), style = Stroke(width = 2.0.dp.toPx()))
        drawCircle(color = Color.White, radius = innerR, center = Offset(cx, cy), style = Stroke(width = 1.8.dp.toPx()))
        for (i in 0 until 8) {
            val angle = Math.toRadians((i * 45).toDouble())
            val x1 = cx + (outerR - 1.dp.toPx()) * cos(angle).toFloat()
            val y1 = cy + (outerR - 1.dp.toPx()) * sin(angle).toFloat()
            val x2 = cx + (outerR + 3.0.dp.toPx()) * cos(angle).toFloat()
            val y2 = cy + (outerR + 3.0.dp.toPx()) * sin(angle).toFloat()
            drawLine(color = Color.White, start = Offset(x1, y1), end = Offset(x2, y2), strokeWidth = 2.4.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

@Composable
fun TimerVectorIcon(seconds: Int) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(26.dp)) {
        Canvas(modifier = Modifier.size(23.dp)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = size.minDimension * 0.42f
            drawArc(
                color = if (seconds > 0) Color(0xFF38BDF8) else Color.White,
                startAngle = -60f,
                sweepAngle = 300f,
                useCenter = false,
                style = Stroke(width = 2.0.dp.toPx(), cap = StrokeCap.Round)
            )
            drawLine(
                color = if (seconds > 0) Color(0xFF38BDF8) else Color.White,
                start = Offset(cx, cy),
                end = Offset(cx + r * 0.55f, cy - r * 0.45f),
                strokeWidth = 2.0.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
        if (seconds > 0) {
            Text(
                text = seconds.toString(),
                color = Color(0xFF38BDF8),
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        }
    }
}

@Composable
fun FlashVectorIcon(statusText: String, active: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(20.dp)) {
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
                style = Stroke(width = 1.9.dp.toPx(), join = StrokeJoin.Round)
            )
        }
        if (statusText.isNotEmpty()) {
            Text(
                text = statusText,
                color = if (active) Color(0xFFFACC15) else Color.White,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun FlipCameraVectorIcon() {
    Canvas(modifier = Modifier.size(23.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 2.0.dp.toPx()
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(w * 0.12f, h * 0.22f),
            size = Size(w * 0.76f, h * 0.58f),
            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
            style = Stroke(width = stroke)
        )
        drawLine(Color.White, Offset(w * 0.62f, h * 0.12f), Offset(w * 0.74f, h * 0.22f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(Color.White, Offset(w * 0.38f, h * 0.90f), Offset(w * 0.26f, h * 0.80f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawCircle(color = Color.White, radius = 2.0.dp.toPx(), center = Offset(w * 0.34f, h * 0.51f))
        drawCircle(color = Color.White, radius = 2.0.dp.toPx(), center = Offset(w * 0.66f, h * 0.51f))
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
    Canvas(modifier = Modifier.size(26.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.88f, h * 0.14f)
            lineTo(w * 0.12f, h * 0.46f)
            lineTo(w * 0.46f, h * 0.56f)
            lineTo(w * 0.58f, h * 0.88f)
            close()
        }
        drawPath(path = path, color = Color.White, style = Stroke(width = 2.1.dp.toPx(), join = StrokeJoin.Round))
    }
}

/**
 * Stacked photos + watermark stamp vector icon for the Batch Watermark entry (replaces removed Filter icon).
 */
@Composable
fun BatchWatermarkVectorIcon(tint: Color = Color.White, modifier: Modifier = Modifier.size(26.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = 2.0.dp.toPx()
        // Back stacked frame
        drawRoundRect(
            color = tint.copy(alpha = 0.65f),
            topLeft = Offset(w * 0.24f, h * 0.10f),
            size = Size(w * 0.64f, h * 0.58f),
            cornerRadius = CornerRadius(3.5.dp.toPx(), 3.5.dp.toPx()),
            style = Stroke(width = stroke)
        )
        // Front frame
        drawRoundRect(
            color = Color(0xFF000000),
            topLeft = Offset(w * 0.12f, h * 0.28f),
            size = Size(w * 0.64f, h * 0.60f),
            cornerRadius = CornerRadius(3.5.dp.toPx(), 3.5.dp.toPx())
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.12f, h * 0.28f),
            size = Size(w * 0.64f, h * 0.60f),
            cornerRadius = CornerRadius(3.5.dp.toPx(), 3.5.dp.toPx()),
            style = Stroke(width = stroke)
        )
        // Plus badge inside front frame
        val cx = w * 0.44f
        val cy = h * 0.58f
        val arm = w * 0.12f
        drawLine(Color(0xFF38BDF8), Offset(cx - arm, cy), Offset(cx + arm, cy), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(Color(0xFF38BDF8), Offset(cx, cy - arm), Offset(cx, cy + arm), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
fun WatermarkStampVectorIcon() {
    Canvas(modifier = Modifier.size(26.dp)) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(w * 0.12f, h * 0.16f),
            size = Size(w * 0.76f, h * 0.68f),
            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
            style = Stroke(width = 2.1.dp.toPx())
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

@Composable
fun ShareVectorIcon(tint: Color = Color.White, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = 1.9.dp.toPx()
        // Box with open top
        drawLine(tint, Offset(w * 0.22f, h * 0.45f), Offset(w * 0.22f, h * 0.84f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.22f, h * 0.84f), Offset(w * 0.78f, h * 0.84f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.78f, h * 0.84f), Offset(w * 0.78f, h * 0.45f), strokeWidth = stroke, cap = StrokeCap.Round)
        // Up arrow
        drawLine(tint, Offset(w * 0.50f, h * 0.14f), Offset(w * 0.50f, h * 0.64f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.50f, h * 0.14f), Offset(w * 0.34f, h * 0.30f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.50f, h * 0.14f), Offset(w * 0.66f, h * 0.30f), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
fun NavigationVectorIcon(tint: Color = Color(0xFF38BDF8), modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.86f, h * 0.14f)
            lineTo(w * 0.14f, h * 0.46f)
            lineTo(w * 0.48f, h * 0.54f)
            lineTo(w * 0.56f, h * 0.86f)
            close()
        }
        drawPath(path = path, color = tint, style = Stroke(width = 1.9.dp.toPx(), join = StrokeJoin.Round))
    }
}

@Composable
fun EditPenVectorIcon(tint: Color = Color(0xFFFDE68A), modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = 1.9.dp.toPx()
        val path = Path().apply {
            moveTo(w * 0.20f, h * 0.80f)
            lineTo(w * 0.26f, h * 0.58f)
            lineTo(w * 0.68f, h * 0.16f)
            lineTo(w * 0.84f, h * 0.32f)
            lineTo(w * 0.42f, h * 0.74f)
            close()
        }
        drawPath(path = path, color = tint, style = Stroke(width = stroke, join = StrokeJoin.Round))
    }
}

@Composable
fun LockVectorIcon(tint: Color = Color(0xFF34D399), modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = 1.9.dp.toPx()
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.22f, h * 0.46f),
            size = Size(w * 0.56f, h * 0.40f),
            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
            style = Stroke(width = stroke)
        )
        drawArc(
            color = tint,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.32f, h * 0.16f),
            size = Size(w * 0.36f, h * 0.36f),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
    }
}

@Composable
fun InfoCircleVectorIcon(tint: Color = Color.White, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = 1.9.dp.toPx()
        drawCircle(color = tint, radius = size.minDimension * 0.40f, style = Stroke(width = stroke))
        drawCircle(color = tint, radius = 1.5.dp.toPx(), center = Offset(w * 0.50f, h * 0.33f))
        drawLine(tint, Offset(w * 0.50f, h * 0.46f), Offset(w * 0.50f, h * 0.70f), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
fun TrashVectorIcon(tint: Color = Color(0xFFF87171), modifier: Modifier = Modifier.size(20.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = 1.8.dp.toPx()
        drawLine(tint, Offset(w * 0.18f, h * 0.26f), Offset(w * 0.82f, h * 0.26f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.38f, h * 0.16f), Offset(w * 0.62f, h * 0.16f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.26f, h * 0.26f),
            size = Size(w * 0.48f, h * 0.58f),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            style = Stroke(width = stroke)
        )
        drawLine(tint, Offset(w * 0.42f, h * 0.42f), Offset(w * 0.42f, h * 0.70f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.58f, h * 0.42f), Offset(w * 0.58f, h * 0.70f), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
fun ExportCsvVectorIcon(tint: Color = Color(0xFF38BDF8), modifier: Modifier = Modifier.size(20.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = 1.8.dp.toPx()
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.18f, h * 0.12f),
            size = Size(w * 0.64f, h * 0.76f),
            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
            style = Stroke(width = stroke)
        )
        drawLine(tint, Offset(w * 0.30f, h * 0.36f), Offset(w * 0.70f, h * 0.36f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.30f, h * 0.52f), Offset(w * 0.70f, h * 0.52f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.30f, h * 0.68f), Offset(w * 0.56f, h * 0.68f), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}
