package com.phoneagent.ui.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTest {

    private val body = """
        # v1.0.15 · 正式版
        > versionCode 83 · minSdk 26 · APK 约 80 MB · SHA-256 `abc`
        > 覆盖安装保留会话

        ## 本版重点

        ### 1. 测试组件：内置离线 `Python 3.14`
        - 设置新增「Python 组件」
        - 不联网下载

        ### 2. 表格预览统一
        - CSV/XLS/XLSX/ODS 走同一个表格页

        ### 3. **DOCX** 手机端自适应
        - 窄屏不再按 A4 固定宽度

        ### 4. 会话页重做
        - 去掉嵌套 Scaffold

        ## 1.0.15 完整变更
        - 新增 `doc_write`
    """.trimIndent()

    @Test
    fun highlightsUseSectionHeadingsAndStripMarkdown() {
        val highlights = ReleaseNotes.highlights(body)
        assertEquals(4, highlights.size)
        assertEquals("测试组件：内置离线 Python 3.14", highlights[0])
        assertEquals("表格预览统一", highlights[1])
        assertEquals("DOCX 手机端自适应", highlights[2])
        assertEquals("会话页重做", highlights[3])
        assertFalse(highlights.any { it.contains("**") || it.contains("`") || it.contains("###") })
    }

    @Test
    fun highlightFallbackSkipsNoiseLines() {
        val highlights = ReleaseNotes.highlights(
            """
            # v1.0.16
            > versionCode 88 · SHA-256 `deadbeef`
            - 修复工具失败写日志
            - 新增完成提示
            - 优化跟随滚动
            """.trimIndent(),
        )
        assertEquals(3, highlights.size)
        assertFalse(highlights.any { it.contains("versionCode") || it.contains("SHA") })
    }

    @Test
    fun detailKeepsStructureButRemovesSymbols() {
        val cleaned = ReleaseNotes.cleanForDetail(body)
        assertFalse(cleaned.contains("**"))
        assertFalse(cleaned.contains("###"))
        assertFalse(cleaned.contains("`"))
        assertTrue(cleaned.contains("本版重点"))
        assertTrue(cleaned.contains("• 设置新增「Python 组件」"))
    }
}
