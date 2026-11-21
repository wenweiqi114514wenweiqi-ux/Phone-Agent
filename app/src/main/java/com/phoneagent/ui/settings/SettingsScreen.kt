package com.phoneagent.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoneagent.data.SettingsRepository
import com.phoneagent.di.AppContainer
import com.phoneagent.exec.RootShell
import com.phoneagent.exec.ShizukuStatus
import com.phoneagent.tools.SafetyMode
import androidx.compose.runtime.mutableStateOf

private const val PAGE_HOME = 0
private const val PAGE_PERMISSION = 1
private const val PAGE_MODEL = 2
private const val PAGE_APPEARANCE = 3
private const val PAGE_CHANNEL = 4
private const val PAGE_SAFETY = 5
private const val PAGE_FEATURES = 6
private const val PAGE_MEMORY = 8
private const val PAGE_ABOUT = 7
private const val PAGE_AI = 9
private const val PAGE_ARCHIVE = 10
private const val PAGE_LOGS = 11
private const val PAGE_PRIVACY = 12
private const val PAGE_TERMS = 13

private fun pageTitle(page: Int): String = when (page) {
    PAGE_PERMISSION -> "后台与保活"
    PAGE_MODEL -> "模型与接入"
    PAGE_APPEARANCE -> "外观与主题"
    PAGE_AI -> "AI 个性化"
    PAGE_ARCHIVE -> "已归档的对话"
    PAGE_LOGS -> "运行日志"
    PAGE_CHANNEL -> "执行通道"
    PAGE_SAFETY -> "安全策略"
    PAGE_FEATURES -> "功能与日志"
    PAGE_MEMORY -> "记忆 · 关于你"
    PAGE_ABOUT -> "关于"
    PAGE_PRIVACY -> "隐私声明"
    PAGE_TERMS -> "使用条款"
    else -> "设置"
}

/**
 * 设置：首页为分组入口，点击进入二级详情页。
 */
@Composable
fun SettingsScreen(
    container: AppContainer,
    modifier: Modifier = Modifier,
    page: Int = PAGE_HOME,
    onPageChange: (Int) -> Unit = {},
) {
    // 页面状态由 MainScreen 持有：切标签、系统返回、点标题栏返回都改同一份状态，
    // 不再依赖"离开组合时触发副作用"（那条路在切标签的那一帧会被取消，所以之前没生效）。
    fun go(target: Int) = onPageChange(target)

    // 系统返回键 / 全屏手势：子页回到设置首页
    BackHandler(enabled = page != PAGE_HOME) { go(PAGE_HOME) }

    // 别处（底部小提示）请求「打开日志」时直接跳到运行日志页
    val openLogReq by com.phoneagent.util.AppEvents.openLog.collectAsState()
    LaunchedEffect(openLogReq) {
        if (openLogReq > 0) go(PAGE_LOGS)
    }

    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val forward = targetState > initialState
            val enter = fadeIn(tween(220, delayMillis = 30)) +
                slideInHorizontally(
                    animationSpec = spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow),
                ) { full -> if (forward) full / 3 else -full / 3 } +
                scaleIn(initialScale = 0.985f, animationSpec = tween(260))
            val exit = fadeOut(tween(150)) +
                slideOutHorizontally(tween(230)) { full -> if (forward) -full / 6 else full / 6 } +
                scaleOut(targetScale = 0.985f, animationSpec = tween(230))
            enter togetherWith exit
        },
        label = "settingsPage",
    ) { current ->
    if (current == PAGE_HOME) {
        SettingsHome(container = container, modifier = modifier, onOpen = { go(it) })
    } else {
        Column(modifier = modifier.fillMaxSize()) {
            DetailHeader(title = pageTitle(current), onBack = { go(PAGE_HOME) })
            Box(modifier = Modifier.weight(1f)) {
                when (current) {
                    PAGE_PERMISSION -> PermissionSettingsPage(container)
                    PAGE_MODEL -> ModelSettingsPage(container)
                    PAGE_APPEARANCE -> AppearanceSettingsPage(container)
                    PAGE_AI -> AiPersonaSettingsPage(container)
                    PAGE_CHANNEL -> ChannelSettingsPage(container)
                    PAGE_SAFETY -> SafetySettingsPage(container)
                    PAGE_FEATURES -> FeatureSettingsPage(container)
                    PAGE_MEMORY -> MemorySettingsPage(container)
                    PAGE_ARCHIVE -> com.phoneagent.ui.sessions.ArchivedSessionsPage(container)
                    PAGE_LOGS -> LogsPage()
                    PAGE_PRIVACY -> PrivacySettingsPage()
                    PAGE_TERMS -> TermsSettingsPage()
                    else -> AboutSettingsPage(
                        container = container,
                        onOpenPrivacy = { go(PAGE_PRIVACY) },
                        onOpenTerms = { go(PAGE_TERMS) },
                    )
                }
            }
        }
    }
    }
}

