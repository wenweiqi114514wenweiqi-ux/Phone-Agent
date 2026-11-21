package com.phoneagent.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.phoneagent.data.Appearance
import com.phoneagent.data.AppearanceRepository

/**
 * 统一字号体系：以中文可读性为准，正文 15-16sp、行高放宽，辅助小字不低于 11.5sp。
 * 全 App 所有页面都经 MaterialTheme.typography 取值，改这里即全局一致。
 */
private val AppTypography = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.5.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 24.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 13.5.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelSmall = TextStyle(fontSize = 11.5.sp, lineHeight = 16.sp),
)

/**
 * 应用主题：可配置主色 / 圆角 / 深浅 / 字号 / 动态取色。
 * 整体走圆角风格，圆角半径统一由 [Appearance.cornerDp] 推导。
 */
@Composable
fun PhoneAgentTheme(
    appearance: Appearance = Appearance(),
    content: @Composable () -> Unit,
) {
    val dark = when (appearance.darkMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current

    val colorScheme: ColorScheme = when {
        appearance.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> buildScheme(appearance.accent, dark)
    }

    val r = appearance.cornerDp.dp
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(r * 0.45f),
        small = RoundedCornerShape(r * 0.65f),
        medium = RoundedCornerShape(r),
        large = RoundedCornerShape(r * 1.2f),
        extraLarge = RoundedCornerShape(r * 1.5f),
    )

    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * appearance.fontScale)
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = shapes,
            typography = AppTypography,
            content = content,
        )
    }
}

private fun buildScheme(accentKey: String, dark: Boolean): ColorScheme {
    val seed = Color(
        AppearanceRepository.ACCENTS.firstOrNull { it.first == accentKey }?.second ?: 0xFF4F5BD5
    )
    return if (dark) {
        darkColorScheme(
            primary = lerp(seed, Color.White, 0.45f),
            onPrimary = lerp(seed, Color.Black, 0.65f),
            primaryContainer = lerp(seed, Color.Black, 0.58f),
            onPrimaryContainer = lerp(seed, Color.White, 0.88f),
            secondary = lerp(seed, Color.White, 0.62f),
            onSecondary = lerp(seed, Color.Black, 0.7f),
            secondaryContainer = lerp(seed, Color.Black, 0.62f),
            onSecondaryContainer = lerp(seed, Color.White, 0.88f),
            tertiary = lerp(seed, Color.White, 0.5f),
        )
    } else {
        lightColorScheme(
            primary = seed,
            onPrimary = Color.White,
            primaryContainer = lerp(seed, Color.White, 0.85f),
            onPrimaryContainer = lerp(seed, Color.Black, 0.45f),
            secondary = lerp(seed, Color.Black, 0.2f),
            onSecondary = Color.White,
            secondaryContainer = lerp(seed, Color.White, 0.9f),
            onSecondaryContainer = lerp(seed, Color.Black, 0.55f),
            tertiary = lerp(seed, Color.Black, 0.35f),
        )
    }
}
