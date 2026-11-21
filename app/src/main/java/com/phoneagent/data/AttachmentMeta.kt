package com.phoneagent.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 附件元信息：随消息入库，用于气泡预览与 Agent 读取。 */
@Serializable
data class AttachmentMeta(
    val name: String,
    val mime: String,
    val size: Long,
    val path: String,
    /** 助手提交的交付物（聊天里渲染成提交卡，可点开内置预览）。 */
    val submitted: Boolean = false,
    /** 交付物标题与一句话说明（模型给的，可空）。 */
    val title: String? = null,
    val note: String? = null,
    val artifactId: String? = null,
    /** 提交版本号（0 = 只是改过、还没正式提交） */
    val version: Int = 0,
    /** "submitted" 已提交 / "modified" 改过未提交 */
    val kind: String = "submitted",
    /** 记录时间（毫秒），卡片上显示 HH:mm */
    val recordedAt: Long = 0,
) {
    val isImage: Boolean get() = mime.startsWith("image/")
}

object AttachmentCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(list: List<AttachmentMeta>): String? =
        if (list.isEmpty()) null else runCatching { json.encodeToString(list) }.getOrNull()

    fun decode(raw: String?): List<AttachmentMeta> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<AttachmentMeta>>(raw) }.getOrDefault(emptyList())
    }

    /** 拼给模型看的附件说明（只在请求上下文里用，不进聊天气泡正文）。 */
    fun promptBlock(list: List<AttachmentMeta>): String {
        if (list.isEmpty()) return ""
        return buildString {
            append("\n\n[用户附件] 共 ${list.size} 个：\n")
            list.forEach {
                val kb = (it.size / 1024).coerceAtLeast(1)
                append("- ${it.name} · ${kb}KB · 路径：${it.path}\n")
            }
            append("提示：图片用 image_describe 查看；文本/代码用 file_cat；PDF/Word 用 doc_extract_text。")
        }
    }
}
