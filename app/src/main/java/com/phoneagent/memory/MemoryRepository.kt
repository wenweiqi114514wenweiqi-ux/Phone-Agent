package com.phoneagent.memory

import com.phoneagent.data.MemoryDao
import com.phoneagent.data.MemoryEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * 长期记忆仓储：本地存储、跨会话全局共享、按相关度检索后注入 LLM 上下文。
 *
 * 检索策略（当前）：多关键词 LIKE 命中 + 命中次数 × 权重打分。
 * 升级路径：FTS4 → 本地 embedding 向量。
 */
class MemoryRepository(
    private val memoryDao: MemoryDao,
) {
    fun observeAll(): Flow<List<MemoryEntity>> = memoryDao.observeAll()

    suspend fun search(keyword: String, limit: Int = 8): List<MemoryEntity> =
        memoryDao.searchByKeyword(keyword, limit)

    /**
     * 相关记忆检索：把用户这句话拆成若干关键词分别查，再按「命中次数 × 权重」排序。
     * 避免把整句当关键词导致永远查不到。
     */
    suspend fun recall(query: String, limit: Int = 6): List<MemoryEntity> {
        val keys = extractKeywords(query)
        if (keys.isEmpty()) return emptyList()
        val hits = mutableListOf<MemoryEntity>()
        keys.forEach { key ->
            runCatching { memoryDao.searchByKeyword(key, 12) }.getOrNull()?.let { hits += it }
        }
        if (hits.isEmpty()) return emptyList()
        // 对话总结属于「上一轮发生过什么」，默认不注入，避免模型把旧对话当成当前上下文续写
        val filtered = hits.filter { it.category != "conversation_summary" }
        if (filtered.isEmpty()) return emptyList()
        return filtered.groupBy { it.id }
            .map { (_, list) -> list.first() to list.size }
            // 只命中一个关键词的记忆太容易"误伤"：用户这句里出现「计算」，就会把上一次
            // 对话里"设备没有 Python"这种结论捞出来。因此要求 2 个不同关键词命中，
            // 或这条记忆本身被标记为高权重（weight >= 3）。
            .filter { (memory, hitCount) -> hitCount >= 2 || memory.weight >= 3 }
            .sortedWith(
                compareByDescending<Pair<MemoryEntity, Int>> {
                    it.second * (it.first.weight.coerceAtLeast(1))
                }.thenByDescending { it.first.updatedAt }
            )
            .take(limit)
            .map { it.first }
    }

    /** 关键词抽取：中文 2-gram + 英文/数字词，过滤停用词。 */
    fun extractKeywords(query: String): List<String> {
        val text = query.trim()
        if (text.isEmpty()) return emptyList()
        val result = linkedSetOf<String>()

        // 英文 / 数字 / 下划线词
        Regex("[A-Za-z0-9_]{2,}").findAll(text).forEach { result += it.value.lowercase() }

        // 中文：先整段（记忆可能整句包含），再 2-gram 滑窗
        Regex("[\\u4e00-\\u9fa5]{2,}").findAll(text).map { it.value }.toList().forEach { chunk ->
            if (chunk.length in 2..6) result += chunk
            for (i in 0..chunk.length - 2) {
                result += chunk.substring(i, i + 2)
            }
        }
        return result
            .filter { it.length >= 2 && it !in STOPWORDS }
            .take(8)
    }

    suspend fun add(content: String, category: String? = null, tags: String? = null, weight: Int = 1) {
        val now = System.currentTimeMillis()
        memoryDao.upsert(
            MemoryEntity(
                id = UUID.randomUUID().toString(),
                content = sanitize(content),
                category = category,
                tags = tags,
                weight = weight,
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    suspend fun update(memory: MemoryEntity) = memoryDao.upsert(
        memory.copy(content = sanitize(memory.content), updatedAt = System.currentTimeMillis())
    )

    suspend fun delete(memory: MemoryEntity) = memoryDao.delete(memory)

    suspend fun clearAll() = memoryDao.clearAll()

    /** 把命中记忆格式化为注入提示词的文本块。 */
    fun toPromptBlock(memories: List<MemoryEntity>): String =
        if (memories.isEmpty()) {
            ""
        } else {
            val now = System.currentTimeMillis()
            memories.joinToString("\n") { m ->
                val src = when (m.category) {
                    "user" -> "[用户要求记住] "
                    "user_note" -> "[用户随手记] "
                    "conversation_summary" -> "[旧对话摘要·仅供回忆] "
                    else -> ""
                }
                "- $src[" + ageLabel(now - m.updatedAt) + "] " + sanitize(m.content)
            }
    }

    /** 相对时间：让模型知道这条是多久以前记下的。 */
    private fun ageLabel(ageMs: Long): String {
        val minutes = ageMs / 60_000
        return when {
            minutes < 60 -> "刚刚"
            minutes < 60 * 24 -> "${minutes / 60} 小时前"
            minutes < 60 * 24 * 30 -> "${minutes / (60 * 24)} 天前"
            else -> "${minutes / (60 * 24 * 30)} 个月前"
        }
        }

    /**
     * 记忆文本净化：去掉注入式指令前缀与系统角色标记，限制长度。
     * 用户可编辑记忆，因此入库与注入两端都做一次过滤。
     */
    fun sanitize(raw: String): String {
        var text = raw.trim().take(500)
        RISKY_TOKENS.forEach { token ->
            if (text.contains(token, ignoreCase = true)) {
                text = text.replace(token, "「已过滤」", ignoreCase = true)
            }
        }
        return text.ifBlank { "(空)" }
    }

    companion object {
        private val RISKY_TOKENS = listOf(
            "忽略之前", "忽略以上", "ignore previous", "ignore above",
            "system:", "assistant:", "<system", "</system",
            "你现在是", "从现在开始你", "请扮演", "覆盖系统", "开发者模式",
        )

        private val STOPWORDS = setOf(
            "的", "了", "吗", "呢", "吧", "啊", "呀", "哦", "嗯", "是", "在", "有", "和", "与", "就", "都", "也", "还",
            "我", "你", "他", "她", "它", "我们", "你们", "他们", "这个", "那个", "这", "那",
            "请", "帮", "帮忙", "给我", "帮我", "一下", "查", "查一", "查查", "看看", "看一", "告诉", "说",
            "什么", "怎么", "如何", "可以", "能不能", "能否", "需要", "想要", "现在", "今天", "然后", "就是",
        )
    }
}
