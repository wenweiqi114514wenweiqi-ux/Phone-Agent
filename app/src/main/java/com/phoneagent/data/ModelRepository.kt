package com.phoneagent.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 一个已保存的模型配置。 */
@Serializable
data class ModelProfile(
    val id: String,
    val name: String,
    val provider: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    /** 该模型自身支持的最大上下文（token），用于裁剪上限。 */
    val maxContext: Int = 131072,
    val createdAt: Long = 0L,
)

/** 模型列表 + 当前选中项。 */
data class ModelState(
    val profiles: List<ModelProfile> = emptyList(),
    val activeId: String? = null,
) {
    val active: ModelProfile? get() = profiles.firstOrNull { it.id == activeId }
}

/** 添加模型时的预设模板。 */
data class ModelTemplate(
    val key: String,
    val label: String,
    val baseUrl: String,
    val model: String,
    val maxContext: Int,
    val hint: String,
)

/**
 * 多模型配置仓储：列表 + 单选生效，支持增删改与快速切换。
 * 旧版单配置（SettingsRepository.baseUrl/apiKey/model）会在首次启动时迁移为一条记录。
 */
class ModelRepository(
    context: Context,
    private val settings: SettingsRepository,
) {
    private val prefs = context.getSharedPreferences("phone_agent_models", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listSerializer = kotlinx.serialization.builtins.ListSerializer(ModelProfile.serializer())

    private val _state = MutableStateFlow(load())
    val state: StateFlow<ModelState> = _state.asStateFlow()

    private fun load(): ModelState {
        val raw = prefs.getString(KEY_LIST, null)
        val profiles = if (raw.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching { json.decodeFromString<List<ModelProfile>>(raw) }.getOrDefault(emptyList())
        }
        var activeId = prefs.getString(KEY_ACTIVE, null)
        if (activeId != null && profiles.none { it.id == activeId }) activeId = null
        // 若列表非空但没有选中项，默认选第一个
        if (activeId == null && profiles.isNotEmpty()) activeId = profiles.first().id
        return ModelState(profiles, activeId)
    }

    private fun persist(state: ModelState) {
        _state.value = state
        prefs.edit()
            .putString(KEY_LIST, json.encodeToString(listSerializer, state.profiles))
            .putString(KEY_ACTIVE, state.activeId)
            .apply()
    }

    fun active(): ModelProfile? = _state.value.active

    /** 新增模型；返回新记录。 */
    fun add(profile: ModelProfile): ModelProfile {
        val p = if (profile.createdAt == 0L) profile.copy(createdAt = System.currentTimeMillis()) else profile
        val next = _state.value.profiles + p
        persist(ModelState(next, _state.value.activeId ?: p.id))
        return p
    }

    fun update(profile: ModelProfile) {
        val next = _state.value.profiles.map { if (it.id == profile.id) profile else it }
        persist(_state.value.copy(profiles = next, activeId = _state.value.activeId))
    }

    fun delete(id: String) {
        val next = _state.value.profiles.filterNot { it.id == id }
        val active = if (_state.value.activeId == id) next.firstOrNull()?.id else _state.value.activeId
        persist(ModelState(next, active))
    }

    fun setActive(id: String) {
        if (_state.value.profiles.none { it.id == id }) return
        persist(_state.value.copy(activeId = id))
    }

    /** 旧版单模型配置迁移：仅当列表为空且旧配置填过 Key 时执行。 */
    fun migrateLegacyIfNeeded() {
        if (_state.value.profiles.isNotEmpty()) return
        if (settings.apiKey.isBlank()) return
        val host = settings.baseUrl
        val provider = when {
            host.contains("deepseek") -> "DeepSeek"
            host.contains("dashscope") || host.contains("aliyun") -> "通义千问"
            host.contains("openai") -> "OpenAI"
            host.contains("bigmodel") -> "智谱 GLM"
            host.contains("moonshot") -> "Kimi"
            else -> "自定义"
        }
        add(
            ModelProfile(
                id = "legacy_${System.currentTimeMillis()}",
                name = provider + " · " + settings.model.ifBlank { "默认" },
                provider = provider,
                baseUrl = settings.baseUrl,
                apiKey = settings.apiKey,
                model = settings.model,
                maxContext = 131072,
            )
        )
    }

    companion object {
        private const val KEY_LIST = "model_list"
        private const val KEY_ACTIVE = "active_model"

        /** 添加模型时的模板：填 API Key 即可保存。 */
        val TEMPLATES: List<ModelTemplate> = listOf(
            ModelTemplate("DeepSeek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", 1_000_000, "支持 reasoning · 长上下文"),
            ModelTemplate("Qwen", "通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", 131072, "qwen 系列支持 reasoning"),
            ModelTemplate("OpenAI", "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini", 131072, "官方 OpenAI 兼容接口"),
            ModelTemplate("Zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash", 131072, "免费额度较多"),
            ModelTemplate("Moonshot", "Kimi", "https://api.moonshot.cn/v1", "moonshot-v1-8k", 8192, "长文可改 moonshot-v1-128k"),
            ModelTemplate("Custom", "自定义", "", "", 131072, "自己填 Base URL 与模型名"),
        )
    }
}
