package com.phoneagent.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 会话级工作区配置。
 *
 * 工作区列表（name -> path/readonly）和会话绑定（conversationId -> path）都用 SharedPreferences 持久化，
 * 免去 Room 表迁移成本。path 为空/未绑定 = 自由模式（旧行为，路径随便填）。
 */
class WorkspaceRepository(context: Context) {

    private val prefs = context.getSharedPreferences("phone_agent_workspaces", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

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

    /** 绑定会话到指定工作区（byName）。 */
    fun bind(conversationId: String, workspaceName: String) {
        prefs.edit().putString(KEY_BIND_PREFIX + conversationId, workspaceName).apply()
    }

    /** 解除绑定 → 自由模式。 */
    fun unbind(conversationId: String) {
        prefs.edit().remove(KEY_BIND_PREFIX + conversationId).apply()
    }

    /** 会话当前绑定的工作区（null = 自由模式）。 */
    fun boundWorkspace(conversationId: String): Workspace? {
        val name = prefs.getString(KEY_BIND_PREFIX + conversationId, null) ?: return null
        return list().firstOrNull { it.name == name }
    }

    companion object {
        private const val KEY_LIST = "workspace_list"
        private const val KEY_BIND_PREFIX = "ws_bind_"
    }
}
