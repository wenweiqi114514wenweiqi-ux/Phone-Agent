package com.phoneagent.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.component.TestComponentManager
import com.phoneagent.di.AppContainer
import com.phoneagent.util.StorageAccess
import kotlinx.coroutines.launch

/**
 * 设置 → 测试性功能 → 测试组件。
 *
 * 设计目标：普通用户不需要选版本、源、路径，也不用手动装；
 * 打开页面或模型第一次用 python3 时自动准备。技术细节折叠到「高级」。
 */
@Composable
fun TestComponentsPage(
    container: AppContainer,
    onOpenExecution: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val manager = container.testComponents
    val settings = container.settings
    val state by manager.state.collectAsState()
    val scope = rememberCoroutineScope()
    var auto by remember { mutableStateOf(settings.testComponentsAuto) }
    var showUninstall by remember { mutableStateOf(false) }
    var showCleanAll by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        manager.refresh()
        if (settings.testComponentsAuto && manager.state.value.status == TestComponentManager.Status.NOT_INSTALLED) {
            manager.prepare()
        }
    }

    PageColumn {
        CardTitle("Python 运行时", trailing = statusLabel(state.status))
        SettingsCard {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(
                            "Py",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(
                            "Python ${TestComponentManager.VERSION}",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            state.message.ifBlank { "等待检测" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (state.status == TestComponentManager.Status.PREPARING) {
                    Spacer(Modifier.height(14.dp))
                    val p = state.progress
                    if (p == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    }
                }

                if (state.detail.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            state.detail,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    when (state.status) {
                        TestComponentManager.Status.PREPARING -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                "首次准备约需 20–60 秒",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                            )
                        }
                        TestComponentManager.Status.READY -> {
                            TextButton(onClick = {
                                scope.launch { testResult = manager.selfTest() }
                            }) { Text("运行自检") }
                            TextButton(onClick = {
                                testResult = null
                                scope.launch { manager.prepare(force = true) }
                            }) { Text("重新准备") }
                            TextButton(onClick = { showUninstall = true }) { Text("卸载") }
                        }
                        TestComponentManager.Status.NEED_PERMISSION -> {
                            TextButton(onClick = { StorageAccess.openSettings(ctx) }) { Text("去授权文件访问") }
                        }
                        TestComponentManager.Status.NEED_SHELL -> {
                            TextButton(onClick = onOpenExecution) { Text("去启用执行通道") }
                        }
                        else -> {
                            TextButton(onClick = {
                                testResult = null
                                scope.launch { manager.prepare(force = true) }
                            }) { Text(if (state.status == TestComponentManager.Status.ERROR) "重新准备" else "立即准备") }
                            TextButton(onClick = { scope.launch { manager.refresh() } }) { Text("重新检测") }
                        }
                    }
                }

                testResult?.let {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        }

        CardTitle("准备方式")
        SettingsCard {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("自动准备", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "打开此页或模型第一次用 python3 时自动安装，不联网下载",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = auto,
                    onCheckedChange = { on ->
                        auto = on
                        settings.testComponentsAuto = on
                        if (on && state.status == TestComponentManager.Status.NOT_INSTALLED) {
                            scope.launch { manager.prepare() }
                        }
                    },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            InfoRow("组件包", "内置离线包 · 不联网、不安装其它组件")
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            InfoRow("占用空间", "安装后约 70 MB")
        }

        CardTitle("说明")
        Text(
            "测试组件只用于实验性能力：让模型直接运行 Python 脚本，生成音频、处理数据，不再用 printf 逐字节拼二进制。" +
                "旧版 .doc / .ppt 的“生成”仍然需要 LibreOffice，组件商店不解决这个问题；内置预览不受影响。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp),
        )

        CardTitle("高级")
        SettingsCard {
            InfoRow("安装位置", "/data/local/tmp/phoneagent")
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            InfoRow("运行时", if (state.status == TestComponentManager.Status.READY) state.version ?: "已安装" else "未就绪")
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            TextButton(
                onClick = { showCleanAll = true },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text("清理全部组件", color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    if (showUninstall) {
        ConfirmDialog(
            title = "卸载 Python 运行时？",
            text = "会删除 /data/local/tmp 下的 Python，保留用户脚本和 home 目录。模型将无法直接执行 python3。",
            confirm = "卸载",
            onConfirm = {
                showUninstall = false
                scope.launch { manager.uninstall() }
            },
            onDismiss = { showUninstall = false },
        )
    }
    if (showCleanAll) {
        ConfirmDialog(
            title = "清理全部组件？",
            text = "会删除 /data/local/tmp/phoneagent 整个目录，包括用 pip 安装到 home 的包。此操作不可恢复。",
            confirm = "清理",
            onConfirm = {
                showCleanAll = false
                scope.launch { manager.cleanAll() }
            },
            onDismiss = { showCleanAll = false },
        )
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun statusLabel(status: TestComponentManager.Status): String = when (status) {
    TestComponentManager.Status.READY -> "已就绪"
    TestComponentManager.Status.PREPARING -> "准备中"
    TestComponentManager.Status.NOT_INSTALLED -> "未安装"
    TestComponentManager.Status.NEED_PERMISSION -> "缺文件权限"
    TestComponentManager.Status.NEED_SHELL -> "缺执行通道"
    TestComponentManager.Status.ERROR -> "需修复"
    TestComponentManager.Status.UNKNOWN -> "检测中"
}
