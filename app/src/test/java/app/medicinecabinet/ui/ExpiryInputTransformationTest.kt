package app.medicinecabinet.ui

import androidx.compose.ui.text.AnnotatedString
import app.medicinecabinet.ui.forms.ExpiryInputTransformation
import app.medicinecabinet.ui.forms.editableExpiryInput
import org.junit.Assert.*
import org.junit.Test

class ExpiryInputTransformationTest {
    @Test fun `numeric input shows separators as each part is typed`() {
        for ((raw, visible) in mapOf("" to "", "2028" to "2028", "20280" to "2028-0",
            "202806" to "2028-06", "2028063" to "2028-06-3", "20280630" to "2028-06-30")) {
            assertEquals(visible, ExpiryInputTransformation.filter(AnnotatedString(raw)).text.text)
        }
    }
    @Test fun `cursor and selection offsets stay within bounds and preserve every numeric position`() {
        for (length in 0..8) {
            val raw = "20280630".take(length)
            val result = ExpiryInputTransformation.filter(AnnotatedString(raw))
            val mapping = result.offsetMapping
            for (offset in 0..raw.length) {
                val transformed = mapping.originalToTransformed(offset)
                assertTrue(transformed in 0..result.text.length)
                assertEquals(offset, mapping.transformedToOriginal(transformed))
            }
            val positions = (0..result.text.length).map(mapping::transformedToOriginal)
            assertTrue(positions.all { it in 0..raw.length })
            assertEquals(positions.sorted(), positions)
        }
    }
    @Test fun `existing separated input stays readable and saved dates can be extended using digits`() {
        for (raw in listOf("2028-06", "2028/6/30", "2028年6月", "202806300")) {
            val result = ExpiryInputTransformation.filter(AnnotatedString(raw))
            assertEquals(raw, result.text.text)
            assertEquals(raw.length, result.offsetMapping.originalToTransformed(raw.length))
        }
        assertEquals("202806", editableExpiryInput("2028-06"))
        assertEquals("20280630", editableExpiryInput("2028-06-30"))
        assertEquals("2028063", editableExpiryInput("2028063"))
    }
}
