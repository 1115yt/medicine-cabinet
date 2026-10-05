package app.medicinecabinet.data

import androidx.test.core.app.ApplicationProvider
import app.medicinecabinet.TestCabinetApplication
import app.medicinecabinet.domain.InterfaceSize
import app.medicinecabinet.domain.MedicineSort
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestCabinetApplication::class)
class DisplayPreferencesTest {
    @Test fun `medicine sorting defaults to insertion order and survives preference recreation`() {
        val context = ApplicationProvider.getApplicationContext<TestCabinetApplication>()
        val preferences = DisplayPreferences(context)
        assertEquals(MedicineSort.ADDED_FIRST, preferences.medicineSort.value)
        try {
            preferences.setMedicineSort(MedicineSort.EXPIRY_LAST)
            assertEquals(MedicineSort.EXPIRY_LAST, DisplayPreferences(context).medicineSort.value)
        } finally { preferences.setMedicineSort(MedicineSort.ADDED_FIRST) }
    }

    @Test fun `unrecognized sorting preference safely falls back to insertion order`() {
        val context = ApplicationProvider.getApplicationContext<TestCabinetApplication>()
        val storage = context.getSharedPreferences("cabinet-appearance", android.content.Context.MODE_PRIVATE)
        storage.edit().putString("medicine-sort", "fixture-unknown-choice").commit()
        try { assertEquals(MedicineSort.ADDED_FIRST, DisplayPreferences(context).medicineSort.value) }
        finally { DisplayPreferences(context).setMedicineSort(MedicineSort.ADDED_FIRST) }
    }
    @Test fun `display choice survives recreation of preference store`() {
        val context = ApplicationProvider.getApplicationContext<TestCabinetApplication>()
        val preferences = DisplayPreferences(context)
        try {
            preferences.setShowExpiryDays(false)
            assertFalse(DisplayPreferences(context).showExpiryDays.value)
        } finally { preferences.setShowExpiryDays(true) }
        assertTrue(DisplayPreferences(context).showExpiryDays.value)
    }
    @Test fun `interface size survives recreation of preference store`() {
        val context = ApplicationProvider.getApplicationContext<TestCabinetApplication>()
        val preferences = DisplayPreferences(context)
        try {
            preferences.setInterfaceSize(InterfaceSize.COMFORTABLE)
            assertEquals(InterfaceSize.COMFORTABLE, DisplayPreferences(context).interfaceSize.value)
        } finally { preferences.setInterfaceSize(InterfaceSize.STANDARD) }
    }
}
