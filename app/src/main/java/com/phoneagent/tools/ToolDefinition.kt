package com.phoneagent.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** 工具风险等级。 */
enum class ToolRisk { LOW, HIGH }

/** 工具执行方式。 */
enum class ToolHandler { SHELL, API, UI, COMPOSE }

/**
 * 工具定义：注册表条目（对应技术方案书 §5.1 的 JSON Schema）。
 * parameters 为 JSON Schema（JsonObject），供 LLM 填参与本地校验。
 */
@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject = JsonObject(emptyMap()),
    val handler: String = "shell",        // shell | api | compose
    val commandTemplate: String? = null,  // 如 "pm uninstall {pkg}"
    val risk: String = "low",             // low | high
    val enabled: Boolean = true,
    val builtin: Boolean = false,
)

/** 解析后的标准化指令，供执行层消费。 */
data class ParsedCommand(
    val tool: ToolDefinition,
    var args: JsonObject,
)
