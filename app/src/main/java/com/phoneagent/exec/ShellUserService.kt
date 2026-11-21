package com.phoneagent.exec

/**
 * Shell 执行服务（Shizuku UserService）：
 *
 * 注意：Shizuku UserService 与普通 Bound Service 不同——
 * 类本身必须直接实现 IBinder（继承 AIDL Stub），Shizuku 会把实例直接当作 Binder 使用，
 * 而不是调用 onBind()。因此这里不继承 android.app.Service。
 *
 * 运行在 Shizuku 启动的独立进程（shell UID），可执行 pm/am/screencap 等命令。
 */
class ShellUserService : IShellService.Stub() {

    /**
     * Binder 事务缓冲区上限约 1MB，超过会 TransactionFailed。
     * 设 50 万字符（ASCII 约 500KB / 中文约 1.5MB），已是安全上限。
     * 更大输出应在命令端用 head/tail 分页，而非拉大此值。
     */
    private val maxOutputChars = 500000

    override fun exec(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            process.waitFor()
            val code = process.exitValue()
            val raw = if (code == 0) output else "ERROR($code): $error\n$output"
            if (raw.length > maxOutputChars) {
                raw.take(maxOutputChars) + "\n... [输出已截断，原始 ${raw.length} 字符]"
            } else {
                raw
            }
        } catch (e: Exception) {
            "EXCEPTION: ${e.message}"
        }
    }
}
