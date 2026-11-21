package com.phoneagent.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容协议客户端（覆盖豆包 / 通义 / DeepSeek / Ollama 等）。
 * 流式解析：content（正常回答）、reasoning_content（思维链，DeepSeek/Qwen3 等）、
 * tool_calls（函数调用增量，聚积后输出完整调用）。
 * 同时兼容非流式响应（部分 Provider 在工具调用时返回完整 JSON）。
 */
class OpenAiChatClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build(),
) : LlmGateway {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    override fun chat(
        messages: List<ChatMessage>,
        tools: List<ToolSpec>?,
        config: LlmConfig,
    ): Flow<AgentThought> = flow {
        // 深度思考强度 → reasoning_effort；OFF 或不启用则不传
        val thinkingEffort = when {
            !config.deepThinking -> null
            config.thinkingLevel == "LOW" -> "low"
            config.thinkingLevel == "MIDDLE" -> "medium"
            config.thinkingLevel == "MAX" -> "high"
            else -> null // OFF
        }
        val body = json.encodeToString(
            ChatRequest.serializer(),
            ChatRequest(
                model = config.model,
                messages = messages,
                tools = tools,
                stream = true,
                reasoning_effort = thinkingEffort,
                // 请求流式响应同时带 usage，供底部显示上下文占用/缓存占比
                stream_options = buildJsonObject { put("include_usage", JsonPrimitive(true)) },
            )
        ).toRequestBody(mediaType)

        val request = Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}/chat/completions")
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Accept", "text/event-stream")
            .post(body)
            .build()

        // 工具调用增量聚积：index -> (name, arguments)
        val toolAcc = mutableMapOf<Int, Pair<String?, StringBuilder>>()

        val call = client.newCall(request)
        // 协程被取消（用户点"停止"）时立刻掐断连接：
        // execute()/readLine() 都是阻塞调用，不 cancel 的话要等到读超时才退出
        val cancelHandle = currentCoroutineContext()[Job]
            ?.invokeOnCompletion { call.cancel() }
        try {
        call.execute().use { response ->
            if (!response.isSuccessful) {
                val err = response.body?.string().orEmpty()
                throw IllegalStateException("LLM 请求失败: HTTP ${response.code} $err")
            }

            val reader: BufferedReader = response.body?.charStream()?.let { BufferedReader(it) }
                ?: throw IllegalStateException("响应体为空")

            reader.use { r ->
                // 读取第一行（跳过前导空行和 SSE 注释行）判断是否 SSE 流
                var firstLine: String?
                while (true) {
                    firstLine = r.readLine()
                    if (firstLine == null) break
                    val t = firstLine.trim()
                    if (t.isNotEmpty() && !t.startsWith(":")) break
                }
                if (firstLine == null) {
                    // 空响应
                    return@use
                }

                if (firstLine.startsWith("data:", ignoreCase = true)) {
                    // SSE 流式响应
                    processSseLine(firstLine, toolAcc).forEach { emit(it) }
                    while (true) {
                        val line = r.readLine() ?: break
                        processSseLine(line, toolAcc).forEach { emit(it) }
                    }
                } else {
                    // 非流式响应：把剩余内容拼起来当完整 JSON 解析
                    val rest = r.readText()
                    val fullJson = firstLine + rest
                    processNonStreaming(fullJson, toolAcc).forEach { emit(it) }
                }
            }
        }
        } catch (e: java.io.IOException) {
            // 主动停止：不当成"请求失败"抛给上层
            if (!currentCoroutineContext().isActive) throw CancellationException("stopped", e)
            throw e
        } finally {
            cancelHandle?.dispose()
        }

        // 流结束：把聚积完成的工具调用输出为完整事件
        toolAcc.forEach { (_, pair) ->
            val name = pair.first
            val rawArgs = pair.second.toString()
            if (name != null && rawArgs.isNotBlank()) {
                emit(AgentThought(type = "tool_call", content = rawArgs, toolName = name))
            }
        }
    }.flowOn(Dispatchers.IO)

    /** 处理单行 SSE 数据，返回解析出的事件。 */
    private fun processSseLine(
        line: String,
        acc: MutableMap<Int, Pair<String?, StringBuilder>>,
    ): List<AgentThought> {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(":")) return emptyList()
        if (!trimmed.startsWith("data:", ignoreCase = true)) return emptyList()

        val payload = trimmed.removePrefix("data:").trim()
        if (payload == "[DONE]") return emptyList()

        return parseChunk(payload, acc)
    }

    /** 处理非流式完整 JSON 响应。 */
    private fun processNonStreaming(
        body: String,
        acc: MutableMap<Int, Pair<String?, StringBuilder>>,
    ): List<AgentThought> {
        val events = mutableListOf<AgentThought>()
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (_: Exception) {
            return events
        }
        val choices = root["choices"]?.jsonArray ?: return events
        if (choices.isEmpty()) return events

        val choice = choices[0].jsonObject
        val message = choice["message"]?.jsonObject ?: return events

        message["reasoning_content"]?.jsonPrimitive?.contentOrNull?.let {
            events += AgentThought(type = "reasoning", content = it)
        }
        message["content"]?.jsonPrimitive?.contentOrNull?.let {
            events += AgentThought(type = "text", content = it)
        }
        message["tool_calls"]?.jsonArray?.forEach { tc ->
            val obj = tc.jsonObject
            val index = obj["index"]?.jsonPrimitive?.intOrNull ?: 0
            val fn = obj["function"]?.jsonObject
            val name = fn?.get("name")?.jsonPrimitive?.contentOrNull
            val args = fn?.get("arguments")?.jsonPrimitive?.contentOrNull.orEmpty()
            if (name != null) {
                acc[index] = name to StringBuilder(args)
            }
        }
        return events
    }

    /**
     * 解析单个 SSE chunk。
     * @param acc 工具调用增量聚积器（跨 chunk 累积）
     */
    private fun parseChunk(
        payload: String,
        acc: MutableMap<Int, Pair<String?, StringBuilder>>,
    ): List<AgentThought> {
        val events = mutableListOf<AgentThought>()
        val root = try {
            json.parseToJsonElement(payload).jsonObject
        } catch (_: Exception) {
            return events
        }
        val choices = root["choices"]?.jsonArray ?: return events
        if (choices.isEmpty()) {
            // 部分 Provider 在最终 chunk 只带 usage（不含 choices），如 DeepSeek
            emitUsageIfPresent(root).forEach { events += it }
            return events
        }

        val choice = choices[0].jsonObject
        emitUsageIfPresent(root).forEach { events += it }
        // 部分实现把内容放在 message 而非 delta（非流式兼容）
        val delta = (choice["delta"] ?: choice["message"])?.jsonObject ?: return events

        delta["reasoning_content"]?.jsonPrimitive?.contentOrNull?.let {
            events += AgentThought(type = "reasoning", content = it)
        }
        delta["content"]?.jsonPrimitive?.contentOrNull?.let {
            events += AgentThought(type = "text", content = it)
        }
        delta["tool_calls"]?.jsonArray?.forEach { tc ->
            val obj = tc.jsonObject
            val index = obj["index"]?.jsonPrimitive?.intOrNull ?: 0
            val fn = obj["function"]?.jsonObject
            val name = fn?.get("name")?.jsonPrimitive?.contentOrNull
            val argsDelta = fn?.get("arguments")?.jsonPrimitive?.contentOrNull.orEmpty()

            val accEntry = acc.getOrPut(index) { name to StringBuilder() }
            accEntry.second.append(argsDelta)
            events += AgentThought(type = "tool_call_delta", content = argsDelta, toolName = name)
        }
        return events
    }

    /** 若 chunk 带 usage，输出 token 用量事件供底部显示。 */
    private fun emitUsageIfPresent(root: JsonObject): List<AgentThought> {
        val usage = root["usage"] as? JsonObject ?: return emptyList()
        val prompt = (usage["prompt_tokens"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull ?: 0
        val completion = (usage["completion_tokens"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull ?: 0
        val cached = (usage["prompt_tokens_details"] as? JsonObject)
            ?.let { it["cached_tokens"] as? kotlinx.serialization.json.JsonPrimitive }
            ?.intOrNull ?: 0
        val json = buildJsonObject {
            put("prompt", JsonPrimitive(prompt))
            put("completion", JsonPrimitive(completion))
            put("cached", JsonPrimitive(cached))
        }
        return listOf(AgentThought(type = "usage", content = json.toString()))
    }
}
