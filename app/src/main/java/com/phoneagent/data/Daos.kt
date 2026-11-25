package com.phoneagent.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversation WHERE archived = 0 ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversation WHERE archived = 1 ORDER BY updatedAt DESC")
    fun observeArchived(): Flow<List<ConversationEntity>>

    @Query("UPDATE conversation SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: String, title: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE conversation SET archived = :archived, updatedAt = :now WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE conversation SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: String, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM conversation WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): ConversationEntity?

    /** 全部会话（含归档），用于会话目录清理与旧附件迁移。 */
    @Query("SELECT * FROM conversation")
    suspend fun all(): List<ConversationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity)

    @Delete
    suspend fun delete(conversation: ConversationEntity)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM message WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeByConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM message WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun allByConversation(conversationId: String): List<MessageEntity>

    /** 带附件的全部消息：用于把旧版 filesDir/attachments 迁移到会话目录。 */
    @Query("SELECT * FROM message WHERE attachments IS NOT NULL AND attachments != ''")
    suspend fun allWithAttachments(): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM message WHERE conversationId = :conversationId")
    suspend fun countByConversation(conversationId: String): Int

    @Query("SELECT * FROM message WHERE conversationId = :conversationId ORDER BY createdAt ASC LIMIT :limit")
    suspend fun recentByConversation(conversationId: String, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM message WHERE conversationId = :conversationId ORDER BY createdAt DESC LIMIT 1")
    suspend fun lastByConversation(conversationId: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Delete
    suspend fun delete(message: MessageEntity)

    @Query("DELETE FROM message WHERE conversationId = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    /** 截断：删掉这条时间点之后（不含等于）的全部消息。重新生成/重新输入用。 */
    @Query("DELETE FROM message WHERE conversationId = :conversationId AND createdAt > :createdAt")
    suspend fun deleteAfter(conversationId: String, createdAt: Long)
}

@Dao
interface ArtifactDao {
    /** 当前会话里出现过/被改过的文件，最近动的在前。 */
    @Query("SELECT * FROM artifact WHERE conversationId = :conversationId ORDER BY createdAt DESC")
    fun observeByConversation(conversationId: String): Flow<List<ArtifactEntity>>

    @Query(
        "SELECT * FROM artifact WHERE conversationId = :conversationId AND sourcePath = :sourcePath LIMIT 1"
    )
    suspend fun byPath(conversationId: String, sourcePath: String): ArtifactEntity?

    @Query("SELECT * FROM artifact WHERE conversationId = :conversationId ORDER BY createdAt DESC")
    suspend fun allByConversation(conversationId: String): List<ArtifactEntity>

    /** 同一路径已提交过几次（用于版本号）。 */
    @Query("SELECT COUNT(*) FROM artifact WHERE conversationId = :conversationId AND sourcePath = :sourcePath")
    suspend fun countByPath(conversationId: String, sourcePath: String): Int

    @Query("SELECT * FROM artifact WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): ArtifactEntity?

    /** 当前会话所有交付物（含历史版本），用于文件清单统计。 */
    @Query("SELECT * FROM artifact WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun versionsByConversation(conversationId: String): List<ArtifactEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(artifact: ArtifactEntity)

    @Query("DELETE FROM artifact WHERE conversationId = :conversationId")
    suspend fun deleteByConversation(conversationId: String)
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memory ORDER BY weight DESC, updatedAt DESC")
    fun observeAll(): Flow<List<MemoryEntity>>

    /** 骨架阶段先用 LIKE 关键词检索；后续升级 FTS / 本地向量。 */
    @Query("SELECT * FROM memory WHERE content LIKE '%' || :keyword || '%' ORDER BY weight DESC LIMIT :limit")
    suspend fun searchByKeyword(keyword: String, limit: Int): List<MemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: MemoryEntity)

    @Delete
    suspend fun delete(memory: MemoryEntity)

    @Query("DELETE FROM memory")
    suspend fun clearAll()
}

@Dao
interface ToolDao {
    @Query("SELECT * FROM tool")
    fun observeAll(): Flow<List<ToolEntity>>

    @Query("SELECT * FROM tool WHERE enabled = 1")
    suspend fun enabledTools(): List<ToolEntity>

    @Query("SELECT * FROM tool WHERE name = :name LIMIT 1")
    suspend fun byName(name: String): ToolEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tool: ToolEntity)

    @Delete
    suspend fun delete(tool: ToolEntity)

    @Query("DELETE FROM tool")
    suspend fun clearAll()
}

@Dao
interface LogDao {
    @Query("SELECT * FROM log ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<LogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: LogEntity)

    @Query("DELETE FROM log")
    suspend fun clearAll()
}
