package com.phoneagent.ui.update

/**
 * GitHub Release 说明是 Markdown，但更新弹窗原来直接把它当纯文本拼，
 * 结果把 **、`、#、- 和 versionCode/SHA 全带上来了。
 *
 * 这里只做两件事：
 * 1. 给首页提取真正有用的「本版重点」；
 * 2. 给详情页去掉 Markdown 记号，同时保留标题/列表结构。
 */
object ReleaseNotes {

    private val highlightSections = listOf(
        "本版重点", "本次重点", "主要更新", "更新亮点", "Highlights",
    )

    private val noiseHints = listOf(
        "versioncode", "minsdk", "apk 约", "sha-256", "覆盖安装", "下载地址",
        "安装与升级", "安装步骤", "已知限制", "免责声明", "完整变更",
    )

    fun highlights(body: String, limit: Int = 4): List<String> {
        if (body.isBlank()) return emptyList()
        val rawLines = body.replace("\r\n", "\n").lines()

        val sectionIndex = rawLines.indexOfFirst { line ->
            val t = cleanInline(line)
            highlightSections.any { t == it || t.startsWith("$it：") }
        }

        val candidates = if (sectionIndex >= 0) {
            val section = mutableListOf<String>()
            var i = sectionIndex + 1
            while (i < rawLines.size) {
                val raw = rawLines[i]
                val trimmed = raw.trim()
                if (trimmed.startsWith("## ")) break
                section += raw
                i++
            }
            section
        } else {
            rawLines
        }

        // 优先用「### 1. xxx」这种小标题，它就是本版重点本身。
        val headings = candidates.mapNotNull { raw ->
            val t = raw.trim()
            if (t.startsWith("### ") || t.startsWith("#### ")) {
                cleanInline(t.removePrefix("#### ").removePrefix("### "))
                    .replace(Regex("^\\d+[.、]\\s*"), "")
            } else null
        }.filter { it.isNotBlank() && !isNoise(it) }

        val bullets = candidates.mapNotNull { raw ->
            val t = raw.trim()
            when {
                t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ") ->
                    cleanInline(t.substring(1).trim())
                else -> null
            }
        }.filter { it.isNotBlank() && !isNoise(it) }

        val picked = when {
            headings.isNotEmpty() -> headings
            bullets.isNotEmpty() -> bullets
            sectionIndex >= 0 -> candidates.map { cleanInline(it) }.filter { it.isNotBlank() && !isNoise(it) }
            else -> emptyList()
        }

        return picked
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(limit)
    }

    /** 详情页用的纯文本：去掉 Markdown 符号，但保留标题、列表和空行。 */
    fun cleanForDetail(body: String): String {
        if (body.isBlank()) return "该版本没有填写更新说明。"
        val out = StringBuilder()
        var inComment = false
        var inCode = false

        body.replace("\r\n", "\n").lines().forEach { raw ->
            val t = raw.trim()
            if (inComment) {
                if (t.contains("-->")) inComment = false
                return@forEach
            }
            if (t.startsWith("<!--")) {
                if (!t.contains("-->")) inComment = true
                return@forEach
            }
            if (t.startsWith("```")) {
                inCode = !inCode
                return@forEach
            }
            if (inCode) {
                out.append(raw).append('\n')
                return@forEach
            }
            if (t.isBlank()) {
                if (out.isNotEmpty() && !out.endsWith("\n\n")) out.append('\n')
                return@forEach
            }
            if (t == "---" || t == "***") return@forEach
            if (t.startsWith("![") || t.startsWith("|")) return@forEach

            val heading = when {
                t.startsWith("#### ") -> 4
                t.startsWith("### ") -> 3
                t.startsWith("## ") -> 2
                t.startsWith("# ") -> 1
                else -> 0
            }
            if (heading > 0) {
                val text = cleanInline(t.removePrefix("#".repeat(heading)).trim())
                if (text.isNotBlank()) {
                    if (out.isNotEmpty() && !out.endsWith("\n\n")) out.append('\n')
                    out.append(text).append('\n')
                }
                return@forEach
            }

            when {
                t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ") ->
                    out.append("• ").append(cleanInline(t.substring(1).trim())).append('\n')
                t.startsWith("> ") ->
                    out.append(cleanInline(t.removePrefix("> ").trim())).append('\n')
                else -> out.append(cleanInline(t)).append('\n')
            }
        }

        return out.toString().trim().replace(Regex("\n{3,}"), "\n\n")
    }

    fun cleanInline(raw: String): String {
        var s = raw.trim()
        s = s.replace(Regex("!\\[[^]]*]\\([^)]*\\)"), "")
        s = s.replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
        s = s.replace(Regex("<[^>]+>"), "")
        s = s.replace(Regex("\\*\\*|__|`"), "")
        s = s.replace("*", "")
        s = s.replace(Regex("^#{1,6}\\s*"), "")
        s = s.replace(Regex("^>\\s*"), "")
        s = s.replace(Regex("^[-*+]\\s*"), "")
        s = s.replace(Regex("^\\d+[.、]\\s*"), "")
        s = s.replace(Regex("\\s+"), " ")
        return s.trim()
    }

    private fun isNoise(text: String): Boolean {
        val t = text.lowercase()
        return noiseHints.any { t.contains(it) }
    }
}
