package app.medicinecabinet.domain

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class BackupCodecTest {
    private val snapshot = AppSnapshot(
        medicines = listOf(Medicine("m", "测试药品", barcode = "04006381333931")),
        batches = listOf(StockBatch("b", "m", 2, "2028-12-31", "测试批号", "测试抽屉")),
        settings = ReminderSettings(60, false),
    )
    private fun assertInvalid(text: String) {
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(text) }
    }
    // 构造不可信输入，故意绕过正常导出预检，以验证导入边界。
    private fun rawEncode(value: AppSnapshot): String = Json { prettyPrint = true; encodeDefaults = true }
        .encodeToString(LocalBackupV1(exportedAt = "2030-01-01T00:00:00Z", medicines = value.medicines,
            batches = value.batches, shoppingItems = value.shoppingItems, settings = value.settings))
    @Test fun `full backup round trip preserves data and settings`() {
        assertEquals(snapshot, BackupCodec.decode(BackupCodec.encode(snapshot)).snapshot())
    }
    @Test fun `unknown versions are rejected`() {
        assertInvalid(BackupCodec.encode(snapshot).replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"))
    }
    @Test fun `missing medicine references are rejected`() {
        assertInvalid(rawEncode(snapshot.copy(medicines = emptyList())))
    }
    @Test fun `invalid dates and negative inventory are rejected`() {
        assertInvalid(rawEncode(snapshot.copy(batches = listOf(snapshot.batches.single().copy(expiryDate = "2028-02-31")))))
        assertInvalid(rawEncode(snapshot.copy(batches = listOf(snapshot.batches.single().copy(quantity = -1)))))
    }
    @Test fun `duplicate IDs and malformed JSON are rejected`() {
        assertInvalid(rawEncode(snapshot.copy(medicines = snapshot.medicines + snapshot.medicines)))
        assertInvalid("{not a backup}")
    }
    @Test fun `duplicate barcodes cannot create ambiguous product mappings`() {
        assertInvalid(rawEncode(snapshot.copy(medicines = snapshot.medicines + snapshot.medicines.single().copy(id = "other"))))
    }
    @Test fun `backup preserves expiry display preference`() {
        assertFalse(BackupCodec.decode(BackupCodec.encode(snapshot, showExpiryDays = false)).showExpiryDays)
    }
    @Test fun `older backups without display preference use numeric days by default`() {
        val older = BackupCodec.encode(snapshot, showExpiryDays = false).replace(",\n    \"showExpiryDays\": false", "")
        assertFalse(older.contains("showExpiryDays"))
        assertTrue(BackupCodec.decode(older).showExpiryDays)
    }
    @Test fun `backup preserves selected interface size`() {
        val backup = BackupCodec.decode(BackupCodec.encode(snapshot, interfaceSize = InterfaceSize.COMPACT))
        assertEquals(InterfaceSize.COMPACT, backup.interfaceSize)
    }
    @Test fun `older backups restore standard interface size`() {
        val older = BackupCodec.encode(snapshot, interfaceSize = InterfaceSize.COMPACT)
            .replace(",\n    \"interfaceSize\": \"COMPACT\"", "")
        assertFalse(older.contains("interfaceSize"))
        assertEquals(InterfaceSize.STANDARD, BackupCodec.decode(older).interfaceSize)
    }

    @Test fun `invalid inventory is rejected before export`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.encode(snapshot.copy(medicines = emptyList()))
        }
    }

    @Test fun `large legal inventory is rejected consistently by export and import`() {
        val medicine = Medicine("fixture-medicine", "虚构药品", lowStockThreshold = 0)
        val batches = (1..4000).map {
            StockBatch("fixture-batch-$it", medicine.id, 1, "2030-12", "批".repeat(80), "位".repeat(80))
        }
        val large = AppSnapshot(listOf(medicine), batches)
        val raw = rawEncode(large)
        assertTrue(raw.toByteArray(Charsets.UTF_8).size > BackupCodec.MAX_BYTES)
        val exportFailure = assertThrows(IllegalArgumentException::class.java) { BackupCodec.encode(large) }
        val importFailure = assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(raw) }
        assertEquals(BackupCodec.CAPACITY_ERROR, exportFailure.message)
        assertEquals(BackupCodec.CAPACITY_ERROR, importFailure.message)
    }

    @Test fun `capacity is measured in utf8 bytes and exact limit can be restored`() {
        val text = BackupCodec.encode(snapshot)
        val bytes = text.toByteArray(Charsets.UTF_8).size
        assertTrue(bytes > text.length)
        val exact = text + " ".repeat(BackupCodec.MAX_BYTES - bytes)
        assertEquals(BackupCodec.MAX_BYTES, exact.toByteArray(Charsets.UTF_8).size)
        assertEquals(snapshot, BackupCodec.decode(exact).snapshot())
        val failure = assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(exact + " ") }
        assertEquals(BackupCodec.CAPACITY_ERROR, failure.message)
    }

    @Test fun `accepted large inventory round trips all records`() {
        val batches = (1..1000).map {
            StockBatch("fixture-batch-$it", "m", 1, "2030-12", "批".repeat(80), "位".repeat(80))
        }
        val accepted = snapshot.copy(batches = batches)
        val encoded = BackupCodec.encode(accepted, showExpiryDays = false, interfaceSize = InterfaceSize.COMFORTABLE)
        val restored = BackupCodec.decode(encoded)
        assertEquals(accepted, restored.snapshot())
        assertFalse(restored.showExpiryDays)
        assertEquals(InterfaceSize.COMFORTABLE, restored.interfaceSize)
    }
}
