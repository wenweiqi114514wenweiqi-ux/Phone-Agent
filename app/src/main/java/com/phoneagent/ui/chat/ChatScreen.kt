package com.phoneagent.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.phoneagent.data.MessageEntity
import com.phoneagent.data.SettingsRepository
import com.phoneagent.data.WorkspaceRepository
import com.phoneagent.di.AppContainer
import com.phoneagent.llm.AgentThought
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem

/** 待发送附件：与入库结构一致（name/mime/size/path）。 */
private typealias AttachmentUi = com.phoneagent.data.AttachmentMeta

/** 底部面板类型。 */
private enum class SheetKind { WORKSPACE, MODELS, MODEL, TOOLS, CONTEXT, ATTACH }

/**
 * 聊天页（原生 Material3）：
 * 消息列表 + 底部快捷 chips + 输入行 + 底部弹出面板 + 对话框。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    container: AppContainer,
    conversationId: String,
) {
    val viewModel: ChatViewModel = viewModel(
        key = "chat_$conversationId",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ChatViewModel(container, conversationId) as T
        },
    )
    val state by viewModel.state.collectAsState()

    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    // 输入框内容走 ViewModel：切会话/旋转/切页都不会丢
    val input = state.draft
    fun setInput(v: String) = viewModel.updateDraft(v)
    var sheet by remember { mutableStateOf<SheetKind?>(null) }
    var showNewWs by remember { mutableStateOf(false) }
    var showManageWs by remember { mutableStateOf(false) }
    var editWs by remember { mutableStateOf<WorkspaceRepository.Workspace?>(null) }

    // ---- 附件（拍照 / 相册 / 文件，最多 3 个） ----
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var attachments by remember { mutableStateOf<List<AttachmentUi>>(emptyList()) }
    var attachHint by remember { mutableStateOf<String?>(null) }
    var cameraFile by remember { mutableStateOf<java.io.File?>(null) }

    fun addAttachment(item: AttachmentUi) {
        if (attachments.size >= 3) {
            attachHint = "最多添加 3 个附件"
            return
        }
        attachments = attachments + item
        attachHint = null
    }

    fun importUri(uri: android.net.Uri) {
        scope.launch {
            val item = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = java.io.File(ctx.filesDir, "attachments").apply { mkdirs() }
                    val name = queryDisplayName(ctx, uri) ?: "file_${System.currentTimeMillis()}"
                    val target = java.io.File(dir, name)
                    ctx.contentResolver.openInputStream(uri)?.use { ins ->
                        target.outputStream().use { outs -> ins.copyTo(outs) }
                    }
                    AttachmentUi(
                        name = name,
                        path = target.absolutePath,
                        mime = ctx.contentResolver.getType(uri) ?: guessMime(name),
                        size = target.length(),
                    )
                }.getOrNull()
            }
            if (item == null) attachHint = "读取文件失败，换一个试试" else addAttachment(item)
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        val f = cameraFile
        if (ok && f != null && f.exists()) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        val dir = java.io.File(ctx.filesDir, "attachments").apply { mkdirs() }
                        val target = java.io.File(dir, f.name)
                        f.copyTo(target, overwrite = true)
                        f.delete()
                        AttachmentUi(
                            name = target.name,
                            path = target.absolutePath,
                            mime = "image/jpeg",
                            size = target.length(),
                        )
                    }.getOrNull() ?: AttachmentUi(
                        name = f.name,
                        path = f.absolutePath,
                        mime = "image/jpeg",
                        size = f.length(),
                    )
                }
                addAttachment(saved)
            }
        }
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { importUri(it) } }
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { importUri(it) } }

    fun takePhoto() {
        val dir = java.io.File(ctx.cacheDir, "attachments").apply { mkdirs() }
        val f = java.io.File(dir, "IMG_${System.currentTimeMillis()}.jpg")
        cameraFile = f
        val uri = androidx.core.content.FileProvider.getUriForFile(
            ctx, "${ctx.packageName}.fileprovider", f
        )
        cameraLauncher.launch(uri)
    }

    // 只在自己贴底时跟随；用户上翻后停止跟随，内容继续在下方增长
    var followTail by rememberSaveable { mutableStateOf(true) }
    var autoScroll by remember { mutableStateOf(false) }
    // 真的来了新内容才提示；单纯手指滑一下不提示
    var hasNewBelow by remember { mutableStateOf(false) }
    var seenSignal by remember { mutableStateOf(0) }

    // 用户手指滚动（非程序滚动）才停止跟随；已经在底部就没有"离开"一说
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling && !autoScroll && listState.canScrollForward) followTail = false
            if (!scrolling) autoScroll = false
        }
    }
    // 用户自己滚回最底部时恢复跟随
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }.collect { canFwd ->
            if (!canFwd) followTail = true
        }
    }

    val mdRadius = container.settings.markdownRadius

    // 消息操作：重新输入 / 重新思考，都要先确认（会截断这段之后的消息）
    var confirmEdit by remember { mutableStateOf<MessageEntity?>(null) }
    var confirmRegen by remember { mutableStateOf<MessageEntity?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        val groups = groupMessages(state.messages)
        val lastLen = state.messages.lastOrNull()?.content?.length ?: 0

        // 贴底跟随：消息/思考面板变长时滚到最新，但绝不打断用户上翻
        val itemCount = groups.size + if (state.sending) 1 else 0
        val thoughtLen = state.thoughts.sumOf { it.content.length } + state.streamingText.length
        // 内容"版本号"：消息条数 + 流式文字长度 + 过程条数
        val contentSignal = itemCount * 20 + lastLen / 20 + thoughtLen / 32
        LaunchedEffect(followTail, contentSignal) {
            if (followTail) {
                seenSignal = contentSignal
                hasNewBelow = false
            } else if (contentSignal > seenSignal) {
                hasNewBelow = true
            }
        }
        LaunchedEffect(itemCount, lastLen / 40, thoughtLen / 32) {
            if (!followTail || itemCount == 0) return@LaunchedEffect
            autoScroll = true
            listState.scrollToItem(itemCount - 1, Int.MAX_VALUE)
            autoScroll = false
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (groups.isEmpty() && !state.sending) {
                item { GreetingCarousel() }
            }
            items(groups, key = { it.key }) { group ->
                GroupRow(
                    group = group,
                    mdRadius = mdRadius,
                    stats = state.answerStats,
                    shouldAnimate = { viewModel.shouldAnimate(it) },
                    onEditUser = { confirmEdit = it },
                    onRegenerate = { confirmRegen = it },
                )
            }
            if (state.sending && state.streamingText.isEmpty()) {
                item {
                    ThoughtPanel(
                        thoughts = state.thoughts,
                        showReasoning = container.settings.showReasoning,
                    )
                }
            }
            if (state.sending && state.streamingText.isNotEmpty()) {
                item { StreamingAnswer(state.streamingText, mdRadius) }
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = hasNewBelow,
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
        ) {
            Surface(
                onClick = {
                    followTail = true
                    hasNewBelow = false
                    autoScroll = true
                    scope.launch {
                        listState.scrollToItem(maxOf(0, itemCount - 1), Int.MAX_VALUE)
                        autoScroll = false
                    }
                },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f),
            ) {
                Text(
                    text = "↓ 有新内容",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        }

        // 错误提示条
        if (state.error != null) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "错误：${state.error}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { viewModel.retry() }) { Text("重试") }
                    TextButton(onClick = { viewModel.clearError() }) { Text("关闭") }
                }
            }
        }

        // AI 提问选项区
        val pendingQuestion = state.pendingQuestion
        if (pendingQuestion != null) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(pendingQuestion, style = MaterialTheme.typography.bodyMedium)
                    if (state.pendingOptions.isNotEmpty()) {
                        FlowRow(
                            modifier = Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            state.pendingOptions.forEach { opt ->
                                Button(onClick = { viewModel.respondQuestion(opt) }) { Text(opt) }
                            }
                        }
                    }
                }
            }
        }

        // 快捷 chips 行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = state.workspacePath != null,
                onClick = { sheet = SheetKind.WORKSPACE },
                label = { Text(if (state.workspacePath != null) "工作区" else "自由模式") },
            )
            AssistChip(
                onClick = { sheet = SheetKind.MODELS },
                label = { Text("模型 · ${state.activeModelName?.take(10) ?: "未配置"}") },
            )
            FilterChip(
                selected = state.deepThinking,
                onClick = { sheet = SheetKind.MODEL },
                label = {
                    Text(
                        if (state.deepThinking) "思考 · ${state.thinkingLevel} · ${fmtCtx(state.contextLength)}"
                        else "思考与上下文"
                    )
                },
            )
            AssistChip(onClick = { sheet = SheetKind.TOOLS }, label = { Text("工具") })
            AssistChip(onClick = { sheet = SheetKind.CONTEXT }, label = { Text("上下文") })
        }

        // 待发送附件：仅文件名 + 类型 + 大小 + 右上角叉，无预览
        if (attachments.isNotEmpty() || attachHint != null) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    attachments.forEach { a ->
                        AttachmentChip(a) {
                            attachments = attachments.filterNot { it.path == a.path }
                        }
                    }
                }
                if (attachHint != null) {
                    Text(
                        text = attachHint!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }

        if (state.queued) {
            Text(
                text = "已排队 · 当前回答结束后自动发送",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 20.dp, bottom = 2.dp),
            )
        }

        // 输入行
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconButton(onClick = { sheet = SheetKind.ATTACH }) {
                Icon(Icons.Filled.Add, contentDescription = "添加附件")
            }
            OutlinedTextField(
                value = input,
                onValueChange = { setInput(it) },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(if (pendingQuestion != null) "回复 AI 的问题…" else "说点什么，或让我操作手机…")
                },
                // 打字时最多长到 6 行，超出内部滚动；回车即发送（和 ChatGPT 一致）
                maxLines = 6,
                shape = MaterialTheme.shapes.extraLarge,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Send,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSend = {
                        if (state.sending) {
                            // 生成中回车 → 排队，不打断当前回答
                            if (input.isNotBlank() || attachments.isNotEmpty()) {
                                viewModel.send(input, attachments)
                                setInput("")
                                attachments = emptyList()
                                attachHint = null
                            }
                        } else if (pendingQuestion != null) {
                            viewModel.respondQuestion(input)
                            setInput("")
                        } else if (input.isNotBlank() || attachments.isNotEmpty()) {
                            viewModel.send(input, attachments)
                            setInput("")
                            attachments = emptyList()
                            attachHint = null
                        }
                    }
                ),
            )
            if (state.sending) {
                // 生成中：同一个位置变成"停止"，输入框仍然可以继续打字
                FilledIconButton(
                    onClick = { viewModel.stop() },
                    colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Box(
                        modifier = Modifier
                            .size(11.dp)
                            .background(
                                MaterialTheme.colorScheme.onErrorContainer,
                                androidx.compose.foundation.shape.RoundedCornerShape(2.dp),
                            ),
                    )
                }
            } else {
                FilledIconButton(
                    onClick = {
                        if (pendingQuestion != null) {
                            viewModel.respondQuestion(input)
                            setInput("")
                        } else {
                            viewModel.send(input, attachments)
                            setInput("")
                            attachments = emptyList()
                            attachHint = null
                        }
                    },
                    enabled = input.isNotBlank() || attachments.isNotEmpty(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                }
            }
        }
    }

    // 重新输入 / 重新思考都要先确认：它们会截断这段之后的对话
    confirmEdit?.let { msg ->
        AlertDialog(
            onDismissRequest = { confirmEdit = null },
            title = { Text("重新输入这条消息？") },
            text = { Text("这条消息之后的对话会被删除，原文会回到输入框，改完再发。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmEdit = null
                    viewModel.editUserMessage(msg.id)
                }) { Text("继续") }
            },
            dismissButton = {
                TextButton(onClick = { confirmEdit = null }) { Text("取消") }
            },
        )
    }
    confirmRegen?.let { msg ->
        AlertDialog(
            onDismissRequest = { confirmRegen = null },
            title = { Text("重新思考这条回答？") },
            text = { Text("这条回答之后的对话会被删除，然后重新生成一次。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRegen = null
                    viewModel.regenerate(msg.id)
                }) { Text("重新生成") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRegen = null }) { Text("取消") }
            },
        )
    }

    // 底部弹出面板
    val currentSheet = sheet
    if (currentSheet != null) {
        ModalBottomSheet(onDismissRequest = { sheet = null }) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                when (currentSheet) {
                    SheetKind.WORKSPACE -> WorkspaceSheet(
                        path = state.workspacePath,
                        readonly = state.workspaceReadonly,
                        workspaceList = state.workspaceList,
                        onSelect = { name, ro -> sheet = null; viewModel.selectWorkspace(name, ro) },
                        onClear = { sheet = null; viewModel.clearWorkspace() },
                        onNew = { showNewWs = true },
                        onManage = { showManageWs = true },
                    )
                    SheetKind.ATTACH -> AttachSheet(
                        onCamera = { sheet = null; takePhoto() },
                        onGallery = { sheet = null; galleryLauncher.launch("image/*") },
                        onFile = { sheet = null; fileLauncher.launch(arrayOf("*/*")) },
                    )
                    SheetKind.MODELS -> ModelPickerSheet(
                        models = state.models,
                        activeId = state.activeModelId,
                        activeName = state.activeModelName,
                        onSelect = { id ->
                            viewModel.selectModel(id)
                            sheet = null
                        },
                        onManage = { sheet = null },
                    )
                    SheetKind.MODEL -> ModelSheet(
                        deepThinking = state.deepThinking,
                        thinkingLevel = state.thinkingLevel,
                        contextLength = state.contextLength,
                        maxContext = state.activeMaxContext,
                        onToggle = viewModel::setDeepThinking,
                        onLevel = viewModel::setThinkingLevel,
                        onContext = viewModel::setContextLength,
                    )
                    SheetKind.TOOLS -> ToolsSheet()
                    SheetKind.CONTEXT -> ContextSheet(usage = state.usage)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // 高危操作确认
    val pending = state.pendingCommand
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { viewModel.respondConfirm(false) },
            title = { Text("高危操作确认") },
            text = {
                Column {
                    Text("工具：${pending.tool.name}")
                    Text("风险：${state.pendingRisk}")
                    Text(
                        text = pending.tool.commandTemplate ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.respondConfirm(true) }) { Text("允许") } },
            dismissButton = { TextButton(onClick = { viewModel.respondConfirm(false) }) { Text("拒绝") } },
        )
    }

    if (showNewWs) {
        NewWorkspaceDialog(
            existingNames = state.workspaceList.map { it.name },
            onConfirm = { name, path, ro -> showNewWs = false; viewModel.saveWorkspace(name, path, ro) },
            onDismiss = { showNewWs = false },
        )
    }
    if (showManageWs) {
        ManageWorkspaceDialog(
            workspaceList = state.workspaceList,
            currentPath = state.workspacePath,
            onSelect = { name, ro -> showManageWs = false; viewModel.selectWorkspace(name, ro) },
            onEdit = { ws -> showManageWs = false; editWs = ws },
            onDelete = viewModel::deleteWorkspace,
            onNew = { showManageWs = false; showNewWs = true },
            onDismiss = { showManageWs = false },
        )
    }
    editWs?.let { ws ->
        EditWorkspaceDialog(
            current = ws,
            existingNames = state.workspaceList.map { it.name }.filter { it != ws.name },
            onConfirm = { name, path, ro ->
                editWs = null
                viewModel.updateWorkspace(ws.name, name, path, ro)
            },
            onDismiss = { editWs = null },
        )
    }
}

