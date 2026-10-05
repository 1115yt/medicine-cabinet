package app.medicinecabinet.domain

import java.time.LocalDate

object InventoryRules {
    fun dateOf(batch: StockBatch): LocalDate? = batch.expiryDate?.let(ExpiryDates::endDate)

    fun usableQuantity(batches: List<StockBatch>, today: LocalDate): Int = batches
        .filter { it.quantity > 0 && dateOf(it)?.isBefore(today) == false }
        .sumOf { it.quantity.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** 有效期当天仍计入有效库存，次日起过期。未知有效期不作为已确认可用库存。 */
    fun evaluate(snapshot: AppSnapshot, today: LocalDate): List<InventoryAlert> =
        snapshot.medicines.mapNotNull { medicine ->
            val batches = snapshot.batches.filter { it.medicineId == medicine.id }
            val leadDays = medicine.expiryLeadDays ?: snapshot.settings.expiryLeadDays
            val approaching = batches.filter {
                it.quantity > 0 && !it.expiryHandled && dateOf(it)?.let { date ->
                    !date.isAfter(today.plusDays(leadDays.toLong()))
                } == true
            }
            val reasons = buildList {
                if (approaching.any { dateOf(it)!!.isBefore(today) }) add(AlertReason.EXPIRED)
                if (approaching.any { !dateOf(it)!!.isBefore(today) }) add(AlertReason.EXPIRING)
                if (usableQuantity(batches, today) < medicine.lowStockThreshold) {
                    add(AlertReason.LOW_STOCK)
                }
            }
            if (reasons.isEmpty()) return@mapNotNull null
            val stockGap = (medicine.lowStockThreshold - usableQuantity(batches, today)).coerceAtLeast(0)
            val replacementQuantity = approaching.sumOf { it.quantity.toLong() }.coerceAtMost(9999).toInt()
            // 同一批库存可能同时导致临期与不足，使用较大缺口，避免重复计算补货量。
            val suggested = maxOf(stockGap, replacementQuantity, 1)
            val batchIds = approaching.map { it.id }.toSortedSet()
            val signature = "${reasons.joinToString(",")}:${medicine.lowStockThreshold}:${batchIds.joinToString(",")}"
            InventoryAlert(medicine.id, reasons, suggested, batchIds, signature)
        }

    fun reconcileShopping(
        alerts: List<InventoryAlert>,
        previous: List<ShoppingItem>,
    ): List<ShoppingItem> = alerts.map { alert ->
        val old = previous.find { it.medicineId == alert.medicineId }
        if (old != null && old.signature == alert.signature) {
            old.copy(
                reasons = alert.reasons,
                suggestedQuantity = alert.suggestedQuantity,
                requestedQuantity = if (old.customQuantity) old.requestedQuantity else alert.suggestedQuantity,
            )
        } else {
            ShoppingItem(
                medicineId = alert.medicineId,
                reasons = alert.reasons,
                suggestedQuantity = alert.suggestedQuantity,
                requestedQuantity = alert.suggestedQuantity,
                signature = alert.signature,
            )
        }
    }
}