// ================= 首页 =================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHome(container: AppContainer, modifier: Modifier, onOpen: (Int) -> Unit) {
    val settings = container.settings
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val versionName = remember {
        try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
    }
    val appearance by container.appearance.state.collectAsState()
    val shizukuStatus by container.shizukuManager.status.collectAsState()
    // 每次进入设置页自动重新探测，省得用户手动刷新
    LaunchedEffect(Unit) {
        runCatching { RootShell.redetect() }
        runCatching { container.shizukuManager.retryBind() }
    }
    val rootAvailable = RootShell.available

    val channelText = when {
        rootAvailable == true -> "Root 已授权"
        shizukuStatus == ShizukuStatus.READY -> "Shizuku 就绪"
        rootAvailable == false && shizukuStatus != ShizukuStatus.READY -> "未就绪"
        else -> "检测中"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 有问题时才提示，且只给一个入口，详情统一进「运行日志」
        val issueCount by com.phoneagent.util.AppLog.openIssues.collectAsState()
        if (issueCount > 0) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f),
                ),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "最近记录了 ${issueCount} 个问题",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        "已写入运行日志，点开可看时间和堆栈",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    TextButton(
                        onClick = { onOpen(PAGE_LOGS) },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) {
                        Text("查看运行日志", color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }

        // 概览卡
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Phone Agent",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "v$versionName",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                }
                Text(
                    text = "本机 AI 助手 · Kotlin + Compose",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 2.dp),
                )
                Row(
                    modifier = Modifier.padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusPill("通道", channelText)
                    StatusPill("模型", settings.model.ifBlank { "未配置" })
                }
            }
        }

        StorageAccessRow()

        GroupLabel("诊断")
        val logCount by com.phoneagent.util.AppLog.entries.collectAsState()
        SectionEntry(
            icon = Icons.AutoMirrored.Filled.List,
            title = "运行日志",
            summary = buildString {
                if (issueCount > 0) {
                    append("发现 ").append(issueCount).append(" 个问题，点此查看")
                } else if (logCount.isNotEmpty()) {
                    append("暂无异常 · 共 ").append(logCount.size).append(" 条记录")
                } else {
                    append("暂无异常，软件运行正常")
                }
            },
            onClick = { onOpen(PAGE_LOGS) },
        )

        GroupLabel("权限与执行")
        SectionEntry(
            icon = Icons.Filled.Lock,
            title = "后台与保活",
            summary = "通知 · 电池优化 · 前台服务",
            onClick = { onOpen(PAGE_PERMISSION) },
        )
        SectionEntry(
            icon = Icons.Filled.Build,
            title = "执行通道",
            summary = "Root 优先，Shizuku 回退 · 当前 $channelText",
            onClick = { onOpen(PAGE_CHANNEL) },
        )
        SectionEntry(
            icon = Icons.Filled.Warning,
            title = "安全策略",
            summary = when (settings.safetyMode) {
                SafetyMode.ALLOW_ALL -> "全部允许"
                SafetyMode.CONFIRM_HIGH -> "高危操作需确认"
                SafetyMode.CONFIRM_ALL -> "全部操作需确认"
            },
            onClick = { onOpen(PAGE_SAFETY) },
        )

        GroupLabel("助手配置")
        SectionEntry(
            icon = Icons.Filled.Star,
            title = "模型与接入",
            summary = activeSummary(container),
            onClick = { onOpen(PAGE_MODEL) },
        )
        SectionEntry(
            icon = Icons.Filled.Face,
            title = "AI 个性化",
            summary = buildString {
                append(if (settings.outputStyle.isBlank()) "自然口语" else
                    (SettingsRepository.STYLES.firstOrNull { it.first == settings.outputStyle }?.second ?: settings.outputStyle))
                append(" · 篇幅 ")
                append(com.phoneagent.ui.settings.lengthLabel(settings.outputLength))
                append(" · Emoji ")
                append(com.phoneagent.ui.settings.emojiLabel(settings.emojiLevel))
            },
            onClick = { onOpen(PAGE_AI) },
        )
        SectionEntry(
            icon = Icons.Filled.Person,
            title = "记忆 · 关于你",
            summary = buildString {
                append(if (settings.userPersona.isBlank()) "未设置形象" else "形象已设置")
                append(" · 记忆")
                append(if (settings.memoryEnabled) "已开启" else "已关闭")
            },
            onClick = { onOpen(PAGE_MEMORY) },
        )
        SectionEntry(
            icon = Icons.Filled.Favorite,
            title = "个性化",
            summary = buildString {
                append("主色 ${appearance.accent} · 圆角 ${appearance.cornerDp}dp · ")
                append(
                    when (appearance.darkMode) {
                        1 -> "浅色"
                        2 -> "深色"
                        else -> "跟随系统"
                    }
                )
            },
            onClick = { onOpen(PAGE_APPEARANCE) },
        )
        SectionEntry(
            icon = Icons.Filled.Settings,
            title = "功能与日志",
            summary = buildList {
                if (settings.showReasoning) add("思维链")
                if (settings.logEnabled) add("日志")
                if (settings.autoRetry) add("自动重试")
                if (settings.deepThinking) add("深度思考·${settings.thinkingLevel}")
            }.joinToString(" · ").ifBlank { "全部关闭" },
            onClick = { onOpen(PAGE_FEATURES) },
        )

        GroupLabel("其它")
        SectionEntry(
            icon = Icons.AutoMirrored.Filled.List,
            title = "已归档的对话",
            summary = "归档的会话在这里，可取消归档或删除",
            onClick = { onOpen(PAGE_ARCHIVE) },
        )
        SectionEntry(
            icon = Icons.Filled.Info,
            title = "关于",
            summary = "版本、声明与条款、反馈渠道",
            onClick = { onOpen(PAGE_ABOUT) },
        )

        Spacer(Modifier.padding(top = 8.dp))
    }
}