// ================= 消息渲染 =================

private data class GroupRow(
    val key: String,
    val user: MessageEntity?,
    val steps: List<MessageEntity>,
    val answer: MessageEntity?,
    /** 助手提交的交付物消息：单独成块，不会被后续回答覆盖 */
    val cards: List<MessageEntity> = emptyList(),
)

/** 按回合分组：用户提问 / 工具步骤 / 最终回答。 */
private fun groupMessages(messages: List<MessageEntity>): List<GroupRow> {
    val result = mutableListOf<GroupRow>()
    var user: MessageEntity? = null
    val steps = mutableListOf<MessageEntity>()
    val cards = mutableListOf<MessageEntity>()
    var answer: MessageEntity? = null

    fun flush() {
        if (user != null || steps.isNotEmpty() || answer != null || cards.isNotEmpty()) {
            val key = user?.id ?: cards.firstOrNull()?.id ?: steps.firstOrNull()?.id
                ?: answer?.id ?: "g${result.size}"
            result += GroupRow(key, user, steps.toList(), answer, cards.toList())
        }
        user = null
        steps.clear()
        cards.clear()
        answer = null
    }

    messages.forEach { msg ->
        val isStep = msg.role == "assistant" &&
            ((msg.command != null && msg.status != null) || msg.content.startsWith("步骤"))
        val isCard = msg.role == "assistant" && !isStep &&
            com.phoneagent.data.AttachmentCodec.decode(msg.attachments).any { it.submitted }
        when {
            msg.role == "user" -> {
                flush()
                user = msg
            }
            msg.role == "system" -> {
                flush()
                result += GroupRow(msg.id, null, emptyList(), msg)
            }
            isStep -> steps += msg
            isCard -> cards += msg
            else -> answer = msg
        }
    }
    flush()
    return result
}

