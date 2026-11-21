package com.phoneagent.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        MemoryEntity::class,
        ToolEntity::class,
        LogEntity::class,
        ArtifactEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao
    abstract fun toolDao(): ToolDao
    abstract fun logDao(): LogDao
    abstract fun artifactDao(): ArtifactDao

    companion object {
        /** 一列的期望定义，用来把「同版本期间新增、却没写迁移」的历史列补回去。 */
        private class ColumnDef(
            val name: String,
            val type: String,
            val notNull: Boolean = false,
            val default: String? = null,
        )

        private class TableDef(
            val name: String,
            val createSql: String,
            val columns: List<ColumnDef>,
        )

        /** 与实体逐字段对齐；任何一列缺失都会导致 Room 打开时校验失败。 */
        private val SCHEMA: List<TableDef> = listOf(
            TableDef(
                name = "conversation",
                createSql = "CREATE TABLE IF NOT EXISTS `conversation` (`id` TEXT NOT NULL, " +
                    "`title` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                    "`archived` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                columns = listOf(
                    ColumnDef("id", "TEXT", true),
                    ColumnDef("title", "TEXT", true),
                    ColumnDef("createdAt", "INTEGER", true),
                    ColumnDef("updatedAt", "INTEGER", true),
                    ColumnDef("archived", "INTEGER", true, "0"),
                ),
            ),
            TableDef(
                name = "message",
                createSql = "CREATE TABLE IF NOT EXISTS `message` (`id` TEXT NOT NULL, " +
                    "`conversationId` TEXT NOT NULL, `role` TEXT NOT NULL, `content` TEXT NOT NULL, " +
                    "`imageRefs` TEXT, `reasoning` TEXT, `toolCalls` TEXT, `command` TEXT, `status` TEXT, " +
                    "`result` TEXT, `attachments` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                columns = listOf(
                    ColumnDef("id", "TEXT", true),
                    ColumnDef("conversationId", "TEXT", true),
                    ColumnDef("role", "TEXT", true),
                    ColumnDef("content", "TEXT", true),
                    ColumnDef("imageRefs", "TEXT"),
                    ColumnDef("reasoning", "TEXT"),
                    ColumnDef("toolCalls", "TEXT"),
                    ColumnDef("command", "TEXT"),
                    ColumnDef("status", "TEXT"),
                    ColumnDef("result", "TEXT"),
                    ColumnDef("attachments", "TEXT"),
                    ColumnDef("createdAt", "INTEGER", true),
                ),
            ),
            TableDef(
                name = "memory",
                createSql = "CREATE TABLE IF NOT EXISTS `memory` (`id` TEXT NOT NULL, " +
                    "`content` TEXT NOT NULL, `category` TEXT, `tags` TEXT, `weight` INTEGER NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                columns = listOf(
                    ColumnDef("id", "TEXT", true),
                    ColumnDef("content", "TEXT", true),
                    ColumnDef("category", "TEXT"),
                    ColumnDef("tags", "TEXT"),
                    ColumnDef("weight", "INTEGER", true, "1"),
                    ColumnDef("createdAt", "INTEGER", true),
                    ColumnDef("updatedAt", "INTEGER", true),
                ),
            ),
            TableDef(
                name = "tool",
                createSql = "CREATE TABLE IF NOT EXISTS `tool` (`id` TEXT NOT NULL, " +
                    "`name` TEXT NOT NULL, `description` TEXT NOT NULL, `parameters` TEXT NOT NULL, " +
                    "`handler` TEXT NOT NULL, `commandTemplate` TEXT, `risk` TEXT NOT NULL, " +
                    "`enabled` INTEGER NOT NULL, `builtin` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                columns = listOf(
                    ColumnDef("id", "TEXT", true),
                    ColumnDef("name", "TEXT", true),
                    ColumnDef("description", "TEXT", true),
                    ColumnDef("parameters", "TEXT", true),
                    ColumnDef("handler", "TEXT", true),
                    ColumnDef("commandTemplate", "TEXT"),
                    ColumnDef("risk", "TEXT", true),
                    ColumnDef("enabled", "INTEGER", true, "1"),
                    ColumnDef("builtin", "INTEGER", true, "0"),
                ),
            ),
            TableDef(
                name = "artifact",
                createSql = "CREATE TABLE IF NOT EXISTS `artifact` (`id` TEXT NOT NULL, " +
                    "`conversationId` TEXT NOT NULL, `sourcePath` TEXT NOT NULL, `storedPath` TEXT NOT NULL, " +
                    "`name` TEXT NOT NULL, `mime` TEXT NOT NULL, `size` INTEGER NOT NULL, " +
                    "`title` TEXT, `note` TEXT, `version` INTEGER NOT NULL, `kind` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                columns = listOf(
                    ColumnDef("id", "TEXT", true),
                    ColumnDef("conversationId", "TEXT", true),
                    ColumnDef("sourcePath", "TEXT", true),
                    ColumnDef("storedPath", "TEXT", true),
                    ColumnDef("name", "TEXT", true),
                    ColumnDef("mime", "TEXT", true),
                    ColumnDef("size", "INTEGER", true),
                    ColumnDef("title", "TEXT"),
                    ColumnDef("note", "TEXT"),
                    ColumnDef("version", "INTEGER", true, "1"),
                    ColumnDef("kind", "TEXT", true, "'submitted'"),
                    ColumnDef("createdAt", "INTEGER", true),
                ),
            ),
            TableDef(
                name = "log",
                createSql = "CREATE TABLE IF NOT EXISTS `log` (`id` TEXT NOT NULL, " +
                    "`conversationId` TEXT, `userQuery` TEXT, `command` TEXT, `status` TEXT, " +
                    "`result` TEXT, `attachments` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                columns = listOf(
                    ColumnDef("id", "TEXT", true),
                    ColumnDef("conversationId", "TEXT"),
                    ColumnDef("userQuery", "TEXT"),
                    ColumnDef("command", "TEXT"),
                    ColumnDef("status", "TEXT"),
                    ColumnDef("result", "TEXT"),
                    ColumnDef("attachments", "TEXT"),
                    ColumnDef("createdAt", "INTEGER", true),
                ),
            ),
        )

        private fun tableExists(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String): Boolean =
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use {
                it.moveToFirst()
            }

        private fun hasColumn(
            db: androidx.sqlite.db.SupportSQLiteDatabase,
            table: String,
            column: String,
        ): Boolean {
            db.query("PRAGMA table_info(`$table`)").use { c ->
                val nameIdx = c.getColumnIndex("name")
                while (c.moveToNext()) {
                    if (nameIdx >= 0 && c.getString(nameIdx) == column) return true
                }
            }
            return false
        }

        /**
         * 把库里每张表逐列对齐到实体定义：缺表就建，缺列就补。
         * 旧版本在同一个 DB 版本里改过实体却没写迁移，靠这里一次性补齐，
         * 否则 Room 升级后会报 “Migration didn't properly handle: xxx”。
         */
        private fun ensureSchema(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            SCHEMA.forEach { table ->
                if (!tableExists(db, table.name)) {
                    db.execSQL(table.createSql)
                    return@forEach
                }
                table.columns.forEach { col ->
                    if (!hasColumn(db, table.name, col.name)) {
                        val suffix = when {
                            !col.notNull -> ""
                            col.default != null -> " NOT NULL DEFAULT ${col.default}"
                            col.type == "TEXT" -> " NOT NULL DEFAULT ''"
                            else -> " NOT NULL DEFAULT 0"
                        }
                        db.execSQL(
                            "ALTER TABLE `${table.name}` ADD COLUMN `${col.name}` ${col.type}$suffix"
                        )
                    }
                }
            }
        }

        /** v1 → v2：会话增加归档标记。 */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                if (tableExists(db, "conversation") &&
                    !hasColumn(db, "conversation", "archived")
                ) {
                    db.execSQL("ALTER TABLE conversation ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        /** v4 → v5：交付物记录增加 kind（提交 / 被改过）；仍然只补列不重建。 */
        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                ensureSchema(db)
            }
        }

        /** v3 → v4：新增交付物表；同样走逐表对齐，历史漏列一并补。 */
        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                ensureSchema(db)
            }
        }

        /** v2 → v3：消息增加附件元信息，同时把历史漏掉的列全部补齐。 */
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                ensureSchema(db)
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "phone_agent.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                // 每次打开再对齐一遍：库里版本号已经是 3、但表结构缺列时，
                // Room 不会跑迁移也不会校验，只会在查询时抛 “no such column”。
                // 这里兜住那种情况（幂等，正常库上只是几次 PRAGMA）。
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        runCatching { ensureSchema(db) }
                            .onFailure { com.phoneagent.util.AppLog.warn("db", "onOpen ensureSchema 失败", it) }
                    }
                })
                .build()
    }
}
