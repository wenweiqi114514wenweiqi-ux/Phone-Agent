package com.phoneagent.tools

/** 三档安全执行策略。 */
enum class SafetyMode {
    /** 全部允许：所有工具调用直接执行。 */
    ALLOW_ALL,

    /** 需要允许：高危工具弹窗确认，低危直接执行。 */
    CONFIRM_HIGH,

    /** 全部不允许：任何工具调用都要手动确认。 */
    CONFIRM_ALL,
}

/** 安全裁决结果。 */
enum class Decision { EXECUTE, CONFIRM, REFUSE }

/** 安全策略裁决器：依据档位与工具风险等级输出执行决策。 */
object SafetyPolicy {
    fun decide(mode: SafetyMode, risk: ToolRisk): Decision = when (mode) {
        SafetyMode.ALLOW_ALL -> Decision.EXECUTE
        SafetyMode.CONFIRM_HIGH -> if (risk == ToolRisk.HIGH) Decision.CONFIRM else Decision.EXECUTE
        SafetyMode.CONFIRM_ALL -> Decision.CONFIRM
    }

    fun parseRisk(raw: String): ToolRisk = if (raw.equals("high", ignoreCase = true)) ToolRisk.HIGH else ToolRisk.LOW
}
