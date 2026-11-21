package com.phoneagent.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.phoneagent.data.ModelProfile
import com.phoneagent.data.ModelRepository
import com.phoneagent.di.AppContainer
import com.phoneagent.llm.ModelInfo
import kotlinx.coroutines.launch

/**
 * 模型与接入：模型列表 + 单选生效 + 添加自定义模型（选模板，只填 API Key）。
 * 首次使用列表为空，需要先添加。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSettingsPage(container: AppContainer) {
    val repo = container.modelRepository
    val state by repo.state.collectAsState()

    var showTemplates by remember { mutableStateOf(false) }
    var formTarget by remember { mutableStateOf<ModelProfile?>(null) }
    var formIsNew by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<ModelProfile?>(null) }
    var savedHint by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(savedHint) {
        if (savedHint != null) {
            kotlinx.coroutines.delay(2600)
            savedHint = null
        }
    }

    val target = formTarget
    if (target != null) {
        ModalBottomSheet(onDismissRequest = { formTarget = null }) {
            ModelFormSheet(
                container = container,
                initial = target,
                isNew = formIsNew,
                onCancel = { formTarget = null },
                onSave = { profile ->
                    val saved = if (formIsNew) repo.add(profile) else {
                        repo.update(profile)
                        profile
                    }
                    repo.setActive(saved.id)
                    formTarget = null
                    savedHint = if (formIsNew) "已添加「${saved.name}」并设为当前模型"
                    else "已保存「${saved.name}」，当前模型已同步"
                },
            )
        }
    }

    PageColumn {
        if (savedHint != null) {
            Text(
                text = savedHint!!,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
        if (state.profiles.isEmpty()) {
            EmptyModelsCard()
        } else {
            CardTitle("我的模型", "${state.profiles.size} 个 · 单选生效")
            SettingsCard {
                state.profiles.forEachIndexed { index, p ->
                    if (index > 0) HorizontalDivider()
                    ModelRow(
                        profile = p,
                        selected = p.id == state.activeId,
                        onSelect = { repo.setActive(p.id) },
                        onEdit = {
                            formIsNew = false
                            formTarget = p
                        },
                        onDelete = { confirmDelete = p },
                    )
                }
            }
            Text(
                "选中的模型立即用于新消息；聊天页底部也能快速切换。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = { showTemplates = true },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("添加自定义模型", modifier = Modifier.padding(start = 6.dp))
        }
        Spacer(Modifier.height(8.dp))
    }

    if (showTemplates) {
        ModalBottomSheet(onDismissRequest = { showTemplates = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.72f)
                    .padding(horizontal = 16.dp),
            ) {
                Text(
                    "选择模板",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "选服务商后填入 API Key 即可保存，Base URL 与模型名已预填。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 2.dp),
                )
                LazyColumn(modifier = Modifier.weight(1f).padding(top = 8.dp)) {
                    items(ModelRepository.TEMPLATES, key = { it.key }) { t ->
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(t.label, style = MaterialTheme.typography.titleSmall) },
                            supportingContent = {
                                Text(
                                    if (t.baseUrl.isBlank()) t.hint
                                    else "${t.model} · 上限 ${fmtContext(t.maxContext)} · ${t.hint}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            },
                            trailingContent = {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.outline,
                                )
                            },
                            modifier = Modifier.clickable {
                                showTemplates = false
                                formIsNew = true
                                formTarget = ModelProfile(
                                    id = "m_${System.currentTimeMillis()}",
                                    name = t.label,
                                    provider = t.key,
                                    baseUrl = t.baseUrl,
                                    apiKey = "",
                                    model = t.model,
                                    maxContext = t.maxContext,
                                )
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }

    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除模型") },
            text = { Text("确定删除「${p.name}」？") },
            confirmButton = {
                TextButton(onClick = {
                    repo.delete(p.id)
                    confirmDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun EmptyModelsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "还没有模型",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                "先添加一个模型才能聊天。点下方按钮选服务商模板，填入 API Key 即可，其余会自动带好。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun ModelRow(
    profile: ModelProfile,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            RadioButton(selected = selected, onClick = onSelect)
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(profile.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                if (selected) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.extraSmall,
                        modifier = Modifier.padding(start = 6.dp),
                    ) {
                        Text(
                            "使用中",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        },
        supportingContent = {
            Text(
                "${profile.model.ifBlank { "未指定模型" }} · 上限 ${fmtContext(profile.maxContext)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        },
        trailingContent = {
            Row {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "编辑")
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        modifier = Modifier.clickable(onClick = onSelect),
    )
}

/** 表单：新增时只露「名称 + API Key」，其余收进高级设置。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelFormSheet(
    container: AppContainer,
    initial: ModelProfile,
    isNew: Boolean,
    onCancel: () -> Unit,
    onSave: (ModelProfile) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial.name) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var model by remember { mutableStateOf(initial.model) }
    var maxContext by remember { mutableStateOf(initial.maxContext.toString()) }
    var showKey by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(!isNew) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    var picker by remember { mutableStateOf(false) }

    fun fetchModels() {
        error = null
        loading = true
        scope.launch {
            try {
                models = container.modelsClient.listModels(baseUrl.trim(), apiKey.trim())
                if (models.isNotEmpty()) picker = true
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                loading = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.88f)
            .padding(horizontal = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (isNew) "添加模型" else "编辑模型",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("关闭") }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称") },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { showKey = !showKey }) {
                        Text(if (showKey) "隐藏" else "显示", style = MaterialTheme.typography.labelSmall)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            TextButton(onClick = { advanced = !advanced }) {
                Text(if (advanced) "收起高级设置" else "高级设置（Base URL / 模型 / 上限）")
            }

            if (advanced) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("模型名") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    trailingIcon = {
                        TextButton(enabled = !loading, onClick = { fetchModels() }) {
                            Text("获取", style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = maxContext,
                    onValueChange = { maxContext = it.filter { c -> c.isDigit() } },
                    label = { Text("模型最大上下文（token）") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "填该模型真实支持的上限，聊天里超出这个值的上下文不会被使用。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("正在获取模型列表…", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (error != null) {
                Text(
                    "失败：$error",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.large,
                ) { Text("取消") }
                Button(
                    enabled = apiKey.isNotBlank() && name.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank(),
                    onClick = {
                        onSave(
                            initial.copy(
                                name = name.trim(),
                                apiKey = apiKey.trim(),
                                baseUrl = baseUrl.trim(),
                                model = model.trim(),
                                maxContext = maxContext.toIntOrNull()?.coerceIn(2048, 4_000_000)
                                    ?: initial.maxContext,
                            )
                        )
                    },
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.large,
                ) { Text(if (isNew) "保存并使用" else "保存") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (picker) {
        AlertDialog(
            onDismissRequest = { picker = false },
            title = { Text("选择模型（${models.size}）") },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth().height(360.dp)) {
                    items(models, key = { it.id }) { m ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    m.id,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            supportingContent = {
                                m.ownedBy?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                            },
                            trailingContent = {
                                if (m.id == model) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            },
                            modifier = Modifier.clickable { picker = false; model = m.id },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picker = false }) { Text("关闭") } },
        )
    }
}

@Composable
internal fun fmtContext(tokens: Int): String = when {
    tokens >= 1_000_000 -> "${tokens / 1_000_000}M"
    tokens >= 1024 -> "${tokens / 1024}K"
    else -> "$tokens"
}
