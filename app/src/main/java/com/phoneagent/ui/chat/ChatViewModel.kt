package com.phoneagent.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phoneagent.data.AttachmentCodec
import com.phoneagent.data.AttachmentMeta
import com.phoneagent.data.ConversationEntity
import com.phoneagent.data.MessageEntity
import com.phoneagent.di.AppContainer
import com.phoneagent.llm.AgentThought
import com.phoneagent.llm.TokenUsage
import com.phoneagent.tools.ParsedCommand
import com.phoneagent.tools.SafetyPolicy
import com.phoneagent.tools.ToolRisk
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 一轮回答的耗时与 token 统计，按消息 id 存放。 */
data class AnswerStats(
    val elapsedMs: Long = 0,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    /** 模型没返回 usage 时按字数估算 */
    val estimated: Boolean = true,
)

data class ChatUiState(
    val messages: List<MessageEntity> = emptyList(),
    val thoughts: List<AgentThought> = emptyList(),
    val sending: Boolean = false,
    val pendingCommand: ParsedCommand? = null,
    val pendingRisk: ToolRisk? = null,
    val pendingQuestion: String? = null,
    val pendingOptions: List<String> = emptyList(),
    val error: String? = null,
    val executorReady: Boolean = false,
    val usage: TokenUsage = TokenUsage(),
    val deepThinking: Boolean = false,
    val thinkingLevel: String = "OFF",
    val contextLength: Int = 131072,
    val models: List<com.phoneagent.data.ModelProfile> = emptyList(),
    val activeModelId: String? = null,
    val activeModelName: String? = null,
    val activeMaxContext: Int = 131072,
    val workspacePath: String? = null,
    val workspaceReadonly: Boolean = false,
    val workspaceList: List<com.phoneagent.data.WorkspaceRepository.Workspace> = emptyList(),
    /** 已播过入场动画的消息 id，避免切页回来时整屏气泡重放 */
    val animatedIds: Set<String> = emptySet(),
    /** 每条回答的耗时/用量 */
    val answerStats: Map<String, AnswerStats> = emptyMap(),
    /** 正在流式输出的正文（只在内存里，落库后清空） */
    val streamingText: String = "",
    /** 已请求停止，按钮转圈用 */
    val stopping: Boolean = false,
    /** 输入框草稿：按会话保留，切页/切会话不丢 */
    val draft: String = "",
    /** 生成中又发了一条 → 已排队，本轮结束自动发出 */
    val queued: Boolean = false,
)

