package com.phoneagent.ui.artifacts

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phoneagent.data.ArtifactEntity
import com.phoneagent.di.AppContainer
import com.phoneagent.ui.preview.FilePreviewActivity
import com.phoneagent.ui.preview.formatSize
import com.phoneagent.ui.preview.formatTime

/**
 * 「本次对话的文件」：只看当前会话，不混入其它会话。
 *
 * 这里是助手用 submit_file 提交过的交付物（同一路径取最新版本），
 * 点一下就在应用内打开预览。
 */
@Composable
fun ArtifactsPage(
    container: AppContainer,
    conversationId: String,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val items by remember(conversationId) {
        container.database.artifactDao().observeByConversation(conversationId)
    }.collectAsState(initial = emptyList())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "本次对话的文件",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (items.isEmpty()) "还没有文件" else "共 ${items.size} 个 · 点开直接预览原文件",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "这个对话还没动过文件",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "助手改过的文件、主动提交的成果都会出现在这里",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 14.dp, end = 14.dp, top = 8.dp, bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(items, key = { it.id }) { artifact ->
                    ArtifactRow(artifact) {
                        ctx.startActivity(
                            Intent(ctx, FilePreviewActivity::class.java).apply {
                                putExtra(FilePreviewActivity.EXTRA_PATH, artifact.storedPath)
                                putExtra(FilePreviewActivity.EXTRA_NAME, artifact.name)
                                putExtra(FilePreviewActivity.EXTRA_MIME, artifact.mime)
                                putExtra(FilePreviewActivity.EXTRA_SOURCE, artifact.sourcePath)
                                putExtra(FilePreviewActivity.EXTRA_NOTE, artifact.note.orEmpty())
                                putExtra(FilePreviewActivity.EXTRA_VERSION, artifact.version)
                                putExtra(FilePreviewActivity.EXTRA_SIZE, artifact.size)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtifactRow(artifact: ArtifactEntity, onClick: () -> Unit) {
    val ext = artifact.name.substringAfterLast('.', "").uppercase().ifBlank { "FILE" }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(kindColor(artifact.mime, artifact.name), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = ext.take(4),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = artifact.title ?: artifact.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(artifact.name)
                        append(" · ")
                        append(formatSize(artifact.size))
                        append(" · ")
                        append(statusLabel(artifact))
                        append(" · ")
                        append(formatTime(artifact.createdAt))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (!artifact.note.isNullOrBlank()) {
                    Text(
                        text = artifact.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/** 状态：助手提交过几次 / 只是被改过。 */
private fun statusLabel(artifact: ArtifactEntity): String = when {
    artifact.kind == "submitted" && artifact.version > 1 -> "已提交 v${artifact.version}"
    artifact.kind == "submitted" -> "已提交"
    else -> "已修改"
}

/** 按类型给个固定色，扫一眼就能分辨文档/表格/图片/音视频。 */
private fun kindColor(mime: String, name: String): Color {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        mime.startsWith("image/") -> Color(0xFF3F7BD6)
        mime.startsWith("audio/") -> Color(0xFF7A5AC0)
        mime.startsWith("video/") -> Color(0xFF6B4FA0)
        ext in setOf("xlsx", "xls", "csv") -> Color(0xFF2E8B57)
        ext in setOf("pptx", "ppt") -> Color(0xFFC1583C)
        ext in setOf("docx", "doc", "pdf") -> Color(0xFF2F6FB5)
        ext in setOf("md", "txt", "log") -> Color(0xFF5A6570)
        ext in setOf("kt", "java", "py", "js", "ts", "sh", "json", "xml") -> Color(0xFF3C4B57)
        else -> Color(0xFF4A5568)
    }
}
