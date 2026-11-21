package com.phoneagent.exec

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 原生 pptx 生成：一页一个「标题 + 要点」版式，不依赖占位符继承，
 * 所有形状都用绝对坐标，避免不同 Office 版本对版式解释不一致。
 * 结构已用 python-pptx 与 LibreOffice 双端验证。
 */
object PptxWriter {

    private const val MAX_SLIDES = 50
    private const val MAX_LINES_PER_SLIDE = 12
    private const val MAX_CHARS = 240

    private const val NS =
        "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
            "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\""

    fun write(dest: File, slidesText: String): Pair<Boolean, String> {
        val pages = parse(slidesText)
        if (pages.isEmpty()) return false to "slides 是空的：每页用一行 --- 分隔，第一行是标题，后面每行一条要点"
        if (pages.size > MAX_SLIDES) return false to "页数太多（${pages.size}），上限 $MAX_SLIDES 页，请拆成多个文件"
        return try {
            dest.parentFile?.mkdirs()
            ZipOutputStream(dest.outputStream().buffered()).use { z ->
                put(z, "[Content_Types].xml", contentTypes(pages.size))
                put(z, "_rels/.rels", RELS_ROOT)
                put(z, "ppt/presentation.xml", presentation(pages.size))
                put(z, "ppt/_rels/presentation.xml.rels", presentationRels(pages.size))
                put(z, "ppt/theme/theme1.xml", THEME)
                put(z, "ppt/slideMasters/slideMaster1.xml", MASTER)
                put(z, "ppt/slideMasters/_rels/slideMaster1.xml.rels", MASTER_RELS)
                put(z, "ppt/slideLayouts/slideLayout1.xml", LAYOUT)
                put(z, "ppt/slideLayouts/_rels/slideLayout1.xml.rels", LAYOUT_RELS)
                pages.forEachIndexed { i, page ->
                    put(z, "ppt/slides/slide${i + 1}.xml", slideXml(page.first, page.second))
                    put(z, "ppt/slides/_rels/slide${i + 1}.xml.rels", SLIDE_RELS)
                }
            }
            val kb = dest.length() / 1024
            true to "已生成 PPT → ${dest.absolutePath}（${pages.size} 页，${kb} KB）"
        } catch (e: Exception) {
            runCatching { dest.delete() }
            false to "生成 pptx 失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** 每页用一行 --- 分隔；页内第一行是标题，其余是要点（行首的 - / • / * 会被去掉）。 */
    private fun parse(text: String): List<Pair<String, List<String>>> {
        val pages = mutableListOf<Pair<String, List<String>>>()
        val current = mutableListOf<String>()
        fun flush() {
            val lines = current.map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isNotEmpty()) {
                val bullets = lines.drop(1).take(MAX_LINES_PER_SLIDE).map { line ->
                    line.removePrefix("- ").removePrefix("• ").removePrefix("* ").take(MAX_CHARS)
                }
                pages += lines.first().take(120) to bullets
            }
            current.clear()
        }
        text.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { line ->
            if (line.trim() == "---" || line.trim() == "***") flush() else current += line
        }
        flush()
        return pages
    }

    private fun contentTypes(n: Int): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        append("<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>")
        append("<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>")
        append("<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>")
        for (i in 1..n) {
            append("<Override PartName=\"/ppt/slides/slide").append(i)
                .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>")
        }
        append("<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>")
        append("</Types>")
    }

