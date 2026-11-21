package com.phoneagent

import android.app.Application
import com.phoneagent.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application 入口：持有手动 DI 容器（骨架阶段不引入 Hilt，保持依赖面最小）。
 * TODO: 后续如模块膨胀，可迁移到 Hilt。
 */
class PhoneAgentApp : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        com.phoneagent.util.AppLog.init(this)
        installCrashLogger()
        // 1.0.7 的兜底曾把迁移失败的库改名备份过；这里把它原样救回来，数据不丢
        restoreBrokenBackupIfNeeded()
        // 1.0.10 曾为交付物复制过副本；新机制直接读原文件，清掉这些副本省空间
        runCatching {
            val dir = java.io.File(filesDir, "submissions")
            if (dir.exists()) {
                var freed = 0L
                dir.listFiles()?.forEach { f ->
                    freed += f.length()
                    f.delete()
                }
                if (freed > 0) {
                    com.phoneagent.util.AppLog.info("storage", "已清理交付物旧副本 ${freed / 1024}KB")
                }
            }
        }
        container = AppContainer(this)
        // 迁移在首次访问库时发生；提前打开并兜底，避免升级后一进就闪退
        runCatching { container.database.openHelper.writableDatabase }
            .onFailure { e -> recoverFromDbFailure(e) }
        appScope.launch {
            // 任何一项启动任务失败都不能带崩进程
            runCatching { container.initDefaults() }
                .onFailure { logStartupFailure("initDefaults", it) }
            runCatching { com.phoneagent.exec.RootShell.redetect() }
                .onFailure { logStartupFailure("rootRedetect", it) }
            runCatching { container.shizukuManager.retryBind() }
                .onFailure { logStartupFailure("shizukuBind", it) }
            if (runCatching { container.settings.keepAliveEnabled }.getOrDefault(false)) {
                runCatching { com.phoneagent.service.KeepAliveService.start(this@PhoneAgentApp) }
                    .onFailure { logStartupFailure("keepAlive", it) }
            }
        }
    }

    /** 启动期异常：写日志，不抛出（非致命，App 继续可用）。 */
    private fun logStartupFailure(stage: String, error: Throwable) {
        com.phoneagent.util.AppLog.warn("startup", "启动阶段失败：$stage", error)
        runCatching {
            java.io.File(filesDir, "startup_error.txt").writeText(
                "time=" + java.util.Date() + "\nstage=" + stage + "\n" +
                    android.util.Log.getStackTraceString(error)
            )
            // 外部私有目录，便于用文件管理器取出
            getExternalFilesDir("logs")?.let { dir ->
                dir.mkdirs()
                java.io.File(dir, "startup_error.txt").writeText(
                    "time=" + java.util.Date() + "\nstage=" + stage + "\n" +
                        android.util.Log.getStackTraceString(error)
                )
            }
        }
    }

    /** 写入最近一次未捕获异常，供「设置 → 关于」查看。 */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val body = buildString {
                    append("time=").append(java.util.Date()).append("\n")
                    append("thread=").append(thread.name).append("\n")
                    append(android.util.Log.getStackTraceString(error))
                }
                java.io.File(filesDir, "crash_last.txt").writeText(body)
                getExternalFilesDir("logs")?.let { dir ->
                    dir.mkdirs()
                    java.io.File(dir, "crash_last.txt").writeText(body)
                }
                CrashStore.save(this@PhoneAgentApp, body)
                com.phoneagent.util.AppLog.error("crash", "未捕获异常（${thread.name}）", error)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * 1.0.7 的遗留：迁移失败时把库改名为 phone_agent.db.broken-*。
     * 现在迁移已修复，启动时若发现主库不存在/为空，就把最近一次备份恢复回来。
     */
    private fun restoreBrokenBackupIfNeeded() {
        runCatching {
            val dir = filesDir
            val latest = dir.listFiles { f -> f.name.startsWith("phone_agent.db.broken-") }
                ?.maxByOrNull { it.lastModified() }
                ?: return@runCatching
            val main = java.io.File(dir, "phone_agent.db")
            val isEmpty = !main.exists() || main.length() < 16_384L
            if (!isEmpty) return@runCatching
            // 每个备份只恢复一次：避免「恢复→迁移失败→再备份→再恢复」的死循环
            val prefs = getSharedPreferences("phone_agent_recovery", MODE_PRIVATE)
            val attempted = prefs.getStringSet("attempted", emptySet()) ?: emptySet()
            if (latest.name in attempted) return@runCatching
            prefs.edit().putStringSet("attempted", attempted + latest.name).apply()
            if (main.exists()) {
                main.renameTo(java.io.File(dir, "phone_agent.db.empty-" + System.currentTimeMillis()))
            }
            latest.renameTo(main)
            com.phoneagent.util.AppLog.warn("db", "已从备份恢复数据库：${latest.name}")
            val ts = latest.name.substringAfter("broken-", "")
            listOf("phone_agent.db-wal", "phone_agent.db-shm").forEach { n ->
                val f = java.io.File(dir, "$n.broken-$ts")
                if (f.exists()) f.renameTo(java.io.File(dir, n))
            }
        }
    }

    /**
     * 数据库升级失败：只记录，不碰用户文件（改名会让聊天记录"消失"）。
     * 迁移本身已经做成幂等补列，正常情况下不会再走到这里。
     */
    private fun recoverFromDbFailure(error: Throwable) {
        com.phoneagent.util.AppLog.error("db", "数据库打开/迁移失败", error)
        runCatching {
            java.io.File(filesDir, "db_error.txt").writeText(
                "time=" + java.util.Date() + "\n" + android.util.Log.getStackTraceString(error)
            )
        }
        // 绝不改名/删除用户库：宁可这次读不到数据，也不让聊天记录“消失”。
    }

    companion object {
        lateinit var instance: PhoneAgentApp
            private set
    }
}
