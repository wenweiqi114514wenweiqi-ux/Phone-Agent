package com.phoneagent.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * HSV 色盘：外圈选色相，内圆选饱和度，下方滑杆选亮度。
 * 不引第三方取色库，全部自绘，行为与常见设计工具一致。
 */
@Composable
fun ColorWheelDialog(
    title: String,
    initial: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val hsv = remember(initial) {
        val out = FloatArray(3)
        android.graphics.Color.colorToHSV(initial.toInt(), out)
        out
    }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2].coerceAtLeast(0.45f)) }

    val current = Color.hsv(hue, sat, value)
    val hex = "#%02X%02X%02X".format(
        (current.red * 255).roundToInt(),
        (current.green * 255).roundToInt(),
        (current.blue * 255).roundToInt(),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                // 外圈色相 + 内圆饱和度
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .pointerInput(Unit) {
                            fun handle(pos: Offset) {
                                val cx = size.width / 2f
                                val cy = size.height / 2f
                                val ringW = 26.dp.toPx()
                                val outer = min(size.width, size.height) / 2f - 12.dp.toPx()
                                val inner = outer - ringW
                                val dx = pos.x - cx
                                val dy = pos.y - cy
                                val dist = sqrt(dx * dx + dy * dy)
                                if (dist >= inner) {
                                    val deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                                    hue = (deg + 360f) % 360f
                                } else {
                                    sat = (dist / inner).coerceIn(0f, 1f)
                                    val deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                                    if (dist > inner * 0.06f) hue = (deg + 360f) % 360f
                                }
                            }
                            detectTapGestures { handle(it) }
                        }
                        .pointerInput(Unit) {
                            fun handle(pos: Offset) {
                                val cx = size.width / 2f
                                val cy = size.height / 2f
                                val ringW = 26.dp.toPx()
                                val outer = min(size.width, size.height) / 2f - 12.dp.toPx()
                                val inner = outer - ringW
                                val dx = pos.x - cx
                                val dy = pos.y - cy
                                val dist = sqrt(dx * dx + dy * dy)
                                if (dist >= inner) {
                                    val deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                                    hue = (deg + 360f) % 360f
                                } else {
                                    sat = (dist / inner).coerceIn(0f, 1f)
                                }
                            }
                            detectDragGestures(
                                onDragStart = { handle(it) },
                                onDrag = { change, _ -> handle(change.position) },
                            )
                        },
                ) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val ringW = 26.dp.toPx()
                    val outer = min(size.width, size.height) / 2f - 12.dp.toPx()
                    val inner = outer - ringW

                    // 色相环
                    val hues = (0..12).map { Color.hsv(it * 30f % 360f, 1f, 1f) }
                    drawCircle(
                        brush = Brush.sweepGradient(hues, center = Offset(cx, cy)),
                        radius = outer - ringW / 2f,
                        center = Offset(cx, cy),
                        style = Stroke(width = ringW),
                    )
                    // 饱和度内圆：中心白 → 边缘当前色相
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color.White, Color.hsv(hue, 1f, 1f)),
                            center = Offset(cx, cy),
                            radius = inner,
                        ),
                        radius = inner,
                        center = Offset(cx, cy),
                    )
                    // 亮度压暗
                    if (value < 1f) {
                        drawCircle(
                            color = Color.Black.copy(alpha = 1f - value),
                            radius = inner,
                            center = Offset(cx, cy),
                        )
                    }
                    // 色相指示点
                    val hueRad = Math.toRadians(hue.toDouble())
                    val ringR = outer - ringW / 2f
                    drawCircle(
                        color = Color.White,
                        radius = 6.dp.toPx(),
                        center = Offset(
                            cx + (ringR * Math.cos(hueRad)).toFloat(),
                            cy + (ringR * Math.sin(hueRad)).toFloat(),
                        ),
                    )
                    // 饱和度指示点
                    val satR = inner * sat
                    drawCircle(
                        color = Color.White,
                        radius = 6.dp.toPx(),
                        center = Offset(
                            cx + (satR * Math.cos(hueRad)).toFloat(),
                            cy + (satR * Math.sin(hueRad)).toFloat(),
                        ),
                    )
                }

                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(current)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        hex,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "色相 ${hue.roundToInt()}°",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "亮度",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    valueRange = 0.05f..1f,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(current.toArgb().toLong()) }) { Text("应用") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 一行可点的颜色槽：色块 + 名称 + 当前色值 + 恢复默认。 */
@Composable
internal fun ColorSlotRow(
    label: String,
    hint: String,
    color: Color,
    customized: Boolean,
    onClick: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            modifier = Modifier.size(34.dp),
            shape = CircleShape,
            color = color,
            onClick = onClick,
        ) {}
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (customized) "$hint · 已自定义" else hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (customized) {
            TextButton(onClick = onReset) {
                Text("默认", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
