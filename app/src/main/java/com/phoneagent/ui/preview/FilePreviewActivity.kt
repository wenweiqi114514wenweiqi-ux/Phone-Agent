package com.phoneagent.ui.preview

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.webkit.WebSettingsCompat
import android.os.Build
import android.webkit.WebSettings
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.phoneagent.ui.theme.PhoneAgentTheme
import java.io.File

/**
 * 应用内文件预览器。
 *
 * 独立 Activity：横屏/全屏/系统返回/旋转都不影响主界面。
 * 目前支持 文本（TXT/JSON/XML/CSV/代码）、Markdown、HTML、图片；
 * 其余类型（Office、音视频）在后续版本接入，当前统一引导「用其他应用打开」。
 */
class FilePreviewActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { File(path).name }
        val mime = intent.getStringExtra(EXTRA_MIME).orEmpty()
        val sourcePath = intent.getStringExtra(EXTRA_SOURCE).orEmpty()
        val note = intent.getStringExtra(EXTRA_NOTE).orEmpty()
        val version = intent.getIntExtra(EXTRA_VERSION, 0)
        val size = intent.getLongExtra(EXTRA_SIZE, 0L)

        setContent {
            val appearance by (application as com.phoneagent.PhoneAgentApp)
                .container.appearance.state.collectAsState()
            PhoneAgentTheme(appearance = appearance) {
                // 必须铺一层 Surface：Activity 的窗口主题是浅色的，不铺底会把白窗口漏出来
                androidx.compose.material3.Surface(
                    modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background,
                ) {
                PreviewScreen(
                    path = path,
                    name = name,
                    mime = mime,
                    sourcePath = sourcePath,
                    note = note,
                    version = version,
                    size = size,
                    onBack = { finish() },
                )
                }
            }
        }
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_NAME = "name"
        const val EXTRA_MIME = "mime"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_NOTE = "note"
        const val EXTRA_VERSION = "version"
        const val EXTRA_SIZE = "size"
    }
}

private enum class PreviewKind { TEXT, MARKDOWN, HTML, IMAGE, UNSUPPORTED }

