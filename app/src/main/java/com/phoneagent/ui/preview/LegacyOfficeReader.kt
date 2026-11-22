package com.phoneagent.ui.preview

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset

/**
 * 旧版二进制 Office（.doc / .ppt）的只读内容提取。
 *
 * 这两类格式不是 zip，而是 OLE 复合文档：
 *   .doc → WordDocument + 0Table/1Table 里的分片表（piece table）
 *   .ppt → PowerPoint Document 里的记录树（Slide / TextCharsAtom）
 *
 * 这里只做「内容可读」：保留段落、分页和文字，不重建字体、图片、表格线框。
 * 需要原样版式时仍然走系统里能打开 Office 的应用。
 */
object LegacyOfficeReader {

    data class WordDoc(
        val paragraphs: List<String>,
        val truncated: Boolean = false,
    )

    data class PptSlide(
        val lines: List<String>,
    )

    data class PptDeck(
        val slides: List<PptSlide>,
        val truncated: Boolean = false,
    )

    private const val MAX_FILE_BYTES = 96 * 1024 * 1024
    private const val MAX_TEXT_CHARS = 120_000

    fun readWord(file: File): WordDoc {
        val bytes = readFile(file)
        val ole = OleCompoundFile(bytes)
        val word = ole.stream("WordDocument")
            ?: throw IllegalArgumentException("不是有效的 Word 文档：缺少 WordDocument 流")
        val flags = u16(word, 0x0A)
        val tableName = if (flags and 0x0200 != 0) "1Table" else "0Table"
        val table = ole.stream(tableName)
            ?: throw IllegalArgumentException("缺少 $tableName 流，无法读取文字")

        val ccpText = u32(word, 0x4C).coerceAtLeast(0L)
        val fcClx = u32(word, 0x01A2).toInt()
        val lcbClx = u32(word, 0x01A6).toInt()
        if (lcbClx <= 0 || fcClx < 0 || fcClx + lcbClx > table.size) {
            throw IllegalArgumentException("文档分片表损坏，无法读取正文")
        }

        val raw = StringBuilder()
        var truncated = false
        var pos = fcClx
        val end = fcClx + lcbClx
        while (pos < end && raw.length < MAX_TEXT_CHARS) {
            when (table[pos].toInt()) {
                1 -> {
                    val cb = u16(table, pos + 1)
                    pos += 3 + cb
                }
                2 -> {
                    val lcb = u32(table, pos + 1).toInt()
                    val plc = pos + 5
                    if (lcb < 4 || plc + lcb > table.size) break
                    val n = (lcb - 4) / 12
                    if (n <= 0 || n > 200_000) break
                    for (i in 0 until n) {
                        if (raw.length >= MAX_TEXT_CHARS) {
                            truncated = true
                            break
                        }
                        val cpStart = u32(table, plc + i * 4).toInt()
                        val cpEnd = u32(table, plc + (i + 1) * 4).toInt()
                        val chars = (minOf(cpEnd.toLong(), ccpText) - cpStart.toLong()).toInt()
                        if (chars <= 0) continue
                        val pcd = plc + (n + 1) * 4 + i * 8
                        if (pcd + 8 > table.size) break
                        val fcRaw = u32(table, pcd + 2)
                        val compressed = (fcRaw and 0x40000000L) != 0L
                        var fc = if (compressed) (fcRaw and 0x3FFFFFFFL) / 2 else fcRaw
                        if (fc < 0L || fc >= word.size.toLong()) continue
                        val take = minOf(chars, MAX_TEXT_CHARS - raw.length)
                        if (compressed) {
                            val len = minOf(take.toLong(), word.size.toLong() - fc).toInt()
                            if (len > 0) {
                                raw.append(
                                    String(word, fc.toInt(), len, Charset.forName("windows-1252"))
                                )
                            }
                        } else {
                            val len = minOf(take.toLong() * 2L, word.size.toLong() - fc).toInt()
                            if (len >= 2) {
                                raw.append(String(word, fc.toInt(), len - (len % 2), Charsets.UTF_16LE))
                            }
                        }
                    }
                    break
                }
                else -> break
            }
        }

        if (raw.isEmpty()) throw IllegalArgumentException("没有提取到正文，文档可能为空或已加密")
        val clean = cleanWordText(raw.toString())
        val paragraphs = clean.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (paragraphs.isEmpty()) throw IllegalArgumentException("没有提取到正文")
        return WordDoc(paragraphs, truncated)
    }

