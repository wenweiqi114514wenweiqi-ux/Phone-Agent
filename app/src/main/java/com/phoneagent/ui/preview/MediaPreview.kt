package com.phoneagent.ui.preview

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.roundToInt

/* ======================= 音频 ======================= */

@Composable
fun AudioPreview(file: File, name: String, onOpenExternal: () -> Unit) {
    var prepared by remember(file.path) { mutableStateOf(false) }
    var playing by remember(file.path) { mutableStateOf(false) }
    var durationMs by remember(file.path) { mutableIntStateOf(0) }
    var positionMs by remember(file.path) { mutableIntStateOf(0) }
    var dragMs by remember(file.path) { mutableStateOf<Float?>(null) }
    var error by remember(file.path) { mutableStateOf<String?>(null) }

    val player = remember(file.path) {
        MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setOnPreparedListener { mp ->
                prepared = true
                durationMs = mp.duration.coerceAtLeast(0)
            }
            setOnCompletionListener {
                playing = false
                positionMs = durationMs
            }
            setOnErrorListener { _, what, extra ->
                error = "播放失败（$what/$extra），编码可能不受系统支持"
                prepared = false
                playing = false
                true
            }
        }
    }

    DisposableEffect(player) {
        runCatching {
            player.setDataSource(file.absolutePath)
            player.prepareAsync()
        }.onFailure {
            error = it.message ?: "无法打开音频"
        }
        onDispose {
            runCatching { player.stop() }
            player.release()
        }
    }

    LaunchedEffect(playing, prepared) {
        while (playing && prepared) {
            positionMs = runCatching { player.currentPosition }.getOrDefault(positionMs)
            delay(450)
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = MaterialTheme.shapes.extraLarge,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = CircleShape,
                    modifier = Modifier.size(92.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "♪",
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    text = when {
                        error != null -> "无法播放"
                        !prepared -> "正在准备…"
                        else -> if (playing) "正在播放" else "已暂停"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )

                if (error != null) {
                    Text(
                        text = error!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    TextButton(onClick = onOpenExternal, modifier = Modifier.padding(top = 4.dp)) {
                        Text("用其他应用播放")
                    }
                } else {
                    val total = durationMs.coerceAtLeast(1)
                    val shown = (dragMs ?: positionMs.toFloat()).coerceIn(0f, total.toFloat())
                    Slider(
                        value = shown,
                        onValueChange = { dragMs = it },
                        onValueChangeFinished = {
                            val target = (dragMs ?: positionMs.toFloat()).roundToInt()
                            runCatching { player.seekTo(target) }
                            positionMs = target
                            dragMs = null
                        },
                        valueRange = 0f..total.toFloat(),
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = formatDuration(shown.roundToInt()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = formatDuration(durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Row(
                        modifier = Modifier.padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = {
                                runCatching {
                                    player.seekTo((positionMs - 10_000).coerceAtLeast(0))
                                    positionMs = player.currentPosition
                                }
                            },
                        ) { Text("−10s") }
                        Spacer(Modifier.width(6.dp))
                        FilledIconButton(
                            onClick = {
                                if (!prepared) return@FilledIconButton
                                if (playing) {
                                    runCatching { player.pause() }
                                    playing = false
                                } else {
                                    runCatching { player.start() }
                                    playing = true
                                }
                            },
                            enabled = prepared,
                            modifier = Modifier.size(56.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        ) {
                            if (playing) {
                                PauseGlyph(MaterialTheme.colorScheme.onPrimaryContainer)
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = "播放",
                                )
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        TextButton(
                            onClick = {
                                runCatching {
                                    player.seekTo((positionMs + 10_000).coerceAtMost(durationMs))
                                    positionMs = player.currentPosition
                                }
                            },
                        ) { Text("+10s") }
                    }
                }
            }
        }
    }
}

/* ======================= 视频 ======================= */

@Composable
fun VideoPreview(file: File, name: String, onOpenExternal: () -> Unit) {
    var ready by remember(file.path) { mutableStateOf(false) }
    var error by remember(file.path) { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                VideoView(context).apply {
                    val video = this
                    val controller = MediaController(context).apply { setAnchorView(video) }
                    setMediaController(controller)
                    setOnPreparedListener { mp ->
                        ready = true
                        mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                        start()
                    }
                    setOnErrorListener { _, what, extra ->
                        error = "播放失败（$what/$extra），编码可能不受系统支持"
                        ready = false
                        true
                    }
                    setVideoPath(file.absolutePath)
                }
            },
            onRelease = { view ->
                runCatching { view.stopPlayback() }
            },
        )

        if (!ready && error == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(30.dp))
                    Text(
                        text = "正在准备视频…",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.78f),
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }

        if (error != null) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = error!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    TextButton(onClick = onOpenExternal, modifier = Modifier.padding(top = 4.dp)) {
                        Text("用其他应用播放")
                    }
                }
            }
        } else if (ready) {
            Text(
                text = "点击画面显示播放控制",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.65f),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
            )
        }
    }
}

/* ======================= 工具 ======================= */

@Composable
private fun PauseGlyph(tint: Color) {
    Canvas(Modifier.size(24.dp)) {
        val barWidth = size.width * 0.20f
        val radius = CornerRadius(barWidth * 0.35f, barWidth * 0.35f)
        val top = size.height * 0.13f
        val height = size.height * 0.74f
        drawRoundRect(
            color = tint,
            topLeft = Offset(size.width * 0.25f, top),
            size = Size(barWidth, height),
            cornerRadius = radius,
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(size.width * 0.55f, top),
            size = Size(barWidth, height),
            cornerRadius = radius,
        )
    }
}

private fun formatDuration(ms: Int): String {
    val totalSeconds = (ms.coerceAtLeast(0) / 1000).toLong()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
