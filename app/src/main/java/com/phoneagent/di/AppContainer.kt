package com.phoneagent.di

import android.content.Context
import com.phoneagent.agent.AgentController
import com.phoneagent.component.TestComponentManager
import com.phoneagent.data.AppDatabase
import com.phoneagent.data.AppearanceRepository
import com.phoneagent.data.LegacyAttachmentMigrator
import com.phoneagent.data.ModelRepository
import com.phoneagent.data.SettingsRepository
import com.phoneagent.data.UpdateRepository
import com.phoneagent.data.WorkspaceRepository
import com.phoneagent.exec.CommandExecutor
import com.phoneagent.exec.ShizukuExecutor
import com.phoneagent.exec.ShizukuManager
import com.phoneagent.llm.LlmGateway
import com.phoneagent.llm.ModelsClient
import com.phoneagent.llm.OpenAiChatClient
import com.phoneagent.memory.MemoryRepository
import com.phoneagent.tools.ToolParser
import com.phoneagent.tools.ToolRegistry

/**
 * 手动依赖注入容器（骨架阶段；模块膨胀后可迁移 Hilt）。
 * 所有核心组件在这里装配，UI 层经此取用。
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: AppDatabase = AppDatabase.build(appContext)

    val settings: SettingsRepository = SettingsRepository(appContext)
    val workspaceRepository: WorkspaceRepository = WorkspaceRepository(appContext)
    val appearance: AppearanceRepository = AppearanceRepository(appContext)
    val modelRepository: ModelRepository = ModelRepository(appContext, settings).also { it.migrateLegacyIfNeeded() }
    /** 更新检测：GitHub Releases（正式版 / beta 两条通道）。 */
    val updater: UpdateRepository = UpdateRepository(appContext, settings)

    val toolRegistry: ToolRegistry = ToolRegistry(database.toolDao())
    val toolParser: ToolParser = ToolParser(toolRegistry)
    val shizukuManager: ShizukuManager = ShizukuManager(appContext)
    val memoryRepository: MemoryRepository = MemoryRepository(database.memoryDao())
    val testComponents: TestComponentManager =
        TestComponentManager(appContext, settings, shizukuManager)
    val executor: CommandExecutor = ShizukuExecutor(
        shizukuManager,
        toolParser,
        java.io.File(appContext.cacheDir, "downloads"),
        appContext,
        memoryRepository,
        settings,
        testComponents,
    )
    val llmGateway: LlmGateway = OpenAiChatClient()
    val modelsClient: ModelsClient = ModelsClient()

    val agent: AgentController = AgentController(
        settings = settings,
        gateway = llmGateway,
        registry = toolRegistry,
        parser = toolParser,
        executor = executor,
        memoryRepo = memoryRepository,
        messageDao = database.messageDao(),
        logDao = database.logDao(),
        artifactDao = database.artifactDao(),
        modelRepo = modelRepository,
        testComponents = testComponents,
    )

    /** 应用启动时初始化：写入内置默认工具集（幂等）。 */
    suspend fun initDefaults() {
        toolRegistry.ensureDefaults()
    }

    /** 把 1.0.15 及更早版本的 filesDir/attachments 复制到各会话目录。 */
    suspend fun migrateLegacyAttachments(): LegacyAttachmentMigrator.Result? =
        LegacyAttachmentMigrator(
            context = appContext,
            messageDao = database.messageDao(),
            workspaceRepository = workspaceRepository,
        ).migrateIfNeeded()
}