    fun readPowerPoint(file: File): PptDeck {
        val bytes = readFile(file)
        val ole = OleCompoundFile(bytes)
        val stream = ole.stream("PowerPoint Document")
            ?: throw IllegalArgumentException("不是有效的 PowerPoint 文档：缺少 PowerPoint Document 流")

        val slides = mutableListOf<PptSlide>()
        var currentLines: MutableList<String>? = null
        var truncated = false
        var guard = 0
        val fallbackLines = mutableListOf<String>()

        fun readAtom(payloadStart: Int, len: Int, utf16: Boolean): String {
            if (payloadStart < 0 || len <= 0 || payloadStart + len > stream.size) return ""
            return if (utf16) String(stream, payloadStart, len - (len % 2), Charsets.UTF_16LE)
            else String(stream, payloadStart, len, Charset.forName("windows-1252"))
        }

        fun collectText(text: String, lines: MutableList<String>) {
            text.split('\r', '\u000B')
                .asSequence()
                .map { it.replace("\u0000", "").trim() }
                .filter { it.isNotEmpty() }
                .forEach { line ->
                    if (lines.size < 80) lines.add(line.take(2000))
                    else truncated = true
                }
        }

        fun walk(start: Int, endLimit: Int, insideSlide: Boolean, depth: Int) {
            if (depth > 24) return
            var p = start
            while (p + 8 <= endLimit && guard < 300_000) {
                guard++
                val verInst = u16(stream, p)
                val type = u16(stream, p + 2)
                val len = u32(stream, p + 4)
                val ver = verInst and 0x000F
                if (len <= 0 || len > (endLimit - p - 8).toLong()) break
                val atomLen = len.toInt()

                var nowInside = insideSlide
                if (type == 1006 && ver == 0x000F) {
                    if (slides.size < 200) {
                        currentLines = mutableListOf()
                        slides.add(PptSlide(emptyList()))
                        nowInside = true
                    } else {
                        truncated = true
                        nowInside = false
                    }
                }

                if (nowInside && (type == 4000 || type == 4008)) {
                    val txt = readAtom(p + 8, atomLen, utf16 = type == 4000)
                    val lines = currentLines
                    if (lines != null) {
                        collectText(txt, lines)
                        if (slides.isNotEmpty()) {
                            slides[slides.lastIndex] = PptSlide(lines.toList())
                        }
                    } else {
                        collectText(txt, fallbackLines)
                    }
                }

                if (ver == 0x000F) {
                    walk(p + 8, p + 8 + atomLen, nowInside, depth + 1)
                }
                p += 8 + atomLen
            }
        }

        walk(0, stream.size, false, 0)

        // 有些旧文件不使用 Slide 容器，只能把文字按出现顺序收成一页。
        if (slides.isEmpty() && fallbackLines.isNotEmpty()) {
            slides.add(PptSlide(fallbackLines.toList()))
        }
        if (slides.isEmpty()) throw IllegalArgumentException("没有提取到幻灯片文字")
        return PptDeck(slides, truncated)
    }

    private fun readFile(file: File): ByteArray {
        require(file.exists()) { "文件不存在" }
        if (file.length() > MAX_FILE_BYTES) {
            throw IllegalArgumentException("文件超过 96 MB，旧版格式预览会占用过多内存")
        }
        return file.readBytes()
    }

