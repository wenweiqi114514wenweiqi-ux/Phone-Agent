package com.phoneagent.data

import android.content.Context
import java.io.File

/**
 * 1.0.15 及更早版本的附件统一落在 `filesDir/attachments`。
 * 1.0.16 起每个会话有独立目录，旧路径会被文件工具沙箱拒绝。
 *
 * 这里做一次性复制迁移：只复制仍然存在的旧文件，更新消息里的附件路径；
 * 原文件保留，迁移失败也不删旧数据。
 */
class LegacyAttachmentMigrator(
    context: Context,
    private val messageDao: MessageDao,
    private val workspaceRepository: WorkspaceRepository,
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Result(
        val copiedFiles: Int,
        val updatedMessages: Int,
        val missingFiles: Int,
        val failedFiles: Int,
    )

    suspend fun migrateIfNeeded(): Result? {
        if (prefs.getInt(KEY_VERSION, 0) >= VERSION) return null

        val legacyRoots = listOfNotNull(
            File(appContext.filesDir, "attachments").takeIf { it.isDirectory },
            File(appContext.cacheDir, "attachments").takeIf { it.isDirectory },
        )
        if (legacyRoots.isEmpty()) {
            prefs.edit().putInt(KEY_VERSION, VERSION).apply()
            return Result(0, 0, 0, 0)
        }

        var copied = 0
        var updated = 0
        var missing = 0
        var failed = 0

        messageDao.allWithAttachments().forEach { message ->
            val attachments = AttachmentCodec.decode(message.attachments)
            if (attachments.isEmpty()) return@forEach

            var changed = false
            val next = attachments.map { meta ->
                val source = File(meta.path)
                if (!source.exists() || !source.isFile) {
                    missing++
                    return@map meta
                }
                if (!isUnderLegacyRoot(source, legacyRoots)) return@map meta

                runCatching {
                    val dir = File(workspaceRepository.conversationWorkspace(message.conversationId).path)
                    val rawName = meta.name.ifBlank { source.name }
                    // 迁移可能因进程被杀或数据库失败而重跑：同名同大小的目标视为已复制。
                    val preferred = File(dir, ConversationWorkspaceNaming.safeName(rawName))
                    val existing = preferred.takeIf { it.isFile && it.length() == source.length() }
                    val target = existing ?: ConversationWorkspaceNaming.uniqueFile(dir, rawName)
                    if (existing == null) {
                        source.copyTo(target, overwrite = false)
                        copied++
                    }
                    changed = true
                    meta.copy(path = target.absolutePath, size = target.length())
                }.getOrElse {
                    failed++
                    meta
                }
            }

            if (changed) {
                runCatching {
                    messageDao.insert(
                        message.copy(
                            attachments = AttachmentCodec.encode(next),
                        )
                    )
                    updated++
                }.onFailure { failed++ }
            }
        }

        prefs.edit()
            .putInt(KEY_COPIED, copied)
            .putInt(KEY_UPDATED, updated)
            .putInt(KEY_MISSING, missing)
            .putInt(KEY_FAILED, failed)
            .apply()
        if (failed == 0) {
            prefs.edit().putInt(KEY_VERSION, VERSION).apply()
        }

        return Result(copied, updated, missing, failed)
    }

    /** 只迁移旧版附件目录里的文件，避免把用户自己的外部文件复制进会话。 */
    private fun isUnderLegacyRoot(file: File, roots: List<File>): Boolean {
        val canonical = runCatching { file.canonicalFile }.getOrElse { file }
        return roots.any { root ->
            val rootCanonical = runCatching { root.canonicalFile }.getOrElse { root }
            canonical.path == rootCanonical.path ||
                canonical.path.startsWith(rootCanonical.path + File.separator)
        }
    }

    companion object {
        private const val PREFS = "phone_agent_attachment_migration"
        private const val KEY_VERSION = "version"
        private const val KEY_COPIED = "copied"
        private const val KEY_UPDATED = "updated"
        private const val KEY_MISSING = "missing"
        private const val KEY_FAILED = "failed"
        private const val VERSION = 1
    }
}
