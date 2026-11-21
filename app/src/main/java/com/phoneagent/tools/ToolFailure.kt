package com.phoneagent.tools

/**
 * 工具失败归因：区分「用户能自己解决的环境问题」和「软件真的出 bug」。
 *
 * 用户忘了给 Root / Shizuku 授权、文件不存在、路径不可写 —— 这些记进日志但不弹提示、
 * 不算问题数；只有真的抛异常（解析失败、IO 错误、模型异常）才当故障报。
 */
object ToolFailure {

    private val ENV_HINTS = listOf(
        "无执行权限", "请开启 root", "root 未授权", "shizuku", "未连接", "未授权",
        "permission denied", "permission_denied", "not permitted", "operation not permitted",
        "no such file", "not found", "command not found", "没有那个文件",
        "只有只读权限", "read-only", "路径不存在", "文件不存在",
        "请先授权", "请到设置页", "工作区限制", "路径超出工作区",
        "无可用的模型", "未配置", "超时", "timeout", "timed out",
        "connection refused", "unable to resolve host", "no address associated",
        "已按安全策略拒绝",
    )

    /** true = 环境/权限类（不打扰用户，不计入问题数）。 */
    fun isEnv(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val lower = text.lowercase()
        return ENV_HINTS.any { lower.contains(it.lowercase()) }
    }
}
