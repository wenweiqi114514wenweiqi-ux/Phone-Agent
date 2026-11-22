package com.phoneagent.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
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
        else -> buildScheme(appearance, dark)
    }

    // 状态栏 / 手势条是透明的，颜色由顶栏、底栏透上来；这里只负责图标明暗，
    // 否则自定义浅色背景上仍是白色图标，等于看不见。
    val barColor = colorScheme.background
    val view = LocalView.current
    SideEffect {
        val activity = view.context.findActivity() ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(activity.window, view)
        val lightIcons = barColor.luminance() > 0.5f
        controller.isAppearanceLightStatusBars = lightIcons
        controller.isAppearanceLightNavigationBars = lightIcons
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

private fun buildScheme(appearance: Appearance, dark: Boolean): ColorScheme {
    val seed = if (appearance.accentCustom != 0L) {
        Color(appearance.accentCustom)
    } else {
        Color(
            AppearanceRepository.ACCENTS.firstOrNull { it.first == appearance.accent }?.second
                ?: 0xFF4F5BD5
        )
    }
    // 辅助色 / 强调色：没自定义时仍由主色推导，自定义后各自独立配色
    val seed2 = if (appearance.secondaryCustom != 0L) Color(appearance.secondaryCustom) else seed
    val seed3 = if (appearance.tertiaryCustom != 0L) Color(appearance.tertiaryCustom) else seed

    val base = if (dark) {
        darkColorScheme(
            primary = lerp(seed, Color.White, 0.45f),
            onPrimary = lerp(seed, Color.Black, 0.65f),
            primaryContainer = lerp(seed, Color.Black, 0.58f),
            onPrimaryContainer = lerp(seed, Color.White, 0.88f),
            secondary = lerp(seed2, Color.White, 0.62f),
            onSecondary = lerp(seed2, Color.Black, 0.7f),
            secondaryContainer = lerp(seed2, Color.Black, 0.62f),
            onSecondaryContainer = lerp(seed2, Color.White, 0.88f),
            tertiary = lerp(seed3, Color.White, 0.5f),
            tertiaryContainer = lerp(seed3, Color.Black, 0.58f),
            onTertiaryContainer = lerp(seed3, Color.White, 0.88f),
        )
    } else {
        lightColorScheme(
            primary = seed,
            onPrimary = Color.White,
            primaryContainer = lerp(seed, Color.White, 0.85f),
            onPrimaryContainer = lerp(seed, Color.Black, 0.45f),
            secondary = lerp(seed2, Color.Black, 0.2f),
            onSecondary = Color.White,
            secondaryContainer = lerp(seed2, Color.White, 0.9f),
            onSecondaryContainer = lerp(seed2, Color.Black, 0.55f),
            tertiary = lerp(seed3, Color.Black, 0.35f),
            tertiaryContainer = lerp(seed3, Color.White, 0.85f),
            onTertiaryContainer = lerp(seed3, Color.Black, 0.5f),
        )
    }

    // 没动过任何颜色时，直接用 Material 默认方案，保持原生观感。
    val anyCustomColor = appearance.backgroundCustom != 0L ||
        appearance.surfaceCustom != 0L ||
        appearance.accentCustom != 0L ||
        appearance.secondaryCustom != 0L ||
        appearance.tertiaryCustom != 0L
    if (!anyCustomColor) return base

    // 自定义底色 / 面板色：其余所有角色都从这里推导，不再让某些栏位停在旧颜色上。
    val bg = if (appearance.backgroundCustom != 0L) Color(appearance.backgroundCustom) else base.background
    val rawSurface = when {
        appearance.surfaceCustom != 0L -> Color(appearance.surfaceCustom)
        // 只改背景时，卡片跟着背景走一层，而不是继续用原来的纯白/纯黑
        appearance.backgroundCustom != 0L -> lerp(bg, readableOn(bg), 0.06f)
        else -> base.surface
    }
    val sf = separatedLayer(rawSurface, bg)
    val onBg = readableOn(bg)
    val onSf = readableOn(sf)

    // 卡片内部的层级：全都基于「面板色 + 前景色」的明度阶梯，不再使用低透明度叠色。
    fun layer(t: Float) = lerp(sf, onSf, t)
    val variant = layer(0.075f)
    val containerLowest = layer(0.025f)
    val containerLow = layer(0.05f)
    val container = layer(0.08f)
    val containerHigh = layer(0.115f)
    val containerHighest = layer(0.15f)

    val primary = ensureContrast(seed, bg, 3.2f)
    val primaryContainer = lerp(seed, bg, if (dark) 0.55f else 0.24f)
    val secondary = ensureContrast(seed2, bg, 3.0f)
    val secondaryContainer = lerp(seed2, bg, if (dark) 0.58f else 0.26f)
    val tertiary = ensureContrast(seed3, bg, 3.0f)
    val tertiaryContainer = lerp(seed3, bg, if (dark) 0.58f else 0.26f)
    val error = ensureContrast(base.error, bg, 3.0f)
    val errorContainer = lerp(base.error, bg, if (dark) 0.62f else 0.80f)

    return base.copy(
        primary = primary,
        onPrimary = readableOn(primary),
        primaryContainer = primaryContainer,
        onPrimaryContainer = readableOn(primaryContainer),
        inversePrimary = ensureContrast(seed, onBg, 3.0f),
        secondary = secondary,
        onSecondary = readableOn(secondary),
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = readableOn(secondaryContainer),
        tertiary = tertiary,
        onTertiary = readableOn(tertiary),
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = readableOn(tertiaryContainer),
        background = bg,
        onBackground = onBg,
        surface = sf,
        onSurface = onSf,
        surfaceVariant = variant,
        onSurfaceVariant = ensureContrast(lerp(onSf, sf, 0.18f), sf, 3.0f),
        surfaceTint = primary,
        surfaceDim = layer(0.0f),
        surfaceBright = lerp(sf, onSf, 0.20f),
        surfaceContainerLowest = containerLowest,
        surfaceContainerLow = containerLow,
        surfaceContainer = container,
        surfaceContainerHigh = containerHigh,
        surfaceContainerHighest = containerHighest,
        outline = ensureContrast(lerp(onSf, sf, 0.55f), sf, 1.8f),
        outlineVariant = lerp(onSf, sf, 0.78f),
        error = error,
        onError = readableOn(error),
        errorContainer = errorContainer,
        onErrorContainer = readableOn(errorContainer),
        inverseSurface = onSf,
        inverseOnSurface = sf,
        scrim = Color.Black,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
