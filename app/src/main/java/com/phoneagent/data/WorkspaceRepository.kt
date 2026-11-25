package com.phoneagent.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 会话级工作区配置。
 *
 * 三层含义：
 * - 命名工作区（name -> path/readonly）：用户主动创建的共享项目，可被多个会话绑定；
 * - 会话默认目录：每个会话一个 PhoneAgent/<hash>/，由 [ConversationWorkspace] 创建；
 * - 自由模式：用户显式解绑后不再限制路径，属于高级选项。
 */
class WorkspaceRepository(context: Context) {

    private val prefs = context.getSharedPreferences("phone_agent_workspaces", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val conversationWorkspaces = ConversationWorkspace(context)

    /** 一个工作区定义。 */
    @Serializable
    data class Workspace(
        val name: String,
        val path: String,
        val readonly: Boolean = false,
    )

    /** 列出全部已保存工作区。 */
    fun list(): List<Workspace> {
        val raw = prefs.getString(KEY_LIST, "[]") ?: "[]"
        return try {
            json.decodeFromString<List<Workspace>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 保存/更新工作区（按 name 覆盖）。 */
    fun save(ws: Workspace) {
        val current = list().filter { it.name != ws.name } + ws
        prefs.edit().putString(KEY_LIST, json.encodeToString<List<Workspace>>(current)).apply()
    }

    /** 编辑已有工作区：可改名称（同步迁移所有会话绑定）/ 路径 / 只读。 */
    fun update(oldName: String, ws: Workspace) {
        val current = list()
        if (current.none { it.name == oldName }) {
            save(ws)
            return
        }
        val next = current.filter { it.name != oldName && it.name != ws.name } + ws
        val editor = prefs.edit().putString(KEY_LIST, json.encodeToString(next))
        if (oldName != ws.name) {
            prefs.all.keys.forEach { k ->
                if (k.startsWith(KEY_BIND_PREFIX) && prefs.getString(k, null) == oldName) {
                    editor.putString(k, ws.name)
                }
            }
        }
        editor.apply()
    }

    /** 按 name 删除工作区（仅删配置，不删磁盘文件），同时清理相关会话绑定。 */
    fun delete(name: String) {
        val current = list().filter { it.name != name }
        prefs.edit().putString(KEY_LIST, json.encodeToString<List<Workspace>>(current)).apply()
        // 清理绑定到该工作区的会话
        prefs.all.keys.forEach { k ->
            if (k.startsWith(KEY_BIND_PREFIX) && prefs.getString(k, null) == name) {
                prefs.edit().remove(k).apply()
            }
        }
    }

    /** 绑定会话到指定工作区（byName）。绑定后进入共享项目模式。 */
    fun bind(conversationId: String, workspaceName: String) {
        prefs.edit()
            .putString(KEY_BIND_PREFIX + conversationId, workspaceName)
            .putBoolean(KEY_FREE_PREFIX + conversationId, false)
            .apply()
    }

    /** 解除绑定 → 自由模式（路径不再受工作区限制）。 */
    fun unbind(conversationId: String) {
        prefs.edit()
            .remove(KEY_BIND_PREFIX + conversationId)
            .putBoolean(KEY_FREE_PREFIX + conversationId, true)
            .apply()
    }

    /** 回到本会话默认目录：删除命名工作区绑定，并退出自由模式。 */
    fun resetToConversation(conversationId: String) {
        prefs.edit()
            .remove(KEY_BIND_PREFIX + conversationId)
            .putBoolean(KEY_FREE_PREFIX + conversationId, false)
            .apply()
    }

    /** 当前是否处于自由模式（只有显式解绑才是；新会话默认不是）。 */
    fun isFreeMode(conversationId: String): Boolean =
        prefs.getBoolean(KEY_FREE_PREFIX + conversationId, false)

    /** 本会话默认目录（目录不存在会就地创建）。 */
    fun conversationWorkspace(conversationId: String): Workspace =
        conversationWorkspaces.workspaceFor(conversationId)

    /** 当前会话目录占用；不会额外创建目录。 */
    fun conversationSize(conversationId: String): Long =
        conversationWorkspaces.sizeOf(conversationId)

    /** 删除当前会话目录（附件、产物、临时文件）。 */
    fun deleteConversationFiles(conversationId: String): Boolean =
        conversationWorkspaces.deleteDir(conversationId)

    /** 所有会话目录总占用。 */
    fun totalConversationSize(): Long = conversationWorkspaces.totalSize()

    /** 会话目录根路径。 */
    fun conversationRootPath(): String = conversationWorkspaces.rootPath()

    /** 清理没有对应会话记录的目录；返回 (目录数, 释放字节数)。 */
    fun cleanupOrphanConversationDirs(knownConversationIds: Collection<String>): Pair<Int, Long> =
        conversationWorkspaces.deleteOrphans(knownConversationIds)

    /** 会话当前绑定的工作区（null = 未绑定命名工作区）。 */
    fun boundWorkspace(conversationId: String): Workspace? {
        val name = prefs.getString(KEY_BIND_PREFIX + conversationId, null) ?: return null
        return list().firstOrNull { it.name == name }
    }

    companion object {
        private const val KEY_LIST = "workspace_list"
        private const val KEY_BIND_PREFIX = "ws_bind_"
        private const val KEY_FREE_PREFIX = "ws_free_"
    }
}
