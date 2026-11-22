package com.phoneagent.ui.preview

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 旧版二进制 Office 解析的回归测试。
 * 样本由 LibreOffice 从 docx/xlsx/pptx 转成 MS Word 97 / PowerPoint 97 生成，
 * 放在 /tmp/legacy-office；样本不存在时跳过，不影响正常构建。
 */
class LegacyOfficeReaderTest {

    private val dir = File("/tmp/legacy-office")

    @Test
    fun `reads word 97 text`() {
        val file = File(dir, "source.doc")
        assumeTrue("缺少 Word 97 样本", file.exists())
        val doc = LegacyOfficeReader.readWord(file)
        val all = doc.paragraphs.joinToString("\n")
        assertTrue("应包含标题，实际：$all", all.contains("旧版 Word"))
        assertTrue("应包含第一段，实际：$all", all.contains("第一段"))
        assertTrue("应包含第二段，实际：$all", all.contains("第二段"))
    }

    @Test
    fun `reads powerpoint 97 slides`() {
        val file = File(dir, "source.ppt")
        assumeTrue("缺少 PowerPoint 97 样本", file.exists())
        val deck = LegacyOfficeReader.readPowerPoint(file)
        assertTrue("至少两页，实际 ${deck.slides.size}", deck.slides.size >= 2)
        val all = deck.slides.joinToString("\n") { it.lines.joinToString(" ") }
        assertTrue("应包含第一页标题，实际：$all", all.contains("旧版 PPT"))
        assertTrue("应包含第二页内容，实际：$all", all.contains("第二页"))
    }
}
