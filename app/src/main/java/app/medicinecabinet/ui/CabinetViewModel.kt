package app.medicinecabinet.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.medicinecabinet.CabinetApplication
import app.medicinecabinet.BuildConfig
import app.medicinecabinet.data.DisplayPreferences
import app.medicinecabinet.data.LookupPreferences
import app.medicinecabinet.data.ProductLookup
import app.medicinecabinet.data.ApiUsageStore
import app.medicinecabinet.data.BarcodeApiQueries
import app.medicinecabinet.data.ServerCacheCredentials
import app.medicinecabinet.data.ServerCacheWorker
import app.medicinecabinet.data.ServerConnectionMonitor
import app.medicinecabinet.data.ReleaseUpdateMonitor
import app.medicinecabinet.domain.*
import app.medicinecabinet.ui.forms.EditorRequest
import app.medicinecabinet.reminders.ReminderScheduler
import app.medicinecabinet.reminders.ReminderNotifications
import app.medicinecabinet.reminders.ReminderCheckSource
import app.medicinecabinet.reminders.TestNotificationResult
import app.medicinecabinet.security.CredentialStorageException
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ScanLookupState {
    data object Idle : ScanLookupState
    data object Loading : ScanLookupState
    data class Ready(val request: EditorRequest) : ScanLookupState
}

