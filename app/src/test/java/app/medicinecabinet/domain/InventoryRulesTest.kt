package app.medicinecabinet.domain

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class InventoryRulesTest {
    private val today = LocalDate.of(2026, 10, 3)
    private val medicine = Medicine("m", "测试药品", lowStockThreshold = 2)
    private fun batch(id: String, quantity: Int, offset: Long?, handled: Boolean = false) =
        StockBatch(id, "m", quantity, offset?.let { today.plusDays(it).toString() }, expiryHandled = handled)

    @Test fun `expiry date itself is usable and next day is expired`() {
        assertEquals(2, InventoryRules.usableQuantity(listOf(batch("a", 2, 0)), today))
        assertEquals(0, InventoryRules.usableQuantity(listOf(batch("a", 2, -1)), today))
    }
    @Test fun `unknown and expired batches are excluded from confirmed inventory`() {
        assertEquals(3, InventoryRules.usableQuantity(listOf(batch("a", 5, null), batch("b", 2, -1), batch("c", 3, 60)), today))
    }
    @Test fun `mixed batches coalesce reasons without double counting shortage`() {
        val snapshot = AppSnapshot(listOf(medicine), listOf(batch("old", 3, -1), batch("live", 1, 60)))
        val alert = InventoryRules.evaluate(snapshot, today).single()
        assertEquals(listOf(AlertReason.EXPIRED, AlertReason.LOW_STOCK), alert.reasons)
        assertEquals(3, alert.suggestedQuantity)
        assertEquals(setOf("old"), alert.expiryBatchIds)
    }
    @Test fun `lead time boundary is inclusive and threshold equality is sufficient`() {
        val snapshot = AppSnapshot(listOf(medicine), listOf(batch("a", 2, 30), batch("b", 1, 31)))
        val alert = InventoryRules.evaluate(snapshot, today).single()
        assertEquals(listOf(AlertReason.EXPIRING), alert.reasons)
        assertEquals(setOf("a"), alert.expiryBatchIds)
    }
    @Test fun `custom global lead time includes today and boundary but excludes future and expired from near expiry`() {
        val settings = ReminderSettings(expiryLeadDays = 14)
        val snapshot = AppSnapshot(listOf(medicine),
            listOf(batch("today", 2, 0), batch("boundary", 1, 14), batch("future", 1, 15)), settings = settings)
        val alert = InventoryRules.evaluate(snapshot, today).single()
        assertEquals(listOf(AlertReason.EXPIRING), alert.reasons)
        assertEquals(setOf("today", "boundary"), alert.expiryBatchIds)
        assertEquals(listOf(AlertReason.EXPIRED, AlertReason.LOW_STOCK), InventoryRules.evaluate(snapshot.copy(batches =
            listOf(batch("expired", 2, -1))), today).single().reasons)
    }
    @Test fun `medicine lead time overrides global lead time`() {
        val snapshot = AppSnapshot(listOf(medicine.copy(expiryLeadDays = 7)),
            listOf(batch("boundary", 2, 7), batch("future", 1, 8)), settings = ReminderSettings(expiryLeadDays = 30))
        assertEquals(setOf("boundary"), InventoryRules.evaluate(snapshot, today).single().expiryBatchIds)
    }
    @Test fun `zero threshold disables shortage and empty batch does not expire`() {
        assertTrue(InventoryRules.evaluate(AppSnapshot(listOf(medicine.copy(lowStockThreshold = 0)),
            listOf(batch("a", 0, -10))), today).isEmpty())
    }
    @Test fun `handled expiry does not repeat and fresh replacement fills shortage`() {
        assertTrue(InventoryRules.evaluate(AppSnapshot(listOf(medicine),
            listOf(batch("old", 2, -1, true), batch("new", 2, 180))), today).isEmpty())
    }
    @Test fun `dismissal and custom quantity persist until alert state changes`() {
        val first = InventoryRules.evaluate(AppSnapshot(listOf(medicine), listOf(batch("a", 1, 90))), today)
        val previous = InventoryRules.reconcileShopping(first, emptyList()).single().copy(
            status = ShoppingStatus.DISMISSED, requestedQuantity = 4, customQuantity = true)
        assertEquals(previous, InventoryRules.reconcileShopping(first, listOf(previous)).single())
        val changed = InventoryRules.evaluate(AppSnapshot(listOf(medicine), listOf(batch("a", 1, 10))), today)
        assertEquals(ShoppingStatus.PENDING, InventoryRules.reconcileShopping(changed, listOf(previous)).single().status)
    }
}
