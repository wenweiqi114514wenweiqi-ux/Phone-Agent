package com.phoneagent.exec

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku

/** Shizuku 运行状态。 */
enum class ShizukuStatus {
    /** 未安装 Shizuku App */
    NOT_INSTALLED,

    /** 已安装但服务未激活（需电脑 adb 激活） */
    NOT_ACTIVATED,

    /** 服务已激活但本 App 未获授权（需点授权或去 Shizuku App 手动添加） */
    NEED_PERMISSION,

    /** 已授权但 Shell UserService 绑定失败（需重新绑定/重启 Shizuku） */
    SERVICE_NOT_CONNECTED,

    /** 已授权且 Shell 服务已连接，完全可用 */
    READY,
}

/** Shizuku 三步诊断结果，用于设置页展示。 */
data class ShizukuDiagnostics(
    val shizukuInstalled: Boolean,
    val binderConnected: Boolean,
    val permissionGranted: Boolean,
    val shellServiceConnected: Boolean,
)

/**
 * Shizuku 生命周期管理：检测安装/激活/授权状态、请求权限、绑定 UserService。
 * UI 层观察 [status] 展示状态与引导操作。
 */
class ShizukuManager(private val context: Context) {

    private val _status = MutableStateFlow(currentStatus())
    val status: StateFlow<ShizukuStatus> = _status

    /** Shell 服务（运行于 Shizuku 进程）引用，READY 时非空。 */
    @Volatile
    var shellService: IShellService? = null
        private set

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null) shellService = IShellService.Stub.asInterface(binder)
            refresh()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellService = null
            refresh()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        if (Shizuku.pingBinder()) bindUserService()
        refresh()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        shellService = null
        refresh()
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == PERMISSION_CODE) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) bindUserService()
            refresh()
        }
    }

    init {
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        if (Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        ) {
            bindUserService()
        }
    }

    /** 是否已安装 Shizuku App。 */
    fun isShizukuInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (_: Exception) {
        false
    }

    /** 三步诊断：安装 → binder → 授权 → Shell 服务。 */
    fun diagnostics(): ShizukuDiagnostics = ShizukuDiagnostics(
        shizukuInstalled = isShizukuInstalled(),
        binderConnected = runCatching { Shizuku.pingBinder() }.getOrDefault(false),
        permissionGranted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false),
        shellServiceConnected = shellService != null,
    )

    /** 计算当前状态（严格：必须 Shell 服务已连接才算 READY）。 */
    fun currentStatus(): ShizukuStatus {
        if (!isShizukuInstalled()) return ShizukuStatus.NOT_INSTALLED
        if (!Shizuku.pingBinder()) return ShizukuStatus.NOT_ACTIVATED
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            return ShizukuStatus.NEED_PERMISSION
        }
        if (shellService == null) return ShizukuStatus.SERVICE_NOT_CONNECTED
        return ShizukuStatus.READY
    }

    /**
     * 请求 Shizuku 授权。必须传入 Activity context（Android 17 后台启动 Activity 受限），
     * 调用后系统会弹出 Shizuku 授权对话框。
     */
    fun requestPermission(activityContext: Context) {
        if (!Shizuku.pingBinder()) return
        try {
            Shizuku.requestPermission(PERMISSION_CODE)
        } catch (e: Exception) {
            // 授权框启动失败，引导用户去 Shizuku App 手动授权
        }
    }

    /** 打开 Shizuku App（引导激活/查看授权管理用）。 */
    fun openShizukuApp() {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        } catch (_: Exception) {
            // 未安装则忽略
        }
    }

    /** 重新绑定 Shell 服务（已授权但服务未连接时用）。 */
    fun retryBind() {
        shellService = null
        if (Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        ) {
            bindUserService()
        }
        refresh()
    }

    /**
     * 刷新授权：传入 Activity context。
     * 未授权 → 发起授权请求；已授权但未连接 → 重新绑定 Shell 服务。
     */
    fun refreshAuthorization(activityContext: Context) {
        if (!Shizuku.pingBinder()) {
            refresh()
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            requestPermission(activityContext)
        } else {
            retryBind()
        }
        refresh()
    }

    /**
     * 解除绑定：解绑 UserService 并清空本地状态。
     * 用于绑定卡住时重置，之后可重新点「刷新授权」或「请求授权」。
     * 注意：Shizuku 应用级授权需在 Shizuku App 里管理，此处只重置连接。
     */
    fun resetConnection() {
        try {
            val args = Shizuku.UserServiceArgs(ComponentName(context, ShellUserService::class.java))
            Shizuku.unbindUserService(args, connection, true)
        } catch (_: Exception) {
            // 未绑定时忽略
        }
        shellService = null
        refresh()
    }

    private fun bindUserService() {
        if (shellService != null) return
        try {
            val args = Shizuku.UserServiceArgs(ComponentName(context, ShellUserService::class.java))
                .daemon(false)
                .processNameSuffix("service")
                .version(1)
            Shizuku.bindUserService(args, connection)
        } catch (_: Exception) {
            // 未激活/未授权：稍后重试
        }
    }

    private fun refresh() {
        _status.value = currentStatus()
    }

    companion object {
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val PERMISSION_CODE = 10086
    }
}
