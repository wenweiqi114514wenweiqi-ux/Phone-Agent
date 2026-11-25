package com.phoneagent.data

import android.content.Context
import android.os.Environment
import java.io.File
import java.security.MessageDigest

/**
 * 会话目录命名规则（纯函数，方便单测）。
 *
 * 目录名不直接用 conversationId：会话 ID 是 conv_xxxxxxxx，短且可读性太强；
 * 这里用会话 ID 的 SHA-256 前 16 字节，得到 32 位十六进制串，稳定、不可猜、够长。
 */
object ConversationWorkspaceNaming {

    fun keyFor(conversationId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(conversationId.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { b ->
            "%02x".format(b.toInt() and 0xff)
        }
    }

    /** 去掉路径分隔符、控制字符和文件系统非法字符；保留扩展名。 */
    fun safeName(rawName: String): String {
        val cleaned = rawName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim()
            .trim('.')
        if (cleaned.isBlank()) return "file_${System.currentTimeMillis()}"

        val dot = cleaned.lastIndexOf('.')
        val base = if (dot > 0) cleaned.substring(0, dot) else cleaned
        val ext = if (dot > 0) cleaned.substring(dot) else ""
        return base.take(96) + ext.take(16)
    }

    /** 同目录下重名时追加 (1)、(2)…，不覆盖已有文件。 */
    fun uniqueFile(dir: File, rawName: String): File {
        if (!dir.exists()) dir.mkdirs()
        val name = safeName(rawName)
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""

        var candidate = File(dir, name)
        var index = 1
        while (candidate.exists() && index < 1000) {
            candidate = File(dir, "$base ($index)$ext")
            index++
        }
        return candidate
    }
}

/**
 * 每个会话一个独立目录：
 * /storage/emulated/0/PhoneAgent/<32位hex>/
 *
 * 上传文件、AI 产物、临时文件都放这里。会话之间默认互不可见，
 * 需要共享时由用户显式绑定到命名工作区。
 */
class ConversationWorkspace(context: Context) {

    private val appContext = context.applicationContext

    /** 用户可见的根目录；没有外部存储权限时退回应用外部目录。 */
    fun root(): File {
        val external = File(Environment.getExternalStorageDirectory(), ROOT_NAME)
        if (ensureDir(external)) return external

        val fallbackBase = appContext.getExternalFilesDir(null) ?: appContext.filesDir
        val fallback = File(fallbackBase, ROOT_NAME)
        ensureDir(fallback)
        return fallback
    }

    fun dirFor(conversationId: String): File {
        val dir = File(root(), ConversationWorkspaceNaming.keyFor(conversationId))
        ensureDir(dir)
        return dir
    }

    fun pathFor(conversationId: String): String = dirFor(conversationId).absolutePath

    /** 会话目录占用；不会为了算大小而创建空目录。 */
    fun sizeOf(conversationId: String): Long =
        dirSize(File(root(), ConversationWorkspaceNaming.keyFor(conversationId)))

    /** 删除某个会话的整个目录；返回是否真的删掉了内容。 */
    fun deleteDir(conversationId: String): Boolean {
        val dir = File(root(), ConversationWorkspaceNaming.keyFor(conversationId))
        if (!dir.exists()) return false
        return runCatching { dir.deleteRecursively() }.getOrDefault(false)
    }

    /** 所有会话目录的总占用（不含根目录下的散落文件）。 */
    fun totalSize(): Long {
        val root = root()
        return root.listFiles()
            ?.filter { it.isDirectory }
            ?.sumOf { dirSize(it) }
            ?: 0L
    }

    /**
     * 找出没有对应会话记录的目录。目录名是会话 ID 的哈希，不能反推，
     * 所以调用方必须传入仍然存在的会话 ID 列表。
     */
    fun orphanDirs(knownConversationIds: Collection<String>): List<File> {
        val root = root()
        val knownKeys = knownConversationIds.mapTo(HashSet()) { ConversationWorkspaceNaming.keyFor(it) }
        return root.listFiles()
            ?.filter { it.isDirectory && it.name !in knownKeys }
            ?.sortedBy { it.lastModified() }
            ?: emptyList()
    }

    /** 清理无主目录；返回 (目录数, 释放字节数)。 */
    fun deleteOrphans(knownConversationIds: Collection<String>): Pair<Int, Long> {
        var count = 0
        var bytes = 0L
        orphanDirs(knownConversationIds).forEach { dir ->
            val size = dirSize(dir)
            if (runCatching { dir.deleteRecursively() }.getOrDefault(false)) {
                count++
                bytes += size
            }
        }
        return count to bytes
    }

    fun rootPath(): String = root().absolutePath

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        return runCatching {
            dir.walkTopDown()
                .filter { it.isFile }
                .sumOf { it.length() }
        }.getOrDefault(0L)
    }

    fun workspaceFor(conversationId: String): WorkspaceRepository.Workspace =
        WorkspaceRepository.Workspace(
            name = "本会话目录",
            path = pathFor(conversationId),
            readonly = false,
        )

    private fun ensureDir(dir: File): Boolean {
        if (dir.isDirectory && dir.canWrite()) return true
        return runCatching { dir.mkdirs() && dir.isDirectory }.getOrDefault(false)
    }

    companion object {
        private const val ROOT_NAME = "PhoneAgent"
    }
}
