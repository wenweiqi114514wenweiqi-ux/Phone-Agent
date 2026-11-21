package com.phoneagent.exec

import java.util.concurrent.TimeUnit

/**
 * Root 执行通道：通过 su（Magisk / KernelSU / APatch 等授权管理器）执行命令。
 *
 * 说明：
 * - 首次调用时授权管理器会弹窗，用户需在管理器内允许本应用；
 * - 结果缓存到 [available]，避免每条命令都重新探测 su；
 * - 未 root 时所有调用返回 false，由上层回退到 Shizuku。
 */
object RootShell {

    /** root 状态缓存：null=未探测，true=可用，false=不可用。 */
    @Volatile
    var available: Boolean? = null
        private set

    /** 最近一次探测/授权失败的原始信息，供设置页展示。 */
    @Volatile
    var lastError: String? = null
        private set

    /** 探测 root 是否可用（会触发授权管理器弹窗）。 */
    fun detect(timeoutSec: Long = 8): Boolean {
        return try {
            val process = ProcessBuilder("su", "-c", "id")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            val ok = output.contains("uid=0")
            available = ok
            if (!ok) lastError = "su 返回：${output.trim().take(120)}"
            ok
        } catch (e: Exception) {
            available = false
            lastError = e.message
            false
        }
    }

    /** 强制重新探测（进程缓存可能因权限弹窗变化而过期）。 */
    fun redetect(): Boolean {
        available = null
        return detect()
    }

    /** 确保已探测过（只探测一次）。 */
    fun ensureDetected(): Boolean {
        available?.let { return it }
        return detect()
    }

    /**
     * 以 root 执行 shell 命令。
     * 返回 stdout+stderr 合并文本；失败/超时以 ERROR(...) 开头（与 Shizuku 通道约定一致）。
     */
    fun exec(command: String, timeoutSec: Long = 60): String {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val sb = StringBuilder()
            val readerThread = Thread {
                try {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        sb.append(line).append('\n')
                    }
                } catch (_: Exception) {
                    // 流关闭时忽略
                }
            }
            readerThread.start()
            val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return "ERROR(执行超时 ${timeoutSec}s)"
            }
            readerThread.join(1500)
            val text = sb.toString().trim()
            if (text.isBlank()) {
                // 命令成功但无输出属于正常（如 mkdir），返回空串交由上层判定
                if (process.exitValue() == 0) "" else "ERROR(exit ${process.exitValue()})"
            } else {
                text
            }
        } catch (e: Exception) {
            "ERROR(${e.message})"
        }
    }

    /** 状态描述，供 UI 展示。 */
    fun statusText(): String = when (available) {
        true -> "已授权（Root）"
        false -> "未授权" + (lastError?.let { "：$it" } ?: "")
        null -> "未检测"
    }
}
