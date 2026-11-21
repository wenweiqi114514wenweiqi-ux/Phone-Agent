package com.phoneagent.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.data.ConversationEntity
import com.phoneagent.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField

/**
 * 会话列表页（原生 Material3）：ListItem 列表 + FAB 新建 + 删除确认。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    container: AppContainer,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val conversations by container.database.conversationDao()
        .observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    var pendingDelete by remember { mutableStateOf<ConversationEntity?>(null) }
    var pendingRename by remember { mutableStateOf<ConversationEntity?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    // 每个会话的消息条数（用于区分「还没开始」和「聊过的」）
    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    LaunchedEffect(conversations) {
        val computed = withContext(Dispatchers.IO) {
            val dao = container.database.messageDao()
            conversations.associate { it.id to dao.countByConversation(it.id) }
        }
        counts = computed
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    val reusable = conversations.firstOrNull {
                        it.title == "新对话" && counts[it.id] == 0
                    }
                    if (reusable != null) {
                        container.settings.lastConversationId = reusable.id
                        onSelect(reusable.id)
                        return@ExtendedFloatingActionButton
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
                },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("新建会话") },
            )
        },
    ) { padding ->
        if (conversations.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("还没有会话", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(conversations, key = { it.id }) { c ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onSelect(c.id) },
                    ) {
                        ListItem(
                            leadingContent = {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = CircleShape,
                                    modifier = Modifier.padding(end = 4.dp),
                                ) {
                                    Text(
                                        text = c.title.take(1),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    )
                                }
                            },
                            headlineContent = {
                                Text(
                                    c.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                )
                            },
                            supportingContent = {
                                val n = counts[c.id]
                                Text(
                                    text = if (n == null || n == 0) {
                                        "还没有消息 · ${timeFormat.format(Date(c.updatedAt))}"
                                    } else {
                                        "$n 条消息 · ${timeFormat.format(Date(c.updatedAt))}"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            },
                            trailingContent = {
                                Box {
                                    IconButton(onClick = { menuFor = c.id }) {
                                        Icon(
                                            Icons.Filled.MoreVert,
                                            contentDescription = "更多操作",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = menuFor == c.id,
                                        onDismissRequest = { menuFor = null },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("重命名") },
                                            onClick = {
                                                menuFor = null
                                                pendingRename = c
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("归档") },
                                            onClick = {
                                                menuFor = null
                                                scope.launch {
                                                    container.database.conversationDao()
                                                        .setArchived(c.id, true)
                                                    if (container.settings.lastConversationId == c.id ||
                                                        container.settings.lastConversationId.isBlank()
                                                    ) {
                                                        container.settings.lastConversationId = "default"
                                                    }
                                                }
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Text("删除", color = MaterialTheme.colorScheme.error)
                                            },
                                            onClick = {
                                                menuFor = null
                                                pendingDelete = c
                                            },
                                        )
                                    }
                                }
                            },
                        )
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
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除会话") },
            text = { Text("确定删除「${conv.title}」？该会话的所有消息将一并删除，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        container.database.messageDao().deleteByConversation(conv.id)
                        container.database.conversationDao().delete(conv)
                        if (container.settings.lastConversationId == conv.id) {
                            container.settings.lastConversationId = "default"
                            onSelect("default")
                        }
                    }
                    pendingDelete = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}
