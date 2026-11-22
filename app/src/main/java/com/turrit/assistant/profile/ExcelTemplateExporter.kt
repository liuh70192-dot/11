package com.turrit.assistant.profile

import android.content.Context
import android.net.Uri
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ExcelTemplateExporter {
    fun write(context: Context, uri: Uri) {
        val output = context.contentResolver.openOutputStream(uri)
            ?: throw IOException("无法创建模板文件")
        val headers = listOf("序号", "姓名", "用户名", "简介", "头像文件名")
        output.use { stream ->
            ZipOutputStream(stream).use { zip ->
                zip.putText("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
</Types>""")
                zip.putText("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>""")
                zip.putText("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="资料模板" sheetId="1" r:id="rId1"/></sheets></workbook>""")
                zip.putText("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
</Relationships>""")
                val rows = buildString {
                    append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
                    append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
                    append("""<row r="1">""")
                    headers.forEachIndexed { index, value ->
                        append(cell(index, 1, value))
                    }
                    append("</row>")
                    for (row in 2..11) {
                        append("""<row r="$row">""")
                        append(cell(0, row, (row - 1).toString()))
                        repeat(4) { column -> append(cell(column + 1, row, "")) }
                        append("</row>")
                    }
                    append("</sheetData></worksheet>")
                }
                zip.putText("xl/worksheets/sheet1.xml", rows)
            }
        }
    }

    private fun cell(column: Int, row: Int, value: String): String {
        var number = column + 1
        var letters = ""
        while (number > 0) {
            val remainder = (number - 1) % 26
            letters = ('A' + remainder) + letters
            number = (number - 1) / 26
        }
        val escaped = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return """<c r="$letters$row" t="inlineStr"><is><t xml:space="preserve">$escaped</t></is></c>"""
    }

    private fun ZipOutputStream.putText(path: String, value: String) {
        putNextEntry(ZipEntry(path))
        write(value.toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}