    private fun cleanWordText(input: String): String {
        val out = StringBuilder(input.length)
        var inField = false
        var inFieldResult = false
        for (ch in input) {
            val c = ch.code
            when (c) {
                0x13 -> {
                    inField = true
                    inFieldResult = false
                }
                0x14 -> inFieldResult = true
                0x15 -> {
                    inField = false
                    inFieldResult = false
                }
            }
            if (inField && !inFieldResult) continue
            when (c) {
                0x0D, 0x0B, 0x0C, 0x07, 0x0A -> out.append('\n')
                0x09 -> out.append('\t')
                0x1E -> out.append('-')
                0x1F, 0x08 -> Unit
                0xA0 -> out.append(' ')
                else -> if (c >= 0x20) out.append(ch)
            }
        }
        return out.toString()
            .replace("\r", "\n")
            .replace(Regex("\n[ \t]*\n+"), "\n")
            .trim()
    }
}

/* ======================= OLE 复合文档读取 ======================= */

private class OleCompoundFile(private val data: ByteArray) {

    private class Entry(
        val name: String,
        val type: Int,
        val start: Int,
        val size: Long,
    )

    private val sectorSize: Int
    private val miniSectorSize: Int
    private val miniCutoff: Long
    private val fat: IntArray
    private val miniFat: IntArray
    private val entries: List<Entry>
    private val miniStream: ByteArray

    init {
        if (data.size < 512 || u32(data, 0) != 0xE011CFD0L || u32(data, 4) != 0xE11AB1A1L) {
            throw IllegalArgumentException("不是 OLE 复合文档")
        }
        val sectorShift = u16(data, 0x1E)
        val miniShift = u16(data, 0x20)
        require(sectorShift in 7..16 && miniShift in 4..12) { "OLE 扇区参数异常" }
        sectorSize = 1 shl sectorShift
        miniSectorSize = 1 shl miniShift
        miniCutoff = u32(data, 0x38).toLong()
        val fatCount = u32(data, 0x2C).toInt()
        val dirStart = u32(data, 0x30).toInt()
        val miniFatStart = u32(data, 0x3C).toInt()
        val miniFatCount = u32(data, 0x40).toInt()
        val difatStart = u32(data, 0x44).toInt()
        val difatCount = u32(data, 0x48).toInt()

        val fatSectors = ArrayList<Int>()
        for (i in 0 until 109) {
            val s = u32(data, 0x4C + i * 4)
            if (isSector(s)) fatSectors.add(s.toInt())
        }
        var difat = difatStart
        var guard = 0
        while (isSector(difat.toLong()) && guard < difatCount + 16) {
            guard++
            val buf = sector(difat)
            val perSector = sectorSize / 4
            for (i in 0 until perSector - 1) {
                val s = u32(buf, 0, i * 4)
                if (isSector(s)) fatSectors.add(s.toInt())
            }
            difat = u32(buf, 0, (perSector - 1) * 4).toInt()
        }

        val fatEntries = ArrayList<Int>(fatSectors.size * (sectorSize / 4))
        for (i in 0 until minOf(fatSectors.size, maxOf(fatCount, fatSectors.size))) {
            val buf = sector(fatSectors[i])
            for (j in 0 until sectorSize / 4) {
                fatEntries.add(u32(buf, 0, j * 4).toInt())
            }
        }
        fat = fatEntries.toIntArray()

        entries = readDirectory(dirStart)
        val root = entries.firstOrNull { it.type == 5 }
        miniStream = if (root != null && isSector(root.start.toLong()) && root.size > 0) {
            readRegular(root.start, root.size)
        } else {
            ByteArray(0)
        }
        miniFat = if (isSector(miniFatStart.toLong()) && miniFatCount > 0) {
            val bytes = readRegular(miniFatStart, miniFatCount.toLong() * sectorSize)
            IntArray(bytes.size / 4) { i -> u32(bytes, 0, i * 4).toInt() }
        } else {
            IntArray(0)
        }
    }

