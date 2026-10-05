package app.medicinecabinet.domain

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class MedicineListingTest {
    private val today = LocalDate.of(2026, 10, 4)
    private fun medicine(id: String, lead: Int? = null, name: String = id) = Medicine(id, name, expiryLeadDays = lead)
    private fun batch(id: String, medicineId: String, days: Long?, quantity: Int = 1, handled: Boolean = false) =
        StockBatch(id, medicineId, quantity, days?.let { today.plusDays(it).toString() }, expiryHandled = handled)

    @Test fun `near expiry includes today and the final lead day but excludes expired unknown and empty stock`() {
        val medicines = listOf(medicine("today"), medicine("boundary"), medicine("later"), medicine("expired"),
            medicine("unknown"), medicine("empty"), medicine("no-batches"))
        val batches = listOf(batch("a", "today", 0), batch("b", "boundary", 30), batch("c", "later", 31),
            batch("d", "expired", -1), batch("e", "unknown", null), batch("f", "empty", 1, 0))
        val snapshot = AppSnapshot(medicines, batches)
        assertEquals(listOf("today", "boundary"), MedicineListing.select(snapshot, today, MedicineFilter.EXPIRING).map { it.id })
        assertEquals(listOf("expired"), MedicineListing.select(snapshot, today, MedicineFilter.EXPIRED).map { it.id })
    }

    @Test fun `individual lead time overrides the current default`() {
        val short = medicine("short", 3)
        val long = medicine("long", 60)
        val default = medicine("default")
        val snapshot = AppSnapshot(listOf(short, long, default), listOf(batch("a", "short", 4),
            batch("b", "long", 60), batch("c", "default", 4)), settings = ReminderSettings(3))
        assertEquals(listOf("long"), MedicineListing.select(snapshot, today, MedicineFilter.EXPIRING).map { it.id })
    }

    @Test fun `replenished and handled batches remain visible until their quantity is cleared`() {
        val medicine = medicine("mixed")
        val batches = listOf(batch("expired", "mixed", -1, handled = true),
            batch("near", "mixed", 3, handled = true), batch("fresh", "mixed", 180))
        val snapshot = AppSnapshot(listOf(medicine), batches)
        assertEquals(1, MedicineListing.select(snapshot, today, MedicineFilter.EXPIRING).size)
        assertEquals(1, MedicineListing.select(snapshot, today, MedicineFilter.EXPIRED).size)
        assertEquals(listOf("near"), MedicineListing.matchingBatches(medicine, batches, snapshot.settings, today,
            MedicineFilter.EXPIRING).map { it.id })
        assertEquals(listOf("expired"), MedicineListing.matchingBatches(medicine, batches, snapshot.settings, today,
            MedicineFilter.EXPIRED).map { it.id })
        val cleared = snapshot.copy(batches = batches.map { if (it.id != "fresh") it.copy(quantity = 0) else it })
        assertTrue(MedicineListing.select(cleared, today, MedicineFilter.EXPIRING).isEmpty())
        assertTrue(MedicineListing.select(cleared, today, MedicineFilter.EXPIRED).isEmpty())
    }

    @Test fun `month precision uses the actual month end and one medicine is counted once`() {
        val medicine = medicine("month")
        val snapshot = AppSnapshot(listOf(medicine), listOf(StockBatch("month", "month", 1, "2026-10"),
            batch("other", "month", 20)), settings = ReminderSettings(27))
        assertEquals(1, MedicineListing.select(snapshot, today, MedicineFilter.EXPIRING).size)
        assertEquals(2, MedicineListing.matchingBatches(medicine, snapshot.batches, snapshot.settings, today,
            MedicineFilter.EXPIRING).size)
        assertEquals(1, MedicineListing.matchingBatches(medicine, snapshot.batches, ReminderSettings(26), today,
            MedicineFilter.EXPIRING).size)
    }

    @Test fun `expiry day is near and the following day is expired`() {
        val snapshot = AppSnapshot(listOf(medicine("m")), listOf(batch("b", "m", 0)))
        assertEquals(1, MedicineListing.select(snapshot, today, MedicineFilter.EXPIRING).size)
        assertTrue(MedicineListing.select(snapshot, today, MedicineFilter.EXPIRED).isEmpty())
        assertEquals(1, MedicineListing.select(snapshot, today.plusDays(1), MedicineFilter.EXPIRED).size)
    }

    private fun sortableSnapshot() = AppSnapshot(listOf(medicine("unknown"), medicine("long"), medicine("expired"),
        medicine("near"), medicine("same"), medicine("empty")), listOf(batch("u", "unknown", null),
        batch("l", "long", 180), batch("old-history", "long", -365, 0), batch("e", "expired", -2),
        batch("n", "near", 4), batch("fresh", "near", 365), batch("s", "same", 4), batch("z", "empty", -10, 0)))

    @Test fun `added order and most recent preserve the supplied insertion sequence`() {
        val snapshot = sortableSnapshot()
        assertEquals(snapshot.medicines, MedicineListing.select(snapshot, today))
        assertEquals(snapshot.medicines.reversed(), MedicineListing.select(snapshot, today, sort = MedicineSort.ADDED_LAST))
    }

    @Test fun `soonest expiry uses the earliest positive stock batch and keeps equal dates stable`() {
        assertEquals(listOf("expired", "near", "same", "long", "unknown", "empty"),
            MedicineListing.select(sortableSnapshot(), today, sort = MedicineSort.EXPIRY_FIRST).map { it.id })
    }

    @Test fun `longest expiry still puts unknown dates and zero stock last`() {
        assertEquals(listOf("long", "near", "same", "expired", "unknown", "empty"),
            MedicineListing.select(sortableSnapshot(), today, sort = MedicineSort.EXPIRY_LAST).map { it.id })
    }

    @Test fun `category expiry sorting uses only the matching batches`() {
        val snapshot = AppSnapshot(listOf(medicine("mixed"), medicine("near-first")), listOf(
            batch("old", "mixed", -100), batch("near", "mixed", 10), batch("first", "near-first", 1)))
        assertEquals(listOf("near-first", "mixed"), MedicineListing.select(snapshot, today,
            MedicineFilter.EXPIRING, MedicineSort.EXPIRY_FIRST).map { it.id })
    }

    @Test fun `search trims spaces supports specification and location and keeps the category filter`() {
        val snapshot = AppSnapshot(listOf(medicine("near", name = "示例药品").copy(specification = "10片"),
            medicine("later", name = "另一示例")), listOf(batch("n", "near", 1).copy(location = "书房"),
            batch("l", "later", 180).copy(location = "书房")))
        for (query in listOf(" 示例药品 ", "10片", "书房")) {
            assertEquals(listOf("near"), MedicineListing.select(snapshot, today, MedicineFilter.EXPIRING, query = query).map { it.id })
        }
        assertTrue(MedicineListing.select(snapshot, today, query = "不存在").isEmpty())
    }

    @Test fun `name order follows Chinese collation and retains equal name insertion order`() {
        val snapshot = AppSnapshot(listOf(medicine("v", name = "维生素示例"), medicine("b", name = "苯示例"),
            medicine("a-first", name = "阿示例"), medicine("a-second", name = "阿示例")))
        assertEquals(listOf("a-first", "a-second", "b", "v"), MedicineListing.select(snapshot, today,
            sort = MedicineSort.NAME).map { it.id })
    }
}
