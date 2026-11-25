package com.phoneagent.ui.sessions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoneagent.data.ConversationEntity
import com.phoneagent.data.MessageEntity
import com.phoneagent.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 会话列表：页头统计 + 搜索 + 按日期分组 + 统一会话卡片。
 *
 * 交互保持原样：点卡片进入会话，右侧菜单负责重命名 / 归档 / 删除；
 * 只是把原来的嵌套 Scaffold + FAB 换成了页面内的「新建」按钮，避免和底部导航打架。
 */
@Composable
fun SessionsScreen(
    container: AppContainer,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val conversations by container.database.conversationDao()
        .observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var query by rememberSaveable { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<ConversationEntity?>(null) }
    var pendingRename by remember { mutableStateOf<ConversationEntity?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var meta by remember { mutableStateOf<Map<String, ConversationMeta>>(emptyMap()) }

    // 每个会话的消息数 + 最后一条消息；数据量很小，沿用现有一对一查询即可。
    LaunchedEffect(conversations) {
        val computed = withContext(Dispatchers.IO) {
            val dao = container.database.messageDao()
            conversations.associate { c ->
                c.id to ConversationMeta(
                    count = dao.countByConversation(c.id),
                    last = dao.lastByConversation(c.id),
                )
            }
        }
        meta = computed
    }

    fun createConversation() {
        val reusable = conversations.firstOrNull {
            it.title == "新对话" && meta[it.id]?.count == 0
        }
        if (reusable != null) {
            container.settings.lastConversationId = reusable.id
            onSelect(reusable.id)
            return
        }
        val now = System.currentTimeMillis()
        val newId = "conv_${UUID.randomUUID().toString().take(8)}"
        scope.launch {
            container.database.conversationDao().upsert(
                ConversationEntity(
                    id = newId,
                    title = "新对话",
                    createdAt = now,
                    updatedAt = now,
                )
            )
            container.settings.lastConversationId = newId
            onSelect(newId)
        }
    }

    val activeId = container.settings.lastConversationId
    val filtered = remember(conversations, query) {
        val q = query.trim()
        if (q.isEmpty()) conversations
        else conversations.filter { it.title.contains(q, ignoreCase = true) }
    }
    val totalMessages = meta.values.sumOf { it.count }

    Column(modifier = modifier.fillMaxSize()) {
        SessionsHeader(
            count = conversations.size,
            messageCount = totalMessages,
            query = query,
            onQueryChange = { query = it },
            onClearQuery = { query = "" },
            onSearch = { focus.clearFocus() },
            onNew = { createConversation() },
            showSearch = conversations.isNotEmpty(),
        )

        when {
            conversations.isEmpty() -> EmptySessions(onNew = { createConversation() })
            filtered.isEmpty() -> NoSearchResult(query = query, onClear = { query = "" })
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (query.isBlank()) {
                        val groups = filtered.groupBy { bucketLabel(it.updatedAt) }
                        groups.forEach { (label, list) ->
                            item(key = "group_$label") {
                                SessionGroupHeader(label = label, count = list.size)
                            }
                            items(list, key = { it.id }) { c ->
                                SessionCard(
                                    conversation = c,
                                    meta = meta[c.id],
                                    active = c.id == activeId,
                                    onOpen = { onSelect(c.id) },
                                    onMenu = { menuFor = c.id },
                                    menuOpen = menuFor == c.id,
                                    onDismissMenu = { menuFor = null },
                                    onRename = { pendingRename = c },
                                    onArchive = {
                                        scope.launch {
                                            container.database.conversationDao().setArchived(c.id, true)
                                            if (container.settings.lastConversationId == c.id ||
                                                container.settings.lastConversationId.isBlank()
                                            ) {
                                                container.settings.lastConversationId = "default"
                                            }
                                        }
                                    },
                                    onDelete = { pendingDelete = c },
                                )
                            }
                        }
                    } else {
                        item(key = "search_header") {
                            SessionGroupHeader(label = "搜索结果", count = filtered.size)
                        }
                        items(filtered, key = { it.id }) { c ->
                            SessionCard(
                                conversation = c,
                                meta = meta[c.id],
                                active = c.id == activeId,
                                onOpen = { onSelect(c.id) },
                                onMenu = { menuFor = c.id },
                                menuOpen = menuFor == c.id,
                                onDismissMenu = { menuFor = null },
                                onRename = { pendingRename = c },
                                onArchive = {
                                    scope.launch {
                                        container.database.conversationDao().setArchived(c.id, true)
                                        if (container.settings.lastConversationId == c.id ||
                                            container.settings.lastConversationId.isBlank()
                                        ) {
                                            container.settings.lastConversationId = "default"
                                        }
                                    }
                                },
                                onDelete = { pendingDelete = c },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingRename?.let { conv ->
        var draft by remember(conv.id) { mutableStateOf(conv.title) }
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { if (it.length <= 40) draft = it },
                    singleLine = true,
                    label = { Text("标题") },
                    shape = MaterialTheme.shapes.large,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = draft.trim()
                    if (t.isNotEmpty()) {
                        scope.launch {
                            container.database.conversationDao().rename(conv.id, t)
                        }
                    }
                    pendingRename = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRename = null }) { Text("取消") }
            },
        )
    }

    pendingDelete?.let { conv ->
        DeleteConversationDialog(
            container = container,
            conversation = conv,
            onDismiss = { pendingDelete = null },
            onDeleted = {
                pendingDelete = null
                if (container.settings.lastConversationId == conv.id) {
                    container.settings.lastConversationId = "default"
                    onSelect("default")
                }
            },
        )
    }
}

@Composable
private fun SessionsHeader(
    count: Int,
    messageCount: Int,
    query: String,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onSearch: () -> Unit,
    onNew: () -> Unit,
    showSearch: Boolean,
) {
    Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "全部会话",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    buildString {
                        append("$count 个会话")
                        if (messageCount > 0) append(" · $messageCount 条消息")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                )
            }
            FilledTonalButton(
                onClick = onNew,
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 15.dp, vertical = 9.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(5.dp))
                Text("新建", style = MaterialTheme.typography.labelLarge)
            }
        }
        if (showSearch) {
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = MaterialTheme.shapes.extraLarge,
            placeholder = { Text("搜索会话标题", style = MaterialTheme.typography.bodySmall) },
            leadingIcon = {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp),
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = onClearQuery) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "清除搜索",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
        )
        }
    }
}

