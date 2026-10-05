package app.medicinecabinet.domain

import java.text.Collator
import java.time.LocalDate
import java.util.Locale

enum class MedicineFilter(val title: String) {
    ALL("我的药箱"), EXPIRING("临期药品"), EXPIRED("过期药品")
}

enum class MedicineSort(val label: String, val explanation: String) {
    ADDED_FIRST("添加顺序", "先添加的在前"),
    ADDED_LAST("最近添加", "后添加的在前"),
    EXPIRY_FIRST("快到期优先", "剩余天数从少到多，已过期在前；未知日期或无库存排最后。"),
    EXPIRY_LAST("有效期长优先", "剩余天数从多到少；未知日期或无库存排最后。"),
    NAME("药品名称", "按中文拼音和名称排列")
}

/** 分类反映实际库存与日期，不因已经补货或忽略提醒而隐藏仍在药箱中的批次。 */
object MedicineListing {
    fun matchingBatches(medicine: Medicine, batches: List<StockBatch>, settings: ReminderSettings,
        today: LocalDate, filter: MedicineFilter): List<StockBatch> {
        if (filter == MedicineFilter.ALL) return batches
        val lastNearDay = today.plusDays((medicine.expiryLeadDays ?: settings.expiryLeadDays).toLong())
        return batches.filter { batch ->
            val date = InventoryRules.dateOf(batch)
            batch.quantity > 0 && date != null && when (filter) {
                MedicineFilter.EXPIRING -> !date.isBefore(today) && !date.isAfter(lastNearDay)
                MedicineFilter.EXPIRED -> date.isBefore(today)
                MedicineFilter.ALL -> true
            }
        }
    }

    fun select(snapshot: AppSnapshot, today: LocalDate, filter: MedicineFilter = MedicineFilter.ALL,
        sort: MedicineSort = MedicineSort.ADDED_FIRST, query: String = ""): List<Medicine> {
        val grouped = snapshot.batches.groupBy { it.medicineId }
        val text = query.trim()
        val visible = snapshot.medicines.filter { medicine ->
            val batches = grouped[medicine.id].orEmpty()
            val inCategory = filter == MedicineFilter.ALL ||
                matchingBatches(medicine, batches, snapshot.settings, today, filter).isNotEmpty()
            inCategory && (text.isEmpty() || medicine.name.contains(text, true) ||
                medicine.specification.contains(text, true) || batches.any { it.location.contains(text, true) })
        }
        return when (sort) {
            MedicineSort.ADDED_FIRST -> visible
            MedicineSort.ADDED_LAST -> visible.reversed()
            MedicineSort.NAME -> {
                val collator = Collator.getInstance(Locale.CHINA)
                visible.sortedWith { first, second -> collator.compare(first.name, second.name) }
            }
            MedicineSort.EXPIRY_FIRST, MedicineSort.EXPIRY_LAST -> {
                val dates = visible.associate { medicine ->
                    val batches = matchingBatches(medicine, grouped[medicine.id].orEmpty(), snapshot.settings, today, filter)
                    medicine.id to batches.filter { it.quantity > 0 }.mapNotNull(InventoryRules::dateOf).minOrNull()
                }
                // 未知日期始终排最后；稳定排序让同一天的药品保留添加顺序。
                visible.sortedWith { first, second ->
                    val firstDate = dates[first.id]
                    val secondDate = dates[second.id]
                    when {
                        firstDate == null && secondDate == null -> 0
                        firstDate == null -> 1
                        secondDate == null -> -1
                        sort == MedicineSort.EXPIRY_FIRST -> firstDate.compareTo(secondDate)
                        else -> secondDate.compareTo(firstDate)
                    }
                }
            }
        }
    }
}
