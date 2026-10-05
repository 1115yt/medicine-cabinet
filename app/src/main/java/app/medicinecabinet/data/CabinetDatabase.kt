package app.medicinecabinet.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CabinetDao {
    // 文本主键表保留 SQLite 的插入行顺序，编辑现有药品不会变成新添加。
    @Query("SELECT * FROM medicines ORDER BY rowid")
    fun observeMedicines(): Flow<List<MedicineEntity>>
    @Query("SELECT * FROM batches ORDER BY expiryDate, id")
    fun observeBatches(): Flow<List<BatchEntity>>
    @Query("SELECT * FROM shopping ORDER BY medicineId")
    fun observeShopping(): Flow<List<ShoppingEntity>>
    @Query("SELECT * FROM settings WHERE id = 1")
    fun observeSettings(): Flow<SettingsEntity?>
    @Query("SELECT * FROM medicines ORDER BY rowid") suspend fun medicines(): List<MedicineEntity>
    @Query("SELECT * FROM batches") suspend fun batches(): List<BatchEntity>
    @Query("SELECT * FROM shopping") suspend fun shopping(): List<ShoppingEntity>
    @Query("SELECT * FROM settings WHERE id = 1") suspend fun settings(): SettingsEntity?
    @Query("SELECT * FROM notification_receipts") suspend fun receipts(): List<NotificationReceipt>
    @Upsert suspend fun saveMedicine(value: MedicineEntity)
    @Upsert suspend fun saveBatch(value: BatchEntity)
    @Upsert suspend fun saveShopping(values: List<ShoppingEntity>)
    @Upsert suspend fun saveSettings(value: SettingsEntity)
    @Upsert suspend fun saveReceipts(values: List<NotificationReceipt>)
    @Query("DELETE FROM shopping") suspend fun clearShopping()
    @Query("DELETE FROM batches") suspend fun clearBatches()
    @Query("DELETE FROM medicines") suspend fun clearMedicines()
    @Query("DELETE FROM notification_receipts") suspend fun clearReceipts()
    @Query("DELETE FROM notification_receipts WHERE medicineId NOT IN (:activeIds)")
    suspend fun clearInactiveReceipts(activeIds: List<String>)
    @Query("DELETE FROM notification_receipts WHERE medicineId = :medicineId")
    suspend fun clearReceipt(medicineId: String)
    @Query("DELETE FROM medicines WHERE id = :medicineId") suspend fun deleteMedicine(medicineId: String)
    @Query("DELETE FROM batches WHERE id = :batchId") suspend fun deleteBatch(batchId: String)
    @Query("DELETE FROM shopping WHERE medicineId = :medicineId") suspend fun deleteShopping(medicineId: String)
}

@Database(entities = [MedicineEntity::class, BatchEntity::class, ShoppingEntity::class,
    SettingsEntity::class, NotificationReceipt::class], version = 1, exportSchema = true)
abstract class CabinetDatabase : RoomDatabase() {
    abstract fun dao(): CabinetDao
    companion object {
        fun create(context: Context) = Room.databaseBuilder(
            context.applicationContext, CabinetDatabase::class.java, "medicine-cabinet.db",
        ).build()
    }
}