@Composable
private fun SessionGroupHeader(label: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), CircleShape)
        )
        Spacer(Modifier.size(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.78f),
        )
        Spacer(Modifier.size(7.dp))
        Text(
            "$count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.42f),
        )
        Spacer(Modifier.size(10.dp))
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun SessionCard(
    conversation: ConversationEntity,
    meta: ConversationMeta?,
    active: Boolean,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
    menuOpen: Boolean,
    onDismissMenu: () -> Unit,
    onRename: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.48f)
            else MaterialTheme.colorScheme.outlineVariant,
        ),
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 13.dp, end = 6.dp, bottom = 13.dp),
            verticalAlignment = Alignment.Top,
        ) {
            SessionAvatar(title = conversation.title, id = conversation.id, active = active)
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        conversation.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (active) {
                        Spacer(Modifier.size(7.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            shape = CircleShape,
                        ) {
                            Text(
                                "当前",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        timeLabel(conversation.updatedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    )
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 7.dp)
                            .size(3.dp)
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), CircleShape)
                    )
                    Text(
                        when (val n = meta?.count) {
                            null -> "读取中"
                            0 -> "尚未开始"
                            else -> "$n 条消息"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    )
                }
                val preview = previewOf(meta?.last)
                if (preview.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box {
                IconButton(
                    onClick = onMenu,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "更多操作",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        modifier = Modifier.size(19.dp),
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = onDismissMenu,
                ) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        onClick = {
                            onDismissMenu()
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("归档") },
                        onClick = {
                            onDismissMenu()
                            onArchive()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            onDismissMenu()
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionAvatar(title: String, id: String, active: Boolean) {
    val container: Color
    val onContainer: Color
    when ((id.hashCode() and 0x7fffffff) % 3) {
        0 -> {
            container = MaterialTheme.colorScheme.primaryContainer
            onContainer = MaterialTheme.colorScheme.onPrimaryContainer
        }
        1 -> {
            container = MaterialTheme.colorScheme.secondaryContainer
            onContainer = MaterialTheme.colorScheme.onSecondaryContainer
        }
        else -> {
            container = MaterialTheme.colorScheme.tertiaryContainer
            onContainer = MaterialTheme.colorScheme.onTertiaryContainer
        }
    }
    Surface(
        color = container,
        contentColor = onContainer,
        shape = CircleShape,
        border = if (active) BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
        ) else null,
        modifier = Modifier.size(46.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                title.trim().take(1).ifBlank { "新" },
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun EmptySessions(onNew: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 36.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(78.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.AutoMirrored.Filled.List,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("还没有会话", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "新建一个会话，和 Agent 从第一句话开始",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            )
            Spacer(Modifier.height(18.dp))
            Button(onClick = onNew, shape = CircleShape) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("新建会话")
            }
        }
    }
}

@Composable
private fun NoSearchResult(query: String, onClear: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
                modifier = Modifier.size(34.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text("没有找到相关会话", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "没有标题包含「$query」的会话",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            )
            TextButton(onClick = onClear) { Text("清除搜索") }
        }
    }
}

private data class ConversationMeta(
    val count: Int,
    val last: MessageEntity? = null,
)

private fun previewOf(last: MessageEntity?): String {
    if (last == null) return ""
    val body = last.content
        .replace(Regex("[\\r\\n]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (body.isNotBlank()) {
        val who = if (last.role == "user") "你" else "Agent"
        return "$who · ${body.take(72)}"
    }
    return when {
        !last.attachments.isNullOrBlank() -> "你 · 发送了附件"
        !last.toolCalls.isNullOrBlank() -> "Agent · 执行了操作"
        else -> ""
    }
}

private fun timeLabel(timestamp: Long): String {
    val now = Calendar.getInstance()
    val time = Calendar.getInstance().apply { timeInMillis = timestamp }
    val sameDay = now.get(Calendar.YEAR) == time.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == time.get(Calendar.DAY_OF_YEAR)
    val fmt = SimpleDateFormat(if (sameDay) "HH:mm" else "MM-dd HH:mm", Locale.getDefault())
    return fmt.format(Date(timestamp))
}

private fun bucketLabel(timestamp: Long): String {
    val now = Calendar.getInstance()
    val time = Calendar.getInstance().apply { timeInMillis = timestamp }
    if (now.get(Calendar.YEAR) == time.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == time.get(Calendar.DAY_OF_YEAR)
    ) return "今天"

    val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (yesterday.get(Calendar.YEAR) == time.get(Calendar.YEAR) &&
        yesterday.get(Calendar.DAY_OF_YEAR) == time.get(Calendar.DAY_OF_YEAR)
    ) return "昨天"

    return if (now.get(Calendar.YEAR) == time.get(Calendar.YEAR)) {
        SimpleDateFormat("M月d日", Locale.CHINA).format(Date(timestamp))
    } else {
        SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(Date(timestamp))
    }
}
