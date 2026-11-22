package com.turrit.assistant.profile

import android.content.Context
import android.net.Uri
import android.util.Xml
import com.turrit.assistant.profile.model.ProfileField
import com.turrit.assistant.profile.model.ProfileFieldAction
import com.turrit.assistant.profile.model.ProfileRow
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/** Reads the first worksheet of a normal .xlsx file without loading a heavyweight spreadsheet engine. */
class ExcelImporter {
    fun read(context: Context, uri: Uri): List<ProfileRow> {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("无法打开 Excel 文件")
        return input.use(::read)
    }

    fun read(input: InputStream): List<ProfileRow> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory &&
                    (entry.name == "xl/sharedStrings.xml" ||
                        entry.name.matches(Regex("xl/worksheets/sheet\\d+\\.xml")))
                ) {
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var count = 0
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        count += read
                        if (count > MAX_XML_BYTES) throw IOException("Excel 工作表内容过大")
                        output.write(buffer, 0, read)
                    }
                    entries[entry.name] = output.toByteArray()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val sheet = entries.entries.firstOrNull { it.key.startsWith("xl/worksheets/sheet") }?.value
            ?: throw IOException("Excel 中没有找到工作表")
        val sharedStrings = entries["xl/sharedStrings.xml"]?.let(::parseSharedStrings).orEmpty()
        val worksheetRows = parseWorksheet(sheet, sharedStrings)
        if (worksheetRows.isEmpty()) throw IOException("Excel 工作表为空")

        val first = worksheetRows.first()
        val headers = first.cells.mapValues { normalizeHeader(it.value) }
        val headerColumns = mapOf(
            "firstName" to findHeader(headers, setOf("姓名", "名字", "名", "firstname", "first name", "first_name")),
            "lastName" to findHeader(headers, setOf("姓氏", "姓", "lastname", "last name", "last_name")),
            "username" to findHeader(headers, setOf("用户名", "username", "user name", "user_name")),
            "bio" to findHeader(headers, setOf("简介", "个人简介", "bio", "about")),
            "avatar" to findHeader(headers, setOf("头像文件名", "头像", "avatar", "avatar filename", "photo"))
        )
        val singleNameTemplate =
            findHeader(headers, setOf("姓名")) != null && headerColumns["lastName"] == null
        val hasHeader = headerColumns.values.any { it != null }
        val columns = if (hasHeader) {
            headerColumns.mapValues { it.value ?: -1 }
        } else {
            // Default template: 序号 | 名字 | 姓氏 | 用户名 | 简介 | 头像文件名
            mapOf("firstName" to 1, "lastName" to 2, "username" to 3, "bio" to 4, "avatar" to 5)
        }
        val dataRows = if (hasHeader) worksheetRows.drop(1) else worksheetRows

        val result = dataRows.mapNotNull { row ->
            fun value(key: String): String? = columns[key]
                ?.takeIf { it >= 0 }
                ?.let { row.cells[it] }
                ?.trim()
            val firstName = ProfileField.fromCell(value("firstName"))
            val username = ProfileField.fromCell(value("username"))
            val bio = ProfileField.fromCell(value("bio"))
            val avatarName = value("avatar")?.takeIf(String::isNotBlank)
            val lastName = if (singleNameTemplate) {
                ProfileField.fromCell("【清空】")
            } else {
                ProfileField.fromCell(value("lastName"))
            }
            val hasUserData = firstName.action != ProfileFieldAction.KEEP ||
                (!singleNameTemplate && lastName.action != ProfileFieldAction.KEEP) ||
                username.action != ProfileFieldAction.KEEP ||
                bio.action != ProfileFieldAction.KEEP ||
                !avatarName.isNullOrBlank()
            val profile = ProfileRow(
                sourceRow = row.rowNumber,
                firstName = firstName,
                lastName = lastName,
                username = username,
                bio = bio,
                avatarFileName = avatarName
            )
            profile.takeIf { hasUserData && it.hasChanges() }
        }
        if (result.isEmpty()) throw IOException("没有读取到有效资料行，请检查表头和内容")
        return result
    }

    private data class WorksheetRow(val rowNumber: Int, val cells: Map<Int, String>)

    private fun parseSharedStrings(xml: ByteArray): List<String> {
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(xml), "UTF-8")
        val strings = mutableListOf<String>()
        var current: StringBuilder? = null
        var inText = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> current = StringBuilder()
                    "t" -> inText = true
                }
                XmlPullParser.TEXT -> if (inText) current?.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "t" -> inText = false
                    "si" -> {
                        strings += current?.toString().orEmpty()
                        current = null
                    }
                }
            }
            parser.next()
        }
        return strings
    }

    private fun parseWorksheet(xml: ByteArray, shared: List<String>): List<WorksheetRow> {
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(xml), "UTF-8")
        val rows = mutableListOf<WorksheetRow>()
        var rowNumber = 0
        var rowCells = linkedMapOf<Int, String>()
        var column = -1
        var cellType = ""
        var cellValue = StringBuilder()
        var capturing = false

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> {
                        rowNumber = parser.getAttributeValue(null, "r")?.toIntOrNull()
                            ?: (rows.size + 1)
                        rowCells = linkedMapOf()
                    }
                    "c" -> {
                        column = columnIndex(parser.getAttributeValue(null, "r").orEmpty())
                        cellType = parser.getAttributeValue(null, "t").orEmpty()
                        cellValue = StringBuilder()
                    }
                    "v", "t" -> if (column >= 0) capturing = true
                }
                XmlPullParser.TEXT -> if (capturing) cellValue.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "v", "t" -> capturing = false
                    "c" -> {
                        if (column >= 0) {
                            val raw = cellValue.toString()
                            val value = if (cellType == "s") {
                                shared.getOrNull(raw.toIntOrNull() ?: -1).orEmpty()
                            } else raw
                            rowCells[column] = value
                        }
                        column = -1
                        cellType = ""
                        cellValue = StringBuilder()
                    }
                    "row" -> if (rowCells.isNotEmpty()) {
                        rows += WorksheetRow(rowNumber, rowCells.toMap())
                    }
                }
            }
            parser.next()
        }
        return rows
    }

    private fun columnIndex(reference: String): Int {
        var result = 0
        for (character in reference.takeWhile(Char::isLetter).uppercase(Locale.ROOT)) {
            result = result * 26 + (character - 'A' + 1)
        }
        return result - 1
    }

    private fun normalizeHeader(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace(Regex("[\\s_-]+"), "")

    private fun findHeader(headers: Map<Int, String>, aliases: Set<String>): Int? =
        headers.entries.firstOrNull { (_, value) ->
            val normalizedAliases = aliases.map(::normalizeHeader).toSet()
            value in normalizedAliases
        }?.key

    companion object {
        private const val MAX_XML_BYTES = 12 * 1024 * 1024
    }
}
