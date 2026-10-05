package app.medicinecabinet.domain

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ExpiryDatesTest {
    @Test fun `six and eight numeric digits normalize at their original precision`() {
        assertEquals("2028-06", ExpiryDates.normalizeInput("202806"))
        assertEquals("2028-06-30", ExpiryDates.normalizeInput("20280630"))
        assertEquals("2028-02-29", ExpiryDates.normalizeInput("20280229"))
        assertEquals("2028-06", ExpiryDates.normalizeInput(" 202806 "))
        assertEquals(LocalDate.of(2028, 6, 30), ExpiryDates.endDate(ExpiryDates.normalizeInput("202806")!!))
    }
    @Test fun `partial extra and impossible numeric dates are rejected without guessing`() {
        for (invalid in listOf("2028", "20280", "2028063", "202806300", "202813", "202800", "20270229", "20280631", "000001")) {
            assertNull("不应推断有效期：$invalid", ExpiryDates.normalizeInput(invalid))
        }
    }
    @Test fun `month precision uses the real last day and is preserved`() {
        assertEquals(LocalDate.of(2028, 2, 29), ExpiryDates.endDate("2028-02"))
        assertEquals(LocalDate.of(2027, 2, 28), ExpiryDates.endDate("2027-02"))
        assertEquals(LocalDate.of(2030, 4, 30), ExpiryDates.endDate("2030-04"))
        assertEquals("2028-02（按月末计算）", ExpiryDates.display("2028-02"))
        assertTrue(ExpiryDates.isMonthOnly("2028-02"))
    }
    @Test fun `common package date formats normalize without inventing a day`() {
        listOf("2028年6月", "2028/6", "2028.6", "2028-06").forEach {
            assertEquals("2028-06", ExpiryDates.normalizeInput(it))
        }
        listOf("2028年6月3日", "2028/6/3", "2028.6.3", "2028-06-03").forEach {
            assertEquals("2028-06-03", ExpiryDates.normalizeInput(it))
        }
    }
    @Test fun `impossible months dates and year zero are rejected`() {
        listOf("2028-13", "2028-00", "2027-02-29", "2028-06-00", "0000-01", "明年六月", "2028-06-extra").forEach {
            assertNull(ExpiryDates.normalizeInput(it))
        }
    }
    @Test fun `month stock remains usable through the last day`() {
        val batch = StockBatch("b", "m", 2, "2028-02")
        assertEquals(2, InventoryRules.usableQuantity(listOf(batch), LocalDate.of(2028, 2, 29)))
        assertEquals(0, InventoryRules.usableQuantity(listOf(batch), LocalDate.of(2028, 3, 1)))
    }
    @Test fun `backup round trip retains month precision`() {
        val snapshot = AppSnapshot(medicines = listOf(Medicine("m", "示例药品")),
            batches = listOf(StockBatch("b", "m", 2, "2028-02")))
        assertEquals(snapshot, BackupCodec.decode(BackupCodec.encode(snapshot)).snapshot())
    }
}
