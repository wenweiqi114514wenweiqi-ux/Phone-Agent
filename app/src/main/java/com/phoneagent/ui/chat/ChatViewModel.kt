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
    /** 本会话默认目录；即使用户切到命名工作区/自由模式，上传文件仍要知道它。 */
    val conversationWorkspacePath: String? = null,
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
    /** 本轮“正常完成”的时间戳；报错/超时/用户停止不更新。 */
    val roundCompletedAt: Long = 0L,
)

class ChatViewModel(
    private val container: AppContainer,
    private val conversationId: String,
) : ViewModel() {

    private val initialConversationWorkspace = runCatching {
        container.workspaceRepository.conversationWorkspace(conversationId)
    }.getOrNull()
    private val initialBoundWorkspace = container.workspaceRepository.boundWorkspace(conversationId)
    private val initialFreeMode = container.workspaceRepository.isFreeMode(conversationId)
    private val _state = MutableStateFlow(
        ChatUiState(
            // 默认就是本会话目录；只有显式解绑才进入自由模式。
            workspacePath = initialBoundWorkspace?.path
                ?: if (initialFreeMode) null else initialConversationWorkspace?.path,
            workspaceReadonly = initialBoundWorkspace?.readonly ?: false,
            conversationWorkspacePath = initialConversationWorkspace?.path,
        )
    )
    val state: StateFlow<ChatUiState> = _state

    private var confirmContinuation: CancellableContinuation<Boolean>? = null
    private var questionContinuation: CancellableContinuation<String>? = null
    private var lastUserInput: String = ""
    private var seededAnimated = false
    private var runJob: Job? = null
    private var watchdogJob: Job? = null
    /** 最后一次收到内容/过程事件的时刻，用于判断"卡住"。 */
    private var lastActivityAt = 0L
    /** 非用户主动中断的原因（如超时），落库时要标出来。 */
    private var abortReason: String? = null
    private val streamBuf = StringBuilder()
    private var lastStreamAt = 0L
    /** 收到“换段”信号，但旧文字还没被 UI 看到：等下一段第一个增量再清空，避免闪断。 */
    private var streamResetPending = false
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

    /** 回到本会话默认目录（退出自由模式/命名工作区绑定）。 */
    fun useConversationWorkspace() {
        container.workspaceRepository.resetToConversation(conversationId)
        val ws = container.workspaceRepository.conversationWorkspace(conversationId)
        _state.update {
            it.copy(
                workspacePath = ws.path,
                workspaceReadonly = false,
                conversationWorkspacePath = ws.path,
            )
        }
        insertSystemBubble("已切换到本会话目录：${ws.path}")
    }

    /** 当前实际工作区：显式绑定的优先；未绑定且非自由模式时用会话目录。 */
    private fun currentWorkspacePath(): String? {
        _state.value.workspacePath?.let { return it }
        if (container.workspaceRepository.isFreeMode(conversationId)) return null
        return _state.value.conversationWorkspacePath
            ?: runCatching {
                container.workspaceRepository.conversationWorkspace(conversationId).path
            }.getOrNull()
    }

    /**
     * 用户附件落到哪里：优先当前工作区；自由模式/未绑定时落本会话目录。
     * 返回一个不覆盖已有文件的目标路径。
     */
    fun attachmentTarget(rawName: String): java.io.File {
        val path = _state.value.workspacePath
            ?: _state.value.conversationWorkspacePath
            ?: container.workspaceRepository.conversationWorkspace(conversationId).path
        val dir = java.io.File(path)
        dir.mkdirs()
        return com.phoneagent.data.ConversationWorkspaceNaming.uniqueFile(dir, rawName)
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
        // 若当前会话正用该工作区，回落到本会话目录；仍在自由模式则保持 null
        if (container.workspaceRepository.boundWorkspace(conversationId) == null) {
            val free = container.workspaceRepository.isFreeMode(conversationId)
            val fallback = container.workspaceRepository.conversationWorkspace(conversationId)
            _state.update {
                it.copy(
                    workspacePath = if (free) null else fallback.path,
                    workspaceReadonly = false,
                    conversationWorkspacePath = fallback.path,
                )
            }
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

    /**
     * 把节流缓冲里的全部文字立刻推给 UI。
     * 流结束、工具切换、停止时都要调用，保证最后一段不会只留在内存里。
     */
    private fun flushStream() {
        if (!_state.value.sending) return
        lastStreamAt = System.currentTimeMillis()
        val snapshot = streamBuf.toString()
        if (snapshot != _state.value.streamingText) {
            _state.update { it.copy(streamingText = snapshot) }
        }
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
            streamResetPending = false
            lastStreamAt = 0L
            lastActivityAt = System.currentTimeMillis()
            abortReason = null
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
            // 看门狗：连续 75 秒没有任何增量/工具事件就当卡死，主动收尾，
            // 避免"卡在一半"永远转圈，也让用户能继续下一轮。
            watchdogJob?.cancel()
            watchdogJob = launch {
                while (isActive) {
                    kotlinx.coroutines.delay(5_000)
                    if (!_state.value.sending) break
                    if (_state.value.pendingCommand != null || _state.value.pendingQuestion != null) {
                        lastActivityAt = System.currentTimeMillis()
                        continue
                    }
                    val idle = System.currentTimeMillis() - lastActivityAt
                    if (idle >= 75_000) {
                        abortReason = "生成超时：75 秒没有任何新内容，已自动中断"
                        runJob?.cancel()
                        break
                    }
                }
            }
            // 标题生成放后台，绝不阻塞回答
            if (isFirstUserMessage) {
                launch { autoTitle(text.ifBlank { "附件" }) }
            }
            val startedAt = System.currentTimeMillis()
            var promptTokens = 0
            var completionTokens = 0
            try {
                // 组装工作区上下文（默认本会话目录；命名工作区可共享）
                val wsPath = currentWorkspacePath()
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
                        lastActivityAt = System.currentTimeMillis()
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
                            // 上一步的文字先留在屏幕上；等新一步真正出第一个字再换段，
                            // 避免旧文字先消失、新文字还没来的空窗。
                            if (streamResetPending) {
                                streamBuf.setLength(0)
                                streamResetPending = false
                                _state.update { it.copy(streamingText = "") }
                            }
                            streamBuf.append(delta)
                            lastActivityAt = System.currentTimeMillis()
                        }
                        val now = System.currentTimeMillis()
                        if (_state.value.sending && now - lastStreamAt >= 60) {
                            lastStreamAt = now
                            val snapshot = streamBuf.toString()
                            _state.update { it.copy(streamingText = snapshot) }
                        }
                    },
                    onTextFlush = { flushStream() },
                    onTextSegment = { text ->
                        // 先把完整一段落库，再直接挂到当前消息列表。
                        // AgentController 会等这里返回后才进入下一步，不会提前清空气泡。
                        val note = MessageEntity(
                            id = java.util.UUID.randomUUID().toString(),
                            conversationId = conversationId,
                            role = "system",
                            content = text,
                            createdAt = System.currentTimeMillis(),
                        )
                        val saved = runCatching {
                            container.database.messageDao().insert(note)
                        }.isSuccess
                        if (saved) {
                            _state.update { s ->
                                if (s.messages.any { it.id == note.id }) s
                                else s.copy(messages = s.messages + note)
                            }
                            streamBuf.setLength(0)
                            streamResetPending = false
                            _state.update { it.copy(streamingText = "") }
                        } else {
                            com.phoneagent.util.AppLog.warn("chat", "过程文字落库失败")
                        }
                    },
                    onTextReset = {
                        // 不在 reset 当场清空：AgentController 落库的系统过程行
                        // 通过 Room Flow 回到 UI 需要一帧；立刻清会闪一下甚至吞字。
                        if (streamBuf.isNotEmpty()) {
                            streamResetPending = true
                        } else {
                            _state.update { it.copy(streamingText = "") }
                        }
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
                // 流结束/异常/停止都必须先把最后一段推给 UI，再谈收尾。
                if (!cancelled) flushStream()
                // 等 DB 把最终回答推给 UI（新消息 createdAt 一定晚于 startedAt），再撤草稿，避免闪一下
                if (!cancelled) {
                    var tries = 0
                    while (tries < 12 && _state.value.messages.none { m ->
                            m.role == "assistant" && m.command == null && m.status == null &&
                                m.createdAt >= startedAt && m.content.isNotBlank()
                        }) {
                        kotlinx.coroutines.delay(50)
                        tries++
                    }
                }
                var answer = if (cancelled) null else _state.value.messages.lastOrNull { m ->
                    m.role == "assistant" && m.command == null && m.status == null &&
                        m.createdAt >= startedAt && m.content.isNotBlank()
                }
                // Room Flow 还没刷新时，直接查一次库：AgentController 返回前已经 await 落库，
                // 这样不会出现“流式文字被清掉、正式回答还没冒出来”的空白帧。
                if (answer == null && !cancelled) {
                    val latest = withContext(Dispatchers.IO) {
                        container.database.messageDao().lastByConversation(conversationId)
                    }
                    if (latest != null && latest.role == "assistant" &&
                        latest.command == null && latest.status == null &&
                        latest.createdAt >= startedAt && latest.content.isNotBlank()
                    ) {
                        answer = latest
                        _state.update { s ->
                            if (s.messages.any { it.id == latest.id }) s
                            else s.copy(messages = s.messages + latest)
                        }
                    }
                }
                val finalAnswer = answer
                // 没拿到正式回答（报错/卡死/被中断）时，已经流出的内容也不能就这么消失
                val userStopped = _state.value.stopping
                val leftover = streamBuf.toString().trim()
                abortReason?.let { reason ->
                    _state.update { it.copy(error = reason) }
                }
                if (finalAnswer == null && leftover.isNotBlank() && !userStopped) {
                    val fallback = MessageEntity(
                        id = java.util.UUID.randomUUID().toString(),
                        conversationId = conversationId,
                        role = "system",
                        content = leftover + "\n\n（本段被中断，未成为最终回答" +
                            (abortReason?.let { "：" + it } ?: "") + "）",
                        createdAt = System.currentTimeMillis(),
                    )
                    runCatching {
                        container.database.messageDao().insert(fallback)
                        // 落库后立刻挂到当前列表，别等下一次 Flow。
                        _state.update { s ->
                            if (s.messages.any { it.id == fallback.id }) s
                            else s.copy(messages = s.messages + fallback)
                        }
                    }
                }
                streamBuf.setLength(0)
                streamResetPending = false
                watchdogJob?.cancel()
                watchdogJob = null
                if (finalAnswer != null) {
                    val estimated = completionTokens <= 0
                    val out = if (estimated) (finalAnswer.content.length / 2).coerceAtLeast(1) else completionTokens
                    _state.update {
                        it.copy(
                            sending = false,
                            stopping = false,
                            streamingText = "",
                            pendingCommand = null,
                            pendingQuestion = null,
                            pendingOptions = emptyList(),
                            // 只有正常拿到最终回答才算“完成”；停止/报错/超时不弹完成提示。
                            roundCompletedAt = if (!cancelled && !userStopped) {
                                System.currentTimeMillis()
                            } else {
                                it.roundCompletedAt
                            },
                            answerStats = it.answerStats + (finalAnswer.id to AnswerStats(
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
                    if (!cancelled || abortReason != null) send(next.first, next.second)
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
        // 以完整缓冲区为准，而不是上一帧 streamingText：
        // 60ms 节流里的最后几个字也算“用户已经看到/正在看”的内容。
        val partial = streamBuf.toString().trim().ifBlank {
            _state.value.streamingText.trim()
        }
        runJob?.cancel()
        runJob = null
        streamBuf.setLength(0)
        streamResetPending = false
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
