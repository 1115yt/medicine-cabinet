package app.medicinecabinet.domain

import java.time.LocalDate
import java.time.YearMonth

enum class CodeFormat { RETAIL, GS1_CAPABLE, OTHER }

data class ParsedBarcode(
    val productCode: String,
    val lotNumber: String? = null,
    val expiryDate: String? = null,
    val structured: Boolean = false,
)

object BarcodeParser {
    private const val SEPARATOR = '\u001d'

    fun parse(raw: String, format: CodeFormat = CodeFormat.OTHER): ParsedBarcode {
        val value = raw.trim()
        require(value.isNotEmpty() && value.length <= 512) { "条码为空或过长，请重新扫描。" }
        val readable = value.startsWith("(01)")
        val prefix = listOf("]d2", "]C1", "]Q3").firstOrNull { value.startsWith(it) }
        val looksStructured = readable || prefix != null ||
            (format == CodeFormat.GS1_CAPABLE && value.startsWith("01") && value.length > 16)
        if (looksStructured) {
            val fields = if (readable) parseReadable(value) else parseElements(value.removePrefix(prefix ?: ""))
            val gtin = fields?.get("01")
            if (gtin != null && gtin.length == 14 && validGtin(gtin)) {
                return ParsedBarcode(gtin, fields["10"], fields["17"]?.let(::decodeExpiry), true)
            }
        }
        val productCode = if (format == CodeFormat.RETAIL && validGtin(value)) value.padStart(14, '0') else value
        return ParsedBarcode(productCode)
    }

    private fun parseReadable(value: String): Map<String, String>? {
        val matches = Regex("\\((\\d{2})\\)([^()]*)").findAll(value).toList()
        if (matches.isEmpty() || matches.joinToString("") { it.value } != value) return null
        if (matches.any { it.groupValues[1] !in setOf("01", "10", "17", "21") }) return null
        if (matches.map { it.groupValues[1] }.distinct().size != matches.size) return null
        return matches.associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun parseElements(value: String): Map<String, String>? {
        val fields = mutableMapOf<String, String>()
        var index = 0
        while (index < value.length) {
            if (value[index] == SEPARATOR) { index++; continue }
            if (index + 2 > value.length) return null
            val ai = value.substring(index, index + 2)
            if (fields.containsKey(ai)) return null
            index += 2
            val length = when (ai) { "01" -> 14; "17" -> 6; "10", "21" -> null; else -> return null }
            val end = if (length != null) index + length else value.indexOf(SEPARATOR, index).let {
                if (it < 0) value.length else it
            }
            if (end > value.length || end <= index) return null
            fields[ai] = value.substring(index, end)
            index = end
        }
        return fields
    }

    private fun decodeExpiry(value: String): String? = runCatching {
        require(value.length == 6 && value.all(Char::isDigit))
        val year = 2000 + value.substring(0, 2).toInt()
        val month = value.substring(2, 4).toInt()
        val day = value.substring(4, 6).toInt()
        // GS1 日期的 DD=00 表示该月最后一天，仍需由用户核对包装。
        if (day == 0) YearMonth.of(year, month).atEndOfMonth().toString()
        else LocalDate.of(year, month, day).toString()
    }.getOrNull()

    fun validGtin(value: String): Boolean {
        if (value.length !in setOf(8, 12, 13, 14) || !value.all(Char::isDigit)) return false
        val sum = value.dropLast(1).reversed().mapIndexed { index, char ->
            char.digitToInt() * if (index % 2 == 0) 3 else 1
        }.sum()
        return (10 - sum % 10) % 10 == value.last().digitToInt()
    }
}

