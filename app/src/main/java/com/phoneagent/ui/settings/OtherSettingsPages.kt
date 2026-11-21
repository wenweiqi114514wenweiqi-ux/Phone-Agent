package com.phoneagent.ui.settings

import android.annotation.SuppressLint
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.phoneagent.di.AppContainer
import com.phoneagent.exec.RootShell
import com.phoneagent.exec.ShizukuStatus
import com.phoneagent.tools.SafetyMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 执行通道：Root（Magisk / KernelSU）优先，Shizuku 回退。
 */
@Composable
fun ChannelSettingsPage(container: AppContainer) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val shizukuManager = container.shizukuManager
    val status by shizukuManager.status.collectAsState()

    var rootState by remember { mutableStateOf(RootShell.available) }
    var rootBusy by remember { mutableStateOf(false) }
    var rootProbe by remember { mutableStateOf<String?>(null) }

    PageColumn {
        // ---- Root ----
        CardTitle("Root 通道", "优先")
        SettingsCard {
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text("Magisk / KernelSU") },
                supportingContent = {
                    Text(
                        when (rootState) {
                            true -> "已授权，su 可直接执行命令"
                            false -> "不可用" + (RootShell.lastError?.let { "：$it" } ?: "")
                            null -> "尚未检测（点下方按钮触发授权）"
                        }
                    )
                },
                trailingContent = {
                    if (rootBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(
                            text = if (rootState == true) "✓" else "—",
                            color = if (rootState == true) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                        )
                    }
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    enabled = !rootBusy,
                    onClick = {
                        rootBusy = true
                        scope.launch {
                            withContext(Dispatchers.IO) { RootShell.detect() }
                            rootState = RootShell.available
                            rootBusy = false
                        }
                    },
                ) { Text(if (rootState == true) "重新检测" else "检测 / 授权 Root") }
                OutlinedButton(
                    enabled = !rootBusy,
                    onClick = {
                        scope.launch {
                            rootBusy = true
                            val out = withContext(Dispatchers.IO) { RootShell.exec("id") }
                            rootBusy = false
                            rootState = RootShell.available
                            rootProbe = out.take(120).ifBlank { "(空输出)" }
                        }
                    },
                ) { Text("试执行 id") }
            }
            if (rootProbe != null) {
                Text(
                    text = "输出：$rootProbe",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }

        // ---- Shizuku ----
        CardTitle("Shizuku 通道", "回退")
        SettingsCard {
            val diag = remember(status) { shizukuManager.diagnostics() }
            Column3(
                "App 状态" to if (diag.shizukuInstalled) "已安装" else "未安装",
                "服务运行" to if (diag.binderConnected) "运行中" else "未激活",
                "应用授权" to if (diag.permissionGranted) "已授权" else "未授权",
                "Shell 服务" to if (diag.shellServiceConnected) "已连接" else "未连接",
            )
            HorizontalDivider()
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text("当前状态") },
                supportingContent = { Text(shizukuStatusLabel(status)) },
            )
            when (status) {
                ShizukuStatus.NOT_INSTALLED, ShizukuStatus.NOT_ACTIVATED -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = { shizukuManager.openShizukuApp() }) { Text("打开 Shizuku") }
                    }
                    Text(
                        "未激活时：打开 Shizuku，用无线调试或 adb 启动服务后重试。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                ShizukuStatus.NEED_PERMISSION -> {
                    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Button(onClick = { shizukuManager.requestPermission(ctx) }) { Text("请求授权") }
                    }
                }
                ShizukuStatus.SERVICE_NOT_CONNECTED -> {
                    Text(
                        "已授权但 Shell 未连接：点「刷新」重新绑定，或重启 Shizuku 服务。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                else -> Unit
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { shizukuManager.refreshAuthorization(ctx) }) { Text("刷新授权") }
                OutlinedButton(onClick = { shizukuManager.resetConnection() }) { Text("解除绑定") }
            }
        }

        Text(
            "执行优先级：Root（su）→ Shizuku（adb shell）。两者都不可用时，只有纯 API 类工具（如界面操控、系统设置）还能工作。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 6.dp),
        )

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Column3(vararg rows: Pair<String, String>) {
    androidx.compose.foundation.layout.Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        rows.forEach { (k, v) ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(k, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    v,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/**
 * 安全策略：三档，点选即生效。
 */
@Composable
fun SafetySettingsPage(container: AppContainer) {
    val settings = container.settings
    var mode by remember { mutableStateOf(settings.safetyMode) }

    PageColumn {
        SettingsCard {
            SafetyMode.entries.forEachIndexed { index, m ->
                if (index > 0) HorizontalDivider()
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = {
                        Text(
                            when (m) {
                                SafetyMode.ALLOW_ALL -> "全部允许"
                                SafetyMode.CONFIRM_HIGH -> "高危需确认"
                                SafetyMode.CONFIRM_ALL -> "全部需确认"
                            }
                        )
                    },
                    supportingContent = {
                        Text(
                            when (m) {
                                SafetyMode.ALLOW_ALL -> "所有命令直接执行，不再询问（最快，风险最高）"
                                SafetyMode.CONFIRM_HIGH -> "删除、卸载、改系统等高危操作弹窗确认"
                                SafetyMode.CONFIRM_ALL -> "每条命令都先确认（最安全）"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    trailingContent = {
                        RadioButton(selected = mode == m, onClick = {
                            mode = m
                            settings.safetyMode = m
                        })
                    },
                )
            }
        }
        Text(
            "高危判定由工具自身的 risk 标记决定；界面操控类工具默认低危。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * 功能与日志：思维链、日志、自动重试、深度思考默认值。
 */
@Composable
fun FeatureSettingsPage(container: AppContainer) {
    val settings = container.settings
    var showReasoning by remember { mutableStateOf(settings.showReasoning) }
    var logEnabled by remember { mutableStateOf(settings.logEnabled) }
    var autoRetry by remember { mutableStateOf(settings.autoRetry) }
    var deepThinking by remember { mutableStateOf(settings.deepThinking) }
    var level by remember { mutableStateOf(settings.thinkingLevel) }

    PageColumn {
        CardTitle("显示")
        SettingsCard {
            SwitchRow("显示思维链", "回答下方展示模型推理过程", showReasoning) {
                showReasoning = it
                settings.showReasoning = it
            }
            HorizontalDivider()
            SwitchRow("记录执行日志", "把每步命令与输出写入日志表", logEnabled) {
                logEnabled = it
                settings.logEnabled = it
            }
        }

        CardTitle("执行")
        SettingsCard {
            SwitchRow("工具失败自动重试", "同一工具失败后自动再试一次", autoRetry) {
                autoRetry = it
                settings.autoRetry = it
            }
        }

        CardTitle("深度思考")
        SettingsCard {
            SwitchRow("默认开启深度思考", "新对话默认先推理再回答", deepThinking) { on ->
                deepThinking = on
                settings.deepThinking = on
                if (on && level == "OFF") {
                    level = "LOW"
                    settings.thinkingLevel = "LOW"
                }
            }
            HorizontalDivider()
            Text(
                "思考强度",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("OFF", "LOW", "MIDDLE", "MAX").forEach { l ->
                    FilterChip(
                        selected = level == l,
                        onClick = {
                            level = l
                            settings.thinkingLevel = l
                        },
                        label = { Text(l) },
                    )
                }
            }
        }
        Text(
            "强度映射为各家的 reasoning 参数（如 DeepSeek 的 reasoning_effort）。模型不支持时会被忽略。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title) },
        supportingContent = { Text(desc, style = MaterialTheme.typography.bodySmall) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}

/**
 * 关于页。
 */
@SuppressLint("PackageManagerGetSignatures")
@Composable
fun AboutSettingsPage(
    container: AppContainer,
    onOpenPrivacy: () -> Unit = {},
    onOpenTerms: () -> Unit = {},
    onOpenUpdate: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val tools by container.toolRegistry.observeAll().collectAsState(initial = emptyList())
    val versionName = remember {
        try {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            (info.versionName ?: "?") + " · vc" + info.longVersionCode
        } catch (_: Exception) {
            "?"
        }
    }

    PageColumn {
        CardTitle("版本")
        SettingsCard {
            InfoRow("应用版本", "v$versionName")
            HorizontalDivider()
            NavRow("检查更新", "GitHub Releases · beta 可选", onOpenUpdate)
            HorizontalDivider()
            InfoRow("安装包", ctx.packageName)
            HorizontalDivider()
            InfoRow("已注册工具", "${tools.size} 个")
            HorizontalDivider()
            InfoRow("当前模型", container.settings.model.ifBlank { "未配置" })
            HorizontalDivider()
            InfoRow("执行通道", "Root 优先 · Shizuku 回退")
        }

        CardTitle("组件")
        SettingsCard {
            InfoRow("界面", "Jetpack Compose · Material 3")
            HorizontalDivider()
            InfoRow("存储", "Room · SharedPreferences")
            HorizontalDivider()
            InfoRow("Shell", "Shizuku + Root")
            HorizontalDivider()
            InfoRow("OCR", "ML Kit 中文文字识别")
            HorizontalDivider()
            InfoRow("Office 预览", "Universal File Preview（本地）")
        }

        CardTitle("开源许可")
        SettingsCard {
            InfoRow("Universal File Preview", "MIT · docx / xlsx / pptx 本地渲染")
            HorizontalDivider()
            InfoRow("AndroidX · Compose · Room", "Apache-2.0")
            HorizontalDivider()
            InfoRow("OkHttp", "Apache-2.0")
            HorizontalDivider()
            InfoRow("Shizuku API", "Apache-2.0")
            HorizontalDivider()
            InfoRow("Google ML Kit", "Google 服务条款")
            HorizontalDivider()
            InfoRow("本应用", "MIT · 源代码公开")
        }
        Text(
            "文档预览由开源引擎 Universal File Preview（MIT）在设备本地渲染，文件不会上传到任何服务器。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 6.dp),
        )

        CardTitle("声明与条款", trailing = if (container.settings.legalAcceptedVersion > 0) "已同意" else "待同意")
        SettingsCard {
            NavRow(
                title = "隐私声明",
                summary = "哪些数据留在本机、哪些会发给模型服务商、申请了哪些权限",
                onClick = onOpenPrivacy,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            NavRow(
                title = "使用条款",
                summary = "许可范围、使用限制、风险提示与免责",
                onClick = onOpenTerms,
            )
        }
        Text(
            "使用本应用即表示你已阅读并同意上述声明与条款。因使用本应用（含其执行命令、读写文件、操作手机的能力）" +
                "所造成的一切后果，均由用户自行承担，开发者不承担任何责任。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        Spacer(Modifier.height(4.dp))

        FeedbackCard()

        Text(
            "自用工具，仅用于本人设备。请勿用于未授权设备的自动化操作。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        Spacer(Modifier.height(12.dp))
    }
}

/** 关于页里的可点条目。 */
@Composable
private fun NavRow(title: String, summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
internal fun InfoRow(key: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(key, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun shizukuStatusLabel(status: ShizukuStatus): String = when (status) {
    ShizukuStatus.NOT_INSTALLED -> "未安装 Shizuku"
    ShizukuStatus.NOT_ACTIVATED -> "服务未激活"
    ShizukuStatus.NEED_PERMISSION -> "待授权"
    ShizukuStatus.SERVICE_NOT_CONNECTED -> "Shell 未连接"
    ShizukuStatus.READY -> "已就绪"
}
