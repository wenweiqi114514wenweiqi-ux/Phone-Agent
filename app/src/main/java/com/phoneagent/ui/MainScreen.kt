package com.phoneagent.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.PhoneAgentApp
import com.phoneagent.data.ConversationEntity
import com.phoneagent.di.AppContainer
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import com.phoneagent.ui.chat.ChatScreen
import com.phoneagent.ui.settings.SettingsScreen
import com.phoneagent.ui.update.UpdateAvailableDialog
import com.phoneagent.ui.sessions.SessionsScreen
import com.phoneagent.ui.tools.ToolsScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class Tab(val title: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("聊天", Icons.Filled.Home),
    Tab("会话", Icons.AutoMirrored.Filled.List),
    Tab("工具", Icons.Filled.Build),
    Tab("设置", Icons.Filled.Settings),
)

/**
 * 主界面：原生 Material3 骨架 —— 顶部应用栏 + 底部导航 + 四个页面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val app = LocalContext.current.applicationContext as PhoneAgentApp
    val container: AppContainer = app.container

    var selected by rememberSaveable { mutableIntStateOf(0) }
    // 左上角「文件」入口：只看当前会话的交付文件
    var showFiles by rememberSaveable { mutableStateOf(false) }
    // 设置页当前停在哪一级（0 = 主菜单）。由这里持有，切标签时直接归零，
    // 不依赖设置页自己在"退出组合"时做副作用——那一帧 effect 会被取消。
    var settingsPage by rememberSaveable { mutableIntStateOf(0) }
    var conversationId by rememberSaveable {
        mutableStateOf(container.settings.lastConversationId.ifBlank { "default" })
    }

    // 更新检测：启动时静默查一次（24 小时节流），发现新版本再弹窗
    LaunchedEffect(Unit) {
        runCatching { container.updater.maybeAutoCheck() }
    }

    // 上次打开的会话若已不在库里（被删或刚建还没落盘），就地补建而不是丢弃
    LaunchedEffect(conversationId) {
        if (conversationId.isBlank()) return@LaunchedEffect
        runCatching {
        val dao = container.database.conversationDao()
        val exists = withContext(Dispatchers.IO) { dao.byId(conversationId) != null }
        if (!exists) {
            val last = container.settings.lastConversationId.ifBlank { "default" }
            if (last != conversationId) {
                // 已被删除 / 归档：跟随设置回落到仍然有效的会话
                conversationId = last
            } else {
                // 刚建但还没落库（或外部直接进入）：就地补建，绝不丢弃
                val now = System.currentTimeMillis()
                withContext(Dispatchers.IO) {
                    dao.upsert(
                        ConversationEntity(
                            id = conversationId,
                            title = if (conversationId == "default") "默认会话" else "新对话",
                            createdAt = now,
                            updatedAt = now,
                        )
                    )
                }
            }
        }
        }
    }

    // 确保默认会话存在（幂等）
    LaunchedEffect(Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
            val dao = container.database.conversationDao()
            if (dao.byId("default") == null) {
                val now = System.currentTimeMillis()
                dao.upsert(
                    ConversationEntity(id = "default", title = "默认会话", createdAt = now, updatedAt = now)
                )
            }
            }
        }
    }

    val uiScope = rememberCoroutineScope()

    fun createConversation() {
        // 当前会话还没有任何消息时，直接回到它，避免列表里堆一串「新对话」
        uiScope.launch {
            val dao = container.database.conversationDao()
            val empty = withContext(Dispatchers.IO) {
                container.database.messageDao().allByConversation(conversationId).isEmpty()
            }
            if (empty && conversationId != "default") {
                selected = 0
            } else {
                val newId = "conv_${java.util.UUID.randomUUID().toString().take(8)}"
                val now = System.currentTimeMillis()
                // 先落库，再切页面：否则会话页/存在性检查看不到它
                withContext(Dispatchers.IO) {
                    dao.upsert(
                        ConversationEntity(id = newId, title = "新对话", createdAt = now, updatedAt = now)
                    )
                }
                container.settings.lastConversationId = newId
                conversationId = newId
                selected = 0
            }
        }
    }

    val updateState by container.updater.state.collectAsState()
    val updatePrompt by container.updater.prompt.collectAsState()

    // 有未查看的问题 → 底部浮出小提示；点它直接进「设置 → 运行日志」
    val openIssues = rememberOpenIssues()
    val openLogReq by com.phoneagent.util.AppEvents.openLog.collectAsState()
    LaunchedEffect(openLogReq) {
        if (openLogReq > 0) selected = 3
    }

    // 键盘弹出时让内容整体上移，并暂时隐藏底部导航，避免遮挡输入框
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val keyboardOpen = imeBottom > 0.dp

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // 顶栏与页面同底色，不再固定用 surface：自定义背景后整块界面是连续的。
            Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
                title = { Text(tabs[selected].title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    if (selected == 0) {
                        IconButton(onClick = { showFiles = true }) {
                            Icon(
                                painter = painterResource(com.phoneagent.R.drawable.ic_files),
                                contentDescription = "本次对话的文件",
                            )
                        }
                    }
                },
                actions = {
                    if (selected == 0) {
                        IconButton(onClick = { createConversation() }) {
                            Icon(Icons.Filled.Add, contentDescription = "新建对话")
                        }
                    }
                },
            )
            }
        },
        bottomBar = {
            if (!keyboardOpen) {
                Column {
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.background,
                        contentColor = MaterialTheme.colorScheme.onBackground,
                        tonalElevation = 0.dp,
                    ) {
                        tabs.forEachIndexed { index, tab ->
                            NavigationBarItem(
                                selected = selected == index,
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    selectedTextColor = MaterialTheme.colorScheme.onBackground,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f),
                                    unselectedTextColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f),
                                ),
                                onClick = {
                                    if (index != selected) {
                                        // 设置页要回到主菜单；文件覆盖层不该跟着换页留下来
                                        if (selected == tabs.lastIndex) settingsPage = 0
                                        showFiles = false
                                        selected = index
                                    }
                                },
                                icon = { Icon(tab.icon, contentDescription = tab.title) },
                                label = { Text(tab.title) },
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // 切 Tab 时保存各页 UI 状态（聊天列表滚动位置等），回来不重建
            val pageStates = rememberSaveableStateHolder()
            AnimatedContent(
                targetState = selected,
                transitionSpec = {
                    val forward = targetState > initialState
                    (fadeIn(animationSpec = tween(230, delayMillis = 20)) +
                        slideInHorizontally(
                            animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
                        ) { full -> if (forward) full / 8 else -full / 8 } +
                        scaleIn(initialScale = 0.99f, animationSpec = tween(240))
                        ).togetherWith(
                        fadeOut(animationSpec = tween(150)) +
                            slideOutHorizontally(animationSpec = tween(210)) { full ->
                                if (forward) -full / 14 else full / 14
                            } +
                            scaleOut(targetScale = 0.99f, animationSpec = tween(210))
                    )
                },
                label = "tab",
            ) { index ->
                pageStates.SaveableStateProvider("page_$index") {
                when (index) {
                    0 -> ChatScreen(container = container, conversationId = conversationId)
                    1 -> SessionsScreen(
                        container = container,
                        onSelect = { id ->
                            conversationId = id
                            container.settings.lastConversationId = id
                            selected = 0
                        },
                    )
                    2 -> ToolsScreen(container = container)
                    else -> SettingsScreen(
                        container = container,
                        page = settingsPage,
                        onPageChange = { settingsPage = it },
                    )
                }
                }
            }

            // 「本次对话的文件」覆盖页：只在聊天页有效，切到别的标签立刻收起；
            // 系统返回 / 全屏手势也要能把它关掉，而不是直接退出 App
            androidx.activity.compose.BackHandler(enabled = showFiles && selected == 0) {
                showFiles = false
            }
            if (showFiles && selected == 0) {
                com.phoneagent.ui.artifacts.ArtifactsPage(
                    container = container,
                    conversationId = conversationId,
                    onClose = { showFiles = false },
                )
            }

            // 发现新版本：标题「检测到新的版本」+ 版本号 + 摘要 + 三个按钮 + 「不再提示 beta」
            updatePrompt?.let { release ->
                UpdateAvailableDialog(
                    release = release,
                    downloading = updateState.progress != null,
                    progress = updateState.progress ?: 0f,
                    downloaded = updateState.downloadedPath != null &&
                        updateState.downloadedTag == release.tag,
                    hint = updateState.hint,
                    onDismiss = { noBeta -> container.updater.dismiss(noBeta) },
                    onUpdate = { noBeta -> container.updater.startUpdate(release, noBeta) },
                )
            }

            // 悬浮在最上层，不挤占内容
            IssueNotice(
                openIssues = openIssues,
                onOpenLog = { com.phoneagent.util.AppEvents.requestOpenLog() },
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
            )
        }
    }
}