class CabinetViewModel(application: Application) : AndroidViewModel(application) {
    private val cabinetApplication = application as CabinetApplication
    private val repository = cabinetApplication.repository
    private val displayPreferences = DisplayPreferences(application)
    private val lookupPreferences = LookupPreferences(application)
    private val productCache = cabinetApplication.productCache
    val sharedSettings = cabinetApplication.serverPreferences.settings
    val sharedStatus = productCache.shared.store.status
    private val sharedConnectionMonitor = ServerConnectionMonitor(viewModelScope, productCache.shared::checkConnection)
    val sharedConnection = sharedConnectionMonitor.status
    private val releaseUpdateMonitor = ReleaseUpdateMonitor(viewModelScope) {
        cabinetApplication.releaseUpdates.check(BuildConfig.VERSION_NAME)
    }
    val releaseUpdate = releaseUpdateMonitor.status
    fun checkReleaseUpdate() = releaseUpdateMonitor.check()
    fun testSharedConnection() {
        val settings = sharedSettings.value
        if (settings.enabled && settings.available) sharedConnectionMonitor.check(settings.address)
    }
    fun setSharedEnabled(enabled: Boolean) {
        cabinetApplication.serverPreferences.setEnabled(enabled)
        sharedConnectionMonitor.clear()
        if (enabled && sharedStatus.value.pendingCount > 0) ServerCacheWorker.schedule(getApplication())
        else if (!enabled) ServerCacheWorker.cancel(getApplication())
    }
    fun retrySharedUpload() {
        val address = cabinetApplication.serverPreferences.automaticAddress() ?: return
        productCache.shared.store.resetAttempts(ServerCacheCredentials(address, "").partition)
        ServerCacheWorker.schedule(getApplication())
    }
    private val apiUsageStore = ApiUsageStore(application)
    private val apiQueries = BarcodeApiQueries(lookupPreferences, apiUsageStore)
    private val productLookup = ProductLookup(cabinetApplication.catalog, lookupPreferences, cache = productCache, apiQueries = apiQueries)
    val apiUsage = apiUsageStore.usage
    private val _checkingService = MutableStateFlow<BarcodeService?>(null)
    val checkingService = _checkingService.asStateFlow()
    val lookupSettings = lookupPreferences.settings
    fun setLookupEnabled(enabled: Boolean) = lookupPreferences.setEnabled(enabled)
    fun setMxnzpEnabled(enabled: Boolean) = lookupPreferences.setMxnzpEnabled(enabled)
    fun saveMxnzpCredentials(appId: String, appSecret: String) {
        val saveIntent = lookupPreferences.beginMxnzpSave()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { lookupPreferences.saveMxnzp(appId, appSecret, saveIntent) }
                apiUsageStore.invalidateConnectionTest(BarcodeService.MXNZP)
                messageChannel.send("MXNZP 认证已保存，尚未验证；可点击“检测 MXNZP 连接”核对。")
            } catch (_: IllegalArgumentException) { messageChannel.send("认证格式不正确，请核对 MXNZP 的 app_id 和 app_secret。") }
            catch (_: CredentialStorageException) { messageChannel.send("MXNZP 认证未能确认保存，请重试并核对设置中的认证状态。") }
        }
    }
    fun saveLookupAppCode(code: String) {
        val saveIntent = lookupPreferences.beginAppCodeSave()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { lookupPreferences.saveAppCode(code, saveIntent) }
                apiUsageStore.invalidateConnectionTest(BarcodeService.ALIYUN)
                messageChannel.send("阿里云认证已保存，尚未验证；可点击“检测阿里云连接”核对。")
            } catch (_: IllegalArgumentException) { messageChannel.send("AppCode 格式不正确，请在阿里云云市场核对。") }
            catch (_: CredentialStorageException) { messageChannel.send("阿里云认证未能确认保存，请重试并核对设置中的认证状态。") }
        }
    }
    fun testApiConnection(service: BarcodeService, code: String) {
        if (_checkingService.value != null) return
        _checkingService.value = service
        viewModelScope.launch {
            try {
                val result = apiQueries.lookup(service, code, testing = true)
                messageChannel.send(result?.attempts?.lastOrNull()?.description(testing = true)
                    ?: (result as? ProductLookupResult.Manual)?.explanation ?: "请先保存${service.label}认证，本次未发送请求。")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { messageChannel.send("连接检测未完成，请核对网络及本机保存状态后重试。") }
            finally { _checkingService.value = null }
        }
    }
    private val _scanLookup = MutableStateFlow<ScanLookupState>(ScanLookupState.Idle)
    val scanLookup = _scanLookup.asStateFlow()
    private var lookupJob: Job? = null
    private var pendingScan: ParsedBarcode? = null

    private fun scanRequest(parsed: ParsedBarcode) = EditorRequest(barcode = parsed.productCode,
        lotNumber = parsed.lotNumber.orEmpty(), expiryDate = parsed.expiryDate.orEmpty(), fromScan = true)

    fun lookupScan(parsed: ParsedBarcode) {
        cancelScanLookup()
        val medicine = snapshot.value.medicines.find { it.barcode == parsed.productCode }
        if (medicine != null) {
            _scanLookup.value = ScanLookupState.Ready(scanRequest(parsed).copy(medicineId = medicine.id))
            return
        }
        pendingScan = parsed
        _scanLookup.value = ScanLookupState.Loading
        lookupJob = viewModelScope.launch {
            val result = try { productLookup.lookup(parsed.productCode) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ProductLookupResult.Manual("资料查询失败，请核对包装后填写。") }
            ensureActive()
            val request = when (result) {
                is ProductLookupResult.Found -> scanRequest(parsed).copy(suggestedName = result.product.name,
                    suggestedSpecification = result.product.specification, suggestedUnit = result.product.packageUnit,
                    // 内置来源及资料日期继续隐藏；实际 API 调用另用结果卡展示。
                    sourceNote = buildString {
                        if (result.product.manufacturer.isNotBlank()) append("厂家：${result.product.manufacturer}")
                        if (result.product.approval.isNotBlank()) {
                            if (isNotEmpty()) append('\n')
                            append("批准文号：${result.product.approval}")
                        }
                    }, apiAttempts = result.attempts)
                is ProductLookupResult.Manual -> scanRequest(parsed).copy(
                    sourceNote = if (result.attempts.isEmpty()) result.explanation else "未取得可用资料，请核对包装后手动填写。",
                    apiAttempts = result.attempts)
            }
            _scanLookup.value = ScanLookupState.Ready(request)
            pendingScan = null
        }
    }

    fun useManualScanEntry() {
        val parsed = pendingScan ?: return
        cancelScanLookup()
        _scanLookup.value = ScanLookupState.Ready(scanRequest(parsed))
    }
    fun consumeScanResult() { _scanLookup.value = ScanLookupState.Idle }
    fun cancelScanLookup() {
        lookupJob?.cancel()
        lookupJob = null
        pendingScan = null
        _scanLookup.value = ScanLookupState.Idle
    }
    val showExpiryDays = displayPreferences.showExpiryDays
    fun setShowExpiryDays(show: Boolean) = displayPreferences.setShowExpiryDays(show)
    val interfaceSize = displayPreferences.interfaceSize
    fun setInterfaceSize(size: InterfaceSize) = displayPreferences.setInterfaceSize(size)
    val medicineSort = displayPreferences.medicineSort
    fun setMedicineSort(sort: MedicineSort) = displayPreferences.setMedicineSort(sort)
    private val _reminderHealth = MutableStateFlow(ReminderNotifications.inspect(application))
    val reminderHealth = _reminderHealth.asStateFlow()
    val reminderHistory = cabinetApplication.reminderDiagnostics.history
    private val _testNotification = MutableStateFlow<TestNotificationResult?>(null)
    val testNotification = _testNotification.asStateFlow()
    fun sendTestNotification() {
        _testNotification.value = ReminderNotifications.sendTest(getApplication())
        _reminderHealth.value = ReminderNotifications.inspect(getApplication())
    }
    fun checkRemindersNow() {
        ReminderScheduler.checkSoon(getApplication(), ReminderCheckSource.MANUAL)
        viewModelScope.launch { messageChannel.send("已安排检查，请查看实际检查记录；系统可能延后执行。") }
    }
    val snapshot = repository.snapshots.stateIn(viewModelScope, SharingStarted.Eagerly, AppSnapshot())
    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _pendingImport = MutableStateFlow<LocalBackupV1?>(null)
    val pendingImport = _pendingImport.asStateFlow()
    private var pendingExport: String? = null

    init { refresh() }

    fun refresh() {
        apiUsageStore.refresh()
        _reminderHealth.value = ReminderNotifications.inspect(getApplication())
        viewModelScope.launch {
            try {
                // 密钥访问和配置落盘在后台执行，避免返回应用或保存认证时阻塞界面。
                withContext(Dispatchers.IO) {
                    lookupPreferences.refresh()
                    cabinetApplication.serverPreferences.refresh()
                }
                repository.refresh()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { messageChannel.send("药箱读取失败，请重新打开应用。") }
        }
    }

    private fun change(success: String, onSuccess: () -> Unit = {}, action: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                action()
                ReminderScheduler.checkSoon(getApplication())
                onSuccess()
                if (success.isNotBlank()) messageChannel.send(success)
            } catch (error: Exception) {
                messageChannel.send(error.message ?: "操作失败，请稍后重试。")
            } finally { _busy.value = false }
        }
    }

    fun saveRecord(medicine: Medicine, batch: StockBatch, completeShopping: Boolean, onSuccess: () -> Unit) =
        change("已记入药箱", onSuccess) {
            repository.saveRecord(medicine, batch, completeShopping)
            rememberConfirmed(medicine)
        }
    fun updateMedicine(medicine: Medicine, onSuccess: () -> Unit) =
        change("药品资料已更新", onSuccess) {
            repository.updateMedicine(medicine)
            rememberConfirmed(medicine)
        }
    private suspend fun rememberConfirmed(medicine: Medicine) {
        try { productCache.rememberConfirmed(medicine) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { messageChannel.send("药箱已保存，本机条码记忆暂未更新。") }
    }
    fun updateBatch(batch: StockBatch, onSuccess: () -> Unit) =
        change("批次已更新", onSuccess) { repository.updateBatch(batch) }
    fun adjustQuantity(batch: StockBatch, delta: Int) = change("") { repository.adjustQuantity(batch.id, delta) }
    fun clearBatch(batch: StockBatch) = change("此批次已记为零库存，历史记录保留") { repository.clearBatch(batch.id) }
    fun deleteMedicine(medicine: Medicine, onSuccess: () -> Unit) = change("药品及关联记录已删除", onSuccess) {
        repository.deleteMedicine(medicine.id)
        ReminderScheduler.dismissCurrentNotification(getApplication())
    }
    fun deleteBatch(batch: StockBatch, onSuccess: () -> Unit) = change("批次已删除，库存已重新计算", onSuccess) {
        repository.deleteBatch(batch.id)
        ReminderScheduler.dismissCurrentNotification(getApplication())
    }
    fun setShoppingQuantity(item: ShoppingItem, quantity: Int) = change("") {
        repository.setShoppingQuantity(item.medicineId, quantity)
    }
    fun dismissShopping(item: ShoppingItem) = change("已忽略本次；状态变化后会重新提醒") {
        repository.dismissShopping(item.medicineId)
    }
    fun reactivateShopping(item: ShoppingItem) = change("已重新加入补货清单") {
        repository.reactivateShopping(item.medicineId)
    }
    fun updateSettings(settings: ReminderSettings) = change("提醒设置已保存") { repository.updateSettings(settings) }

    /** 先形成可恢复的完整备份，再打开文件选择器，避免创建无法导回的备份。 */
    fun prepareExport(onReady: () -> Unit) {
        if (pendingExport != null) return
        change("", {
            try { onReady() }
            catch (error: Exception) { pendingExport = null; throw error }
        }) {
            pendingExport = repository.exportBackup(showExpiryDays.value, interfaceSize.value)
        }
    }

    fun cancelExport() { pendingExport = null }

    fun exportTo(uri: Uri) = change("备份已保存到你选择的位置") {
        val text = pendingExport ?: error("备份预检已失效，请重新点击保存药箱备份。")
        pendingExport = null
        withContext(Dispatchers.IO) {
            val stream = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                ?: error("无法写入所选文件，请换一个保存位置。")
            stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }
    }

    fun previewImport(uri: Uri) = change("") {
        val text = withContext(Dispatchers.IO) {
            val input = getApplication<Application>().contentResolver.openInputStream(uri)
                ?: error("无法读取所选文件。")
            input.use {
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= BackupCodec.MAX_BYTES) { BackupCodec.CAPACITY_ERROR }
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
        }
        _pendingImport.value = withContext(Dispatchers.Default) { BackupCodec.decode(text) }
    }
    fun cancelImport() { _pendingImport.value = null }
    fun restoreImport() {
        val backup = _pendingImport.value ?: return
        change("药箱已恢复，提醒规则已重新计算", { _pendingImport.value = null }) {
            repository.restore(backup)
            displayPreferences.setShowExpiryDays(backup.showExpiryDays)
            displayPreferences.setInterfaceSize(backup.interfaceSize)
        }
    }
}
