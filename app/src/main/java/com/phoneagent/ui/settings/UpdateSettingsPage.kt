package com.phoneagent.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.data.RemoteRelease
import com.phoneagent.data.UpdateRepository
import com.phoneagent.di.AppContainer
import com.phoneagent.ui.update.UpdateDetailsDialog
import com.phoneagent.util.AppLog
import kotlinx.coroutines.launch

/**
 * 设置 → 版本更新。
 *
 * 「加入 beta 更新」是唯一开关：
 * - 关闭：只查 GitHub 上没勾预发布的正式版；
 * - 打开：beta（预发布）也参与比较，可手动更新到测试版。
 */
@Composable
fun UpdateSettingsPage(container: AppContainer) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by container.updater.state.collectAsState()
    var beta by remember { mutableStateOf(container.settings.betaUpdates) }
    var auto by remember { mutableStateOf(container.settings.autoCheckUpdate) }
    var details by remember { mutableStateOf<RemoteRelease?>(null) }
    var result by remember { mutableStateOf<String?>(null) }

    val candidate = container.updater.candidate()
    val progress = state.progress

    PageColumn {
        CardTitle("当前版本")
        SettingsCard {
            InfoRow("应用版本", container.updater.currentVersionLabel)
            HorizontalDivider()
            InfoRow(
                "版本类型",
                if (container.updater.installedName().contains("beta", ignoreCase = true)) "Beta 测试版" else "正式版",
            )
            HorizontalDivider()
            InfoRow("更新来源", "GitHub Releases")
            HorizontalDivider()
            InfoRow(
                "最近检查",
                if (state.lastCheckAt <= 0) "尚未检查" else AppLog.timeTextOf(state.lastCheckAt),
            )
        }

        CardTitle("更新通道")
        SettingsCard {
            SwitchRow(
                title = "加入 beta 更新",
                summary = "关闭时只查找正式版本；打开后会检查 beta 预发布版本，可手动更新",
                checked = beta,
            ) { on ->
                beta = on
                container.settings.betaUpdates = on
                result = null
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            SwitchRow(
                title = "启动时自动检查",
                summary = "24 小时内只请求一次；发现新版本才会弹窗",
                checked = auto,
            ) { on ->
                auto = on
                container.settings.autoCheckUpdate = on
            }
        }

        CardTitle("检查更新")
        SettingsCard {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            scope.launch {
                                result = null
                                val found = container.updater.check(silent = true)
                                val err = container.updater.state.value.lastError
                                val st = container.updater.state.value
                                result = when {
                                    found != null -> "发现新版本 v${found.versionLabel}"
                                    err != null -> "检查失败：$err"
                                    else -> "当前已是最新版本" + if (st.fromCache) "（使用上次缓存）" else ""
                                }
                                // 手动检查也走同一个「检测到新的版本」弹窗，和自动检查保持一致
                                found?.let { if (!st.fromCache) container.updater.showPrompt(it) }
                            }
                        },
                        enabled = !state.checking,
                    ) {
                        Text(if (state.checking) "检查中…" else "立即检查更新")
                    }
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    TextButton(onClick = {
                        runCatching {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateRepository.REPO_URL)))
                        }
                    }) { Text("查看仓库") }
                }

                if (state.checking) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                result?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.startsWith("发现")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                candidate?.let { release ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "v${release.versionLabel}" + if (release.prerelease) " · Beta" else " · 正式版",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                buildString {
                                    append("APK ")
                                    append(if (release.apkSize > 0) "%.1f MB".format(release.apkSize / 1048576.0) else "未知大小")
                                    if (release.publishedAt > 0) append(" · ").append(AppLog.timeTextOf(release.publishedAt))
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { details = release }) { Text("详细") }
                        Button(
                            onClick = { container.updater.startUpdate(release) },
                            enabled = progress == null,
                        ) { Text(if (progress != null) "${(progress * 100).toInt()}%" else "更新") }
                    }
                }

                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "正在下载 ${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                state.hint?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Text(
            "beta 与正式版是同一个安装包（com.phoneagent），更新会覆盖安装，聊天记录与设置都保留。" +
                "系统只允许升级到更高 versionCode，装过 beta 后正式版号必须更高才能覆盖。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        Spacer(Modifier.height(12.dp))
    }

    details?.let { release ->
        UpdateDetailsDialog(
            release = release,
            downloading = progress != null,
            progress = progress ?: 0f,
            downloaded = state.downloadedPath != null && state.downloadedTag == release.tag,
            onClose = { details = null },
            onUpdate = {
                container.updater.startUpdate(release)
                details = null
            },
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
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
        Switch(checked = checked, onCheckedChange = null)
    }
}
