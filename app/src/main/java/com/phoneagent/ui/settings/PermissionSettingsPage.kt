package com.phoneagent.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.phoneagent.di.AppContainer
import com.phoneagent.service.KeepAliveService

/**
 * Phone Use 权限页：系统设置 / 悬浮窗 / 电池优化 / 通知 / 保活。
 */
@Composable
fun PermissionSettingsPage(container: AppContainer) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { tick++ }

    val batteryOn = remember(tick) { isIgnoringBattery(ctx) }
    val notifOn = remember(tick) { hasNotificationPermission(ctx) }
    var keepAlive by remember { mutableStateOf(KeepAliveService.running || container.settings.keepAliveEnabled) }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { tick++ }

    fun open(intent: Intent) {
        try {
            launcher.launch(intent)
        } catch (_: Exception) {
        }
    }

    PageColumn {
        CardTitle("后台权限")
        SettingsCard {
            PermissionRow(
                title = "忽略电池优化",
                desc = "避免后台执行被系统冻结",
                granted = batteryOn,
                actionText = if (batteryOn) "已允许" else "去设置",
                onAction = {
                    open(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${ctx.packageName}"),
                        )
                    )
                },
            )
            HorizontalDivider()
            PermissionRow(
                title = "通知权限",
                desc = "保活与任务进度依赖常驻通知",
                granted = notifOn,
                actionText = if (notifOn) "已授权" else "去授权",
                onAction = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS))
                    }
                },
            )
        }

        CardTitle("后台与系统入口")
        SettingsCard {
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text("后台保活") },
                supportingContent = { Text("前台服务常驻，长任务期间保持 Agent 存活") },
                trailingContent = {
                    Switch(
                        checked = keepAlive,
                        onCheckedChange = { on ->
                            keepAlive = on
                            if (on) KeepAliveService.start(ctx) else KeepAliveService.stop(ctx)
                        },
                    )
                },
            )
            HorizontalDivider()
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text("应用信息") },
                supportingContent = { Text("自启动 / 后台限制 / 省电策略") },
                modifier = Modifier.clickable {
                    open(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                    )
                },
            )
        }

        Text(
            "提示：国产 ROM 还需在系统里允许「自启动」「后台运行」，否则保活服务会被清理。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 6.dp),
        )

        Spacer(modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
internal fun PermissionRow(
    title: String,
    desc: String,
    granted: Boolean,
    actionText: String,
    onAction: () -> Unit,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title) },
        supportingContent = {
            Text(desc, style = MaterialTheme.typography.bodySmall)
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (granted) "✓" else "✕",
                    color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(end = 2.dp),
                )
                TextButton(onClick = onAction) { Text(actionText) }
            }
        },
    )
}

// ===== 状态查询 =====

internal fun isIgnoringBattery(ctx: Context): Boolean = try {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    pm.isIgnoringBatteryOptimizations(ctx.packageName)
} catch (_: Exception) {
    false
}

internal fun hasNotificationPermission(ctx: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    } else true
