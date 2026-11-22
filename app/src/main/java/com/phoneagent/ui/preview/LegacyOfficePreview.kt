package com.phoneagent.ui.preview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 旧版 Word（.doc）：按段落展示提取出的正文。
 * 二进制格式没有可靠的版式重建，这里明确标注「内容预览」，原版仍可交给外部应用。
 */
@Composable
fun LegacyWordPreview(file: File, onOpenExternal: () -> Unit) {
    val result by produceState<Result<LegacyOfficeReader.WordDoc>?>(null, file.path) {
        value = withContext(Dispatchers.IO) {
            runCatching { LegacyOfficeReader.readWord(file) }
        }
    }
    when {
        result == null -> LoadingBox("正在读取旧版 Word…")
        result!!.isFailure -> LegacyError(
            title = "无法读取这个 .doc",
            detail = result!!.exceptionOrNull()?.message ?: "文档结构不完整",
            onOpenExternal = onOpenExternal,
        )
        else -> {
            val doc = result!!.getOrThrow()
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    LegacyBanner(
                        title = "旧版 .doc · 内容预览",
                        detail = "已提取正文与段落；字体、图片、分栏等版式需用其他应用查看原文件。",
                        onOpenExternal = onOpenExternal,
                    )
                }
                itemsIndexed(doc.paragraphs) { index, paragraph ->
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shape = MaterialTheme.shapes.large,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = paragraph,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                    if (index == doc.paragraphs.lastIndex && doc.truncated) {
                        Text(
                            text = "正文较长，后续内容已省略。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f),
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 旧版 PowerPoint（.ppt）：按记录顺序分页展示文字。 */
@Composable
fun LegacySlidePreview(file: File, onOpenExternal: () -> Unit) {
    val result by produceState<Result<LegacyOfficeReader.PptDeck>?>(null, file.path) {
        value = withContext(Dispatchers.IO) {
            runCatching { LegacyOfficeReader.readPowerPoint(file) }
        }
    }
    when {
        result == null -> LoadingBox("正在读取旧版 PowerPoint…")
        result!!.isFailure -> LegacyError(
            title = "无法读取这个 .ppt",
            detail = result!!.exceptionOrNull()?.message ?: "幻灯片结构不完整",
            onOpenExternal = onOpenExternal,
        )
        else -> {
            val deck = result!!.getOrThrow()
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    LegacyBanner(
                        title = "旧版 .ppt · 按页文字预览",
                        detail = "按文件内的幻灯片顺序提取文字；图片、图表、动画与精确排版需用其他应用查看。",
                        onOpenExternal = onOpenExternal,
                    )
                }
                itemsIndexed(deck.slides) { index, slide ->
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shape = MaterialTheme.shapes.large,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(50),
                                ) {
                                    Text(
                                        text = "${index + 1}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                    )
                                }
                                Text(
                                    text = "第 ${index + 1} 页",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                            if (slide.lines.isEmpty()) {
                                Text(
                                    text = "（本页没有可提取的文字）",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 10.dp),
                                )
                            } else {
                                slide.lines.forEach { line ->
                                    Text(
                                        text = line,
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.padding(top = 9.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                if (deck.truncated) {
                    item {
                        Text(
                            text = "幻灯片较长，后续页已省略。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f),
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LegacyBanner(title: String, detail: String, onOpenExternal: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 3.dp),
            )
            TextButton(onClick = onOpenExternal, modifier = Modifier.padding(top = 2.dp)) {
                Text("用其他应用查看原版")
            }
        }
    }
}

@Composable
private fun LoadingBox(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.68f),
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun LegacyError(title: String, detail: String, onOpenExternal: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.66f),
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.size(6.dp))
            TextButton(onClick = onOpenExternal) { Text("用其他应用打开") }
        }
    }
}
