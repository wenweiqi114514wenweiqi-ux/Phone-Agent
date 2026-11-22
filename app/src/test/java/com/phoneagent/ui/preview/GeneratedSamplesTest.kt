package com.phoneagent.ui.preview

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class GeneratedSamplesTest {
    private val dir = File("/home/user/Doubao/chats/38448535673037058/旧版Office与音视频测试")

    @Test
    fun `word sample reaches final marker`() {
        val file = File(dir, "01_旧版Word测试.doc")
        assumeTrue(file.exists())
        val doc = LegacyOfficeReader.readWord(file)
        val all = doc.paragraphs.joinToString("\n")
        assertTrue(all, all.contains("WORD-END-2026"))
        assertTrue(all, all.contains("第二页"))
        assertTrue(all, all.contains("→ √ × ± °C"))
    }

    @Test
    fun `ppt sample has four pages and final marker`() {
        val file = File(dir, "03_旧版PPT测试.ppt")
        assumeTrue(file.exists())
        val deck = LegacyOfficeReader.readPowerPoint(file)
        assertTrue("pages=${deck.slides.size}", deck.slides.size >= 4)
        val all = deck.slides.joinToString("\n") { it.lines.joinToString(" ") }
        assertTrue(all, all.contains("PPT-END-2026"))
        assertTrue(all, all.contains("响应时间"))
        assertTrue(all, all.contains("99.9%"))
    }
}
