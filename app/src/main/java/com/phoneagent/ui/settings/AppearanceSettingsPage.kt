package com.phoneagent.ui.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.data.AppearanceRepository
import com.phoneagent.di.AppContainer

/**
 * 个性化：主色 / 圆角 / 深浅 / 字号 / 动态取色，改动即时生效。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceSettingsPage(container: AppContainer) {
    val appearance by container.appearance.state.collectAsState()
    val repo = container.appearance
    var cornerDraft by remember(appearance.cornerDp) {
        mutableFloatStateOf(appearance.cornerDp.toFloat())
    }

    PageColumn {
        // ---- 实时预览 ----
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    "预览",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                )
                Text(
                    "Phone Agent",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "这是一段示例文字，用来观察字号与配色效果。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                )
                Row(
                    modifier = Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = {}) { Text("主按钮") }
                    OutlinedButton(onClick = {}) { Text("次按钮") }
                }
            }
        }

        // ---- 主色 ----
        CardTitle("主题色", appearance.accent)
        SettingsCard {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppearanceRepository.ACCENTS.forEach { (name, argb) ->
                    val selected = appearance.accent == name
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .then(
                                if (selected) Modifier.border(
                                    2.dp, MaterialTheme.colorScheme.onSurface, CircleShape
                                ) else Modifier
                            )
                            .clickable { repo.update { it.copy(accent = name, dynamicColor = false) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(argb)),
                        )
                        if (selected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = name,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text("动态取色（Android 12+）") },
                supportingContent = { Text("跟随系统壁纸配色，会覆盖上面的主题色") },
                trailingContent = {
                    Switch(
                        checked = appearance.dynamicColor,
                        enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                        onCheckedChange = { on -> repo.update { it.copy(dynamicColor = on) } },
                    )
                },
            )
        }

        // ---- 色彩定制（色盘） ----
        var editing by remember { mutableStateOf(-1) }   // -1 关闭 / 0 主色 / 1 背景 / 2 面板 / 3 辅助色 / 4 强调色
        CardTitle("色彩定制", "点色块用色盘调")
        SettingsCard {
            val accentSwatch = if (appearance.accentCustom != 0L) {
                Color(appearance.accentCustom)
            } else {
                Color(
                    AppearanceRepository.ACCENTS.firstOrNull { it.first == appearance.accent }?.second
                        ?: 0xFF4F5BD5
                )
            }
            ColorSlotRow(
                label = "主色",
                hint = appearance.accent,
                color = accentSwatch,
                customized = appearance.accentCustom != 0L,
                onClick = { editing = 0 },
                onReset = { repo.update { it.copy(accentCustom = 0L) } },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            ColorSlotRow(
                label = "背景",
                hint = "整体底色",
                color = if (appearance.backgroundCustom != 0L) Color(appearance.backgroundCustom)
                else MaterialTheme.colorScheme.background,
                customized = appearance.backgroundCustom != 0L,
                onClick = { editing = 1 },
                onReset = { repo.update { it.copy(backgroundCustom = 0L) } },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            ColorSlotRow(
                label = "面板 / 卡片",
                hint = "列表与卡片底",
                color = if (appearance.surfaceCustom != 0L) Color(appearance.surfaceCustom)
                else MaterialTheme.colorScheme.surfaceVariant,
                customized = appearance.surfaceCustom != 0L,
                onClick = { editing = 2 },
                onReset = { repo.update { it.copy(surfaceCustom = 0L) } },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            ColorSlotRow(
                label = "辅助色",
                hint = "次级按钮与容器",
                color = if (appearance.secondaryCustom != 0L) Color(appearance.secondaryCustom)
                else MaterialTheme.colorScheme.secondary,
                customized = appearance.secondaryCustom != 0L,
                onClick = { editing = 3 },
                onReset = { repo.update { it.copy(secondaryCustom = 0L) } },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            ColorSlotRow(
                label = "强调色",
                hint = "高亮与装饰",
                color = if (appearance.tertiaryCustom != 0L) Color(appearance.tertiaryCustom)
                else MaterialTheme.colorScheme.tertiary,
                customized = appearance.tertiaryCustom != 0L,
                onClick = { editing = 4 },
                onReset = { repo.update { it.copy(tertiaryCustom = 0L) } },
            )
            Text(
                "自定义底色后，正文颜色会按底色亮度自动反转，保证可读；辅助色与强调色可单独配，用来做层次区分。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        // ---- 圆角 ----
        CardTitle("圆角", "${cornerDraft.toInt()}dp")
        SettingsCard {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Slider(
                    value = cornerDraft,
                    onValueChange = { cornerDraft = it },
                    onValueChangeFinished = {
                        repo.update { it.copy(cornerDp = cornerDraft.toInt()) }
                    },
                    valueRange = 8f..32f,
                    steps = 7,
                )
                Text(
                    "影响卡片、输入框、按钮、面板的统一圆角",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }

        // ---- Markdown 圆角 ----
        val settingsRepo = container.settings
        var mdDraft by remember(settingsRepo.markdownRadius) {
            mutableFloatStateOf(settingsRepo.markdownRadius.toFloat())
        }
        CardTitle("代码 / 表格圆角", "${mdDraft.toInt()}dp")
        SettingsCard {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Slider(
                    value = mdDraft,
                    onValueChange = { mdDraft = it },
                    onValueChangeFinished = {
                        settingsRepo.markdownRadius = mdDraft.toInt()
                    },
                    valueRange = 4f..24f,
                    steps = 9,
                )
                Text(
                    "只影响聊天里的 Markdown 代码块与表格",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }

        // ---- 深浅模式 ----
        CardTitle("显示模式")
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(0 to "跟随系统", 1 to "浅色", 2 to "深色").forEach { (mode, label) ->
                FilterChip(
                    selected = appearance.darkMode == mode,
                    onClick = { repo.update { it.copy(darkMode = mode) } },
                    label = { Text(label) },
                )
            }
        }

        // ---- 字号 ----
        CardTitle("字号")
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(0.9f to "小", 1.0f to "标准", 1.1f to "大", 1.2f to "特大").forEach { (scale, label) ->
                FilterChip(
                    selected = appearance.fontScale == scale,
                    onClick = { repo.update { it.copy(fontScale = scale) } },
                    label = { Text(label) },
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        OutlinedButton(
            onClick = { repo.reset() },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
        ) { Text("恢复默认外观") }

        Spacer(modifier = Modifier.height(8.dp))
        if (editing >= 0) {
            val slotTitle = when (editing) {
                0 -> "主色"
                1 -> "背景色"
                2 -> "面板色"
                3 -> "辅助色"
                else -> "强调色"
            }
            val initial = when (editing) {
                0 -> appearance.accentCustom.takeIf { it != 0L } ?: 0xFF4F5BD5
                1 -> appearance.backgroundCustom.takeIf { it != 0L } ?: MaterialTheme.colorScheme.background.toArgb().toLong()
                2 -> appearance.surfaceCustom.takeIf { it != 0L } ?: MaterialTheme.colorScheme.surfaceVariant.toArgb().toLong()
                3 -> appearance.secondaryCustom.takeIf { it != 0L } ?: MaterialTheme.colorScheme.secondary.toArgb().toLong()
                else -> appearance.tertiaryCustom.takeIf { it != 0L } ?: MaterialTheme.colorScheme.tertiary.toArgb().toLong()
            }
            ColorWheelDialog(
                title = slotTitle,
                initial = initial,
                onDismiss = { editing = -1 },
                onConfirm = { argb ->
                    repo.update {
                        when (editing) {
                            0 -> it.copy(accentCustom = argb, dynamicColor = false)
                            1 -> it.copy(backgroundCustom = argb)
                            2 -> it.copy(surfaceCustom = argb)
                            3 -> it.copy(secondaryCustom = argb, dynamicColor = false)
                            else -> it.copy(tertiaryCustom = argb, dynamicColor = false)
                        }
                    }
                    editing = -1
                },
            )
        }

    }
}
