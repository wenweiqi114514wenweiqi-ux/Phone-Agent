package com.phoneagent.ui.sessions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phoneagent.data.ConversationEntity
import com.phoneagent.di.AppContainer
import com.phoneagent.ui.preview.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 删除会话：可选同时删除该会话的文件目录。
 * 两个入口（会话列表、已归档）共用，避免行为不一致。
 */
@Composable
fun DeleteConversationDialog(
    container: AppContainer,
    conversation: ConversationEntity,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var deleteFiles by remember(conversation.id) { mutableStateOf(true) }
    var dirSize by remember(conversation.id) { mutableStateOf<Long?>(null) }
    var busy by remember(conversation.id) { mutableStateOf(false) }
    var error by remember(conversation.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(conversation.id) {
        dirSize = withContext(Dispatchers.IO) {
            runCatching {
                container.workspaceRepository.conversationSize(conversation.id)
            }.getOrDefault(0L)
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("删除会话") },
        text = {
            Column {
                Text("确定删除「${conversation.title}」？该会话的所有消息将一并删除，无法恢复。")
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = deleteFiles,
                        onCheckedChange = { deleteFiles = it },
                        enabled = !busy,
                    )
                    Column(modifier = Modifier.padding(start = 4.dp)) {
                        Text(
                            "同时删除本会话文件（${dirSize?.let(::formatSize) ?: "计算中…"}）",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "取消勾选则保留 PhoneAgent 目录下的附件与产物",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "删除失败：$it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                if (deleteFiles) {
                                    container.workspaceRepository.deleteConversationFiles(conversation.id)
                                }
                                container.database.messageDao().deleteByConversation(conversation.id)
                                container.database.conversationDao().delete(conversation)
                            }
                            onDeleted()
                        } catch (e: Exception) {
                            error = e.message ?: e.javaClass.simpleName
                        } finally {
                            busy = false
                        }
                    }
                },
            ) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        },
    )
}
