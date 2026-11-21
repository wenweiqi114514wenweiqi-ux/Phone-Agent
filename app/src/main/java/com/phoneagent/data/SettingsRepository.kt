package com.phoneagent.data

import android.content.Context
import com.phoneagent.tools.SafetyMode

/**
 * 配置存储：SharedPreferences 轻量持久化。
 * 覆盖：LLM Provider 配置、安全三档、思维链/日志/重试开关。
 */
class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("phone_agent_settings", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, PRESETS.getValue("DeepSeek").first)!!
        set(value) = prefs.edit().putString(KEY_BASE_URL, value).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "")!!
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    var model: String
        get() = prefs.getString(KEY_MODEL, PRESETS.getValue("DeepSeek").second)!!
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    var safetyMode: SafetyMode
        get() = SafetyMode.valueOf(prefs.getString(KEY_SAFETY, SafetyMode.CONFIRM_HIGH.name)!!)
        set(value) = prefs.edit().putString(KEY_SAFETY, value.name).apply()

    var showReasoning: Boolean
        get() = prefs.getBoolean(KEY_REASONING, true)
        set(value) = prefs.edit().putBoolean(KEY_REASONING, value).apply()

    var logEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOG, true)
        set(value) = prefs.edit().putBoolean(KEY_LOG, value).apply()

    var autoRetry: Boolean
        get() = prefs.getBoolean(KEY_RETRY, true)
        set(value) = prefs.edit().putBoolean(KEY_RETRY, value).apply()

    var deepThinking: Boolean
        get() = prefs.getBoolean(KEY_DEEP_THINKING, false)
        set(value) = prefs.edit().putBoolean(KEY_DEEP_THINKING, value).apply()

    var thinkingLevel: String
        get() = prefs.getString(KEY_THINKING_LEVEL, "OFF")!!
        set(value) = prefs.edit().putString(KEY_THINKING_LEVEL, value).apply()

    /** 上下文长度（token），用于裁剪会话历史。 */
    var contextLength: Int
        get() = prefs.getInt(KEY_CONTEXT, 131072)
        set(value) = prefs.edit().putInt(KEY_CONTEXT, value).apply()

    /** 长期记忆总开关。关闭后不检索、不注入。 */
    var memoryEnabled: Boolean
        get() = prefs.getBoolean(KEY_MEMORY_ON, true)
        set(value) = prefs.edit().putBoolean(KEY_MEMORY_ON, value).apply()

    /** 用户自定义形象（AI 眼中的你），进系统提示词。 */
    var userPersona: String
        get() = prefs.getString(KEY_PERSONA, "")!!
        set(value) = prefs.edit().putString(KEY_PERSONA, value).apply()

    /** Markdown 代码块/表格圆角（dp）。 */
    var markdownRadius: Int
        get() = prefs.getInt(KEY_MD_RADIUS, 12)
        set(value) = prefs.edit().putInt(KEY_MD_RADIUS, value).apply()

    /** 输出长度档位：0 简短 / 1 适中 / 2 详细 / 3 详尽。 */
    var outputLength: Int
        get() = prefs.getInt(KEY_OUT_LEN, 1)
        set(value) = prefs.edit().putInt(KEY_OUT_LEN, value).apply()

    /** 输出风格：natural / concise / pro / playful / blunt。 */
    var outputStyle: String
        get() = prefs.getString(KEY_OUT_STYLE, "natural")!!
        set(value) = prefs.edit().putString(KEY_OUT_STYLE, value).apply()

    /** Emoji 使用量：0 不用 / 1 偶尔 / 2 适度 / 3 较多。 */
    var emojiLevel: Int
        get() = prefs.getInt(KEY_EMOJI, 1)
        set(value) = prefs.edit().putInt(KEY_EMOJI, value).apply()

    /** AI 的性格与特点（≤40 字）。 */
    /** 上次打开的会话 id：重启或切页回来时直接回到这个会话。 */
    var lastConversationId: String
        get() = prefs.getString("last_conversation", "default") ?: "default"
        set(value) = prefs.edit().putString("last_conversation", value).apply()

    /** 后台保活开关（持久化，启动时自动恢复）。 */
    var keepAliveEnabled: Boolean
        get() = prefs.getBoolean("keepalive_enabled", false)
        set(value) = prefs.edit().putBoolean("keepalive_enabled", value).apply()

    var aiPersona: String
        get() = prefs.getString(KEY_AI_PERSONA, "")!!
        set(value) = prefs.edit().putString(KEY_AI_PERSONA, value).apply()

    /** 已同意的声明版本号；0 表示从未同意（首启必弹声明页）。 */
    var legalAcceptedVersion: Int
        get() = prefs.getInt(KEY_LEGAL_VERSION, 0)
        set(value) = prefs.edit().putInt(KEY_LEGAL_VERSION, value).apply()

    /** 同意时间，仅用于「关于」页展示。 */
    var legalAcceptedAt: Long
        get() = prefs.getLong(KEY_LEGAL_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LEGAL_AT, value).apply()

    companion object {
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_SAFETY = "safety_mode"
        private const val KEY_REASONING = "show_reasoning"
        private const val KEY_LOG = "log_enabled"
        private const val KEY_RETRY = "auto_retry"
        private const val KEY_DEEP_THINKING = "deep_thinking"
        private const val KEY_THINKING_LEVEL = "thinking_level"
        private const val KEY_CONTEXT = "context_length"
        private const val KEY_MEMORY_ON = "memory_enabled"
        private const val KEY_PERSONA = "user_persona"
        private const val KEY_MD_RADIUS = "markdown_radius"
        private const val KEY_OUT_LEN = "output_length"
        private const val KEY_OUT_STYLE = "output_style"
        private const val KEY_EMOJI = "emoji_level"
        private const val KEY_AI_PERSONA = "ai_persona"
        private const val KEY_LEGAL_VERSION = "legal_accepted_version"
        private const val KEY_LEGAL_AT = "legal_accepted_at"

        /** 可选上下文长度（token）。 */
        val CONTEXT_OPTIONS: List<Int> = listOf(
            4096, 8192, 16384, 32768, 65536, 131072, 262144, 524288, 1048576,
        )

        /** 输出风格可选项（key 中文名）。 */
        val STYLES: List<Pair<String, String>> = listOf(
            "natural" to "自然口语",
            "concise" to "简洁直接",
            "pro" to "专业严谨",
            "playful" to "轻松活泼",
            "blunt" to "直白不客套",
        )

        /** 思考强度选项。 */
        val THINKING_LEVELS: List<String> = listOf("OFF", "LOW", "MIDDLE", "MAX")

        /** Provider 预设：name -> (base_url, 默认模型, 说明)。统一 OpenAI 兼容协议。 */
        val PRESETS: Map<String, Triple<String, String, String>> = linkedMapOf(
            "DeepSeek" to Triple("https://api.deepseek.com/v1", "deepseek-chat", "支持思维链 reasoning_content"),
            "豆包·火山方舟" to Triple("https://ark.cn-beijing.volces.com/api/v3", "", "填接入点模型 ID"),
            "通义千问" to Triple("https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", "qwen3 系列支持 reasoning"),
            "Ollama 远程" to Triple("http://192.168.1.100:11434/v1", "", "局域网模型，自填模型名"),
            "OpenAI" to Triple("https://api.openai.com/v1", "gpt-4o-mini", "OpenAI 官方"),
        )
    }
}
