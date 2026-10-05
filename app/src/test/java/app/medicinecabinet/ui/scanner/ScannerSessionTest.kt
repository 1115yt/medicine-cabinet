package app.medicinecabinet.ui.scanner

import app.medicinecabinet.domain.CodeFormat
import app.medicinecabinet.domain.ParsedBarcode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ScannerSessionTest {
    @Test fun `rejected qr leaves same session ready for valid retail code`() {
        val accepted = mutableListOf<ParsedBarcode>()
        val errors = mutableListOf<String>()
        val session = ScannerSession({ accepted.add(it) }, { errors.add(it) })
        val rejected = "PRIVATE-FIXTURE-" + "q".repeat(513)
        assertFalse(session.submit(rejected, CodeFormat.OTHER))
        assertTrue(session.acceptsFrames())
        assertEquals(1, errors.size)
        assertTrue(errors.single().contains("继续扫描"))
        assertFalse(errors.single().contains("PRIVATE-FIXTURE"))
        assertTrue(session.submit("4006381333931", CodeFormat.RETAIL))
        assertEquals(listOf(ParsedBarcode("04006381333931")), accepted)
        assertFalse(session.acceptsFrames())
    }

    @Test fun `empty rejected code also permits a later valid result`() {
        val accepted = mutableListOf<ParsedBarcode>()
        val session = ScannerSession({ accepted.add(it) }, {})
        assertFalse(session.submit(" ", CodeFormat.OTHER))
        assertTrue(session.submit("fixture-code", CodeFormat.OTHER))
        assertEquals("fixture-code", accepted.single().productCode)
    }

    @Test fun `accepted code prevents duplicate results and late errors`() {
        val accepted = mutableListOf<ParsedBarcode>()
        val errors = mutableListOf<String>()
        val session = ScannerSession({ accepted.add(it) }, { errors.add(it) })
        assertTrue(session.submit("fixture-first", CodeFormat.OTHER))
        assertFalse(session.submit("fixture-second", CodeFormat.OTHER))
        assertFalse(session.submit("q".repeat(513), CodeFormat.OTHER))
        session.reportError("迟到识别错误")
        assertEquals("fixture-first", accepted.single().productCode)
        assertTrue(errors.isEmpty())
    }

    @Test fun `closing session drops late valid invalid and camera errors`() {
        val accepted = mutableListOf<ParsedBarcode>()
        val errors = mutableListOf<String>()
        val session = ScannerSession({ accepted.add(it) }, { errors.add(it) })
        session.close()
        assertFalse(session.acceptsFrames())
        assertFalse(session.submit("fixture", CodeFormat.OTHER))
        assertFalse(session.submit("q".repeat(513), CodeFormat.OTHER))
        session.reportError("无法打开摄像头")
        assertTrue(accepted.isEmpty())
        assertTrue(errors.isEmpty())
    }

    @Test fun `concurrent results deliver accepted callback exactly once`() {
        val accepted = mutableListOf<ParsedBarcode>()
        val session = ScannerSession({ accepted.add(it) }, {})
        val start = CountDownLatch(1)
        val finished = CountDownLatch(8)
        val threads = (1..8).map { index -> Thread {
            try {
                assertTrue(start.await(5, TimeUnit.SECONDS))
                session.submit("fixture-$index", CodeFormat.OTHER)
            } finally { finished.countDown() }
        }.apply { start() } }
        start.countDown()
        assertTrue(finished.await(10, TimeUnit.SECONDS))
        threads.forEach { it.join(1000) }
        assertEquals(1, accepted.size)
        assertFalse(session.acceptsFrames())
    }

    @Test fun `accepted callback may close the session without delivering again`() {
        val accepted = mutableListOf<ParsedBarcode>()
        lateinit var session: ScannerSession
        session = ScannerSession({ accepted.add(it); session.close() }, {})
        assertTrue(session.submit("fixture-first", CodeFormat.OTHER))
        assertFalse(session.submit("fixture-second", CodeFormat.OTHER))
        assertEquals(1, accepted.size)
    }
}
