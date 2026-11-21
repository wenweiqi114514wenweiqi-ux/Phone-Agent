package com.phoneagent.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.ui.legal.DocSection
import com.phoneagent.ui.legal.LegalDocs

/**
 * 隐私声明 / 使用条款二级页。
 * 内容与首次启动的同意页共用 LegalDocs，避免两处不一致。
 */
@Composable
fun PrivacySettingsPage() {
    PageColumn {
        Text(
            "隐私声明",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "生效日期：2026-11-21 · 版本 v${LegalDocs.VERSION}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        LegalDocs.PRIVACY.forEach { DocSection(it) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        Spacer(Modifier.height(6.dp))
        FeedbackCard()
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
fun TermsSettingsPage() {
    PageColumn {
        Text(
            "使用条款",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "生效日期：2026-11-21 · 版本 v${LegalDocs.VERSION}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        LegalDocs.TERMS.forEach { DocSection(it) }
        Spacer(Modifier.height(4.dp))
        Text(
            LegalDocs.SUMMARY,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * 反馈通道：邮箱（可直接发信，也能复制）+ Telegram 频道。
 */
@Composable
internal fun FeedbackCard() {
    val ctx = LocalContext.current
    CardTitle("反馈渠道")
    SettingsCard {
        ContactRow(
            icon = Icons.Filled.Email,
            title = "邮箱",
            value = LegalDocs.CONTACT_EMAIL,
            hint = "点按发信给开发者",
        ) {
            val version = appVersion(ctx)
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${LegalDocs.CONTACT_EMAIL}")).apply {
                putExtra(Intent.EXTRA_SUBJECT, "[PhoneAgent 反馈] v$version")
                putExtra(Intent.EXTRA_TEXT, feedbackTemplate(ctx, version))
            }
            runCatching { ctx.startActivity(intent) }.onFailure {
                copyText(ctx, LegalDocs.CONTACT_EMAIL)
                toast(ctx, "没有邮件应用，邮箱已复制到剪贴板")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        ContactRow(
            icon = Icons.Filled.Share,
            title = "Telegram",
            value = LegalDocs.TELEGRAM_HANDLE,
            hint = "点按打开频道",
        ) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(LegalDocs.TELEGRAM_URL))
            runCatching { ctx.startActivity(intent) }.onFailure {
                copyText(ctx, LegalDocs.TELEGRAM_URL)
                toast(ctx, "没有可用的浏览器 / TG，链接已复制")
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        "反馈时附上：机型、系统版本、复现步骤；涉及界面问题建议直接截图。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(horizontal = 6.dp),
    )
}

@Composable
private fun ContactRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    hint: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        Spacer(Modifier.weight(1f))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
        )
    }
}


/** 版本号：versionName + versionCode。 */
private fun appVersion(ctx: Context): String = runCatching {
    val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
    val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    "${info.versionName}（versionCode $code）"
}.getOrDefault("未知")

/**
 * 邮件正文模板：上半部分留空给用户写问题，下半部分自动附加排查所需信息。
 * 版本号、机型、系统版本都是排查时最先要问的东西，这里直接带出去，省一轮来回。
 */
private fun feedbackTemplate(ctx: Context, version: String): String {
    val model = runCatching {
        "${android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${android.os.Build.MODEL}"
    }.getOrDefault("未知")
    val system = "Android ${android.os.Build.VERSION.RELEASE}（API ${android.os.Build.VERSION.SDK_INT}）"
    val llmModel = runCatching {
        (ctx.applicationContext as com.phoneagent.PhoneAgentApp).container.settings.model
    }.getOrDefault("").ifBlank { "未配置" }
    return """
问题描述：
（在这里写你遇到的具体问题 / 想要的功能）

复现步骤：
1.

期望结果：

——— 以下信息自动附加，请勿删除 ———
应用版本：v$version
设备：$model
系统：$system
当前模型：$llmModel
    """.trimIndent()
}

private fun copyText(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("PhoneAgent", text))
}

private fun toast(ctx: Context, text: String) {
    Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
}
