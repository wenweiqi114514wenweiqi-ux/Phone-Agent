package com.phoneagent.ui.update

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.phoneagent.data.RemoteRelease

/**
 * 发现新版本弹窗。
 *
 * 标题：检测到新的版本
 * 副标题：v1.0.15-beta（略小）
 * 正文：更新内容摘要
 * 按钮：暂不更新 / 更新 / 查看详细内容
 * 单选框：不再提示 beta（只在这条更新是 beta 时出现）
 */
@Composable
fun UpdateAvailableDialog(
    release: RemoteRelease,
    downloading: Boolean,
    progress: Float,
    downloaded: Boolean,
    hint: String?,
    onDismiss: (noBeta: Boolean) -> Unit,
    onUpdate: (noBeta: Boolean) -> Unit,
) {
    var noBeta by remember(release.tag) { mutableStateOf(false) }
    var details by remember(release.tag) { mutableStateOf(false) }

    Dialog(
        onDismissRequest = { onDismiss(noBeta) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 26.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(22.dp)) {
                if (!details) {
                    Text(
                        "检测到新的版本",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "v${release.versionLabel}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (release.prerelease) {
                            Spacer(Modifier.height(0.dp))
                            Text(
                                "Beta",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    val highlights = remember(release.body) {
                        ReleaseNotes.highlights(release.body, limit = 4)
                    }
                    if (highlights.isEmpty()) {
                        Text(
                            "包含问题修复与体验优化，建议更新。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            "本版重点",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Spacer(Modifier.height(6.dp))
                        highlights.forEach { line ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Text(
                                    "•",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    line,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 7.dp),
                                )
                            }
                        }
                    }
                    if (release.prerelease) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { noBeta = !noBeta }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = noBeta, onCheckedChange = { noBeta = it })
                            Text(
                                "不再提示 beta 版本",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "之后只检查正式版，可在「设置 → 版本更新」重新打开",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(start = 44.dp),
                        )
                    }
                } else {
                    Text(
                        "更新内容",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "v${release.versionLabel}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    ReleaseNotesBody(release.body, maxHeight = 320.dp)
                }

                if (downloading) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "正在下载 ${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                hint?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!details) {
                        TextButton(
                            onClick = { onDismiss(noBeta) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        ) {
                            Text("暂不更新", style = MaterialTheme.typography.labelMedium)
                        }
                    } else {
                        TextButton(
                            onClick = { details = false },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        ) {
                            Text("返回", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Button(
                        onClick = { onUpdate(noBeta) },
                        enabled = !downloading,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text(
                            when {
                                downloading -> "${(progress * 100).toInt()}%"
                                downloaded -> "安装"
                                else -> "更新"
                            },
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    TextButton(
                        onClick = { details = !details },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Text(
                            if (details) "收起" else "查看详细内容",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}

/** 设置页手动检查后打开：只展示说明 + 一个更新按钮。 */
@Composable
fun UpdateDetailsDialog(
    release: RemoteRelease,
    downloading: Boolean,
    progress: Float,
    downloaded: Boolean,
    onClose: () -> Unit,
    onUpdate: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 26.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(22.dp)) {
                Text("更新内容", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "v${release.versionLabel}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                )
                ReleaseNotesBody(release.body, maxHeight = 360.dp)
                if (downloading) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                    Text(
                        "正在下载 ${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onClose) { Text("关闭") }
                    Button(onClick = onUpdate, enabled = !downloading) {
                        Text(if (downloaded) "安装" else "更新")
                    }
                }
            }
        }
    }
}

/**
 * 详情页正文：按行还原标题/列表/代码，去掉 Markdown 记号。
 * 不再把 `##`、`**`、反引号这些符号直接甩给用户。
 */
@Composable
private fun ReleaseNotesBody(body: String, maxHeight: Dp) {
    val lines = remember(body) {
        body.replace("\r\n", "\n").ifBlank { "该版本没有填写更新说明。" }.lines()
    }
    SelectionContainer {
        Column(
            modifier = Modifier
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            var inCode = false
            lines.forEach { raw ->
                val t = raw.trim()
                when {
                    t.isBlank() -> Spacer(Modifier.height(6.dp))
                    t.startsWith("<!--") -> Unit
                    t.startsWith("```") -> inCode = !inCode
                    inCode -> Text(
                        raw,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    t.startsWith("#### ") || t.startsWith("### ") || t.startsWith("## ") || t.startsWith("# ") -> {
                        val level = t.takeWhile { it == '#' }.length
                        Text(
                            ReleaseNotes.cleanInline(t.removePrefix("#".repeat(level)).trim()),
                            style = if (level <= 2) MaterialTheme.typography.titleSmall
                            else MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                        )
                    }
                    t == "---" || t == "***" -> HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                    t.startsWith("|") -> Unit
                    t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ") -> Row(
                        modifier = Modifier.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            "•",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            ReleaseNotes.cleanInline(t.substring(1).trim()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 7.dp),
                        )
                    }
                    else -> Text(
                        ReleaseNotes.cleanInline(t),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}