@Composable
private fun GroupRow(
    group: GroupRow,
    mdRadius: Int,
    stats: Map<String, AnswerStats>,
    shouldAnimate: (String) -> Boolean,
    onEditUser: (MessageEntity) -> Unit = {},
    onRegenerate: (MessageEntity) -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        group.user?.let { UserBubble(it, onEdit = { onEditUser(it) }) }
        if (group.steps.isNotEmpty()) StepsCollapsed(group.steps)
        group.cards.forEach { card ->
            if (card.content.isBlank() || card.content.startsWith("已把 ")) {
                Spacer(Modifier.size(if (group.steps.isNotEmpty()) 14.dp else 6.dp))
                SubmissionCards(
                    com.phoneagent.data.AttachmentCodec.decode(card.attachments).filter { it.submitted }
                )
            } else {
                AssistantBubble(card, mdRadius, stats[card.id], shouldAnimate(card.id))
            }
        }
        // 卡片块与正文之间：正常段落间隙，别贴在一起
        if (group.cards.isNotEmpty() && group.answer != null) {
            Spacer(Modifier.size(14.dp))
        }
        group.answer?.let {
            if (it.role == "system") {
                SystemLine(it.content)
            } else {
                AssistantBubble(
                    it, mdRadius, stats[it.id], shouldAnimate(it.id),
                    onRegenerate = { onRegenerate(it) },
                )
            }
        }
    }
}

/** 旧版本把附件清单拼进了正文；渲染时裁掉，只留用户原话。 */
private val legacyAttachmentBlock = Regex("\\n\\n\\[用户附件\\] 共 (\\d+) 个[\\s\\S]*")

@Composable
private fun UserBubble(msg: MessageEntity, onEdit: () -> Unit = {}) {
    val atts = remember(msg.attachments) {
        com.phoneagent.data.AttachmentCodec.decode(msg.attachments)
    }
    val legacyMatch = remember(msg.content) { legacyAttachmentBlock.find(msg.content) }
    val text = remember(msg.content, legacyMatch) {
        if (legacyMatch != null) msg.content.substring(0, legacyMatch.range.first) else msg.content
    }
    val legacyCount = remember(legacyMatch) { legacyMatch?.groupValues?.getOrNull(1)?.toIntOrNull() }
    Column(modifier = Modifier.fillMaxWidth()) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = bubbleShape(tailAtEnd = true),
            modifier = Modifier.fillMaxWidth(0.88f),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                if (text.isNotBlank()) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
                if (atts.isNotEmpty()) {
                    if (text.isNotBlank()) Spacer(Modifier.height(8.dp))
                    AttachmentPreviewList(atts)
                } else if (legacyCount != null && legacyCount > 0) {
                    if (text.isNotBlank()) Spacer(Modifier.height(6.dp))
                    Text(
                        text = "📎 ${legacyCount} 个附件（旧消息，不再显示预览）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
    // 用户消息下方：复制 + 重新输入（低亮小图标，不打扰正文）
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        MessageAction(text = "复制") {
            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("message", text))
            android.widget.Toast.makeText(ctx, "已复制", android.widget.Toast.LENGTH_SHORT).show()
        }
        Spacer(Modifier.width(10.dp))
        IconButton(
            onClick = onEdit,
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = "重新输入",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                modifier = Modifier.size(15.dp),
            )
        }
    }
    }
}

