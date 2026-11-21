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
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.OutlinedTextField
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
import kotlin.math.roundToInt

/**
 * 取色对话框：方块选「饱和度 × 亮度」，横条选「色相」。
 *
 * 之所以不用同心圆色盘：圆盘里"角度"同时承担色相和饱和度，点按与拖拽的处理稍有出入就会出现
 * 手指一动颜色乱跳；方块 + 色相条这两条轴互相独立，行为可预测，也不存在点到环外角落误触的问题。
 */
@Composable
fun ColorWheelDialog(
    title: String,
    initial: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val hsv = remember(initial) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toInt(), it) }
    }
    // 不再把亮度强行抬到 0.45：暗色就是暗色，打开后什么都不改、直接应用也要保持原值
    var hue by remember { mutableFloatStateOf(hsv[0].coerceIn(0f, 360f)) }
    var sat by remember { mutableFloatStateOf(hsv[1].coerceIn(0f, 1f)) }
    var value by remember { mutableFloatStateOf(hsv[2].coerceIn(0f, 1f)) }

    val current = Color.hsv(hue, sat, value)
    val currentHex = current.toHexString()

    // 十六进制输入框：手动输入与色盘互相回填，但不互相覆盖正在输入的内容
    var hexInput by remember { mutableStateOf(currentHex) }
    var syncedHex by remember { mutableStateOf(currentHex) }

    fun applyHsv(h: Float, s: Float, v: Float) {
        hue = h.coerceIn(0f, 360f)
        sat = s.coerceIn(0f, 1f)
        value = v.coerceIn(0f, 1f)
        val hex = Color.hsv(hue, sat, value).toHexString()
        if (hexInput.equals(syncedHex, ignoreCase = true)) {
            hexInput = hex
            syncedHex = hex
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                // ---- 饱和度 × 亮度 方块：横轴饱和度，纵轴亮度（上亮下暗）----
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(172.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .pointerInput(hue) {
                            fun pick(pos: Offset) {
                                val x = (pos.x / size.width).coerceIn(0f, 1f)
                                val y = (pos.y / size.height).coerceIn(0f, 1f)
                                applyHsv(hue, x, 1f - y)
                            }
                            detectTapGestures { pick(it) }
                        }
                        .pointerInput(hue) {
                            fun pick(pos: Offset) {
                                val x = (pos.x / size.width).coerceIn(0f, 1f)
                                val y = (pos.y / size.height).coerceIn(0f, 1f)
                                applyHsv(hue, x, 1f - y)
                            }
                            detectDragGestures(
                                onDragStart = { pick(it) },
                                onDrag = { change, _ -> pick(change.position) },
                            )
                        },
                ) {
                    // 底色：当前色相满饱和
                    drawRect(color = Color.hsv(hue, 1f, 1f))
                    // 横向：白 → 透明（饱和度）
                    drawRect(
                        brush = Brush.horizontalGradient(
                            listOf(Color.White, Color.White.copy(alpha = 0f)),
                        ),
                    )
                    // 纵向：透明 → 黑（亮度）
                    drawRect(
                        brush = Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black),
                        ),
                    )
                    // 指示点
                    val px = size.width * sat
                    val py = size.height * (1f - value)
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.55f),
                        radius = 9.dp.toPx(),
                        center = Offset(px, py),
                        style = Stroke(width = 3.dp.toPx()),
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 9.dp.toPx(),
                        center = Offset(px, py),
                        style = Stroke(width = 1.6f.dp.toPx()),
                    )
                }

                Spacer(Modifier.height(14.dp))

                // ---- 色相条 ----
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(26.dp)
                        .pointerInput(Unit) {
                            fun pick(pos: Offset) {
                                val f = (pos.x / size.width).coerceIn(0f, 1f)
                                applyHsv(f * 360f, sat, value)
                            }
                            detectTapGestures { pick(it) }
                        }
                        .pointerInput(Unit) {
                            fun pick(pos: Offset) {
                                val f = (pos.x / size.width).coerceIn(0f, 1f)
                                applyHsv(f * 360f, sat, value)
                            }
                            detectDragGestures(
                                onDragStart = { pick(it) },
                                onDrag = { change, _ -> pick(change.position) },
                            )
                        },
                ) {
                    val barH = size.height
                    val radius = barH / 2f
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            (0..12).map { Color.hsv((it * 30f) % 360f, 1f, 1f) },
                        ),
                        size = size,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                    )
                    // 色相指针
                    val x = (hue / 360f) * size.width
                    drawCircle(
                        color = Color.White,
                        radius = radius - 1.dp.toPx(),
                        center = Offset(x.coerceIn(radius, size.width - radius), barH / 2f),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }

                Spacer(Modifier.height(14.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(current)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = hexInput,
                        onValueChange = { text ->
                            val cleaned = text.uppercase().filter { it == '#' || it.isDigit() || it in 'A'..'F' }.take(7)
                            hexInput = if (cleaned.startsWith("#")) cleaned else "#" + cleaned.trimStart('#')
                            val parsed = parseHex(hexInput)
                            if (parsed != null) {
                                val out = FloatArray(3)
                                android.graphics.Color.colorToHSV(parsed.toInt(), out)
                                hue = out[0]
                                sat = out[1]
                                value = out[2]
                                syncedHex = hexInput
                            }
                        },
                        singleLine = true,
                        label = { Text("色值") },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    "饱和度 ${(sat * 100).roundToInt()}% · 亮度 ${(value * 100).roundToInt()}% · 色相 ${hue.roundToInt()}°",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )

                Spacer(Modifier.height(12.dp))

                // ---- 快捷色：常用 AI 渐变与中性色，点一下直接套用 ----
                Text(
                    "快捷色",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickColors.forEach { argb ->
                        val c = Color(argb)
                        Surface(
                            modifier = Modifier.size(26.dp),
                            shape = CircleShape,
                            color = c,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                            ),
                            onClick = {
                                val out = FloatArray(3)
                                android.graphics.Color.colorToHSV(argb.toInt(), out)
                                applyHsv(out[0], out[1], out[2])
                                hexInput = c.toHexString()
                                syncedHex = hexInput
                            },
                        ) {}
                    }
                }
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

private val QuickColors: List<Long> = listOf(
    0xFF22D3EE, 0xFF6366F1, 0xFFF472B6, 0xFF4F5BD5, 0xFF2E9E4F, 0xFFF57C00, 0xFF8B90A0, 0xFF12141A,
)

private fun Color.toHexString(): String {
    val r = (red * 255f).roundToInt().coerceIn(0, 255)
    val g = (green * 255f).roundToInt().coerceIn(0, 255)
    val b = (blue * 255f).roundToInt().coerceIn(0, 255)
    return "#%02X%02X%02X".format(r, g, b)
}

/** 解析 #RRGGBB / RRGGBB，返回不透明 ARGB；非法输入返回 null。 */
private fun parseHex(text: String): Long? {
    val body = text.trim().removePrefix("#")
    if (body.length != 6) return null
    val rgb = body.toLongOrNull(16) ?: return null
    return (0xFF000000L or rgb)
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
