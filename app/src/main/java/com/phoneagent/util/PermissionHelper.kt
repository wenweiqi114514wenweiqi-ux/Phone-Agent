package com.phoneagent.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.phoneagent.a11y.AgentAccessibilityService

/**
 * 权限状态查询与跳转：集中处理 Phone Use 相关系统权限。
 */
object PermissionHelper {

    /** 无障碍服务是否已开启（以服务连接状态为准）。 */
    fun isAccessibilityEnabled(): Boolean = AgentAccessibilityService.isEnabled()

    /** 修改系统设置权限（WRITE_SETTINGS）。 */
    fun isWriteSettingsGranted(context: Context): Boolean = Settings.System.canWrite(context)

    /** 悬浮窗权限。 */
    fun isOverlayGranted(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** 是否已加入电池优化白名单。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 跳转无障碍设置页。 */
    fun openAccessibilitySettings(context: Context) {
        safeStart(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    /** 跳转修改系统设置授权页。 */
    fun openWriteSettings(context: Context) {
        safeStart(
            context,
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")),
        )
    }

    /** 跳转悬浮窗授权页。 */
    fun openOverlaySettings(context: Context) {
        safeStart(
            context,
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
        )
    }

    /** 请求忽略电池优化（系统弹窗）。 */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            safeStart(
                context,
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${context.packageName}")),
            )
        }
    }

    /** 跳转应用详情页（用于厂商自启动/后台管理）。 */
    fun openAppDetails(context: Context) {
        safeStart(
            context,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}")),
        )
    }

    private fun safeStart(context: Context, intent: Intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {
            // 部分 ROM 缺少对应设置页，静默失败避免崩溃
        }
    }
}
