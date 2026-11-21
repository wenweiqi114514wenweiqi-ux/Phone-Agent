package com.phoneagent.exec

import com.phoneagent.tools.ParsedCommand

/** 执行结果。 */
data class ExecResult(
    val ok: Boolean,
    val output: String,
    val error: String? = null,
)

/** 命令执行器接口：消费标准化指令，返回执行结果。 */
interface CommandExecutor {
    /** 执行层是否就绪（Shizuku 是否已激活）。 */
    val isReady: Boolean

    /** 下载/长任务进度回调（percent 0-100，-1 表示未知总量）。执行器有耗时任务时可注入。 */
    var onProgress: ((percent: Int, bytes: Long) -> Unit)?

    suspend fun execute(command: ParsedCommand): ExecResult
}
