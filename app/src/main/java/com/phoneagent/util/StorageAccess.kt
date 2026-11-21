package com.phoneagent.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 存储权限的唯一入口。
 * - Android 11+（API 30）：工作区常在 /storage/emulated/0 下，直接读写需要「所有文件访问权限」
 * - Android 8–10（API 26–29）：用运行时 READ/WRITE_EXTERNAL_STORAGE
 * 所有判断都走这里，避免各页面自己写 isExternalStorageManager 造成口径不一致。
 */
object StorageAccess {

    /** 当前是否已经拿到能读写工作区的能力。 */
    fun hasAccess(ctx: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        else ->
            ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
    }

    /** 是否需要走系统运行时权限（只有 Android 10 及以下）。 */
    fun usesRuntimePermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.R

    fun runtimePermissions(): Array<String> = arrayOf(
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.READ_EXTERNAL_STORAGE,
    )

    /** 申请「所有文件访问权限」的设置页；个别 ROM 没有分应用页面，退回总开关页面。 */
    fun settingsIntents(ctx: Context): List<Intent> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + ctx.packageName),
                )
            )
            add(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
        add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
    }

    /** 依次尝试上面的入口，全部失败就什么都不做（不抛异常给 UI）。 */
    fun openSettings(ctx: Context) {
        settingsIntents(ctx).forEach { intent ->
            val ok = runCatching {
                ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (ok) return
        }
    }
}