    fun stream(name: String): ByteArray? {
        val e = entries.firstOrNull { it.type == 2 && it.name == name } ?: return null
        if (e.size <= 0) return ByteArray(0)
        return if (e.size < miniCutoff) readMini(e.start, e.size) else readRegular(e.start, e.size)
    }

    private fun readDirectory(start: Int): List<Entry> {
        val result = ArrayList<Entry>()
        var sec = start
        var guard = 0
        while (isSector(sec.toLong()) && guard < 10_000) {
            guard++
            val buf = sector(sec)
            var off = 0
            while (off + 128 <= buf.size) {
                val type = buf[off + 0x42].toInt() and 0xFF
                if (type != 0) {
                    val name = readName(buf, off)
                    val streamStart = u32(buf, off + 0x74).toInt()
                    val size = u32(buf, off + 0x78).toLong() and 0xFFFFFFFFL
                    if (name.isNotEmpty()) result.add(Entry(name, type, streamStart, size))
                }
                off += 128
            }
            sec = nextFat(sec)
        }
        return result
    }

    private fun readName(buf: ByteArray, off: Int): String {
        val b = StringBuilder()
        for (i in 0 until 32) {
            val lo = buf[off + i * 2].toInt() and 0xFF
            val hi = buf[off + i * 2 + 1].toInt() and 0xFF
            val c = lo or (hi shl 8)
            if (c == 0) break
            if (c >= 0x20) b.append(c.toChar())
        }
        return b.toString()
    }

    private fun readRegular(start: Int, size: Long): ByteArray {
        if (size <= 0) return ByteArray(0)
        val out = ByteArrayOutputStream(minOf(size, 32L * 1024 * 1024).toInt())
        var sec = start
        var guard = 0
        while (isSector(sec.toLong()) && out.size().toLong() < size && guard < 100_000) {
            guard++
            val buf = sector(sec)
            val need = (size - out.size()).toInt()
            out.write(buf, 0, minOf(buf.size, need))
            sec = nextFat(sec)
        }
        return out.toByteArray()
    }

    private fun readMini(start: Int, size: Long): ByteArray {
        val out = ByteArrayOutputStream(minOf(size, 16L * 1024 * 1024).toInt())
        var sec = start
        var guard = 0
        while (sec in miniFat.indices && out.size().toLong() < size && guard < 100_000) {
            guard++
            val off = sec * miniSectorSize
            if (off < 0 || off >= miniStream.size) break
            val need = minOf(miniSectorSize, miniStream.size - off, (size - out.size()).toInt())
            if (need <= 0) break
            out.write(miniStream, off, need)
            sec = miniFat[sec]
        }
        return out.toByteArray()
    }

    private fun nextFat(sec: Int): Int {
        if (sec < 0 || sec >= fat.size) return -1
        return fat[sec]
    }

    private fun sector(id: Int): ByteArray {
        val off = (id + 1) * sectorSize
        require(id >= 0 && off >= 0 && off + sectorSize <= data.size) { "OLE 扇区越界" }
        return data.copyOfRange(off, off + sectorSize)
    }

    private fun isSector(v: Long): Boolean = v in 0..0xFFFFFFFAL
}

/* ======================= 小端读取 ======================= */

private fun u16(b: ByteArray, off: Int): Int {
    if (off < 0 || off + 2 > b.size) return 0
    return (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
}

private fun u32(b: ByteArray, off: Int): Long = u32(b, 0, off)

private fun u32(b: ByteArray, off: Int, relative: Int): Long {
    val p = if (relative == 0) off else off + relative
    if (p < 0 || p + 4 > b.size) return 0
    return (b[p].toLong() and 0xFF) or
        ((b[p + 1].toLong() and 0xFF) shl 8) or
        ((b[p + 2].toLong() and 0xFF) shl 16) or
        ((b[p + 3].toLong() and 0xFF) shl 24)
}
