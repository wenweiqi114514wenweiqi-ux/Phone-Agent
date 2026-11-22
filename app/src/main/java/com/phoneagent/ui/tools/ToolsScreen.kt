package com.phoneagent.ui.tools

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.di.AppContainer
import com.phoneagent.tools.ToolDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 工具管理页：顶部搜索 + 分组可折叠（默认收起），组内可单独启用/禁用。
 */
@Composable
fun ToolsScreen(container: AppContainer, modifier: Modifier = Modifier) {
    val tools by container.toolRegistry.observeAll().collectAsState(initial = emptyList())
    val enabledCount = tools.count { it.enabled }
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    // 默认全展开：新加的工具（如 file_zip）不能被藏在折叠分组里
    var collapsed by remember { mutableStateOf(setOf<String>()) }

    val searching = query.isNotBlank()
    val filtered = if (!searching) tools else tools.filter {
        it.name.contains(query, ignoreCase = true) ||
            it.description.contains(query, ignoreCase = true)
    }
    val groups = filtered
        .groupBy { handlerLabel(it.handler) }
        .toSortedMap(compareBy { handlerOrder(it) })

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "head") {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "工具",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "$enabledCount/${tools.size} 已启用",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f),
                    )
                }
                val zipNames = tools.count { it.name == "file_zip" || it.name == "file_unzip" || it.name == "file_zip_list" }
                val docNames = tools.count { it.name == "doc_write" || it.name == "sheet_write" || it.name == "ppt_write" }
                Text(
                    "关闭后模型将看不到该工具 · 共 " + tools.size + " 个 · 压缩 " +
                        (if (zipNames > 0) "就绪（" + zipNames + "/3）" else "缺失") +
                        " · 文档 " + (if (docNames > 0) "就绪（" + docNames + "/3）" else "缺失"),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (zipNames > 0 && docNames > 0) {
                        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f)
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                Text(
                    "点分组标题可展开/收起；列表由代码内置定义直接合并，不依赖数据库",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f),
                )
            }
        }

        item(key = "search") {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onBackground,
                    unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                    focusedPlaceholderColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.52f),
                    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.52f),
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
                placeholder = { Text("搜索工具名或功能，如 截图 / ocr / 记忆") },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        Text(
                            "清除",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { query = "" }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                },
            )
        }

        if (filtered.isEmpty()) {
            item(key = "empty") {
                Text(
                    "没有匹配的工具。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f),
                    modifier = Modifier.padding(vertical = 16.dp, horizontal = 4.dp),
                )
            }
        }

        groups.forEach { (label, list) ->
            val open = searching || !collapsed.contains(label)
            item(key = "hdr_$label") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (!searching) {
                                collapsed =
                                    if (collapsed.contains(label)) collapsed - label else collapsed + label
                            }
                        }
                        .padding(top = 8.dp, start = 4.dp, end = 4.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (open) "▾" else "▸",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    Text(
                        text = "$label · ${list.size}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = if (searching) "搜索中" else if (open) "收起" else "展开",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f),
                    )
                }
            }
            if (open) {
                items(list.sortedBy { it.name }, key = { it.name }) { tool ->
                    ToolCard(
                        tool = tool,
                        onToggle = { on ->
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    container.toolRegistry.upsert(tool.copy(enabled = on))
                                }
                            }
                        },
                    )
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun ToolCard(tool: ToolDefinition, onToggle: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = tool.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    RiskBadge(tool.risk)
                    if (!tool.builtin) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.padding(start = 4.dp),
                        ) {
                            Text(
                                "自定义",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
            },
            supportingContent = {
                Text(
                    text = tool.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            trailingContent = { Switch(checked = tool.enabled, onCheckedChange = onToggle) },
        )
    }
}

@Composable
private fun RiskBadge(risk: String) {
    val isHigh = risk == "high"
    Surface(
        color = if (isHigh) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.padding(start = 6.dp),
    ) {
        Text(
            text = if (isHigh) "高危" else "安全",
            style = MaterialTheme.typography.labelSmall,
            color = if (isHigh) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

private fun handlerLabel(handler: String): String = when (handler) {
    "shell" -> "Shell 命令"
    "api" -> "系统接口"
    "ui" -> "界面操控"
    "compose" -> "复合任务"
    else -> "其它"
}

private fun handlerOrder(label: String): Int = when (label) {
    "界面操控" -> 0
    "系统接口" -> 1
    "Shell 命令" -> 2
    "复合任务" -> 3
    else -> 4
}
