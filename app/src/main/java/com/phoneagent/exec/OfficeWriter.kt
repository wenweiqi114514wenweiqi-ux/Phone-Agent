package com.phoneagent.exec

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 原生 Office 生成：直接写出合法的 docx / xlsx（OOXML），不依赖设备上有 zip 命令，
 * 也不让模型手工拼 ZIP 字节。所有部件都由这里统一生成，结构不会出现"多套一层目录"的问题。
 */
object OfficeWriter {

    private const val MAX_DOC_CHARS = 400_000
    private const val MAX_SHEET_ROWS = 5_000
    private const val MAX_SHEET_COLS = 30
    private const val MAX_CELL_CHARS = 30_000

    // ===================== Word =====================

    fun writeDocx(dest: File, title: String, content: String): Pair<Boolean, String> {
        if (content.length > MAX_DOC_CHARS) {
            return false to "内容太长（${content.length} 字），请拆成多个文件写"
        }
        return try {
            dest.parentFile?.mkdirs()
            ZipOutputStream(dest.outputStream().buffered()).use { z ->
                put(z, "[Content_Types].xml", CONTENT_TYPES_DOCX)
                put(z, "_rels/.rels", RELS_ROOT_DOCX)
                put(z, "word/document.xml", buildDocumentXml(title, content))
                put(z, "word/styles.xml", STYLES_DOCX)
                put(z, "word/_rels/document.xml.rels", RELS_DOCX)
            }
            val kb = dest.length() / 1024
            true to "已生成 Word 文档 → ${dest.absolutePath}（${kb} KB）"
        } catch (e: Exception) {
            runCatching { dest.delete() }
            false to "生成 docx 失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun buildDocumentXml(title: String, content: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">")
        append("<w:body>")
        if (title.isNotBlank()) append(paragraph(title.trim(), style = "Heading1"))
        content.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { raw ->
            val line = raw.trimEnd()
            val t = line.trim()
            when {
                t.isEmpty() -> Unit
                t == "---" -> Unit
                t.startsWith("### ") -> append(paragraph(t.removePrefix("### "), style = "Heading3"))
                t.startsWith("## ") -> append(paragraph(t.removePrefix("## "), style = "Heading2"))
                t.startsWith("# ") -> append(paragraph(t.removePrefix("# "), style = "Heading1"))
                t.startsWith("- ") || t.startsWith("* ") || t.startsWith("• ") ->
                    append(paragraph("• " + t.substring(2), bullet = true))
                Regex("^\\d+[.、)]\\s*").containsMatchIn(t) -> append(paragraph(t, bullet = true))
                t.startsWith("> ") -> append(paragraph(t.removePrefix("> "), quote = true))
                else -> append(paragraph(t, style = null))
            }
        }
        // 空文档也要有一个段落，Word 不接受 body 里只有 sectPr
        if (title.isBlank() && content.isBlank()) append(paragraph(""))
        append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>")
        append("<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\"")
        append(" w:header=\"851\" w:footer=\"992\" w:gutter=\"0\"/></w:sectPr>")
        append("</w:body></w:document>")
    }

    private fun paragraph(
        text: String,
        style: String? = null,
        bullet: Boolean = false,
        quote: Boolean = false,
    ): String = buildString {
        append("<w:p>")
        if (style != null || bullet || quote) {
            append("<w:pPr>")
            if (quote) append("<w:pStyle w:val=\"Quote\"/>")
            else if (style != null) append("<w:pStyle w:val=\"").append(style).append("\"/>")
            if (bullet) append("<w:ind w:left=\"420\" w:hanging=\"240\"/>")
            if (quote) append("<w:ind w:left=\"420\"/>")
            append("</w:pPr>")
        }
        append(runs(text, quote))
        append("</w:p>")
    }

    /** 支持 **加粗**、`行内代码` 两种行内标记，其余原样。 */
    private fun runs(text: String, italic: Boolean = false): String = buildString {
        parseRuns(text, italic).forEach { run ->
            append("<w:r><w:rPr>")
            if (run.bold) append("<w:b/>")
            if (run.italic) append("<w:i/>")
            if (run.mono) append("<w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\"/>")
            append("</w:rPr><w:t xml:space=\"preserve\">")
            append(xml(run.text.replace('\t', ' ')))
            append("</w:t></w:r>")
        }
    }

    private data class Run(val text: String, val bold: Boolean, val mono: Boolean, val italic: Boolean)

    private fun parseRuns(text: String, italic: Boolean): List<Run> {
        val out = mutableListOf<Run>()
        text.split("**").forEachIndexed { i, part ->
            if (part.isEmpty()) return@forEachIndexed
            val bold = i % 2 == 1
            part.split('`').forEachIndexed { j, seg ->
                if (seg.isNotEmpty()) out += Run(seg, bold, j % 2 == 1, italic)
            }
        }
        return out
    }

    // ===================== Excel =====================

    fun writeXlsx(dest: File, sheetName: String, rowsText: String, header: Boolean): Pair<Boolean, String> {
        val rows = rowsText.replace("\r\n", "\n").replace('\r', '\n')
            .split('\n')
            .filter { it.isNotBlank() }
            .map { line -> line.split('|', '\t').map { it.trim() } }
            .take(MAX_SHEET_ROWS)
        if (rows.isEmpty()) return false to "rows 是空的，没有可写的数据"
        val cols = rows.maxOf { it.size }.coerceAtMost(MAX_SHEET_COLS)
        val name = sanitizeSheetName(sheetName)
        return try {
            dest.parentFile?.mkdirs()
            ZipOutputStream(dest.outputStream().buffered()).use { z ->
                put(z, "[Content_Types].xml", CONTENT_TYPES_XLSX)
                put(z, "_rels/.rels", RELS_ROOT_XLSX)
                put(z, "xl/workbook.xml", workbookXml(name))
                put(z, "xl/_rels/workbook.xml.rels", RELS_XLSX)
                put(z, "xl/styles.xml", STYLES_XLSX)
                put(z, "xl/worksheets/sheet1.xml", sheetXml(rows, cols, header))
            }
            val kb = dest.length() / 1024
            true to "已生成 Excel 表格 → ${dest.absolutePath}（${rows.size} 行 × $cols 列，${kb} KB）"
        } catch (e: Exception) {
            runCatching { dest.delete() }
            false to "生成 xlsx 失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun sanitizeSheetName(raw: String): String {
        val cleaned = raw.trim().replace(Regex("[\\\\/*?:\\[\\]]"), " ").trim()
        return (cleaned.ifBlank { "Sheet1" }).take(31)
    }

    private fun colName(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) {
            val m = (n - 1) % 26
            sb.insert(0, ('A' + m))
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    private fun sheetXml(rows: List<List<String>>, cols: Int, header: Boolean): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        append("<dimension ref=\"A1:").append(colName(cols - 1)).append(rows.size).append("\"/>")
        append("<cols>")
        for (c in 0 until cols) {
            val width = rows.map { it.getOrNull(c)?.length ?: 0 }.max().coerceIn(8, 50)
            append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1)
                .append("\" width=\"").append(width + 2).append("\" customWidth=\"1\"/>")
        }
        append("</cols><sheetData>")
        rows.forEachIndexed { r, cells ->
            append("<row r=\"").append(r + 1).append("\">")
            val isHeader = header && r == 0
            for (c in 0 until cols) {
                val v = cells.getOrNull(c).orEmpty().take(MAX_CELL_CHARS)
                val ref = colName(c) + (r + 1)
                if (v.isEmpty()) continue
                val style = if (isHeader) " s=\"1\"" else ""
                val num = NUMERIC.matches(v) && v.length <= 15
                if (num && !isHeader) {
                    append("<c r=\"").append(ref).append("\"").append(style).append("><v>")
                        .append(v).append("</v></c>")
                } else {
                    append("<c r=\"").append(ref).append("\" t=\"inlineStr\"").append(style)
                        .append("><is><t xml:space=\"preserve\">").append(xml(v)).append("</t></is></c>")
                }
            }
            append("</row>")
        }
        append("</sheetData>")
        append("<pageMargins left=\"0.7\" right=\"0.7\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/>")
        append("</worksheet>")
    }

    private val NUMERIC = Regex("-?\\d+(\\.\\d+)?")

    private fun workbookXml(sheetName: String): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"" +
            " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
            "<sheets><sheet name=\"${xml(sheetName)}\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"

    // ===================== 公共部件 =====================

    private fun put(z: ZipOutputStream, name: String, body: String) {
        z.putNextEntry(ZipEntry(name))
        z.write(body.toByteArray(Charsets.UTF_8))
        z.closeEntry()
    }

    private fun xml(s: String): String = buildString(s.length + 8) {
        s.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }

    private val CONTENT_TYPES_DOCX = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
</Types>"""

    private val RELS_ROOT_DOCX = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

    private val RELS_ROOT_XLSX = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

    private val RELS_DOCX = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

    private val STYLES_DOCX = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:docDefaults>
<w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="等线"/><w:sz w:val="21"/><w:szCs w:val="21"/></w:rPr></w:rPrDefault>
<w:pPrDefault><w:pPr><w:spacing w:after="160" w:line="300" w:lineRule="auto"/></w:pPr></w:pPrDefault>
</w:docDefaults>
<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style>
<w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/>
<w:pPr><w:keepNext/><w:spacing w:before="240" w:after="120"/><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:sz w:val="36"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/>
<w:pPr><w:keepNext/><w:spacing w:before="200" w:after="80"/><w:outlineLvl w:val="1"/></w:pPr><w:rPr><w:b/><w:sz w:val="30"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading3"><w:name w:val="heading 3"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/>
<w:pPr><w:keepNext/><w:spacing w:before="160" w:after="60"/><w:outlineLvl w:val="2"/></w:pPr><w:rPr><w:b/><w:sz w:val="26"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Quote"><w:name w:val="Quote"/><w:basedOn w:val="Normal"/>
<w:pPr><w:ind w:left="420"/></w:pPr><w:rPr><w:i/><w:color w:val="595959"/></w:rPr></w:style>
</w:styles>"""

    private val CONTENT_TYPES_XLSX = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>"""

    private val RELS_XLSX = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

    private val STYLES_XLSX = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>
<fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>
<fill><patternFill patternType="solid"><fgColor rgb="FFEFEFEF"/><bgColor indexed="64"/></patternFill></fill></fills>
<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="2">
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/>
</cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>"""
}
