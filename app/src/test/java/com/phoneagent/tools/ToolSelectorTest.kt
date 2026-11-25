package com.phoneagent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSelectorTest {

    private fun tool(name: String, builtin: Boolean = true) =
        ToolDefinition(name = name, description = "", builtin = builtin)

    private val all = listOf(
        tool("file_list"), tool("file_write"), tool("submit_file"), tool("shell_exec"),
        tool("http_get"), tool("web_fetch"), tool("doc_write"), tool("ocr_image"),
        tool("pm_install"), tool("pm_uninstall"), tool("am_start"),
        tool("screencap"), tool("input_tap"), tool("input_text"),
        tool("settings_get"), tool("set_system_setting"),
        tool("sys_free"), tool("sys_df"), tool("sys_getprop"), tool("sys_ps"), tool("logcat"), tool("reboot"),
        tool("ping"), tool("dns_lookup"),
        tool("my_custom_tool", builtin = false),
    )

    @Test
    fun plainQuestionKeepsOnlyCoreAndCustomTools() {
        val selected = ToolSelector.select(all, "帮我写一段自我介绍")
        val names = selected.map { it.name }.toSet()
        assertTrue("file_list" in names)
        assertTrue("doc_write" in names)
        assertTrue("my_custom_tool" in names)
        assertFalse("pm_install" in names)
        assertFalse("input_tap" in names)
        assertFalse("sys_free" in names)
        assertFalse("ping" in names)
    }

    @Test
    fun appKeywordsPullInAppTools() {
        val names = ToolSelector.select(all, "帮我安装这个 apk，再启动应用").map { it.name }.toSet()
        assertTrue("pm_install" in names)
        assertTrue("am_start" in names)
        assertFalse("settings_get" in names)
    }

    @Test
    fun screenAndSystemKeywordsPullInTheirGroups() {
        val names = ToolSelector.select(all, "截图后点击屏幕，再查看内存占用").map { it.name }.toSet()
        assertTrue("screencap" in names)
        assertTrue("input_tap" in names)
        assertTrue("sys_free" in names)
    }

    @Test
    fun explicitToolNameIsKeptEvenWithoutGroupKeyword() {
        val names = ToolSelector.select(all, "直接用 pm_uninstall 处理").map { it.name }.toSet()
        assertTrue("pm_uninstall" in names)
    }

    @Test
    fun customToolAlwaysKept() {
        val selected = ToolSelector.select(all, "普通聊天")
        assertEquals(1, selected.count { !it.builtin })
    }

    @Test
    fun colloquialKeywordsPullInExpectedGroups() {
        val screen = ToolSelector.select(all, "帮我截屏，然后点一下那个按钮").map { it.name }.toSet()
        assertTrue("screencap" in screen)
        assertTrue("input_tap" in screen)

        val app = ToolSelector.select(all, "把这个包装一下").map { it.name }.toSet()
        assertTrue("pm_install" in app)

        val system = ToolSelector.select(all, "看看存储空间和系统版本").map { it.name }.toSet()
        assertTrue("sys_df" in system)
        assertTrue("sys_getprop" in system)

        val process = ToolSelector.select(all, "看看后台运行了什么").map { it.name }.toSet()
        assertTrue("sys_ps" in process)
    }
}
