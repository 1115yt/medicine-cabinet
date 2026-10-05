package app.medicinecabinet.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 日期当天显示“今天到期”，不提前计作过期。 */
object ExpiryPresentation {
    fun remainingLabel(date: LocalDate, today: LocalDate): String {
        val days = ChronoUnit.DAYS.between(today, date)
        return when {
            days < 0 -> "已过期 ${-days} 天"
            days == 0L -> "今天到期"
            else -> "剩余 $days 天"
        }
    }

    fun earliestBatch(batches: List<StockBatch>): StockBatch? = batches
        .filter { it.quantity > 0 && InventoryRules.dateOf(it) != null }
        .minByOrNull { InventoryRules.dateOf(it)!! }

    fun earliestDate(batches: List<StockBatch>): LocalDate? = earliestBatch(batches)?.let(InventoryRules::dateOf)

    fun reasonLabel(reason: AlertReason, batches: List<StockBatch>, today: LocalDate): String {
        val dates = batches.filter { it.quantity > 0 }.mapNotNull(InventoryRules::dateOf)
        val date = when (reason) {
            AlertReason.EXPIRED -> dates.filter { it.isBefore(today) }.minOrNull()
            AlertReason.EXPIRING -> dates.filter { !it.isBefore(today) }.minOrNull()
            AlertReason.LOW_STOCK -> null
        }
        return date?.let { remainingLabel(it, today) } ?: reason.displayName()
    }
}