class ChatViewModel(
    private val container: AppContainer,
    private val conversationId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state

    private var confirmContinuation: CancellableContinuation<Boolean>? = null
    private var questionContinuation: CancellableContinuation<String>? = null
    private var lastUserInput: String = ""
    private var seededAnimated = false
    private var runJob: Job? = null
    private val streamBuf = StringBuilder()
    private var lastStreamAt = 0L
    private var pendingSend: Pair<String, List<AttachmentMeta>>? = null

    init {
        // 先把历史读进内存，避免打开页面时空列表闪一下问候语
        viewModelScope.launch {
            runCatching {
                val pre = container.database.messageDao().allByConversation(conversationId)
                if (pre.isNotEmpty()) _state.update { it.copy(messages = pre) }
            }
        }
        viewModelScope.launch {
            runCatching {
            container.database.messageDao().observeByConversation(conversationId)
                .catch { e ->
                    com.phoneagent.util.AppLog.error("db", "读取聊天记录失败", e)
                    _state.update {
                        it.copy(error = "读取聊天记录失败：" + (e.message ?: e.javaClass.simpleName))
                    }
                }
                .collect { msgs ->
                if (!seededAnimated) {
                    seededAnimated = true
                    val ids = msgs.map { it.id }
                    _state.update { it.copy(animatedIds = it.animatedIds + ids) }
                }
                _state.update { it.copy(messages = msgs) }
            }
            }
        }
        viewModelScope.launch {
            container.shizukuManager.status.collect { status ->
                _state.update { it.copy(executorReady = status == com.phoneagent.exec.ShizukuStatus.READY) }
            }
        }
        _state.update {
            it.copy(
                draft = drafts[conversationId].orEmpty(),
                deepThinking = container.settings.deepThinking,
                thinkingLevel = container.settings.thinkingLevel,
                contextLength = container.settings.contextLength,
                workspaceList = container.workspaceRepository.list(),
            )
        }
        // 模型列表 + 当前选中
        viewModelScope.launch {
            container.modelRepository.state.collect { ms ->
                _state.update {
                    it.copy(
                        models = ms.profiles,
                        activeModelId = ms.active?.id,
                        activeModelName = ms.active?.name,
                        activeMaxContext = ms.active?.maxContext ?: 131072,
                    )
                }
            }
        }
        // 读取会话当前绑定工作区
        container.workspaceRepository.boundWorkspace(conversationId)?.let { ws ->
            _state.update { it.copy(workspacePath = ws.path, workspaceReadonly = ws.readonly) }
        }
    }

    /** 切换到指定工作区（绑定会话），并插入一条系统提示气泡。 */
    fun selectWorkspace(name: String, readonly: Boolean) {
        val ws = container.workspaceRepository.list().firstOrNull { it.name == name } ?: return
        container.workspaceRepository.bind(conversationId, name)
        _state.update { it.copy(workspacePath = ws.path, workspaceReadonly = ws.readonly) }
        insertSystemBubble("✅ 已切换到工作区：${ws.path}" + if (ws.readonly) "（只读模式）" else "")
    }

    /** 切回自由模式（解除绑定）。 */
    fun clearWorkspace() {
        container.workspaceRepository.unbind(conversationId)
        _state.update { it.copy(workspacePath = null, workspaceReadonly = false) }
        insertSystemBubble("已切换为自由模式：文件操作不再限制路径")
    }

    /** 新建/保存工作区。 */
    fun saveWorkspace(name: String, path: String, readonly: Boolean) {
        container.workspaceRepository.save(
            com.phoneagent.data.WorkspaceRepository.Workspace(name, path, readonly)
        )
        _state.update { it.copy(workspaceList = container.workspaceRepository.list()) }
    }

    /** 编辑工作区（改名/改路径/只读）；当前会话正用它时同步刷新。 */
    fun updateWorkspace(oldName: String, name: String, path: String, readonly: Boolean) {
        container.workspaceRepository.update(
            oldName,
            com.phoneagent.data.WorkspaceRepository.Workspace(name, path, readonly),
        )
        _state.update { it.copy(workspaceList = container.workspaceRepository.list()) }
        if (container.workspaceRepository.boundWorkspace(conversationId)?.name == name) {
            _state.update { it.copy(workspacePath = path, workspaceReadonly = readonly) }
        }
    }

    /** 删除工作区（含解除会话绑定）。 */
    fun deleteWorkspace(name: String) {
        container.workspaceRepository.delete(name)
        _state.update { it.copy(workspaceList = container.workspaceRepository.list()) }
        // 若当前会话正用该工作区，切回自由模式
        if (container.workspaceRepository.boundWorkspace(conversationId) == null) {
            _state.update { it.copy(workspacePath = null, workspaceReadonly = false) }
        }
    }

    /** 插入一条灰色系统提示气泡。 */
    private fun insertSystemBubble(text: String) {
        viewModelScope.launch {
            container.database.messageDao().insert(
                MessageEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    role = "system",
                    content = text,
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
    }

    /** 深度思考总开关。 */
    fun setDeepThinking(on: Boolean) {
        container.settings.deepThinking = on
        _state.update { it.copy(deepThinking = on) }
    }

    /** 重试上一条输入：先删除上一条失败的用户消息，避免重复累积。 */
    fun retry() {
        if (lastUserInput.isBlank()) return
        viewModelScope.launch {
            // 找到该会话最后一条用户消息，如果内容等于上一条输入且后面没有成功的 assistant 回答，就删掉
            val recent = container.database.messageDao().recentByConversation(conversationId, 10)
            val lastUserMsg = recent.lastOrNull { it.role == "user" }
            if (lastUserMsg != null && lastUserMsg.content == lastUserInput) {
                // 检查这条用户消息后面是否有 assistant 最终回答（无 command/status 的）
                val idx = recent.indexOf(lastUserMsg)
                val hasAnswerAfter = recent.subList(idx + 1, recent.size)
                    .any { it.role == "assistant" && it.command == null }
                if (!hasAnswerAfter) {
                    container.database.messageDao().delete(lastUserMsg)
                }
            }
            send(lastUserInput)
        }
    }

    /** 清除错误状态。 */
    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /** 思考强度（OFF/LOW/MIDDLE/MAX）。 */
    fun setThinkingLevel(level: String) {
        container.settings.thinkingLevel = level
        _state.update { it.copy(thinkingLevel = level) }
    }

    /** 上下文长度（token）。 */
    fun setContextLength(tokens: Int) {
        container.settings.contextLength = tokens
        _state.update { it.copy(contextLength = tokens) }
    }

    /** 这条消息是否该播入场动画：只有第一次见到的消息才播。 */
    fun shouldAnimate(messageId: String): Boolean {
        val seen = _state.value.animatedIds
        if (messageId in seen) return false
        _state.update { it.copy(animatedIds = it.animatedIds + messageId) }
        return true
    }

    /** 快速切换当前模型。 */
    fun selectModel(id: String) {
        container.modelRepository.setActive(id)
    }

    fun send(text: String, attachments: List<AttachmentMeta> = emptyList()) {
        if (text.isBlank() && attachments.isEmpty()) return
        if (_state.value.sending) {
            // 正在生成时又发了 → 排队，等这一轮结束自动发出（ChatGPT 的行为）
            pendingSend = text.trim() to attachments
            _state.update { it.copy(queued = true) }
            return
        }
        launchRound(text, attachments, insertUserMessage = true)
    }

    /**
     * 跑一轮。
     * @param insertUserMessage 重新生成时为 false：用户那条消息已经在库里，不再插一条
     */
    private fun launchRound(
        text: String,
        attachments: List<AttachmentMeta>,
        insertUserMessage: Boolean,
    ) {
        if (_state.value.sending) return
        if (container.modelRepository.active() == null) {
            _state.update { it.copy(error = "还没有可用的模型，请到「设置 → 模型与接入」添加一个") }
            return
        }
        // 气泡只显示用户原话；附件说明只在发给模型的上下文里拼（AgentController 负责）
        val plain = text.trim()
        val fullText = plain
        lastUserInput = plain + AttachmentCodec.promptBlock(attachments)
        val isFirstUserMessage = _state.value.messages.none { it.role == "user" }
        runJob = viewModelScope.launch {
            ensureConversation()
            streamBuf.setLength(0)
            lastStreamAt = 0L
            _state.update {
                it.copy(
                    sending = true,
                    stopping = false,
                    thoughts = emptyList(),
                    error = null,
                    usage = TokenUsage(),
                    streamingText = "",
                )
            }
            // 标题生成放后台，绝不阻塞回答
            if (isFirstUserMessage) {
                launch { autoTitle(text.ifBlank { "附件" }) }
            }
            val startedAt = System.currentTimeMillis()
            var promptTokens = 0
            var completionTokens = 0
            try {
                // 组装工作区上下文（有则传给 Agent 做沙箱）
                val wsPath = _state.value.workspacePath
                val workspace = if (wsPath != null) {
                    com.phoneagent.agent.WorkspaceContext(
                        root = wsPath,
                        readonly = _state.value.workspaceReadonly,
                    )
                } else null
                container.agent.run(
                    conversationId = conversationId,
                    userText = fullText,
                    onThought = { t ->
                        _state.update {
                            var cur = it.copy(thoughts = it.thoughts + t)
                            // 累积 token 用量（usage 事件）
                            if (t.type == "usage") {
                                val u = parseUsage(t.content)
                                promptTokens = u.promptTokens
                                completionTokens = u.completionTokens
                                cur = cur.copy(usage = u)
                            }
                            cur
                        }
                    },
                    onNeedConfirm = { cmd -> awaitConfirm(cmd) },
                    onAskUser = { question, options -> awaitQuestion(question, options) },
                    workspace = workspace,
                    attachments = attachments,
                    insertUserMessage = insertUserMessage,
                    // 正文流式：约 60ms 推一次，既不卡帧又能看到逐字增长
                    onTextDelta = { delta ->
                        // 已点停止：忽略连接里残留的增量，不让人看到"停了还在写"
                        if (_state.value.sending) {
                            streamBuf.append(delta)
                        }
                        val now = System.currentTimeMillis()
                        if (_state.value.sending && now - lastStreamAt >= 60) {
                            lastStreamAt = now
                            val snapshot = streamBuf.toString()
                            _state.update { it.copy(streamingText = snapshot) }
                        }
                    },
                    onTextReset = {
                        streamBuf.setLength(0)
                        _state.update { it.copy(streamingText = "") }
                    },
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 用户点了停止：已流出的内容由 stop() 落库，这里只做收尾
                throw e
            } catch (e: Exception) {
                com.phoneagent.util.AppLog.error("agent", "生成回答失败", e)
                _state.update {
                    it.copy(
                        error = e.message ?: "未知错误",
                        // 发送失败把原文还回输入框，不用重新打字
                        draft = if (it.draft.isBlank()) fullText else it.draft,
                    )
                }
            } finally {
                val cancelled = !kotlinx.coroutines.currentCoroutineContext().isActive
                val elapsed = System.currentTimeMillis() - startedAt
                // 等 DB 把最终回答推给 UI（新消息 createdAt 一定晚于 startedAt），再撤草稿，避免闪一下
                if (!cancelled) {
                    var tries = 0
                    while (tries < 8 && _state.value.messages.none { m ->
                            m.role == "assistant" && m.command == null && m.status == null &&
                                m.createdAt >= startedAt && m.content.isNotBlank()
                        }) {
                        kotlinx.coroutines.delay(50)
                        tries++
                    }
                }
                val answer = if (cancelled) null else _state.value.messages.lastOrNull { m ->
                    m.role == "assistant" && m.command == null && m.status == null &&
                        m.createdAt >= startedAt && m.content.isNotBlank()
                }
                streamBuf.setLength(0)
                if (answer != null) {
                    val estimated = completionTokens <= 0
                    val out = if (estimated) (answer.content.length / 2).coerceAtLeast(1) else completionTokens
                    _state.update {
                        it.copy(
                            sending = false,
                            stopping = false,
                            streamingText = "",
                            pendingCommand = null,
                            pendingQuestion = null,
                            pendingOptions = emptyList(),
                            answerStats = it.answerStats + (answer.id to AnswerStats(
                                elapsedMs = elapsed,
                                promptTokens = promptTokens,
                                completionTokens = out,
                                estimated = estimated,
                            )),
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            sending = false,
                            stopping = false,
                            streamingText = "",
                            pendingCommand = null,
                            pendingQuestion = null,
                            pendingOptions = emptyList(),
                        )
                    }
                }
                runCatching {
                    container.database.conversationDao().touch(conversationId)
                }
                // 排队的那条：这一轮正常结束才自动发出（用户主动停下来时不再追发）
                val next = pendingSend
                pendingSend = null
                if (next != null) {
                    _state.update { it.copy(queued = false) }
                    if (!cancelled) send(next.first, next.second)
                }
            }
        }
    }

    /** 重新思考：删掉这条回答及其之后的所有消息，用同一个问题重跑（不重复插用户消息）。 */
    fun regenerate(assistantMessageId: String) {
        if (_state.value.sending) return
        viewModelScope.launch {
            runCatching {
                val dao = container.database.messageDao()
                val all = dao.allByConversation(conversationId)
                val target = all.firstOrNull { it.id == assistantMessageId } ?: return@runCatching
                val ask = all.lastOrNull {
                    it.role == "user" && it.createdAt <= target.createdAt
                } ?: return@runCatching
                dao.deleteAfter(conversationId, ask.createdAt)
                launchRound(
                    text = ask.content,
                    attachments = AttachmentCodec.decode(ask.attachments),
                    insertUserMessage = false,
                )
            }.onFailure { com.phoneagent.util.AppLog.warn("chat", "重新生成失败", it) }
        }
    }

    /** 重新输入：删掉这条用户消息及其之后的全部内容，把原文放回输入框，等用户改完再发。 */
    fun editUserMessage(userMessageId: String) {
        if (_state.value.sending) return
        viewModelScope.launch {
            runCatching {
                val dao = container.database.messageDao()
                val all = dao.allByConversation(conversationId)
                val target = all.firstOrNull { it.id == userMessageId && it.role == "user" }
                    ?: return@runCatching
                // 这条本身也删掉（createdAt - 1 把它包含进来），避免原文和改后的新消息重复
                dao.deleteAfter(conversationId, target.createdAt - 1)
                updateDraft(target.content)
            }.onFailure { com.phoneagent.util.AppLog.warn("chat", "回填失败", it) }
        }
    }

    /**
     * 停止生成：已经流出来的内容照样保留（末尾标"已停止"），不把用户看到的东西吞掉。
     */
    fun stop() {
        val partial = _state.value.streamingText.trim()
        runJob?.cancel()
        runJob = null
        streamBuf.setLength(0)
        _state.update {
            it.copy(
                sending = false,
                stopping = false,
                streamingText = "",
                pendingCommand = null,
                pendingQuestion = null,
                pendingOptions = emptyList(),
            )
        }
        if (partial.isBlank()) return
        viewModelScope.launch {
            runCatching {
                val dao = container.database.messageDao()
                val last = dao.allByConversation(conversationId).lastOrNull {
                    it.role == "assistant" && it.command == null && it.status == null
                }
                // 已经落库了同内容的回答就不再补一条
                if (last?.content?.replace("（已停止）", "")?.trim() == partial) return@runCatching
                dao.insert(
                    MessageEntity(
                        id = java.util.UUID.randomUUID().toString(),
                        conversationId = conversationId,
                        role = "assistant",
                        content = partial + "\n\n（已停止）",
                        createdAt = System.currentTimeMillis(),
                    )
                )
            }.onFailure { com.phoneagent.util.AppLog.warn("agent", "停止后落库失败", it) }
        }
    }

    /** 输入框草稿：按会话存，切走再切回来还在。 */
    fun updateDraft(text: String) {
        drafts[conversationId] = text
        _state.update { it.copy(draft = text) }
    }

    /** 会话还没有正式名字时，用第一条消息自动命名（新对话/默认会话 → 摘要）。 */
    /** 会话不存在就补齐：新建会话与发送消息之间的竞态由这里兜底。 */
    private suspend fun ensureConversation() {
        runCatching {
            val dao = container.database.conversationDao()
            val now = System.currentTimeMillis()
            val conv = withContext(Dispatchers.IO) { dao.byId(conversationId) }
            if (conv == null) {
                withContext(Dispatchers.IO) {
                    dao.upsert(
                        ConversationEntity(
                            id = conversationId,
                            title = "新对话",
                            createdAt = now,
                            updatedAt = now,
                        )
                    )
                }
            } else {
                withContext(Dispatchers.IO) { dao.touch(conversationId, now) }
            }
        }
    }

    /**
     * 标题：先用首句占位（列表立刻有名字），再让当前模型浓缩成 6–14 字。
     * 取名期间用户手动改过名，就不再覆盖。
     */
    private suspend fun autoTitle(firstText: String) {
        runCatching {
            val dao = container.database.conversationDao()
            val conv = dao.byId(conversationId) ?: return@runCatching
            val t = conv.title.trim()
            if (t.isNotEmpty() && t != "新对话" && t != "默认会话") return@runCatching
            val fallback = firstText.replace(Regex("\\s+"), " ").trim().take(18)
            if (fallback.isNotBlank()) {
                dao.upsert(conv.copy(title = fallback, updatedAt = System.currentTimeMillis()))
            }
            val ai = container.agent.generateTitle(firstText) ?: return@runCatching
            val latest = dao.byId(conversationId) ?: return@runCatching
            if (latest.title == fallback || latest.title == "新对话" || latest.title == "默认会话") {
                dao.upsert(latest.copy(title = ai, updatedAt = System.currentTimeMillis()))
            }
        }
    }

    /** 高危命令确认挂起：弹窗由 UI 层根据 pendingCommand 渲染，用户在弹窗点击后 resume。 */
    private suspend fun awaitConfirm(cmd: ParsedCommand): Boolean {
        _state.update {
            it.copy(pendingCommand = cmd, pendingRisk = SafetyPolicy.parseRisk(cmd.tool.risk))
        }
        return suspendCancellableCoroutine { cont ->
            confirmContinuation = cont
        }
    }

    fun respondConfirm(allow: Boolean) {
        confirmContinuation?.resume(allow, null)
        confirmContinuation = null
        _state.update { it.copy(pendingCommand = null) }
    }

    /** ask_user 挂起：输入框变选项框，等待用户点击选项或输入回复。 */
    private suspend fun awaitQuestion(question: String, options: List<String>): String {
        _state.update { it.copy(pendingQuestion = question, pendingOptions = options) }
        com.phoneagent.notification.AgentNotifier.notifyWaiting(question)
        return suspendCancellableCoroutine { cont ->
            questionContinuation = cont
        }
    }

    fun respondQuestion(answer: String) {
        questionContinuation?.resume(answer, null)
        questionContinuation = null
        com.phoneagent.notification.AgentNotifier.cancelWaiting()
        _state.update { it.copy(pendingQuestion = null, pendingOptions = emptyList()) }
    }

    /** 解析 LLM 返回的 usage 事件（JSON: {prompt, completion, cached}）。 */
    private fun parseUsage(json: String): TokenUsage = try {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(json) as? JsonObject
            ?: return TokenUsage()
        TokenUsage(
            promptTokens = (root["prompt"] as? JsonPrimitive)?.intOrNull ?: 0,
            completionTokens = (root["completion"] as? JsonPrimitive)?.intOrNull ?: 0,
            cachedTokens = (root["cached"] as? JsonPrimitive)?.intOrNull ?: 0,
        )
    } catch (_: Exception) {
        TokenUsage()
    }

    companion object {
        /** 会话草稿（进程内保留；Activity 重建也不丢） */
        private val drafts = mutableMapOf<String, String>()
    }
}