    private fun presentation(n: Int): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<p:presentation ").append(NS).append(">")
        append("<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst>")
        append("<p:sldIdLst>")
        for (i in 1..n) {
            append("<p:sldId id=\"").append(254 + i).append("\" r:id=\"rId").append(i + 1).append("\"/>")
        }
        append("</p:sldIdLst>")
        append("<p:sldSz cx=\"12192000\" cy=\"6858000\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/>")
        append("</p:presentation>")
    }

    private fun presentationRels(n: Int): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        append("<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>")
        for (i in 1..n) {
            append("<Relationship Id=\"rId").append(i + 1)
                .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide")
                .append(i).append(".xml\"/>")
        }
        append("</Relationships>")
    }

    private fun slideXml(title: String, bullets: List<String>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<p:sld ").append(NS).append("><p:cSld><p:spTree>")
        append("<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>")
        append("<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/>")
        append("<a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>")
        append(textBox(2, "Title", 838200, 365125, 10515600, 1325563, listOf(title), 3200, bold = true, bullets = false))
        append(
            textBox(
                3, "Body", 838200, 1825563, 10515600, 4114125,
                if (bullets.isEmpty()) listOf("") else bullets, 1800, bold = false, bullets = true,
            )
        )
        append("</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>")
    }

    private fun textBox(
        id: Int,
        name: String,
        x: Int,
        y: Int,
        cx: Int,
        cy: Int,
        paras: List<String>,
        size: Int,
        bold: Boolean,
        bullets: Boolean,
    ): String = buildString {
        append("<p:sp><p:nvSpPr><p:cNvPr id=\"").append(id).append("\" name=\"").append(name)
        append("\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>")
        append("<p:spPr><a:xfrm><a:off x=\"").append(x).append("\" y=\"").append(y)
        append("\"/><a:ext cx=\"").append(cx).append("\" cy=\"").append(cy).append("\"/></a:xfrm>")
        append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>")
        append("<p:txBody><a:bodyPr wrap=\"square\" rtlCol=\"0\"><a:normAutofit/></a:bodyPr><a:lstStyle/>")
        paras.forEach { text ->
            append("<a:p><a:pPr")
            if (bullets) append(" marL=\"228600\" indent=\"-228600\"")
            append(">")
            if (bullets) append("<a:buFont typeface=\"Arial\"/><a:buChar char=\"•\"/>")
            append("<a:defRPr sz=\"").append(size).append("\"").append(if (bold) " b=\"1\"" else "").append("/></a:pPr>")
            append("<a:r><a:rPr lang=\"zh-CN\" sz=\"").append(size).append("\"")
                .append(if (bold) " b=\"1\"" else "").append(" dirty=\"0\"/><a:t>")
            append(xml(text)).append("</a:t></a:r></a:p>")
        }
        append("</p:txBody></p:sp>")
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

    private fun put(z: ZipOutputStream, name: String, body: String) {
        z.putNextEntry(ZipEntry(name))
        z.write(body.toByteArray(Charsets.UTF_8))
        z.closeEntry()
    }

    private val RELS_ROOT = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>
</Relationships>"""

    private val MASTER = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sldMaster $NS>
<p:cSld><p:spTree>
<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>
</p:spTree></p:cSld>
<p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>
<p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst>
<p:txStyles><p:titleStyle><a:lvl1pPr><a:defRPr sz="3200"/></a:lvl1pPr></p:titleStyle>
<p:bodyStyle><a:lvl1pPr><a:defRPr sz="1800"/></a:lvl1pPr></p:bodyStyle>
<p:otherStyle><a:lvl1pPr><a:defRPr sz="1800"/></a:lvl1pPr></p:otherStyle></p:txStyles>
</p:sldMaster>"""

    private val LAYOUT = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sldLayout $NS type="blank" preserve="1">
<p:cSld name="Blank"><p:spTree>
<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>
</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>
</p:sldLayout>"""

    private val MASTER_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="../theme/theme1.xml"/>
</Relationships>"""

    private val LAYOUT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="../slideMasters/slideMaster1.xml"/>
</Relationships>"""

    private val SLIDE_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>
</Relationships>"""

    private val THEME = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Office">
<a:themeElements>
<a:clrScheme name="Office"><a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1><a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1><a:dk2><a:srgbClr val="44546A"/></a:dk2><a:lt2><a:srgbClr val="E7E6E6"/></a:lt2><a:accent1><a:srgbClr val="4472C4"/></a:accent1><a:accent2><a:srgbClr val="ED7D31"/></a:accent2><a:accent3><a:srgbClr val="A5A5A5"/></a:accent3><a:accent4><a:srgbClr val="FFC000"/></a:accent4><a:accent5><a:srgbClr val="5B9BD5"/></a:accent5><a:accent6><a:srgbClr val="70AD47"/></a:accent6><a:hlink><a:srgbClr val="0563C1"/></a:hlink><a:folHlink><a:srgbClr val="954F72"/></a:folHlink></a:clrScheme>
<a:fontScheme name="Office"><a:majorFont><a:latin typeface="Calibri Light"/><a:ea typeface=""/><a:cs typeface=""/></a:majorFont><a:minorFont><a:latin typeface="Calibri"/><a:ea typeface=""/><a:cs typeface=""/></a:minorFont></a:fontScheme>
<a:fmtScheme name="Office">
<a:fillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:fillStyleLst>
<a:lnStyleLst><a:ln w="6350"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln><a:ln w="12700"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln><a:ln w="19050"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln></a:lnStyleLst>
<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>
<a:bgFillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:bgFillStyleLst>
</a:fmtScheme>
</a:themeElements><a:objectDefaults/><a:extraClrSchemeLst/></a:theme>"""
}
