package com.phoneagent.llm

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** 统一消息体（OpenAI 兼容协议）。 */
@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
    /** DeepSeek reasoning 模型要求多轮对话时回传上一轮的思维链。 */
    @SerialName("reasoning_content")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val reasoningContent: String? = null,
)

/** 工具调用（Function Calling）参数定义，注入 LLM 请求。 */
@Serializable
data class ToolSpec(
    val type: String = "function",
    val function: FunctionSpec,
)

@Serializable
data class FunctionSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject, // JSON Schema
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec>? = null,
    val stream: Boolean = true,
    val temperature: Double = 0.2,
    val max_tokens: Int? = null,
    /** 深度思考强度（OpenAI o 系列 / 部分兼容模型的 reasoning_effort）。OFF 时忽略。 */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val reasoning_effort: String? = null,
    /** 请求流式响应同时返回 usage（DeepSeek / Qwen 等支持）。 */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val stream_options: JsonObject? = null,
)

/** LLM 单次请求的 token 用量（用于底部显示上下文占用 / 缓存占比）。 */
@Serializable
data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cachedTokens: Int = 0,
) {
    val totalTokens: Int get() = promptTokens + completionTokens
    /** 缓存命中占 prompt 的比例（0~1）。 */
    val cacheRatio: Double
        get() = if (promptTokens > 0) cachedTokens.toDouble() / promptTokens else 0.0
}

/** 模型返回的单个工具调用。 */
@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject,
)

/**
 * 统一思维链/过程事件，UI 过程面板逐步渲染。
 * type: reasoning（模型原生思考）/ plan（计划块）/ tool_call / command / result / text
 */
data class AgentThought(
    val type: String,
    val content: String,
    val toolName: String? = null,
    val ts: Long = System.currentTimeMillis(),
)

/** LLM 配置：多 Provider 可配置，统一 OpenAI 兼容协议。 */
data class LlmConfig(
    val baseUrl: String, // 如 https://api.deepseek.com/v1
    val apiKey: String,
    val model: String,
    /** 是否启用深度思考（总开关）。 */
    val deepThinking: Boolean = false,
    /** 思考强度：OFF / LOW / MIDDLE / MAX。OFF 或未开启深度思考时不生效。 */
    val thinkingLevel: String = "OFF",
)
