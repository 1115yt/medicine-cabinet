package app.medicinecabinet.domain

import org.junit.Assert.*
import org.junit.Test

class BarcodeParserTest {
    @Test fun `retail code normalizes but never invents an expiry date`() {
        val parsed = BarcodeParser.parse("4006381333931", CodeFormat.RETAIL)
        assertEquals("04006381333931", parsed.productCode)
        assertNull(parsed.expiryDate)
        assertFalse(parsed.structured)
    }
    @Test fun `GS1 readable fields fill date and batch`() {
        val parsed = BarcodeParser.parse("(01)09504000059118(17)271231(10)7654321D")
        assertEquals("09504000059118", parsed.productCode)
        assertEquals("2027-12-31", parsed.expiryDate)
        assertEquals("7654321D", parsed.lotNumber)
    }
    @Test fun `variable GS1 field requires separator before next field`() {
        val parsed = BarcodeParser.parse("]d2010950400005911810ABC\u001d17280200")
        assertEquals("2028-02-29", parsed.expiryDate)
        assertEquals("ABC", parsed.lotNumber)
    }
    @Test fun `invalid calendar date is not accepted`() {
        assertNull(BarcodeParser.parse("(01)09504000059118(17)271332").expiryDate)
    }
    @Test fun `trace code and arbitrary QR URL remain identifiers`() {
        val trace = "81012345678901234567"
        assertEquals(trace, BarcodeParser.parse(trace, CodeFormat.GS1_CAPABLE).productCode)
        val url = "https://example.invalid/medicine?expiry=2028"
        assertEquals(url, BarcodeParser.parse(url).productCode)
        assertNull(BarcodeParser.parse(url).expiryDate)
    }
    @Test fun `duplicate or unsupported structured fields fall back without inference`() {
        val raw = "(01)09504000059118(17)271231(17)280101"
        assertEquals(raw, BarcodeParser.parse(raw).productCode)
        assertFalse(BarcodeParser.parse(raw).structured)
    }
    @Test(expected = IllegalArgumentException::class) fun `empty barcode is rejected`() { BarcodeParser.parse(" ") }
}

