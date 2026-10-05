package app.medicinecabinet.data

import android.content.Context
import app.medicinecabinet.domain.InterfaceSize
import app.medicinecabinet.domain.MedicineSort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 展示偏好独立保存，不改变药箱数据库结构。 */
class DisplayPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("cabinet-appearance", Context.MODE_PRIVATE)
    private val _showExpiryDays = MutableStateFlow(preferences.getBoolean("show-expiry-days", true))
    val showExpiryDays = _showExpiryDays.asStateFlow()
    private val _interfaceSize = MutableStateFlow(InterfaceSize.entries.find {
        it.name == preferences.getString("interface-size", InterfaceSize.STANDARD.name)
    } ?: InterfaceSize.STANDARD)
    val interfaceSize = _interfaceSize.asStateFlow()
    private val _medicineSort = MutableStateFlow(MedicineSort.entries.find {
        it.name == preferences.getString("medicine-sort", MedicineSort.ADDED_FIRST.name)
    } ?: MedicineSort.ADDED_FIRST)
    val medicineSort = _medicineSort.asStateFlow()

    fun setMedicineSort(sort: MedicineSort) {
        preferences.edit().putString("medicine-sort", sort.name).apply()
        _medicineSort.value = sort
    }

    fun setShowExpiryDays(show: Boolean) {
        preferences.edit().putBoolean("show-expiry-days", show).apply()
        _showExpiryDays.value = show
    }

    fun setInterfaceSize(size: InterfaceSize) {
        preferences.edit().putString("interface-size", size.name).apply()
        _interfaceSize.value = size
    }
}
