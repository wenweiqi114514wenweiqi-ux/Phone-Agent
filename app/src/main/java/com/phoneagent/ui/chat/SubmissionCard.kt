package com.phoneagent.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phoneagent.PhoneAgentApp
import com.phoneagent.data.AttachmentMeta
import com.phoneagent.ui.preview.FilePreviewActivity
import com.phoneagent.ui.preview.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一次最多平铺几张卡；再多就收起来，避免一条消息占满整屏 */
private const val MAX_VISIBLE_CARDS = 3

/**
 * 助手的交付物卡片。
 * 不复制文件，只记真实路径：点卡片 → 内置预览器直接打开那个文件。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SubmissionCards(items: List<AttachmentMeta>) {
    if (items.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val shown = if (expanded) items else items.take(MAX_VISIBLE_CARDS)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        shown.forEach { SubmissionCard(it) }
        val rest = items.size - shown.size
        if (rest > 0) {
            Text(
                text = "还有 $rest 个文件",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = 4.dp, top = 2.dp)
                    .combinedClickable(onClick = { expanded = true }, onLongClick = {}),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SubmissionCard(item: AttachmentMeta) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    // 只 stat 一下，开销可忽略：文件被删/改名就显示"已不存在"
    val exists = remember(item.path) { runCatching { File(item.path).exists() }.getOrDefault(true) }
    val ext = remember(item.name) {
        item.name.substringAfterLast('.', "").uppercase().ifBlank { "FILE" }.take(4)
    }
    val kindLabel = if (item.kind == "modified" || item.version <= 0) "已修改" else "已提交 v${item.version}"

    fun open(context: Context) {
        scope.launch {
            val container = (context.applicationContext as PhoneAgentApp).container
            val artifact = item.artifactId?.let { id ->
                withContext(Dispatchers.IO) {
                    runCatching { container.database.artifactDao().byId(id) }.getOrNull()
                }
            }
            context.startActivity(
                Intent(context, FilePreviewActivity::class.java).apply {
                    putExtra(FilePreviewActivity.EXTRA_PATH, item.path)
                    putExtra(FilePreviewActivity.EXTRA_NAME, item.name)
                    putExtra(FilePreviewActivity.EXTRA_MIME, item.mime)
                    putExtra(FilePreviewActivity.EXTRA_SOURCE, artifact?.sourcePath.orEmpty())
                    putExtra(FilePreviewActivity.EXTRA_NOTE, item.note.orEmpty())
                    putExtra(FilePreviewActivity.EXTRA_VERSION, artifact?.version ?: item.version)
                    putExtra(FilePreviewActivity.EXTRA_SIZE, item.size)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    Box {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (exists) 1f else 0.55f)
                .combinedClickable(
                    onClick = { if (exists) open(ctx) else Toast.makeText(ctx, "文件已经不在了", Toast.LENGTH_SHORT).show() },
                    onLongClick = { menu = true },
                ),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(typeColor(item.mime, item.name), RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = ext,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title ?: item.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            // 有标题时把真实文件名也带上，不然看不出来是哪个文件
                            if (item.title != null) {
                                append(item.name)
                                append(" · ")
                            }
                            append(formatSize(item.size))
                            append(" · ")
                            append(typeLabel(item.mime, item.name))
                            append(" · ")
                            append(kindLabel)
                            if (item.recordedAt > 0) {
                                append(" · ")
                                append(timeText(item.recordedAt))
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(
                    text = if (exists) "打开" else "已不存在",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (exists) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("用其他应用打开") },
                onClick = {
                    menu = false
                    runCatching { openExternal(ctx, item) }
                        .onFailure { Toast.makeText(ctx, "没有能打开这个文件的应用", Toast.LENGTH_SHORT).show() }
                },
            )
            DropdownMenuItem(
                text = { Text("复制文件路径") },
                onClick = {
                    menu = false
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText(item.name, item.path))
                    Toast.makeText(ctx, "已复制路径", Toast.LENGTH_SHORT).show()
                },
            )
        }
    }
}

/** 用外部应用打开：走 FileProvider，不要求「所有文件访问权限」。 */
private fun openExternal(ctx: Context, item: AttachmentMeta) {
    val file = File(item.path)
    if (!file.exists()) {
        Toast.makeText(ctx, "文件已经不在了", Toast.LENGTH_SHORT).show()
        return
    }
    val authority = ctx.packageName + ".fileprovider"
    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, authority, file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, item.mime.ifBlank { "*/*" })
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    ctx.startActivity(intent)
}

private fun timeText(ms: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

private fun typeLabel(mime: String, name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        mime.startsWith("image/") -> "图片"
        mime.startsWith("audio/") -> "音频"
        mime.startsWith("video/") -> "视频"
        ext == "md" -> "Markdown"
        ext in setOf("txt", "log") -> "文本"
        ext == "json" -> "JSON"
        ext == "pdf" -> "PDF"
        ext in setOf("doc", "docx") -> "Word"
        ext in setOf("xls", "xlsx", "csv") -> "表格"
        ext in setOf("ppt", "pptx") -> "幻灯片"
        ext in setOf("zip", "rar", "7z") -> "压缩包"
        mime.startsWith("text/") -> "文本"
        else -> ext.uppercase().ifBlank { "文件" }
    }
}

private fun typeColor(mime: String, name: String): Color {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        mime.startsWith("image/") -> Color(0xFF7A5AC0)
        mime.startsWith("audio/") -> Color(0xFF7A5AC0)
        mime.startsWith("video/") -> Color(0xFF6B4FA0)
        ext in setOf("xlsx", "xls", "csv") -> Color(0xFF2E8B57)
        ext in setOf("pptx", "ppt") -> Color(0xFFC1583C)
        ext in setOf("docx", "doc") -> Color(0xFF2F6FB5)
        ext == "pdf" -> Color(0xFFB4433A)
        ext in setOf("md", "txt", "log", "json") -> Color(0xFF3F7BD6)
        else -> Color(0xFF4A5568)
    }
}