private fun detectKind(name: String, mime: String): PreviewKind {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        mime == "text/markdown" || ext == "md" || ext == "markdown" -> PreviewKind.MARKDOWN
        mime == "text/html" || ext == "html" || ext == "htm" -> PreviewKind.HTML
        mime.startsWith("image/") || ext in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp") ->
            PreviewKind.IMAGE
        mime.startsWith("text/") || ext in setOf(
            "txt", "log", "json", "xml", "csv", "yml", "yaml", "ini", "conf", "sh",
            "kt", "java", "py", "js", "ts", "css", "sql", "toml", "gradle", "properties",
        ) -> PreviewKind.TEXT
        else -> PreviewKind.UNSUPPORTED
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewScreen(
    path: String,
    name: String,
    mime: String,
    sourcePath: String,
    note: String,
    version: Int,
    size: Long,
    onBack: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // 优先用记录里的路径；若文件被删/改名，再试原始位置
    val resolvedPath = remember(path, sourcePath) {
        when {
            path.isNotBlank() && File(path).exists() -> path
            sourcePath.isNotBlank() && File(sourcePath).exists() -> sourcePath
            else -> path.ifBlank { sourcePath }
        }
    }
    val file = remember(resolvedPath) { File(resolvedPath) }
    val exists = remember(resolvedPath) { file.exists() }
    val kind = remember(name, mime, resolvedPath) { detectKind(name, mime) }
    var showInfo by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // HTML 预览的配色模式：跟随深色主题时默认开启深色渲染，用户可一键切回原色
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    var htmlDark by rememberSaveable { mutableStateOf(darkTheme) }
    val pageBg = MaterialTheme.colorScheme.background.toArgb()

    // 路径在手机存储里但没有「所有文件访问权限」→ 一进来就引导开启，而不是给一个空白文件
    var showPerm by remember(resolvedPath) {
        mutableStateOf(
            !com.phoneagent.util.StorageAccess.hasAccess(ctx) &&
                (resolvedPath.startsWith("/storage") || resolvedPath.startsWith("/sdcard"))
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            actions = {
                if (kind == PreviewKind.HTML) {
                    TextButton(onClick = { htmlDark = !htmlDark }) {
                        Text(
                            if (htmlDark) "原色" else "深色",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                IconButton(onClick = { showInfo = true }) {
                    Icon(Icons.Filled.Info, contentDescription = "文件信息")
                }
            },
        )

        Box(modifier = Modifier.fillMaxSize()) {
            if (!exists) {
                MissingPreview(name = name, path = resolvedPath, onOpenExternal = {
                    openExternal(ctx, file, mime)
                })
                return@Column
            }
            when (kind) {
                PreviewKind.TEXT -> TextPreview(file)
                PreviewKind.MARKDOWN -> MarkdownPreview(file)
                PreviewKind.HTML -> key(htmlDark) {
                    HtmlPreview(file = file, dark = htmlDark, bgColor = pageBg)
                }
                PreviewKind.IMAGE -> ImagePreview(file)
                PreviewKind.UNSUPPORTED -> UnsupportedPreview(
                    name = name,
                    onOpenExternal = { openExternal(ctx, file, mime) },
                )
            }
        }
    }

    if (showPerm) {
        AlertDialog(
            onDismissRequest = { showPerm = false },
            title = { Text("需要文件访问权限") },
            text = { Text("这个文件在手机存储里，开启「所有文件访问权限」后才能在这里预览。") },
            confirmButton = {
                TextButton(onClick = {
                    com.phoneagent.util.StorageAccess.openSettings(ctx)
                }) { Text("去开启") }
            },
            dismissButton = {
                TextButton(onClick = { showPerm = false }) { Text("关闭") }
            },
        )
    }

    if (showInfo) {
        ModalBottomSheet(onDismissRequest = { showInfo = false }, sheetState = sheetState) {
            FileInfoSheet(
                name = name,
                mime = mime,
                size = if (size > 0) size else file.length(),
                storedPath = path,
                sourcePath = sourcePath,
                note = note,
                version = version,
                lastModified = file.lastModified(),
                onOpenExternal = { openExternal(ctx, file, mime) },
            )
        }
    }
}

/** 纯文本：等宽、可滚动；超大文件截断，避免一次渲染把内存吃满。 */
@Composable
private fun TextPreview(file: File) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val loaded = remember(file.absolutePath) { runCatching { readPreviewText(file) } }
    val error = loaded.exceptionOrNull()

    if (error != null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("无法读取文件：${error.message}", style = MaterialTheme.typography.bodyMedium)
            if (!com.phoneagent.util.StorageAccess.hasAccess(ctx)) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "文件在手机存储里，需要「所有文件访问权限」才能读取。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                TextButton(onClick = { com.phoneagent.util.StorageAccess.openSettings(ctx) }) {
                    Text("去开启权限")
                }
            }
        }
        return
    }

    val text = loaded.getOrDefault("")
    var wrap by rememberSaveable { mutableStateOf(true) }
    var fontSp by rememberSaveable { mutableStateOf(13f) }

    // 每块 160 行：一块 Text 不会太长（排版稳定），跨块还能连续长按选中
    val chunks = remember(text) {
        val lines = text.lines()
        if (lines.size <= 160) listOf(text) else lines.chunked(160).map { it.joinToString("\n") }
    }

    val style = MaterialTheme.typography.bodySmall.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = fontSp.sp,
        lineHeight = (fontSp * 1.55f).sp,
    )

    Column(modifier = Modifier.fillMaxSize()) {
        // 排版开关：低调的一行小字，不抢内容
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { wrap = !wrap }) {
                Text(
                    if (wrap) "自动换行 · 开" else "自动换行 · 关",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { fontSp = (fontSp - 1f).coerceAtLeast(11f) }) {
                Text("A-", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = { fontSp = (fontSp + 1f).coerceAtMost(22f) }) {
                Text("A+", style = MaterialTheme.typography.labelSmall)
            }
        }

        SelectionContainer {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 14.dp, end = 14.dp, top = 4.dp, bottom = 24.dp,
                ),
            ) {
                items(chunks.size) { i ->
                    if (wrap) {
                        // 宽度 = 屏幕内容宽 → 长行自动折行，不会拖出屏幕
                        Text(
                            text = chunks[i],
                            style = style,
                            softWrap = true,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        ) {
                            Text(text = chunks[i], style = style, softWrap = false)
                        }
                    }
                    Spacer(Modifier.height(1.dp))
                }
            }
        }
    }
}

/** 读正文：超过 2MB 只取前 2MB，避免超大文件把预览拖死。 */
private fun readPreviewText(file: File): String {
    val maxBytes = 2L * 1024 * 1024
    if (file.length() <= maxBytes) return file.readText()
    val buf = ByteArray(maxBytes.toInt())
    val read = file.inputStream().use { input ->
        var total = 0
        while (total < buf.size) {
            val n = input.read(buf, total, buf.size - total)
            if (n <= 0) break
            total += n
        }
        total
    }
    return String(buf, 0, read, Charsets.UTF_8) + "\n\n…（文件较大，仅显示前 2MB）"
}

