package com.phoneagent.ui.legal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 首次启动的声明页（隐私声明 + 使用条款）。
 *
 * 必须显式二选一（同意 / 不同意）才能继续：选「不同意」直接退出应用，
 * 不接受就进不去主界面——这是唯一在启动路径上拦得住用户的位置。
 */
@Composable
fun AgreementScreen(
    versionName: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    // -1 未选择 / 0 同意 / 1 不同意
    var choice by rememberSaveable { mutableIntStateOf(-1) }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Column(modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp)) {
                Text(
                    "用户协议与隐私声明",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "PhoneAgent v$versionName · 请在继续前完整阅读",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 正文：可滚动，字号偏小，尽量一屏能多看几行
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 4.dp),
            ) {
                DocHeader("隐私声明")
                LegalDocs.PRIVACY.forEach { section -> DocSection(section) }

                Spacer(Modifier.height(18.dp))
                DocHeader("使用条款")
                LegalDocs.TERMS.forEach { section -> DocSection(section) }

                Spacer(Modifier.height(14.dp))
                Text(
                    LegalDocs.SUMMARY,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // 底部：单选框 + 主按钮
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                ChoiceRow(
                    label = "同意",
                    desc = "我已阅读并同意上述隐私声明与使用条款",
                    selected = choice == 0,
                    onClick = { choice = 0 },
                )
                ChoiceRow(
                    label = "不同意",
                    desc = "不同意则无法使用本应用，将直接退出",
                    selected = choice == 1,
                    onClick = { choice = 1 },
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { if (choice == 0) onAccept() else onDecline() },
                    enabled = choice >= 0,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(
                        when (choice) {
                            0 -> "同意并继续"
                            1 -> "不同意，退出应用"
                            else -> "请先选择"
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.padding(start = 2.dp, end = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DocHeader(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 6.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun DocSection(section: LegalSection) {
    Column(modifier = Modifier.padding(bottom = 10.dp)) {
        Text(
            section.title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            section.body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            lineHeight = MaterialTheme.typography.bodySmall.lineHeight,
        )
    }
}
