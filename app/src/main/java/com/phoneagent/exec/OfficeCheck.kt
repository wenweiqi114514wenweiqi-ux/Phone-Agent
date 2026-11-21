package com.phoneagent.exec

import java.io.File
import java.util.zip.ZipFile

/**
 * 交付物体检：在「提交给用户」和「自动补提交卡」两个入口统一把关。
 * 目标是别再让 0 字节文件、构建目录里的 XML 中间件、结构坏掉的 Office 文件变成一张卡片。
 */
object OfficeCheck {

    /** 构建/临时目录的特征片段（全小写比较）。 */
    private val TEMP_MARKERS = listOf(
        "/tmp/", "/cache/", "/.cache/", "/build_docx/", "/build_xlsx/", "/build_pptx/",
        "/_docx_build/", "/_xlsx_build/", "/_pptx_build/", "/build/", "/.trash/",
    )

    /** 返回 null = 可以交付；否则返回一句给模型看的原因。 */
    fun issue(file: File): String? {
        if (!file.exists()) return "文件不存在：${file.absolutePath}"
        if (file.isDirectory) return "这是目录，不能当交付物：${file.absolutePath}"
        if (file.length() == 0L) return "空文件（0 字节），没有任何内容：${file.name}"

        val path = file.absolutePath.replace('\\', '/').lowercase()
        val name = file.name
        if (name.startsWith(".") || name.startsWith("~$")) {
            return "这是临时/锁文件：$name，不是成果"
        }
        TEMP_MARKERS.firstOrNull { path.contains(it) }?.let {
            return "这是构建中间产物（目录 $it），不是最终文件；请把成果另存到工作区里的正式文件名再提交"
        }

        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext == "docx" || ext == "xlsx" || ext == "pptx") {
            val required = when (ext) {
                "docx" -> listOf("[Content_Types].xml", "word/document.xml")
                "xlsx" -> listOf("[Content_Types].xml", "xl/workbook.xml")
                else -> listOf("[Content_Types].xml", "ppt/presentation.xml")
            }
            val missing = try {
                ZipFile(file).use { z -> required.filter { z.getEntry(it) == null } }
            } catch (e: Exception) {
                return "不是有效的 $ext（打不开压缩结构）：${e.message ?: e.javaClass.simpleName}"
            }
            if (missing.isNotEmpty()) {
                return "结构不合格：" + missing.joinToString("、") + " 不在压缩包根目录，打开会提示文件损坏。" +
                    "写 Word 用 doc_write、写表格用 sheet_write；确实要自己打包时，file_zip 的 src 传构建目录本身，不要传父目录"
            }
        }
        return null
    }
}
