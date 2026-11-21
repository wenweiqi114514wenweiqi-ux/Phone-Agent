package com.phoneagent.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 外观偏好：主色、圆角、深浅模式、字体缩放、动态取色。 */
data class Appearance(
    val accent: String = "Indigo",
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
