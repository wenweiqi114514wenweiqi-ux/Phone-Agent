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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
    }
}
