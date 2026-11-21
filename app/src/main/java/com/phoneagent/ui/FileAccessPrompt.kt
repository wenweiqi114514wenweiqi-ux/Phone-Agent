package com.phoneagent.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.phoneagent.util.StorageAccess

/**
 * 启动即检测文件访问权限：
 * 工作区一般在 /storage/emulated/0 下，没有权限连预览都读不出来。
 * 缺权限 → 当场弹窗；用户去系统里开启后返回，弹窗自动消失并给一条"已开启"提示。
 * 「稍后」只对本次启动有效，下次冷启动会再检测一次（不覆盖用户的选择，但也不放任）。
 */
@Composable
fun StorageAccessGate(onGranted: () -> Unit = {}) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(StorageAccess.hasAccess(ctx)) }
    var dismissed by rememberSaveable { mutableStateOf(false) }
    var justGranted by remember { mutableStateOf(false) }

    // Android 10 及以下：走系统运行时权限
    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        granted = StorageAccess.hasAccess(ctx)
        if (granted) justGranted = true
        dismissed = false
        if (granted) onGranted()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val now = StorageAccess.hasAccess(ctx)
                if (now && !granted) {
                    justGranted = true
                    onGranted()
                }
                granted = now
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (granted) return

    // 启动后第一次检测就缺权限 → 弹
    if (dismissed) return

    AlertDialog(
        onDismissRequest = { dismissed = true },
        title = { Text("需要文件访问权限") },
        text = {
            Text(
                "工作区一般在手机存储里（例如 Download/工作区）。\n\n" +
                    "没有这个权限，应用读不到、也预览不了里面的文件，AI 改完的文件点开会是空白。\n\n" +
                    "开启后返回应用，提示会自动消失。"
            )
        },
        confirmButton = {
            TextButton(onClick = {
                if (StorageAccess.usesRuntimePermission()) {
                    runtimeLauncher.launch(StorageAccess.runtimePermissions())
                } else {
                    StorageAccess.openSettings(ctx)
                }
            }) { Text("去开启") }
        },
        dismissButton = {
            TextButton(onClick = { dismissed = true }) { Text("稍后") }
        },
    )
}

/** 兼容旧调用点：MainActivity 里原来叫 FileAccessGate。 */
@Composable
fun FileAccessGate() = StorageAccessGate()
