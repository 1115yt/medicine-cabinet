package app.medicinecabinet.domain

import java.time.LocalDate
import java.time.YearMonth

/** 保存包装标注的精度；仅标注有效年月时，提醒按该月最后一天计算。 */
object ExpiryDates {
    private val monthPattern = Regex("\\d{4}-\\d{2}")
    private val datePattern = Regex("\\d{4}-\\d{2}-\\d{2}")
    private val inputPattern = Regex("(\\d{4})[-/.年](\\d{1,2})(?:[-/.月](\\d{1,2})日?|月)?\\.?")

    fun isMonthOnly(value: String): Boolean = monthPattern.matches(value)

    fun endDate(value: String): LocalDate? = runCatching {
        val date = when {
            monthPattern.matches(value) -> YearMonth.parse(value).atEndOfMonth()
            datePattern.matches(value) -> LocalDate.parse(value)
            else -> return null
        }
        require(date.year in 1..9999)
        date
    }.getOrNull()

    /** 手动输入接受六/八位数字、中文和分隔符；保存时统一格式并保留精度。 */
    fun normalizeInput(value: String): String? {
        val input = value.trim()
        if (Regex("[0-9]{6}|[0-9]{8}").matches(input)) {
            val normalized = input.take(4) + "-" + input.substring(4, 6) +
                if (input.length == 8) "-" + input.takeLast(2) else ""
            return normalized.takeIf { endDate(it) != null }
        }
        val match = inputPattern.matchEntire(input) ?: return null
        val year = match.groupValues[1]
        val month = match.groupValues[2].padStart(2, '0')
        val day = match.groupValues[3].takeIf { it.isNotEmpty() }?.padStart(2, '0')
        val normalized = if (day == null) "$year-$month" else "$year-$month-$day"
        return normalized.takeIf { endDate(it) != null }
    }

    fun display(value: String): String =
        if (isMonthOnly(value)) "$value（按月末计算）" else value
}
