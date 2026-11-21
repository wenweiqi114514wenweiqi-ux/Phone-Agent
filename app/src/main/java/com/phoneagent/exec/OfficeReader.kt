package com.phoneagent.exec

import java.io.File
import java.util.zip.ZipFile

/**
 * 原生读表：把 xlsx 读成文本表格，模型不用再解压去看 sheet1.xml。
 * 支持 inlineStr、共享字符串（Excel/WPS 生成的文件基本都是这种）、数字、布尔。
 */
object OfficeReader {

    private const val MAX_ROWS = 200
    private const val MAX_COLS = 26

    fun readSheet(file: File, wanted: String): Pair<Boolean, String> {
        if (!file.exists()) return false to "文件不存在：${file.absolutePath}"
        return try {
            ZipFile(file).use { zip ->
                val workbook = entryText(zip, "xl/workbook.xml") ?: return false to "不是有效的 xlsx：缺少 xl/workbook.xml"
                val rels = entryText(zip, "xl/_rels/workbook.xml.rels").orEmpty()
                val targets = mutableMapOf<String, String>()
                REL_TAG.findAll(rels).forEach { m ->
                    val tag = m.value
                    val id = REL_ID.find(tag)?.groupValues?.get(1) ?: return@forEach
                    val target = REL_TARGET.find(tag)?.groupValues?.get(1) ?: return@forEach
                    targets[id] = target
                }
                val sheets = mutableListOf<Pair<String, String>>()
                SHEET_TAG.findAll(workbook).forEach { m ->
                    val tag = m.value
                    val name = ATTR_NAME.find(tag)?.groupValues?.get(1) ?: "Sheet1"
                    val rid = ATTR_RID.find(tag)?.groupValues?.get(1) ?: ""
                    val target = targets[rid] ?: "worksheets/sheet1.xml"
                    val path = when {
                        target.startsWith("/") -> target.trimStart('/')
                        target.startsWith("xl/") -> target
                        else -> "xl/" + target
                    }
                    sheets += name to path
                }
                if (sheets.isEmpty()) return false to "这个 xlsx 里没有工作表"
                val pick = if (wanted.isBlank()) sheets.first()
                else sheets.firstOrNull { it.first == wanted.trim() }
                    ?: return false to "没有名为「${wanted.trim()}」的工作表；现有：" + sheets.joinToString("、") { it.first }

                val shared = mutableListOf<String>()
                entryText(zip, "xl/sharedStrings.xml")?.let { sst ->
                    SI.findAll(sst).forEach { si ->
                        shared += T_TAG.findAll(si.value).joinToString("") { unescape(it.groupValues[1]) }
                    }
                }

                val sheetXml = entryText(zip, pick.second)
                    ?: return false to "工作表「${pick.first}」的部件缺失：${pick.second}"
                val table = parseSheet(sheetXml, shared)
                if (table.isEmpty()) return false to "工作表「${pick.first}」是空的"
                val colsUsed = table.maxOf { it.size }
                val text = buildString {
                    append("工作表「").append(pick.first).append("」：")
                    append(table.size).append(" 行 × ").append(colsUsed).append(" 列")
                    if (sheets.size > 1) {
                        append("（文件里共 ").append(sheets.size).append(" 个工作表：")
                            .append(sheets.joinToString("、") { it.first }).append("）")
                    }
                    append("\n\n")
                    table.forEach { row -> append(row.joinToString("\t")).append("\n") }
                    if (table.size >= MAX_ROWS) append("\n（只显示前 ").append(MAX_ROWS).append(" 行）")
                }
                true to text
            }
        } catch (e: Exception) {
            false to "读取 xlsx 失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** 解析 worksheet：返回按行列排好的二维文本。 */
    private fun parseSheet(xml: String, shared: List<String>): List<List<String>> {
        val result = mutableListOf<List<String>>()
        ROW.findAll(xml).forEach { rowMatch ->
            if (result.size >= MAX_ROWS) return@forEach
            val cells = mutableMapOf<Int, String>()
            var maxCol = -1
            CELL.findAll(rowMatch.value).forEach { m ->
                val attrs = m.groupValues[1]
                val body = m.groupValues[2]
                val ref = ATTR_REF.find(attrs)?.groupValues?.get(1) ?: return@forEach
                val col = colIndex(ref.takeWhile { it.isLetter() })
                if (col < 0 || col >= MAX_COLS) return@forEach
                val type = ATTR_TYPE.find(attrs)?.groupValues?.get(1) ?: ""
                val raw = when {
                    type == "inlineStr" -> T_TAG.find(body)?.groupValues?.get(1) ?: ""
                    type == "s" -> V_TAG.find(body)?.groupValues?.get(1)
                        ?.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                    type == "b" -> if (V_TAG.find(body)?.groupValues?.get(1) == "1") "TRUE" else "FALSE"
                    else -> V_TAG.find(body)?.groupValues?.get(1) ?: ""
                }
                cells[col] = unescape(raw)
                if (col > maxCol) maxCol = col
            }
            if (maxCol >= 0) {
                result += (0..maxCol).map { cells[it] ?: "" }
            }
        }
        return result
    }

    private fun colIndex(letters: String): Int {
        if (letters.isEmpty()) return -1
        var n = 0
        letters.uppercase().forEach { ch ->
            if (ch < 'A' || ch > 'Z') return -1
            n = n * 26 + (ch - 'A' + 1)
        }
        return n - 1
    }

    private fun entryText(zip: ZipFile, name: String): String? {
        val entry = zip.getEntry(name) ?: return null
        return zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun unescape(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { m ->
            m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value
        }

    private val REL_TAG = Regex("<Relationship\\b[^>]*>")
    private val REL_ID = Regex("Id=\"([^\"]*)\"")
    private val REL_TARGET = Regex("Target=\"([^\"]*)\"")
    private val SHEET_TAG = Regex("<sheet\\b[^>]*>")
    private val ATTR_NAME = Regex("name=\"([^\"]*)\"")
    private val ATTR_RID = Regex("r:id=\"([^\"]*)\"")
    private val ATTR_REF = Regex("r=\"([A-Z]+\\d+)\"")
    private val ATTR_TYPE = Regex("t=\"([^\"]*)\"")
    private val SI = Regex("<si\\b[^>]*>(.*?)</si>", RegexOption.DOT_MATCHES_ALL)
    private val T_TAG = Regex("<t\\b[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
    private val V_TAG = Regex("<v\\b[^>]*>(.*?)</v>", RegexOption.DOT_MATCHES_ALL)
    private val ROW = Regex("<row\\b[^>]*(?:/>|>.*?</row>)", RegexOption.DOT_MATCHES_ALL)
    private val CELL = Regex("<c\\b([^>]*?)(?:/>|>(.*?)</c>)", RegexOption.DOT_MATCHES_ALL)
}
