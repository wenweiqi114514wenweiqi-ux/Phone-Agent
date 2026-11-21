package com.phoneagent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.data.SettingsRepository
import com.phoneagent.di.AppContainer

private val LENGTH_LABELS = listOf("简短", "适中", "详细", "很详细")
private val EMOJI_LABELS = listOf("不用", "少量", "适中", "很多")

/**
 * AI 个性化：输出长度 / 风格 / Emoji 用量 三个滑块 + 性格设定（≤40 字，需点保存）。
 */
@Composable
fun AiPersonaSettingsPage(container: AppContainer) {
    val settings = container.settings

    var length by remember { mutableFloatStateOf(settings.outputLength.toFloat()) }
    var emoji by remember { mutableFloatStateOf(settings.emojiLevel.toFloat()) }
    var style by remember { mutableStateOf(settings.outputStyle) }
    var persona by remember { mutableStateOf(settings.aiPersona) }
    var savedTick by remember { mutableStateOf(false) }

    PageColumn {
        CardTitle("表达方式", "立即生效")

        SettingsCard {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("输出长度", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        LENGTH_LABELS[length.toInt().coerceIn(0, 3)],
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = length,
                    onValueChange = {
                        length = it
                        settings.outputLength = it.toInt().coerceIn(0, 3)
                    },
                    valueRange = 0f..3f,
                    steps = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "越往右回答越长，会讲清来龙去脉。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )

                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Emoji 使用量", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        EMOJI_LABELS[emoji.toInt().coerceIn(0, 3)],
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = emoji,
                    onValueChange = {
                        emoji = it
                        settings.emojiLevel = it.toInt().coerceIn(0, 3)
                    },
                    valueRange = 0f..3f,
                    steps = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "控制了回复里的表情多少，「不用」则完全不出现。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        CardTitle("输出风格")
        SettingsCard {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                SettingsRepository.STYLES.forEach { (key, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = style == key,
                            onClick = {
                                style = key
                                settings.outputStyle = key
                            },
                        )
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        CardTitle("AI 的性格与特点", "${persona.length}/40")
        SettingsCard {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                OutlinedTextField(
                    value = persona,
                    onValueChange = { if (it.length <= 40) { persona = it; savedTick = false } },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    minLines = 2,
                    placeholder = { Text("例如：像个靠谱的朋友，说话直接，偶尔吐槽") },
                    supportingText = { Text("限 40 字内，会作为性格设定注入系统提示词") },
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (savedTick) {
                        Text(
                            "已保存",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 10.dp),
                        )
                    }
                    Button(
                        onClick = {
                            settings.aiPersona = persona.trim()
                            savedTick = true
                        },
                        shape = MaterialTheme.shapes.large,
                    ) { Text("保存") }
                }
            }
        }

        Text(
            "这些设定只影响回答的口吻与篇幅，不会改变它的能力上限。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}
