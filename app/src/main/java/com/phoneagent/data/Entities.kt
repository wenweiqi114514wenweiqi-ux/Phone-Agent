package com.phoneagent.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 会话：多会话隔离对话历史（对标桌面 harness）。 */
@Entity(tableName = "conversation")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** 归档后不出现在会话列表，只从「设置 → 已归档的对话」进入。 */
    val archived: Boolean = false,
)

/** 消息：含思维链原文、工具调用记录、实际执行的命令与结果。 */
@Entity(tableName = "message")
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,             // user | assistant | system
    val content: String,
    val imageRefs: String? = null, // JSON 数组
    val reasoning: String? = null, // 思维链原文
    val toolCalls: String? = null, // JSON 数组
    val command: String? = null,   // 实际执行的命令
    val status: String? = null,    // ok | error | pending | refused
    val result: String? = null,
    /** 用户附件的 JSON 数组（name/mime/size/path），用于气泡预览。 */
    val attachments: String? = null,
    val createdAt: Long,
)

/**
 * 交付物：助手通过 submit_file 提交给用户看的文件。
 * 同一路径多次提交会累加 version，文件本体只保留最新一份（storedPath）。
 */
@Entity(tableName = "artifact")
data class ArtifactEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    /** 文件真实路径（不复制，预览直接读这个路径） */
    val sourcePath: String,
    /** 兼容旧字段：现在与 sourcePath 相同，早期版本曾是私有副本路径 */
    val storedPath: String,
    val name: String,
    val mime: String,
    val size: Long,
    /** 模型给的标题与一句话说明，可空 */
    val title: String? = null,
    val note: String? = null,
    /** 同一 sourcePath 的第几次提交 */
    val version: Int = 1,
    /** submitted = 助手主动提交给用户看的；modified = 这次对话里被改过的 */
    val kind: String = "submitted",
    val createdAt: Long,
)

/** 长期记忆：本地存储、跨会话全局共享、检索注入。 */
@Entity(tableName = "memory")
data class MemoryEntity(
    @PrimaryKey val id: String,
    val content: String,
    val category: String? = null,  // 如 "path" / "preference" / "fact"
    val tags: String? = null,      // 逗号分隔
    val weight: Int = 1,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 工具注册表：内置默认集 + 用户自定义。 */
@Entity(tableName = "tool")
data class ToolEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val parameters: String,        // JSON Schema
    val handler: String,           // shell | api | compose
    val commandTemplate: String? = null,
    val risk: String,              // low | high
    val enabled: Boolean = true,
    val builtin: Boolean = false,
)

/** 执行日志：受日志开关控制。 */
@Entity(tableName = "log")
data class LogEntity(
    @PrimaryKey val id: String,
    val conversationId: String? = null,
    val userQuery: String? = null,
    val command: String? = null,
    val status: String? = null,
    val result: String? = null,
    /** 用户附件的 JSON 数组（name/mime/size/path），用于气泡预览。 */
    val attachments: String? = null,
    val createdAt: Long,
)