private fun hostOf(url: String): String =
    url.removePrefix("https://").removePrefix("http://").substringBefore("/").ifBlank { "未配置" }

@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun StatusPill(label: String, value: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        shape = CircleShape,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$label · ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SectionEntry(
    icon: ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
        onClick = onClick,
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            },
            headlineContent = { Text(title, style = MaterialTheme.typography.titleSmall) },
            supportingContent = {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            },
            trailingContent = {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                )
            },
        )
    }
}

// ================= 详情页公用骨架 =================

@Composable
internal fun DetailHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 详情页通用滚动列。 */
@Composable
internal fun PageColumn(
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** 统一的分组卡片。 */
@Composable
internal fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp), content = content)
    }
}

@Composable
internal fun CardTitle(text: String, trailing: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            Text(
                trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** 首页模型摘要：优先显示多模型仓库当前激活的模型。 */
@Composable
private fun activeSummary(container: AppContainer): String {
    val state by container.modelRepository.state.collectAsState()
    val active = state.active
    return if (active == null) "未添加模型 · 点进去添加" else "${active.name} · ${active.model}"
}

internal fun lengthLabel(v: Int): String = when (v) {
    0 -> "简短"
    1 -> "适中"
    2 -> "详细"
    else -> "很详细"
}

internal fun emojiLabel(v: Int): String = when (v) {
    0 -> "不用"
    1 -> "少量"
    2 -> "适中"
    else -> "很多"
}

/**
 * 文件访问权限：唯一入口，直接显示当前状态。
 * 冷启动已有弹窗（StorageAccessGate），这里只是"随时可查、可补开"的地方。
 */
@Composable
private fun StorageAccessRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var granted by remember { mutableStateOf(com.phoneagent.util.StorageAccess.hasAccess(ctx)) }
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                granted = com.phoneagent.util.StorageAccess.hasAccess(ctx)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    SectionEntry(
        icon = Icons.Filled.Lock,
        title = "文件权限",
        summary = if (granted) {
            "已开启 · 应用内可以直接读取并预览工作区里的文件"
        } else {
            "未开启 · 工作区在手机存储里时，应用内预览会读不到文件，点此开启"
        },
        onClick = { com.phoneagent.util.StorageAccess.openSettings(ctx) },
    )
}
