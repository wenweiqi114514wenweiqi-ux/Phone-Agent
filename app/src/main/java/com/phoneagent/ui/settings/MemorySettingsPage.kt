package com.phoneagent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.data.MemoryEntity
import com.phoneagent.di.AppContainer
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 记忆 · 关于你：
 * 你的形象（AI 眼中的你）+ AI 记忆开关 + 管理我的记忆（可编辑，防提示词注入）。
 */
@Composable
fun MemorySettingsPage(container: AppContainer) {
    val settings = container.settings
    val scope = rememberCoroutineScope()
    val memories by container.memoryRepository.observeAll().collectAsState(initial = emptyList())

    var persona by remember { mutableStateOf(settings.userPersona) }
    var personaSaved by remember { mutableStateOf(false) }
    var memoryOn by remember { mutableStateOf(settings.memoryEnabled) }
    var showPrivacyNote by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MemoryEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    PageColumn {
        // ---- 你的形象 ----
        CardTitle("你的形象", "AI 眼中的你")
        SettingsCard {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    "写几句关于你自己：身份、常用语言、设备、偏好、说话风格。会作为背景注入系统提示词。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = persona,
                    onValueChange = { persona = it; personaSaved = false },
                    placeholder = { Text("例如：高二学生，用 OPPO Find X9，喜欢直接给结论，不要客套。") },
                    minLines = 3,
                    maxLines = 6,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (personaSaved) {
                        Text(
                            "已保存",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp, top = 8.dp),
                        )
                    }
                    Button(
                        onClick = {
                            settings.userPersona = persona.trim()
                            personaSaved = true
                        },
                        shape = MaterialTheme.shapes.large,
                    ) { Text("保存形象") }
                }
            }
        }

        // ---- AI 记忆 ----
        CardTitle("AI 记忆")
        SettingsCard {
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text("启用 AI 记忆") },
                supportingContent = {
                    Text(
                        "开启后，回答前会检索与问题相关的记忆条目（不会全量注入）",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                trailingContent = {
                    Switch(
                        checked = memoryOn,
                        onCheckedChange = { on ->
                            memoryOn = on
                            settings.memoryEnabled = on
                            showPrivacyNote = on
                        },
                    )
                },
            )
        }

        if (memoryOn) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.75f),
                ),
            ) {
                Text(
                    "隐私提醒：记忆会以明文保存在本机。请不要写入密码、银行卡、身份证号、住址等你不想被模型看到的隐私；" +
                        "记忆内容可能被发送给模型服务商。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(14.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("管理我的记忆", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(
                    "${memories.size} 条",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(end = 6.dp),
                )
                IconButton(onClick = { creating = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "新增记忆")
                }
            }

            if (memories.isEmpty()) {
                SettingsCard {
                    Text(
                        "还没有记忆。在聊天里说「记住……」，或点上方 + 手动添加。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                SettingsCard {
                    memories.forEachIndexed { index, m ->
                        if (index > 0) HorizontalDivider()
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = {
                                Text(
                                    m.content,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 3,
                                )
                            },
                            supportingContent = {
                                Text(
                                    "${m.category ?: "note"} · 权重 ${m.weight} · ${timeFormat.format(Date(m.updatedAt))}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = { editing = m }) {
                                        Icon(Icons.Filled.Edit, contentDescription = "编辑")
                                    }
                                    IconButton(onClick = {
                                        scope.launch { container.memoryRepository.delete(m) }
                                    }) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = "删除",
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }

            if (memories.isNotEmpty()) {
                OutlinedButton(
                    onClick = { confirmClear = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Text("清空全部记忆", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Text(
            "记忆条目会先做一次注入过滤（去掉「忽略之前的指令」这类文本），再作为「参考资料」注入，" +
                "不具备指令效力。你随时可以编辑或删除。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 6.dp),
        )

        Spacer(Modifier.height(8.dp))
    }

    // 编辑 / 新增对话框
    if (editing != null || creating) {
        MemoryEditDialog(
            original = editing,
            onDismiss = { editing = null; creating = false },
            onSave = { text, weight, category ->
                scope.launch {
                    val repo = container.memoryRepository
                    val old = editing
                    if (old != null) {
                        repo.update(old.copy(content = text, weight = weight, category = category))
                    } else {
                        repo.add(text, category = category, weight = weight)
                    }
                }
                editing = null
                creating = false
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空全部记忆") },
            text = { Text("将删除所有记忆条目，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { container.memoryRepository.clearAll() }
                    confirmClear = false
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun MemoryEditDialog(
    original: MemoryEntity?,
    onDismiss: () -> Unit,
    onSave: (text: String, weight: Int, category: String) -> Unit,
) {
    var text by remember { mutableStateOf(original?.content ?: "") }
    var category by remember { mutableStateOf(original?.category ?: "user_note") }
    var weightText by remember { mutableStateOf((original?.weight ?: 1).toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (original == null) "新增记忆" else "编辑记忆") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("内容") },
                    minLines = 3,
                    maxLines = 8,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("分类") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = weightText,
                    onValueChange = { weightText = it.filter { c -> c.isDigit() } },
                    label = { Text("权重（1-9，越高越优先）") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = {
                    onSave(text.trim(), (weightText.toIntOrNull() ?: 1).coerceIn(1, 9), category.trim())
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
