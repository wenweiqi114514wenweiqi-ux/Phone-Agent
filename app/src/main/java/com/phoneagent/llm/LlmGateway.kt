package com.phoneagent.llm

import kotlinx.coroutines.flow.Flow

/**
 * LLM 网关接口：统一 OpenAI 兼容协议。
 * 流式返回 AgentThought 事件，覆盖思维链 / 文本 / 工具调用。
 */
interface LlmGateway {
    /**
     * @param messages  拼装好的请求消息（含系统提示词 + 记忆 + 会话历史 + 当前输入）
     * @param tools     当前启用的工具清单（可空，表示纯对话）
     * @param config    当前生效的 Provider 配置
     */
    fun chat(
        messages: List<ChatMessage>,
        tools: List<ToolSpec>?,
        config: LlmConfig,
    ): Flow<AgentThought>
}
