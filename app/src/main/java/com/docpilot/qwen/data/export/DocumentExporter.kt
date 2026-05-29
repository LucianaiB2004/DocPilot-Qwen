package com.docpilot.qwen.data.export

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.docpilot.qwen.data.local.DocumentEntity
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ExportFormat(val extension: String, val mimeType: String) {
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    PDF("pdf", "application/pdf"),
    CSV("csv", "text/csv"),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
}

class DocumentExporter(private val context: Context) {
    fun export(document: DocumentEntity, format: ExportFormat, templateContent: String? = null): File {
        val title = document.name.substringBeforeLast('.').ifBlank { "docpilot-export" }
        val text = redactSensitive(templateContent?.takeIf { it.isNotBlank() } ?: document.markdown.ifBlank { document.parseJson })
        val dir = File(context.getExternalFilesDir(null), "exports").also { it.mkdirs() }
        val file = uniqueFile(dir, safeFileName(title), format.extension)
        when (format) {
            ExportFormat.DOCX -> writeDocx(file, document.name, text)
            ExportFormat.PDF -> writePdf(file, document.name, text)
            ExportFormat.CSV -> file.writeText(markdownToCsv(text), Charsets.UTF_8)
            ExportFormat.XLSX -> writeXlsx(file, document.name, markdownToRows(text))
        }
        return file
    }

    private fun writePdf(file: File, title: String, text: String) {
        val pdf = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 18f
            isFakeBoldText = true
        }
        val lines = text.lines().flatMap { wrapLine(it, 78) }.ifEmpty { listOf("暂无内容") }
        var index = 0
        var pageNumber = 1
        while (index < lines.size) {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
            var y = 42f
            page.canvas.drawText(if (pageNumber == 1) title else "$title / $pageNumber", 40f, y, titlePaint)
            y += 30f
            while (index < lines.size && y < 800f) {
                page.canvas.drawText(lines[index].take(100), 40f, y, paint)
                y += 18f
                index += 1
            }
            pdf.finishPage(page)
            pageNumber += 1
        }
        file.outputStream().use { pdf.writeTo(it) }
        pdf.close()
    }

    private fun writeDocx(file: File, title: String, text: String) {
        zip(file) {
            entry("[Content_Types].xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                </Types>
            """.trimIndent())
            entry("_rels/.rels", """
                <?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                </Relationships>
            """.trimIndent())
            entry("word/document.xml", buildDocxDocument(title, text))
        }
    }

    private fun writeXlsx(file: File, title: String, rows: List<List<String>>) {
        val safeRows = rows.ifEmpty { listOf(listOf(title), listOf("暂无可导出的表格数据")) }
        zip(file) {
            entry("[Content_Types].xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                </Types>
            """.trimIndent())
            entry("_rels/.rels", """
                <?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                </Relationships>
            """.trimIndent())
            entry("xl/workbook.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheets><sheet name="DocPilot" sheetId="1" r:id="rId1"/></sheets>
                </workbook>
            """.trimIndent())
            entry("xl/_rels/workbook.xml.rels", """
                <?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                </Relationships>
            """.trimIndent())
            entry("xl/worksheets/sheet1.xml", buildSheet(safeRows))
        }
    }

    private fun buildDocxDocument(title: String, text: String): String {
        val paragraphs = (listOf("# $title") + text.lines()).joinToString("") { line ->
            val style = if (line.startsWith("#")) "<w:b/>" else ""
            "<w:p><w:r><w:rPr>$style</w:rPr><w:t xml:space=\"preserve\">${line.trimStart('#', ' ').escapeXml()}</w:t></w:r></w:p>"
        }
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>$paragraphs<w:sectPr/></w:body>
            </w:document>
        """.trimIndent()
    }

    private fun buildSheet(rows: List<List<String>>): String {
        val body = rows.take(500).mapIndexed { rowIndex, row ->
            val cells = row.take(50).mapIndexed { colIndex, value ->
                val ref = "${columnName(colIndex)}${rowIndex + 1}"
                "<c r=\"$ref\" t=\"inlineStr\"><is><t>${value.escapeXml()}</t></is></c>"
            }.joinToString("")
            "<row r=\"${rowIndex + 1}\">$cells</row>"
        }.joinToString("")
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>$body</sheetData>
            </worksheet>
        """.trimIndent()
    }

    private fun markdownToCsv(text: String): String {
        return markdownToRows(text).ifEmpty {
            text.lines().filter { it.isNotBlank() }.map { listOf(it) }
        }.joinToString("\n") { row ->
            row.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" }
        }
    }

    private fun markdownToRows(text: String): List<List<String>> {
        return text.lines()
            .map { it.trim() }
            .filter { it.startsWith("|") && it.endsWith("|") }
            .map { row -> row.trim('|').split('|').map { it.trim() } }
            .filterNot { cells -> cells.all { it.replace("-", "").replace(":", "").isBlank() } }
            .filter { cells -> cells.any { it.isNotBlank() } }
    }

    private fun redactSensitive(text: String): String {
        return text
            .replace(Regex("""(?i)(api[-_ ]?key|secret|token|authorization)\s*[:=]\s*["']?[^,\s"']+"""), "$1=[REDACTED]")
            .replace(Regex("""sk-[A-Za-z0-9_-]{12,}"""), "sk-[REDACTED]")
            .replace(Regex("""Bearer\s+[A-Za-z0-9._-]{16,}"""), "Bearer [REDACTED]")
            .replace(Regex("""\b\d{15,18}[\dXx]\b"""), "[ID_REDACTED]")
            .replace(Regex("""\b1[3-9]\d{9}\b"""), "[PHONE_REDACTED]")
    }

    private fun zip(file: File, block: ZipWriter.() -> Unit) {
        ZipOutputStream(file.outputStream()).use { ZipWriter(it).block() }
    }

    private class ZipWriter(private val zip: ZipOutputStream) {
        fun entry(path: String, content: String) {
            zip.putNextEntry(ZipEntry(path))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    private fun uniqueFile(dir: File, base: String, extension: String): File {
        var file = File(dir, "$base.$extension")
        var index = 1
        while (file.exists()) {
            file = File(dir, "$base-$index.$extension")
            index += 1
        }
        return file
    }

    private fun safeFileName(name: String): String = name.replace(Regex("""[\\/:*?"<>|]"""), "_").take(80)

    private fun wrapLine(line: String, width: Int): List<String> {
        if (line.length <= width) return listOf(line)
        return line.chunked(width)
    }

    private fun columnName(index: Int): String {
        var value = index
        val result = StringBuilder()
        do {
            result.insert(0, ('A'.code + value % 26).toChar())
            value = value / 26 - 1
        } while (value >= 0)
        return result.toString()
    }

    private fun String.escapeXml(): String = replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
