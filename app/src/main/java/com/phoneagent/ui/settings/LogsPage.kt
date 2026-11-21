package com.phoneagent.ui.settings

import android.content.ClipData
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.phoneagent.util.AppLog

/**
 * 设置 → 运行日志。
 *
 * 软件自身出的问题（数据库、启动、模型、工具执行）都会记在这里，
 * 用户在应用里遇到异常现象时先看这一页，不必再靠截图堆栈。
 * 纯本机存储，不联网、不上传。
 */
@Composable
fun LogsPage() {
    val ctx = LocalContext.current
    val entries by AppLog.entries.collectAsState()
    var expanded by remember { mutableStateOf<Int?>(null) }
    // 0 全部 / 1 仅问题（软件故障）/ 2 仅环境（权限、路径等用户可自解）
    var filter by remember { mutableStateOf(0) }

    // 进页面即视为「已查看」，底部小提示的红点清掉
    LaunchedEffect(Unit) { AppLog.markRead() }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (entries.isEmpty()) "暂无日志" else "最近 ${entries.size} 条",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            FilterTab("全部", filter == 0) { filter = 0 }
            FilterTab("问题", filter == 1) { filter = 1 }
            FilterTab("环境", filter == 2) { filter = 2 }
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = {
                val text = AppLog.dump()
                if (text.isBlank()) {
                    Toast.makeText(ctx, "没有日志可复制", Toast.LENGTH_SHORT).show()
                } else {
                    ctx.getSystemService(android.content.ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("phone-agent-log", text))
                    Toast.makeText(ctx, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                }
            }) { Text("复制全部") }
            TextButton(onClick = {
                AppLog.clear()
                Toast.makeText(ctx, "已清空", Toast.LENGTH_SHORT).show()
            }) { Text("清空") }
        }

        val visible = when (filter) {
            1 -> entries.filter { it.level == AppLog.Level.WARN || it.level == AppLog.Level.ERROR }
            2 -> entries.filter { it.level == AppLog.Level.ENV }
            else -> entries
        }

        if (visible.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        when (filter) {
                            1 -> "没有问题记录"
                            2 -> "没有环境类记录"
                            else -> "一切正常"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "软件出现异常时会自动记录在这里",
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
                    start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(visible.size) { index ->
                    val e = visible[index]
                    val open = expanded == index
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = if (open) null else index }
                            .padding(vertical = 8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(levelColor(e.level), CircleShape),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = AppLog.timeTextOf(e.time),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = e.tag,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        Text(
                            text = e.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = if (open) Int.MAX_VALUE else 3,
                            modifier = Modifier.padding(start = 15.dp, top = 2.dp),
                        )
                        if (open && e.stack != null) {
                            Text(
                                text = e.stack.trim(),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 15.dp, top = 4.dp),
                            )
                        } else if (!open && e.stack != null) {
                            Text(
                                text = "点击看堆栈",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(start = 15.dp, top = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterTab(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

@Composable
private fun levelColor(level: AppLog.Level): Color = when (level) {
    AppLog.Level.ERROR -> MaterialTheme.colorScheme.error
    AppLog.Level.WARN -> MaterialTheme.colorScheme.tertiary
    // 环境类用中性色：用户能自己解决的，不标红
    AppLog.Level.ENV -> MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
    AppLog.Level.INFO -> MaterialTheme.colorScheme.outline
}
