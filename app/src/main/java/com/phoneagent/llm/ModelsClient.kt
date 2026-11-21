package com.phoneagent.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 模型信息（/models 接口返回）。 */
data class ModelInfo(
    val id: String,
    val ownedBy: String? = null,
)

/**
 * 调用 OpenAI 兼容协议的 GET /models 接口，拉取当前 API Key 可用的模型列表。
 * 用于设置页：填完 Base URL + API Key 后一键获取可选模型。
 */
class ModelsClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun listModels(baseUrl: String, apiKey: String): List<ModelInfo> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) throw IllegalStateException("请先填写 Base URL")
        if (apiKey.isBlank()) throw IllegalStateException("请先填写 API Key")

        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/models")
            .addHeader("Authorization", "Bearer $apiKey")
            .get()
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string()?.take(200).orEmpty()
                throw IllegalStateException("HTTP ${resp.code}: $errBody")
            }
            val body = resp.body?.string().orEmpty()
            val root = json.parseToJsonElement(body).jsonObject
            val data = root["data"]?.jsonArray ?: return@withContext emptyList()
            data.mapNotNull { el ->
                val obj = el.jsonObject
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val ownedBy = obj["owned_by"]?.jsonPrimitive?.contentOrNull
                ModelInfo(id = id, ownedBy = ownedBy)
            }.sortedBy { it.id }
        }
    }
}
