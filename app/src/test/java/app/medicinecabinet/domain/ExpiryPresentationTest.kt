package app.medicinecabinet.domain

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ExpiryPresentationTest {
    private val today = LocalDate.of(2026, 10, 3)
    @Test fun `future dates display precise remaining days`() {
        assertEquals("剩余 12 天", ExpiryPresentation.remainingLabel(today.plusDays(12), today))
    }
    @Test fun `expiry date itself is labelled today`() {
        assertEquals("今天到期", ExpiryPresentation.remainingLabel(today, today))
    }
    @Test fun `past dates display days expired`() {
        assertEquals("已过期 1 天", ExpiryPresentation.remainingLabel(today.minusDays(1), today))
    }
    @Test fun `empty stock and unknown dates do not distort earliest expiry`() {
        val batches = listOf(StockBatch("empty", "m", 0, today.minusDays(10).toString()),
            StockBatch("unknown", "m", 3), StockBatch("valid", "m", 1, today.plusDays(5).toString()))
        assertEquals(today.plusDays(5), ExpiryPresentation.earliestDate(batches))
        assertEquals("剩余 5 天", ExpiryPresentation.reasonLabel(AlertReason.EXPIRING, batches, today))
    }
    @Test fun `expired and approaching batches each retain their numeric meaning`() {
        val batches = listOf(StockBatch("old", "m", 1, today.minusDays(3).toString()),
            StockBatch("new", "m", 2, today.plusDays(12).toString()))
        assertEquals("已过期 3 天", ExpiryPresentation.reasonLabel(AlertReason.EXPIRED, batches, today))
        assertEquals("剩余 12 天", ExpiryPresentation.reasonLabel(AlertReason.EXPIRING, batches, today))
        assertEquals("库存不足", ExpiryPresentation.reasonLabel(AlertReason.LOW_STOCK, batches, today))
    }
}
