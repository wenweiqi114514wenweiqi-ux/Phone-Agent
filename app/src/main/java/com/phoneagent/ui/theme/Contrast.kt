package com.phoneagent.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min

/**
 * 颜色工具：所有自定义配色都经过这里。
 *
 * 目标不是「让用户选什么就原样画什么」，而是保证文字在任意底色上都能读：
 * 1. 前景色按 WCAG 对比度挑选，而不是简单判断亮度；
 * 2. 主色如果本身太浅/太深，自动往可读方向压一档，同时保留色相；
 * 3. 面板色与背景色太接近时自动拉开一层，避免卡片和页面糊成一片。
 */

/** WCAG 相对对比度，1.0 表示完全一样。 */
fun colorContrast(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    val hi = max(la, lb)
    val lo = min(la, lb)
    return (hi + 0.05f) / (lo + 0.05f)
}

/** 在给定底色上挑一个对比度最高的前景色（不是纯黑纯白，避免刺眼）。 */
fun readableOn(bg: Color): Color {
    val dark = Color(0xFF14161B)
    val light = Color(0xFFF5F6F8)
    return if (colorContrast(bg, dark) >= colorContrast(bg, light)) dark else light
}

/**
 * 把 [fg] 往黑或白方向推，直到和 [bg] 达到 [minRatio]。
 * 色相基本保留，只改明度，适合主色、强调色这种既要做文字又要做按钮的颜色。
 */
fun ensureContrast(fg: Color, bg: Color, minRatio: Float): Color {
    if (colorContrast(fg, bg) >= minRatio) return fg
    val dark = Color(0xFF0B0C0F)
    val light = Color(0xFFFFFFFF)
    val target = if (colorContrast(bg, dark) >= colorContrast(bg, light)) dark else light
    var step = 1
    while (step <= 12) {
        val candidate = lerp(fg, target, step / 12f)
        if (colorContrast(candidate, bg) >= minRatio) return candidate
        step++
    }
    return target
}

/**
 * 面板 / 卡片色。若和背景几乎同亮度，就往前景方向挪一点，
 * 保证「背景—卡片」至少能看出层次；已经很分明就原样返回。
 */
fun separatedLayer(layer: Color, bg: Color): Color {
    if (colorContrast(layer, bg) >= 1.14f) return layer
    val dir = readableOn(bg)
    var step = 1
    while (step <= 10) {
        val candidate = lerp(layer, dir, step / 24f)
        if (colorContrast(candidate, bg) >= 1.14f) return candidate
        step++
    }
    return lerp(layer, dir, 0.40f)
}
