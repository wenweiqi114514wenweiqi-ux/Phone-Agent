package com.phoneagent.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 外观偏好：主色、圆角、深浅模式、字体缩放、动态取色。 */
data class Appearance(
    val accent: String = "Indigo",
    /** 自定义主色 ARGB；0 表示沿用 [accent] 预设 */
    val accentCustom: Long = 0L,
    /** 自定义背景色 ARGB；0 表示用主题默认 */
    val backgroundCustom: Long = 0L,
    /** 自定义面板 / 卡片色 ARGB；0 表示用主题默认 */
    val surfaceCustom: Long = 0L,
    /** 自定义辅助色（secondary）ARGB；0 表示由主色推导 */
    val secondaryCustom: Long = 0L,
    /** 自定义强调色（tertiary）ARGB；0 表示由主色推导 */
    val tertiaryCustom: Long = 0L,
    val cornerDp: Int = 20,
    /** 0=跟随系统 1=浅色 2=深色 */
    val darkMode: Int = 0,
    val fontScale: Float = 1f,
    val dynamicColor: Boolean = false,
)

/**
 * 外观偏好存储。写入同时更新 StateFlow，主题层实时重组。
 */
class AppearanceRepository(context: Context) {

    private val prefs = context.getSharedPreferences("phone_agent_appearance", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        Appearance(
            accent = prefs.getString(KEY_ACCENT, "Indigo") ?: "Indigo",
            accentCustom = prefs.getLong(KEY_ACCENT_CUSTOM, 0L),
            backgroundCustom = prefs.getLong(KEY_BG_CUSTOM, 0L),
            surfaceCustom = prefs.getLong(KEY_SURFACE_CUSTOM, 0L),
            secondaryCustom = prefs.getLong(KEY_SECONDARY_CUSTOM, 0L),
            tertiaryCustom = prefs.getLong(KEY_TERTIARY_CUSTOM, 0L),
            cornerDp = prefs.getInt(KEY_CORNER, 20).coerceIn(6, 32),
            darkMode = prefs.getInt(KEY_DARK, 0).coerceIn(0, 2),
            fontScale = prefs.getFloat(KEY_FONT, 1f).coerceIn(0.8f, 1.3f),
            dynamicColor = prefs.getBoolean(KEY_DYNAMIC, false),
        )
    )

    val state: StateFlow<Appearance> = _state.asStateFlow()

    fun update(transform: (Appearance) -> Appearance) {
        val next = transform(_state.value)
        _state.value = next
        prefs.edit()
            .putString(KEY_ACCENT, next.accent)
            .putLong(KEY_ACCENT_CUSTOM, next.accentCustom)
            .putLong(KEY_BG_CUSTOM, next.backgroundCustom)
            .putLong(KEY_SURFACE_CUSTOM, next.surfaceCustom)
            .putLong(KEY_SECONDARY_CUSTOM, next.secondaryCustom)
            .putLong(KEY_TERTIARY_CUSTOM, next.tertiaryCustom)
            .putInt(KEY_CORNER, next.cornerDp)
            .putInt(KEY_DARK, next.darkMode)
            .putFloat(KEY_FONT, next.fontScale)
            .putBoolean(KEY_DYNAMIC, next.dynamicColor)
            .apply()
    }

    fun reset() {
        update { Appearance() }
    }

    companion object {
        private const val KEY_ACCENT = "accent"
        private const val KEY_ACCENT_CUSTOM = "accent_custom"
        private const val KEY_BG_CUSTOM = "bg_custom"
        private const val KEY_SURFACE_CUSTOM = "surface_custom"
        private const val KEY_SECONDARY_CUSTOM = "secondary_custom"
        private const val KEY_TERTIARY_CUSTOM = "tertiary_custom"
        private const val KEY_CORNER = "corner"
        private const val KEY_DARK = "dark"
        private const val KEY_FONT = "font_scale"
        private const val KEY_DYNAMIC = "dynamic_color"

        /** 可选主色（key -> ARGB）。 */
        val ACCENTS: List<Pair<String, Long>> = listOf(
            "Indigo" to 0xFF4F5BD5,
            "Blue" to 0xFF2F6FED,
            "Teal" to 0xFF00897B,
            "Green" to 0xFF2E9E4F,
            "Orange" to 0xFFF57C00,
            "Pink" to 0xFFE0578E,
            "Purple" to 0xFF8E44D0,
            "Slate" to 0xFF546E7A,
        )
    }
}