/** Markdown：轻量渲染（标题/列表/引用/代码块/分隔线），表格按等宽文本展示。 */
@Composable
private fun MarkdownPreview(file: File) {
    val lines = remember(file.absolutePath) {
        runCatching { file.readLines().take(6000) }.getOrElse { listOf("无法读取文件：${it.message}") }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        var inCode = false
        val codeBuffer = StringBuilder()
        lines.forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.trimStart().startsWith("```") -> {
                    if (inCode) {
                        Surface(
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        ) {
                            Text(
                                text = codeBuffer.toString().trimEnd(),
                                style = MaterialTheme.typography.bodySmall
                                    .copy(fontFamily = FontFamily.Monospace),
                                softWrap = true,
                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                            )
                        }
                        codeBuffer.clear()
                        inCode = false
                    } else {
                        inCode = true
                    }
                }
                inCode -> codeBuffer.append(line).append("\n")
                line.startsWith("# ") -> Text(
                    line.removePrefix("# "),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                )
                line.startsWith("## ") -> Text(
                    line.removePrefix("## "),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                )
                line.startsWith("### ") -> Text(
                    line.removePrefix("### "),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                )
                line.startsWith("> ") -> Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    Box(
                        modifier = Modifier
                            .size(width = 3.dp, height = 16.dp)
                            .background(MaterialTheme.colorScheme.outline),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        line.removePrefix("> "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                line.startsWith("- ") || line.startsWith("* ") ->
                    Text("· " + line.substring(2), style = MaterialTheme.typography.bodyMedium)
                line.matches(Regex("^[-*_]{3,}$")) -> HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                line.isBlank() -> Spacer(Modifier.size(6.dp))
                else -> Text(line, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** HTML：系统 WebView 渲染本地文件；禁 JS 桥、禁跨文件访问。 */
@Composable
private fun HtmlPreview(file: File, dark: Boolean, bgColor: Int) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            android.webkit.WebView(context).apply {
                // 先铺主题底色，避免 WebView 默认白底在加载中先闪一下
                setBackgroundColor(bgColor)
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = false
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.loadsImagesAutomatically = true
                settings.textZoom = 100
                applyDarkMode(settings, dark)
                loadUrl("file://" + file.absolutePath)
            }
        },
        update = { /* 只加载一次；切换模式由 key 触发重建 */ },
    )
}

/** 让 WebView 按主题反色：33+ 用算法反色，29–32 用 forceDark，更低版本交给页面自身。 */
@Suppress("DEPRECATION")
private fun applyDarkMode(settings: WebSettings, dark: Boolean) {
    if (Build.VERSION.SDK_INT >= 33) {
        WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, dark)
    } else if (Build.VERSION.SDK_INT >= 29) {
        settings.forceDark = if (dark) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
    }
}

/** 图片：采样解码后按手势缩放。 */
@Composable
private fun ImagePreview(file: File) {
    val bitmap = remember(file.absolutePath) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 2400 || bounds.outHeight / sample > 2400) sample *= 2
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = sample
            })
        }.getOrNull()
    }
    if (bitmap == null) {
        UnsupportedPreview(name = file.name, onOpenExternal = {})
        return
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    offset += pan
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = file.name,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                ),
        )
    }
}

/** 文件不在了：给出路径，方便用户去工作区确认。 */
@Composable
private fun MissingPreview(name: String, path: String, onOpenExternal: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 28.dp),
        ) {
            Text(
                "文件已经不在了",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "可能被删除、改名或移出了工作区。下面是记录的位置：",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                path,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 9.dp),
            )
        }
    }
}

@Composable
private fun UnsupportedPreview(name: String, onOpenExternal: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "这个格式暂时还不能内置预览",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Word / Excel / PPT / 音视频 会在后续版本接入",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 8.dp),
            )
            TextButton(onClick = onOpenExternal, modifier = Modifier.padding(top = 4.dp)) {
                Text("用其他应用打开 $name")
            }
        }
    }
}

@Composable
private fun FileInfoSheet(
    name: String,
    mime: String,
    size: Long,
    storedPath: String,
    sourcePath: String,
    note: String,
    version: Int,
    lastModified: Long,
    onOpenExternal: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text("文件信息", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(12.dp))

        InfoRow("名称", name)
        InfoRow("类型", mime.ifBlank { "未知类型" })
        InfoRow("大小", formatSize(size))
        InfoRow("修改时间", formatTime(lastModified))
        if (version > 0) InfoRow("版本", "第 $version 次提交")
        InfoRow("文件位置", storedPath)
        if (note.isNotBlank()) InfoRow("说明", note)

        Spacer(Modifier.size(10.dp))
        Surface(
            color = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextButton(onClick = onOpenExternal, modifier = Modifier.fillMaxWidth()) {
                Text("用其他应用打开", color = MaterialTheme.colorScheme.onPrimary)
            }
        }
        Spacer(Modifier.size(16.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.width(76.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

internal fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format("%.1f GB", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024)
    bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

internal fun formatTime(ts: Long): String {
    if (ts <= 0) return "未知"
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = ts }
    return String.format(
        "%d-%02d-%02d %02d:%02d",
        cal.get(java.util.Calendar.YEAR),
        cal.get(java.util.Calendar.MONTH) + 1,
        cal.get(java.util.Calendar.DAY_OF_MONTH),
        cal.get(java.util.Calendar.HOUR_OF_DAY),
        cal.get(java.util.Calendar.MINUTE),
    )
}

/** 交给系统默认打开方式（FileProvider 授权读）。 */
internal fun openExternal(ctx: android.content.Context, file: File, mime: String) {
    if (!file.exists()) {
        Toast.makeText(ctx, "文件不存在", Toast.LENGTH_SHORT).show()
        return
    }
    runCatching {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(intent, "用其他应用打开"))
    }.onFailure {
        Toast.makeText(ctx, "没有能打开该格式的应用", Toast.LENGTH_SHORT).show()
    }
}
