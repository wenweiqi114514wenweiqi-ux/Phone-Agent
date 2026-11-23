package com.phoneagent.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationWorkspaceNamingTest {

    @Test
    fun `key is stable and long enough`() {
        val a = ConversationWorkspaceNaming.keyFor("conv_abcdef12")
        val b = ConversationWorkspaceNaming.keyFor("conv_abcdef12")
        val c = ConversationWorkspaceNaming.keyFor("conv_12345678")
        assertEquals(a, b)
        assertNotEquals(a, c)
        assertEquals(32, a.length)
        assertTrue(a.matches(Regex("[0-9a-f]{32}")))
    }

    @Test
    fun `safe name strips path and illegal characters`() {
        assertEquals("报告.docx", ConversationWorkspaceNaming.safeName("../目录/报告.docx"))
        assertTrue(ConversationWorkspaceNaming.safeName("a:b*c?.txt").endsWith(".txt"))
    }

    @Test
    fun `unique file does not overwrite existing`() {
        val dir = Files.createTempDirectory("phoneagent-ws").toFile()
        try {
            val first = ConversationWorkspaceNaming.uniqueFile(dir, "测试.txt")
            first.writeText("one")
            val second = ConversationWorkspaceNaming.uniqueFile(dir, "测试.txt")
            second.writeText("two")
            assertEquals("测试.txt", first.name)
            assertEquals("测试 (1).txt", second.name)
            assertEquals("one", first.readText())
            assertEquals("two", second.readText())
        } finally {
            dir.deleteRecursively()
        }
    }
}
