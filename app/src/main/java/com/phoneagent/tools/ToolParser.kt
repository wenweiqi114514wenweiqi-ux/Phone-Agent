package com.phoneagent.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 工具调用解析器：
 * 1. 把 LLM 的 tool_call / 命令文本解析为标准化指令（ParsedCommand）；
 * 2. 白名单与参数校验：只允许注册表内工具；参数按 schema 必填校验；
 * 3. 命令渲染：用参数渲染 commandTemplate，并阻断 shell 拼接字符注入。
 */
class ToolParser(
    private val registry: ToolRegistry,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** 被禁止出现在参数值中的 shell 元字符（防注入）。 */
    private val forbiddenChars = listOf(";", "|", "&", ">", "<", "`", "$", "\\", "\n", "\r")

    /**
     * @param toolName 工具名（注册表内）
     * @param args     LLM 填写的参数（JsonObject）
     * @throws IllegalArgumentException 校验失败时抛出，由上层转成错误反馈
     */
    suspend fun parse(toolName: String, args: JsonObject): ParsedCommand {
        val tool = registry.byName(toolName)
            ?: throw IllegalArgumentException("未知工具: $toolName（不在注册表中）")

        if (!tool.enabled) {
            throw IllegalArgumentException("工具已被禁用: $toolName")
        }

        validateArgs(tool, args)
        return ParsedCommand(tool = tool, args = args)
    }

    /** 把 LLM 输出的命令文本块（回退协议）解析为指令，如 {"tool":"pm_uninstall","args":{...}}。 */
    suspend fun parseCommandText(text: String): ParsedCommand {
        val obj = try {
            json.parseToJsonElement(text.trim().trim('`')).jsonObject
        } catch (_: Exception) {
            throw IllegalArgumentException("命令文本格式无法解析: $text")
        }
        val name = obj["tool"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("命令文本缺少 tool 字段")
        val args = obj["args"]?.let {
            if (it is JsonObject) it else JsonObject(emptyMap())
        } ?: JsonObject(emptyMap())
        return parse(name, args)
    }

    /** 这些工具的参数本身可能包含 shell 元字符（写文件内容、通用命令、HTTP body），跳过注入过滤，由高危确认兜底。 */
    private val skipFilterTools = setOf("shell_exec", "file_write", "file_append", "http_post", "curl_exec")

    /** 用参数渲染最终 shell 命令（执行层消费），并做注入阻断。 */
    fun renderShell(tool: ToolDefinition, args: JsonObject): String {
        val template = tool.commandTemplate
            ?: throw IllegalArgumentException("工具 ${tool.name} 无命令模板，无法以 shell 方式执行")
        var result = template
        val skipFilter = tool.name in skipFilterTools
        for ((key, value) in args) {
            var raw = value.jsonPrimitive.contentOrNull ?: ""
            if (!skipFilter) {
                forbiddenChars.forEach { c -> raw = raw.replace(c, "") }
            }
            result = result.replace("{${key}}", raw)
        }
        return result
    }

    private fun validateArgs(tool: ToolDefinition, args: JsonObject) {
        val schema = tool.parameters
        val required = schema["required"]?.let {
            (it as? kotlinx.serialization.json.JsonArray)?.mapNotNull { el ->
                el.jsonPrimitive.contentOrNull
            }
        } ?: emptyList()

        required.forEach { key ->
            if (!args.containsKey(key)) {
                throw IllegalArgumentException("工具 ${tool.name} 缺少必填参数: $key")
            }
        }
        // TODO: 按 schema 的 type 做类型校验（骨架先只查必填）
    }
}
