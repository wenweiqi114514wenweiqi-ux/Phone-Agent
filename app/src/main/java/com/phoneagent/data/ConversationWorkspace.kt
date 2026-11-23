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