/** 消息下方的一枚小操作（文字形态，低亮）。 */
@Composable
private fun MessageAction(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
        modifier = Modifier
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

/** 附件一眼可见：图片给缩略图，其它给文件卡（名称 + 类型 + 大小），不再显示路径。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttachmentPreviewList(list: List<com.phoneagent.data.AttachmentMeta>) {
    val images = list.filter { it.isImage }
    val files = list.filterNot { it.isImage }
    Column {
        if (images.isNotEmpty()) {
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                images.forEach { AttachmentThumb(it) }
            }
        }
        files.forEachIndexed { index, f ->
            if (index > 0 || images.isNotEmpty()) Spacer(Modifier.height(6.dp))
            AttachmentFileCard(f)
        }
    }
}

@Composable
private fun AttachmentThumb(item: com.phoneagent.data.AttachmentMeta) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, item.path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(item.path, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 320 || bounds.outHeight / sample > 320) sample *= 2
                android.graphics.BitmapFactory.decodeFile(
                    item.path,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
                )
            }.getOrNull()
        }
    }
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .size(92.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(shape),
            )
        } else {
            Text("图片", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun AttachmentFileCard(item: com.phoneagent.data.AttachmentMeta) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = extLabel(item.name),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = typeLabel(item.mime, item.name) + " · " + sizeLabel(item.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

private fun extLabel(name: String): String {
    val ext = name.substringAfterLast('.', "").uppercase()
    return ext.ifBlank { "FILE" }.take(4)
}

@Composable
private fun SystemLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}

/** 工具步骤：默认折叠为一行，点击展开明细。 */
@Composable
private fun StepsCollapsed(steps: List<MessageEntity>) {
    var expanded by remember { mutableStateOf(false) }
    val okCount = steps.count { it.status == "ok" }
    val failed = steps.count { it.status == "error" || it.status == "refused" || it.status == "skipped" }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = buildString {
                    append("已执行 ")
                    append(steps.size)
                    append(" 条命令")
                    if (failed > 0) {
                        append(" · ")
                        append(failed)
                        append(" 条失败")
                    }
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (expanded) "收起" else "展开",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Text(
                text = if (expanded) "▴" else "▾",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(260)) + fadeIn(animationSpec = tween(200)),
            exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(140)),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 4.dp)) {
                steps.forEachIndexed { index, step ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 6.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                    val ok = step.status == "ok"
                    val mark = when (step.status) {
                        "ok" -> "✓"
                        "skipped", "pending" -> "⤼"
                        else -> "✕"
                    }
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = mark,
                            style = MaterialTheme.typography.labelSmall,
                            color = when {
                                ok -> MaterialTheme.colorScheme.primary
                                step.status == "skipped" || step.status == "pending" ->
                                    MaterialTheme.colorScheme.outline
                                else -> MaterialTheme.colorScheme.error
                            },
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(
                                text = displayCommand(step),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val detail = step.result ?: step.content
                            if (detail.isNotBlank() && detail != step.command) {
                                Text(
                                    text = detail.trim().replace("\n", " ").take(80) +
                                        if (detail.trim().length > 80) "…" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 步骤行标题：命令名 + 精简参数。 */
private fun displayCommand(step: MessageEntity): String {
    val cmd = step.command?.trim()
    if (cmd.isNullOrBlank()) {
        return step.content.removePrefix("步骤").trim().take(60).ifBlank { "（无命令）" }
    }
    return cmd.replace("\n", " ").take(72)
}

@Composable
private fun AssistantBubble(
    msg: MessageEntity,
    mdRadius: Int,
    stats: AnswerStats?,
    animate: Boolean,
    onRegenerate: () -> Unit = {},
) {
    var shown by remember(msg.id) { mutableStateOf(!animate) }
    LaunchedEffect(msg.id) { if (animate) shown = true }

    AnimatedVisibility(
        visible = shown,
        enter = if (animate) {
            fadeIn(animationSpec = tween(420)) +
                slideInVertically(animationSpec = tween(420), initialOffsetY = { it / 12 })
        } else {
            EnterTransition.None
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 交付物卡片（助手提交的文件）
            val submitted = remember(msg.attachments) {
                com.phoneagent.data.AttachmentCodec.decode(msg.attachments).filter { it.submitted }
            }
            if (submitted.isNotEmpty()) {
                SubmissionCards(submitted)
                if (msg.content.isNotBlank()) Spacer(Modifier.size(14.dp))
            }
            // 无卡片、无背景、无边框：文字直接落在页面上
            // 长按可选中，调系统文字工具栏（复制/全选/分享）
            SelectionContainer {
                MarkdownContent(msg.content, mdRadius)
            }
            if (!msg.reasoning.isNullOrBlank()) ReasoningCollapsed(msg.reasoning)
            stats?.let { s ->
                Text(
                    text = buildString {
                        append("用时 ")
                        append(String.format(java.util.Locale.US, "%.1f", s.elapsedMs / 1000f))
                        append("s · 输出 ")
                        append(s.completionTokens)
                        append(" tok")
                        if (s.promptTokens > 0) {
                            append(" · 输入 ")
                            append(s.promptTokens)
                            append(" tok")
                        }
                        if (s.estimated) append("（约）")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            // 这条回答的操作：重新思考（只在这一轮结束后出现，流式中不显示）
            if (msg.content.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onRegenerate,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "重新思考",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReasoningCollapsed(reasoning: String) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "收起思维链" else "查看思维链", style = MaterialTheme.typography.labelSmall)
    }
    if (expanded) {
        Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            Text(
                text = reasoning,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(10.dp),
            )
        }
    }
}

/**
 * 实时执行区：只显示"正在做什么"（含思维链）。
 * 正文由 StreamingAnswer 在主气泡位置流式长出来，两者不重复。
 */
@Composable
private fun ThoughtPanel(thoughts: List<AgentThought>, showReasoning: Boolean) {
    if (thoughts.isEmpty()) return

    // 只看最后一轮：多步执行时把历史轮的思考拼接起来会显得"一直在重复同一句"
    val reasoningText = thoughts.takeLastWhile { it.type == "reasoning" }
        .joinToString("") { it.content }
    val latestProgress = thoughts.lastOrNull { it.type == "progress" }
    val latestToolCall = thoughts.lastOrNull { it.type == "tool_call" || it.type == "command" }

    val cursor = rememberInfiniteTransition(label = "cursor")
    val cursorAlpha by cursor.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "cursorAlpha",
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "▍",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                modifier = Modifier
                    .blur(5.dp)
                    .alpha(cursorAlpha),
            )
            Text(
                text = when {
                    latestProgress != null -> latestProgress.content
                    latestToolCall != null ->
                        "使用了 ${latestToolCall.toolName ?: latestToolCall.content.take(20)} · 执行中"
                    else -> "思考中…"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        if (showReasoning && reasoningText.isNotBlank()) {
            Text(
                text = reasoningText.trim().take(240),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 13.dp, top = 4.dp),
            )
        }
    }
}

/** 流式阶段的块：文字段 / 代码段（代码段允许"还没闭合"）。 */
private sealed class StreamBlock {
    data class Text(val content: String) : StreamBlock()
    data class Code(val lang: String, val code: String, val open: Boolean) : StreamBlock()
}

/**
 * 流式切块：和 splitMarkdown 的区别是——
 * 遇到 ``` 开了头就算代码块（哪怕还没闭合），所以第一行代码就能进框，
 * 不会先冒出一堆 ``` 和星号，再在结束时"整块变成代码框"。
 */
private fun splitStreamingMarkdown(text: String): List<StreamBlock> {
    val out = mutableListOf<StreamBlock>()
    val textBuf = StringBuilder()
    val codeBuf = StringBuilder()
    var inCode = false
    var lang = ""

    fun flushText() {
        val t = textBuf.toString()
        if (t.isNotBlank()) out += StreamBlock.Text(cleanPartialInline(t))
        textBuf.clear()
    }
    fun flushCode(open: Boolean) {
        out += StreamBlock.Code(lang, codeBuf.toString().trimEnd('\n'), open)
        codeBuf.clear()
        if (!open) lang = ""
    }

    text.lines().forEach { line ->
        if (line.trimStart().startsWith("```")) {
            if (inCode) {
                flushCode(open = false)
                inCode = false
            } else {
                flushText()
                inCode = true
                lang = line.trim().removePrefix("```").trim()
            }
        } else if (inCode) {
            if (codeBuf.isNotEmpty()) codeBuf.append('\n')
            codeBuf.append(line)
        } else {
            if (textBuf.isNotEmpty()) textBuf.append('\n')
            textBuf.append(line)
        }
    }
    if (inCode) {
        flushCode(open = true)
    } else {
        flushText()
    }
    return out
}

/** 流式期间把"还没配对的" ** 和 ` 去掉，别让用户看到半截语法符号。 */
private fun cleanPartialInline(s: String): String {
    var t = s
    // 末尾残留的星号/反引号（正在打，尚未闭合）
    t = t.replace(Regex("[*`]+$"), "")
    // 行内 ** 数量为奇数 → 最后一个视为没打完，去掉
    val boldCount = Regex("\\*\\*").findAll(t).count()
    if (boldCount % 2 == 1) {
        val last = t.lastIndexOf("**")
        if (last >= 0) t = t.removeRange(last, last + 2)
    }
    return t
}

/**
 * 流式正文：按块成形，代码从第一行起就在框里，框随行数往下长。
 * 文字保持无框（和最终回答一致），所以结束时是"接着写完"，不会整块跳变。
 */
@Composable
private fun StreamingAnswer(text: String, mdRadius: Int) {
    val blocks = remember(text) { splitStreamingMarkdown(text) }
    val cursor = rememberInfiniteTransition(label = "cursor")
    val cursorAlpha by cursor.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "cursorAlpha",
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        blocks.forEach { b ->
            when (b) {
                is StreamBlock.Text -> if (b.content.isNotBlank()) {
                    Text(
                        text = renderMarkdown(b.content),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                is StreamBlock.Code -> StreamingCodeBox(b.lang, b.code, mdRadius, b.open)
            }
        }
        // 光标固定在最后，跟着长
        Text(
            text = "▍",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary.copy(alpha = cursorAlpha),
        )
    }
}

/** 流式代码框：和最终代码块同一套外观，逐行长出来。 */
@Composable
private fun StreamingCodeBox(lang: String, code: String, radius: Int, open: Boolean) {
    Surface(
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
        shape = RoundedCornerShape(radius.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (lang.isNotBlank()) {
                Text(
                    text = lang,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Text(
                text = code,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
            if (open) {
                // 还在写：底部一个很淡的"还在继续"提示，不抢内容
                Text(
                    text = "…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/** 一行小字、低亮度的动作提示。 */
@Composable
private fun ActionLine(text: String) {
    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .padding(end = 7.dp)
                .height(5.dp)
                .width(5.dp)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                    RoundedCornerShape(3.dp),
                ),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f),
        )
    }
}

/**
 * 空状态：按当前时段轮播的问候句。
 * 切换动画为「模糊滚动」——新句从下方带着模糊升起并逐渐清晰，旧句向上带模糊淡出。
 */
@Composable
private fun GreetingCarousel() {
    val lines = remember {
        val hour = java.time.LocalTime.now().hour
        greetingLines(hour)
    }
    var cur by remember { mutableIntStateOf(0) }
    var prev by remember { mutableIntStateOf(-1) }
    val anim = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(4200)
            prev = cur
            cur = (cur + 1) % lines.size
            anim.snapTo(0f)
            anim.animateTo(1f, animationSpec = tween(durationMillis = 1000))
        }
    }

    Box(
        modifier = Modifier.fillMaxWidth().height(220.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (prev >= 0) {
            val p = lines[prev % lines.size]
            Text(
                text = p,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f * (1f - anim.value)),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .graphicsLayer {
                        translationY = -anim.value * 36f
                        alpha = (1f - anim.value).coerceIn(0f, 1f)
                    }
                    .softBlur((1f - anim.value) * 9f),
            )
        }
        Text(
            text = lines[cur],
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .graphicsLayer {
                    translationY = (1f - anim.value) * 44f
                    alpha = anim.value.coerceIn(0f, 1f)
                }
                .softBlur((1f - anim.value) * 10f),
        )
    }
}

/** API 31+ 才真正应用模糊；低版本自动降级为纯位移淡入。 */
private fun Modifier.softBlur(radiusDp: Float): Modifier =
    if (android.os.Build.VERSION.SDK_INT >= 31 && radiusDp > 0.4f) {
        this.blur(radiusDp.dp)
    } else {
        this
    }

/** 按时段准备问候句，每次进入随机起点，循环播放。 */
private fun greetingLines(hour: Int): List<String> = when (hour) {
    in 5..8 -> listOf(
        "这么早就醒了？",
        "早上好，今天有什么安排？",
        "先去吃点东西吧，我在这儿等着。",
        "今天想要做点什么？",
        "早，需要我帮你查天气吗？",
        "新的一天，要不要先列个清单？",
        "早晨效率最高，趁现在做点正事？",
        "早上好，昨天的事还想接着弄吗？",
    )
    in 9..11 -> listOf(
        "上午好，今天想干点什么？",
        "有什么卡住的，说出来我看看。",
        "今天想要做点什么？",
        "上午的时间最值钱，先处理难的？",
        "需要我帮你整理文件吗？",
        "要不要先把今天的任务过一遍？",
        "有什么要查的，直接说。",
        "上午好，灵感来了吗？",
    )
    in 12..13 -> listOf(
        "中午了，吃了吗？",
        "午休前想让我做点什么？",
        "中午好，要不要设个提醒？",
        "这个点别太拼，先歇会儿。",
        "今天想要做点什么？",
        "需要我帮你看看日程吗？",
        "吃完饭容易困，来点轻的活？",
        "中午好，有什么顺手能办的？",
    )
    in 14..17 -> listOf(
        "下午好，今天做点什么？",
        "午后容易犯困，要不要放首歌？",
        "有什么卡住的，我们一起拆一下。",
        "今天想要做点什么？",
        "下午好，进度怎么样了？",
        "需要我帮你把文件收一下吗？",
        "这个点适合处理琐事，交给我？",
        "要不要我帮你查点资料？",
    )
    in 18..22 -> listOf(
        "晚上好，今天过得怎么样？",
        "晚上好，灵感大爆发了？",
        "这个点适合整理今天的事，要我帮你总结吗？",
        "今天想要做点什么？",
        "晚上好，想聊点什么？",
        "需要我帮你清一下手机空间吗？",
        "忙了一天，先歇会儿再说。",
        "晚上好，明天的安排要不要先定下来？",
    )
    else -> listOf(
        "还没睡？",
        "夜深了，别熬太久。",
        "这个点找我，是有什么放不下的事？",
        "今天想要做点什么？",
        "深夜好，需要我帮你做点什么？",
        "安静的时段，适合想点事情。",
        "如果只是睡不着，我陪你聊两句。",
        "早点休息，明天再弄也不迟。",
    )
}

// ================= 底部面板内容 =================

@Composable
private fun WorkspaceSheet(
    path: String?,
    readonly: Boolean,
    workspaceList: List<WorkspaceRepository.Workspace>,
    onSelect: (String, Boolean) -> Unit,
    onClear: () -> Unit,
    onNew: () -> Unit,
    onManage: () -> Unit,
) {
    Text("工作区", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text(
        text = if (path != null) "当前：$path${if (readonly) "（只读）" else ""}"
        else "当前：自由模式（文件路径不受限制）",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = 4.dp),
    )
    Spacer(Modifier.height(8.dp))
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text("自由模式", style = MaterialTheme.typography.titleSmall) },
        supportingContent = {
            Text(
                "不限制文件操作路径",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        },
        trailingContent = { if (path == null) Icon(Icons.Filled.Check, contentDescription = null) },
        modifier = Modifier.clickable { onClear() },
    )
    workspaceList.forEach { ws ->
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(ws.name, style = MaterialTheme.typography.titleSmall) },
            supportingContent = {
                Text(
                    ws.path + if (ws.readonly) "（只读）" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            },
            trailingContent = { if (path == ws.path) Icon(Icons.Filled.Check, contentDescription = null) },
            modifier = Modifier.clickable { onSelect(ws.name, ws.readonly) },
        )
    }
    Row(modifier = Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onNew) { Text("新建") }
        TextButton(onClick = onManage) { Text("管理") }
    }
}

/**
 * 模型设置面板：思考强度 + 上下文长度。
 * 设计上不使用任何「框」：无卡片边框、无输入框，只有文字行与分隔线。
 */
/** 聊天页快速切换模型。 */
@Composable
private fun ModelPickerSheet(
    models: List<com.phoneagent.data.ModelProfile>,
    activeId: String?,
    activeName: String?,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
) {
    Text("切换模型", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text(
        text = if (activeName != null) "当前：$activeName" else "当前没有可用模型",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = 2.dp),
    )
    if (models.isEmpty()) {
        Text(
            "还没有添加任何模型，先去设置里添加一个。",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 12.dp),
        )
        TextButton(onClick = onManage) { Text("知道了") }
    } else {
        Column(modifier = Modifier.padding(top = 8.dp)) {
            models.forEach { p ->
                ListItem(
                    colors = androidx.compose.material3.ListItemDefaults.colors(
                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    ),
                    leadingContent = {
                        androidx.compose.material3.RadioButton(
                            selected = p.id == activeId,
                            onClick = { onSelect(p.id) },
                        )
                    },
                    headlineContent = { Text(p.name, style = MaterialTheme.typography.titleSmall) },
                    supportingContent = {
                        Text(
                            "${p.model} · 上限 ${fmtCtx(p.maxContext)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    },
                    modifier = Modifier.clickable { onSelect(p.id) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelSheet(
    deepThinking: Boolean,
    thinkingLevel: String,
    contextLength: Int,
    maxContext: Int,
    onToggle: (Boolean) -> Unit,
    onLevel: (String) -> Unit,
    onContext: (Int) -> Unit,
) {
    Text("模型设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text(
        "只影响本次对话的请求参数",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = 2.dp),
    )

    // ---- 深度思考 ----
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("深度思考", style = MaterialTheme.typography.bodyLarge)
            Text(
                "先推理再回答，需要模型支持 reasoning",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Switch(
            checked = deepThinking,
            onCheckedChange = { on ->
                onToggle(on)
                if (on && thinkingLevel == "OFF") onLevel("LOW")
            },
        )
    }

    // ---- 思考强度（纯文字选择，无框） ----
    Text(
        "思考强度",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = 12.dp),
    )
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf("OFF" to "关闭", "LOW" to "低", "MIDDLE" to "中", "MAX" to "高").forEach { (lv, cn) ->
            val selected = thinkingLevel == lv
            Column(
                modifier = Modifier.clickable { onLevel(lv) },
            ) {
                Text(
                    text = if (selected) "$cn · $lv" else "$cn · $lv",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .height(2.dp)
                        .width(if (selected) 28.dp else 0.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.dp)),
                )
            }
        }
    }

    // ---- 上下文长度（滑块，9 档） ----
    val options = SettingsRepository.CONTEXT_OPTIONS
    val currentIndex = options.indexOfLast { it <= contextLength }.coerceAtLeast(0)
    var sliderIndex by remember(currentIndex) { mutableFloatStateOf(currentIndex.toFloat()) }
    val selectedTokens = options[sliderIndex.toInt().coerceIn(0, options.lastIndex)]
    val effective = minOf(selectedTokens, maxContext)

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "上下文长度",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.weight(1f))
        Text(
            fmtCtx(selectedTokens),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    Slider(
        value = sliderIndex,
        onValueChange = { sliderIndex = it },
        valueRange = 0f..options.lastIndex.toFloat(),
        steps = (options.size - 2).coerceAtLeast(0),
        onValueChangeFinished = {
            onContext(options[sliderIndex.toInt().coerceIn(0, options.lastIndex)])
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(modifier = Modifier.fillMaxWidth()) {
        Text("4K", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.weight(1f))
        Text("1M", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
    Text(
        text = "当前模型上限 ${fmtCtx(maxContext)}" +
            if (selectedTokens > maxContext) "，超出部分不会生效（实际按 $effective token 裁剪）" else "，未超出模型上限",
        style = MaterialTheme.typography.labelSmall,
        color = if (selectedTokens > maxContext) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = 4.dp),
    )
    Text(
        "越大能带越多历史，但更慢更贵；超出部分会被自动裁剪",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = 2.dp),
    )
}

private fun fmtCtx(tokens: Int): String = when {
    tokens >= 1_000_000 -> "${tokens / 1_000_000}M"
    tokens >= 1024 -> "${tokens / 1024}K"
    else -> "$tokens"
}

@Composable
private fun ToolsSheet() {
    Text("工具", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text(
        "内置工具覆盖：文件操作、应用管理、屏幕输入、系统信息、网络请求、OCR 识别、" +
            "界面操控（需无障碍）、系统设置与后台保活。\n\n在「工具」页可查看全部工具并启用/禁用。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun ContextSheet(usage: com.phoneagent.llm.TokenUsage) {
    Text("上下文用量", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Column(modifier = Modifier.padding(top = 6.dp)) {
        Text("输入 tokens：${usage.promptTokens}", style = MaterialTheme.typography.bodySmall)
        Text("输出 tokens：${usage.completionTokens}", style = MaterialTheme.typography.bodySmall)
        Text("缓存命中：${(usage.cacheRatio * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
    }
}

// ================= 工作区对话框 =================

@Composable
private fun NewWorkspaceDialog(
    existingNames: List<String>,
    onConfirm: (String, String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("/sdcard/AgentWork") }
    var readOnly by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (_: Exception) {
            }
            val resolved = uriToFilePath(uri)
            if (resolved != null) {
                path = resolved
                error = null
            } else {
                error = "无法解析该目录，请手动输入路径"
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建工作区") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text("根目录") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedButton(
                    onClick = { folderPicker.launch(null) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text("选择文件夹（系统选择器）") }
                if (error != null) {
                    Text(
                        error!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = readOnly, onCheckedChange = { readOnly = it })
                    Text("只读（禁止写入/删除/下载）", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && path.isNotBlank() && name !in existingNames,
                onClick = { onConfirm(name.trim(), path.trim(), readOnly) },
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ManageWorkspaceDialog(
    workspaceList: List<WorkspaceRepository.Workspace>,
    currentPath: String?,
    onSelect: (String, Boolean) -> Unit,
    onEdit: (WorkspaceRepository.Workspace) -> Unit,
    onDelete: (String) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    var menuFor by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("管理工作区")
                Text(
                    text = "点一项直接切换；右侧 ⋮ 可编辑或删除",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (workspaceList.isEmpty()) {
                    Text(
                        "还没有工作区。点下面的「新建工作区」选一个文件夹即可。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                workspaceList.forEach { ws ->
                    val inUse = currentPath != null && currentPath == ws.path
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onSelect(ws.name, ws.readonly) }
                            .padding(vertical = 8.dp, horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = ws.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                if (inUse) {
                                    Spacer(Modifier.width(6.dp))
                                    Surface(
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                        shape = RoundedCornerShape(50),
                                    ) {
                                        Text(
                                            text = "使用中",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                        )
                                    }
                                }
                                if (ws.readonly) {
                                    Spacer(Modifier.width(6.dp))
                                    Surface(
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                                        shape = RoundedCornerShape(50),
                                    ) {
                                        Text(
                                            text = "只读",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                        )
                                    }
                                }
                            }
                            Text(
                                text = ws.path,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Box {
                            IconButton(
                                onClick = { menuFor = ws.name },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = "更多",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            DropdownMenu(
                                expanded = menuFor == ws.name,
                                onDismissRequest = { menuFor = null },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("编辑名称 / 路径 / 只读") },
                                    onClick = { menuFor = null; onEdit(ws) },
                                )
                                DropdownMenuItem(
                                    text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                    onClick = { menuFor = null; onDelete(ws.name) },
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onNew) { Text("新建工作区") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** SAF content:// Uri → 真实路径（主存储 / 可插拔卷）。 */
private fun uriToFilePath(uri: android.net.Uri): String? {
    return try {
        if (uri.authority == "com.android.externalstorage.documents") {
            val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
            val parts = docId.split(":")
            val volume = parts[0]
            val relative = if (parts.size > 1) parts[1] else ""
            if (volume.equals("primary", true)) "/storage/emulated/0/$relative"
            else "/storage/$volume/$relative"
        } else null
    } catch (_: Exception) {
        null
    }
}

// ================= Markdown =================

private sealed class MdSegment {
    data class Text(val content: String) : MdSegment()
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdSegment()
    data class Code(val lang: String, val code: String) : MdSegment()
}

private fun isTableSeparator(line: String): Boolean {
    val t = line.trim()
    return (t.startsWith("|") || t.contains("|")) &&
        t.replace("|", "").replace("-", "").replace(" ", "").replace(":", "").isBlank()
}

private fun splitMarkdown(text: String): List<MdSegment> {
    val lines = text.lines()
    val segments = mutableListOf<MdSegment>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]

        // 代码块 ``` 包裹
        if (line.trimStart().startsWith("```")) {
            val lang = line.trim().removePrefix("```").trim()
            val code = StringBuilder()
            var j = i + 1
            while (j < lines.size && !lines[j].trimStart().startsWith("```")) {
                if (code.isNotEmpty()) code.append('\n')
                code.append(lines[j])
                j++
            }
            segments += MdSegment.Code(lang, code.toString())
            i = if (j < lines.size) j + 1 else j
            continue
        }

        // 表格
        if (line.contains('|') && i + 1 < lines.size && isTableSeparator(lines[i + 1])) {
            val header = line.trim().trim('|').split('|').map { it.trim() }
            val rows = mutableListOf<List<String>>()
            var j = i + 2
            while (j < lines.size) {
                val dataLine = lines[j].trim()
                if (dataLine.isBlank() || !(dataLine.startsWith("|") || dataLine.contains('|'))) break
                rows += dataLine.trim('|').split('|').map { it.trim() }
                j++
            }
            if (rows.isNotEmpty()) {
                segments += MdSegment.Table(header, rows)
                i = j
                continue
            }
        }

        // 普通文本段（直到下一个表格/代码块）
        val buf = StringBuilder(line)
        var k = i + 1
        while (k < lines.size) {
            if (lines[k].trimStart().startsWith("```")) break
            if (lines[k].contains('|') && k + 1 < lines.size && isTableSeparator(lines[k + 1])) break
            buf.append('\n').append(lines[k])
            k++
        }
        segments += MdSegment.Text(buf.toString())
        i = k
    }
    return segments
}

@Composable
private fun MarkdownContent(text: String, radius: Int) {
    // 自己带 Column：外层可能被 SelectionContainer 包成 Box，
    // 直接往父容器吐子节点会让段落/代码块叠在一起。
    Column(modifier = Modifier.fillMaxWidth()) {
        splitMarkdown(text).forEach { seg ->
            when (seg) {
                is MdSegment.Text -> if (seg.content.isNotBlank()) {
                    Text(
                        text = renderMarkdown(seg.content),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                is MdSegment.Table -> MarkdownTable(seg, radius)
                is MdSegment.Code -> MarkdownCode(seg, radius)
            }
        }
    }
}

/** 代码块：圆角卡片包裹，等宽字体，横向可滚动。 */
@Composable
private fun MarkdownCode(code: MdSegment.Code, radius: Int) {
    Surface(
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
        shape = RoundedCornerShape(radius.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (code.lang.isNotBlank()) {
                Text(
                    text = code.lang,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Text(
                text = code.code,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        }
    }
}

/** 表格：整体圆角包裹，单元格细线分隔。 */
@Composable
private fun MarkdownTable(table: MdSegment.Table, radius: Int) {
    val cols = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    if (cols == 0) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(radius.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = 2.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                (0 until cols).forEach { c ->
                    Text(
                        text = table.header.getOrNull(c) ?: "",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 7.dp),
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            table.rows.forEach { row ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    (0 until cols).forEach { c ->
                        Text(
                            text = row.getOrNull(c) ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 5.dp),
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

private fun renderMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    text.lines().forEachIndexed { idx, line ->
        if (idx > 0) append("\n")
        when {
            line.startsWith("### ") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(line.removePrefix("### "))
            }
            line.startsWith("## ") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(line.removePrefix("## "))
            }
            line.startsWith("# ") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(line.removePrefix("# "))
            }
            line.startsWith("- ") || line.startsWith("* ") -> {
                append("• ")
                renderInline(line.removePrefix("- ").removePrefix("* "))
            }
            else -> renderInline(line)
        }
    }
}

private fun AnnotatedString.Builder.renderInline(line: String) {
    var remaining = line
    while (remaining.isNotEmpty()) {
        val boldStart = remaining.indexOf("**")
        val codeStart = remaining.indexOf('`')
        val next = listOfNotNull(
            boldStart.takeIf { it >= 0 }?.let { "bold" to it },
            codeStart.takeIf { it >= 0 }?.let { "code" to it },
        ).minByOrNull { it.second }
        if (next == null) {
            append(remaining)
            break
        }
        val (type, start) = next
        append(remaining.substring(0, start))
        when (type) {
            "bold" -> {
                val end = remaining.indexOf("**", start + 2)
                if (end >= 0) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(remaining.substring(start + 2, end))
                    }
                    remaining = remaining.substring(end + 2)
                } else {
                    append(remaining.substring(start))
                    break
                }
            }
            else -> {
                val end = remaining.indexOf('`', start + 1)
                if (end >= 0) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                        append(remaining.substring(start + 1, end))
                    }
                    remaining = remaining.substring(end + 1)
                } else {
                    append(remaining.substring(start))
                    break
                }
            }
        }
    }
}

/** 气泡形状：跟随主题圆角，仅在尾巴一侧收窄。 */
@Composable
private fun bubbleShape(tailAtEnd: Boolean): androidx.compose.ui.graphics.Shape {
    val base = MaterialTheme.shapes.large
    return if (tailAtEnd) {
        RoundedCornerShape(
            topStart = base.topStart,
            topEnd = base.topEnd,
            bottomStart = base.bottomStart,
            bottomEnd = androidx.compose.foundation.shape.CornerSize(8.dp),
        )
    } else {
        RoundedCornerShape(
            topStart = base.topStart,
            topEnd = base.topEnd,
            bottomStart = androidx.compose.foundation.shape.CornerSize(8.dp),
            bottomEnd = base.bottomEnd,
        )
    }
}

/** 待发送附件的紧凑卡片：文件名 + 类型/大小 + 右上角 ×，无预览。 */
@Composable
private fun AttachmentChip(item: AttachmentUi, onRemove: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        shape = MaterialTheme.shapes.medium,
    ) {
        Box {
            Column(
                modifier = Modifier.padding(start = 10.dp, end = 22.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 150.dp),
                )
                Text(
                    text = typeLabel(item.mime, item.name) + " · " + sizeLabel(item.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            Text(
                text = "✕",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clickable(onClick = onRemove)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}

/** 添加附件面板：三行平铺，无卡片无边框。 */
@Composable
private fun AttachSheet(onCamera: () -> Unit, onGallery: () -> Unit, onFile: () -> Unit) {
    Text("添加附件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text(
        text = "最多 3 个；只显示文件名与类型，不预览",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = 2.dp),
    )
    Spacer(Modifier.height(8.dp))
    listOf(
        Triple("拍照", "调用系统相机，拍摄后自动压缩为 JPEG", onCamera),
        Triple("从相册选择", "选一张图片发给模型看", onGallery),
        Triple("添加文件", "文本 / 代码 / PDF / 文档等任意文件", onFile),
    ).forEach { (title, desc, action) ->
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(title, style = MaterialTheme.typography.titleSmall) },
            supportingContent = {
                Text(
                    desc,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            },
            modifier = Modifier.clickable(onClick = action),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    }
}

private fun queryDisplayName(ctx: android.content.Context, uri: android.net.Uri): String? =
    runCatching {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()

private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "pdf" -> "application/pdf"
    "txt", "md", "json", "kt", "java", "py", "sh", "log", "csv" -> "text/plain"
    "doc", "docx" -> "application/msword"
    else -> "application/octet-stream"
}

private fun typeLabel(mime: String, name: String): String = when {
    mime.startsWith("image/") -> "图片"
    mime == "application/pdf" -> "PDF"
    mime.startsWith("text/") -> "文本"
    mime.contains("word") -> "Word"
    else -> name.substringAfterLast('.', "文件").uppercase()
}

private fun sizeLabel(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1fMB", bytes / 1024f / 1024f)
    bytes >= 1024 -> "${bytes / 1024}KB"
    else -> "${bytes}B"
}

/** 编辑已有工作区：名称 / 根目录 / 只读，保存即生效（改名会同步会话绑定）。 */
@Composable
private fun EditWorkspaceDialog(
    current: WorkspaceRepository.Workspace,
    existingNames: List<String>,
    onConfirm: (String, String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var name by remember(current.name) { mutableStateOf(current.name) }
    var path by remember(current.path) { mutableStateOf(current.path) }
    var readOnly by remember(current.readonly) { mutableStateOf(current.readonly) }
    var error by remember { mutableStateOf<String?>(null) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (_: Exception) {
            }
            val resolved = uriToFilePath(uri)
            if (resolved != null) {
                path = resolved
                error = null
            } else {
                error = "无法解析该目录，请手动输入路径"
            }
        }
    }

    val nameTaken = name.trim() in existingNames

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑工作区") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 24) name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    isError = nameTaken,
                    supportingText = if (nameTaken) {
                        { Text("名称已被占用") }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text("根目录") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedButton(
                    onClick = { folderPicker.launch(null) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text("选择文件夹（系统选择器）") }
                if (error != null) {
                    Text(
                        text = error!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = readOnly, onCheckedChange = { readOnly = it })
                    Text("只读（禁止写入/删除/下载）", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && path.isNotBlank() && !nameTaken,
                onClick = { onConfirm(name.trim(), path.trim(), readOnly) },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
