package app.medicinecabinet.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import app.medicinecabinet.domain.*
import app.medicinecabinet.reminders.ReminderScheduler
import app.medicinecabinet.ui.forms.*
import app.medicinecabinet.ui.scanner.ScannerScreen
import app.medicinecabinet.ui.screens.*
import app.medicinecabinet.ui.components.DeleteRecordDialog
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Composable
fun CabinetApp(viewModel: CabinetViewModel, shoppingRequest: Int = 0) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val showExpiryDays by viewModel.showExpiryDays.collectAsStateWithLifecycle()
    val interfaceSize by viewModel.interfaceSize.collectAsStateWithLifecycle()
    val medicineSort by viewModel.medicineSort.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val pendingImport by viewModel.pendingImport.collectAsStateWithLifecycle()
    val scanLookup by viewModel.scanLookup.collectAsStateWithLifecycle()
    val lookupSettings by viewModel.lookupSettings.collectAsStateWithLifecycle()
    val apiUsage by viewModel.apiUsage.collectAsStateWithLifecycle()
    val checkingService by viewModel.checkingService.collectAsStateWithLifecycle()
    val sharedSettings by viewModel.sharedSettings.collectAsStateWithLifecycle()
    val sharedStatus by viewModel.sharedStatus.collectAsStateWithLifecycle()
    val sharedConnection by viewModel.sharedConnection.collectAsStateWithLifecycle()
    val releaseUpdate by viewModel.releaseUpdate.collectAsStateWithLifecycle()
    val reminderHealth by viewModel.reminderHealth.collectAsStateWithLifecycle()
    val reminderHistory by viewModel.reminderHistory.collectAsStateWithLifecycle()
    val testNotification by viewModel.testNotification.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var cabinetFilterName by rememberSaveable { mutableStateOf(MedicineFilter.ALL.name) }
    val cabinetFilter = MedicineFilter.entries.find { it.name == cabinetFilterName } ?: MedicineFilter.ALL
    var scanning by rememberSaveable { mutableStateOf(false) }
    var editorJson by rememberSaveable { mutableStateOf<String?>(null) }
    var medicineEditorId by rememberSaveable { mutableStateOf<String?>(null) }
    var clearingBatchId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingMedicineId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingBatchId by rememberSaveable { mutableStateOf<String?>(null) }
    var today by remember { mutableStateOf(LocalDate.now()) }
    var notificationsAllowed by remember { mutableStateOf(ReminderScheduler.notificationsAllowed(context)) }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsAllowed = ReminderScheduler.notificationsAllowed(context)
        viewModel.refresh()
        if (it) ReminderScheduler.checkSoon(context)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        if (it != null) viewModel.exportTo(it)
        else viewModel.cancelExport()
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) viewModel.previewImport(it)
    }
    fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
            Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }
    fun openEditor(request: EditorRequest) { editorJson = Json.encodeToString(request) }

    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(scanLookup) {
        (scanLookup as? ScanLookupState.Ready)?.let {
            openEditor(it.request)
            viewModel.consumeScanResult()
        }
    }
    LaunchedEffect(shoppingRequest) { if (shoppingRequest > 0) tab = 2 }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            val current = LocalDate.now()
            if (today != current) { today = current; viewModel.refresh() }
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                today = LocalDate.now()
                notificationsAllowed = ReminderScheduler.notificationsAllowed(context)
                viewModel.refresh()
                // 返回前台时补查提醒；已有去重规则控制未处理状态的再通知间隔。
                ReminderScheduler.checkSoon(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val request = remember(editorJson) { editorJson?.let { Json.decodeFromString<EditorRequest>(it) } }
    if (request != null) {
        Box(Modifier.fillMaxSize()) {
            RecordEditor(request, snapshot, busy, { editorJson = null }) { medicine, batch, replenish ->
                val onSuccess = {
                    editorJson = null
                    if (snapshot.medicines.isEmpty() && snapshot.settings.enabled && !notificationsAllowed) askNotifications()
                }
                if (request.batchId != null) viewModel.updateBatch(batch, onSuccess)
                else viewModel.saveRecord(medicine, batch, replenish, onSuccess)
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 84.dp))
        }
    } else if (scanning) {
        ScannerScreen({ scanning = false }, { scanning = false; openEditor(EditorRequest()) }) { parsed ->
            scanning = false
            viewModel.lookupScan(parsed)
        }
    } else {
        BackHandler(enabled = tab != 0) { tab = 0; cabinetFilterName = MedicineFilter.ALL.name }
        val labels = listOf("总览", "药箱", "补货", "设置")
        val icons = listOf(Icons.Outlined.Home, Icons.Outlined.Inventory2, Icons.Outlined.ShoppingBag, Icons.Outlined.Settings)
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                labels.forEachIndexed { index, label ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index; cabinetFilterName = MedicineFilter.ALL.name },
                        icon = { Icon(icons[index], null) }, label = { Text(label) })
                }
            }
        }, floatingActionButton = {
            if (tab == 1 && cabinetFilter == MedicineFilter.ALL) ExtendedFloatingActionButton(onClick = { scanning = true },
                icon = { Icon(Icons.Outlined.QrCodeScanner, null) }, text = { Text("扫码录入") })
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
                    when (tab) {
                        0 -> HomeScreen(snapshot, today, showExpiryDays, { scanning = true }, { openEditor(EditorRequest()) },
                            { tab = 2 }, { cabinetFilterName = MedicineFilter.ALL.name; tab = 1 },
                            { cabinetFilterName = MedicineFilter.EXPIRING.name; tab = 1 },
                            { cabinetFilterName = MedicineFilter.EXPIRED.name; tab = 1 })
                        1 -> CabinetScreen(snapshot, today, showExpiryDays, busy,
                            { openEditor(EditorRequest(medicineId = it?.id)) }, { medicineEditorId = it.id },
                            { openEditor(EditorRequest(medicineId = it.medicineId, batchId = it.id)) },
                            viewModel::adjustQuantity, { clearingBatchId = it.id },
                            { deletingMedicineId = it.id }, { deletingBatchId = it.id }, cabinetFilter, medicineSort,
                            viewModel::setMedicineSort, { tab = 0; cabinetFilterName = MedicineFilter.ALL.name })
                        2 -> ShoppingScreen(snapshot, today, showExpiryDays, busy,
                            { medicine, item -> openEditor(EditorRequest(medicineId = medicine.id,
                                quantity = item.requestedQuantity, replenish = true)) },
                            viewModel::setShoppingQuantity, viewModel::dismissShopping, viewModel::reactivateShopping)
                        3 -> SettingsScreen(snapshot.settings, showExpiryDays, interfaceSize, notificationsAllowed, busy, viewModel::updateSettings,
                            ::askNotifications, {
                                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                            }, { viewModel.prepareExport { exportLauncher.launch("家庭药箱-$today.backup.json") } },
                            { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                            viewModel::setShowExpiryDays, viewModel::setInterfaceSize, lookupSettings,
                            viewModel::setLookupEnabled, viewModel::saveLookupAppCode,
                            viewModel::setMxnzpEnabled, viewModel::saveMxnzpCredentials,
                            apiUsage, checkingService, viewModel::testApiConnection,
                            sharedSettings, sharedStatus, viewModel::setSharedEnabled, viewModel::retrySharedUpload,
                            sharedConnection, viewModel::testSharedConnection, reminderHealth, reminderHistory,
                            testNotification, viewModel::sendTestNotification, viewModel::checkRemindersNow,
                            updateState = releaseUpdate, onCheckUpdate = viewModel::checkReleaseUpdate)
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }

    if (scanLookup == ScanLookupState.Loading) AlertDialog(onDismissRequest = viewModel::cancelScanLookup,
        title = { Text("正在查找条码资料") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("先查本机与内置资料，再使用已配置的补充查询。请核对包装信息后保存到药箱。")
            }
        }, confirmButton = { TextButton(onClick = viewModel::useManualScanEntry) { Text("改为手动填写") } },
        dismissButton = { TextButton(onClick = viewModel::cancelScanLookup) { Text("取消录入") } })

    snapshot.medicines.find { it.id == medicineEditorId }?.let { medicine ->
        MedicineEditor(medicine, busy, { medicineEditorId = null }) {
            viewModel.updateMedicine(it) { medicineEditorId = null }
        }
    }
    snapshot.batches.find { it.id == clearingBatchId }?.let { batch ->
        AlertDialog(onDismissRequest = { clearingBatchId = null }, title = { Text("确认已清理此批次？") },
            text = { Text("将库存数量记为 0，并保留历史信息。请在实际清理之后确认。") },
            confirmButton = { TextButton(onClick = { clearingBatchId = null; viewModel.clearBatch(batch) }, enabled = !busy) { Text("已清理，记为零") } },
            dismissButton = { TextButton(onClick = { clearingBatchId = null }) { Text("取消") } })
    }
    snapshot.medicines.find { it.id == deletingMedicineId }?.let { medicine ->
        val count = snapshot.batches.count { it.medicineId == medicine.id }
        DeleteRecordDialog("删除${medicine.name}？",
            "将删除此药品、关联的 $count 个批次、补货清单及提醒记录。\n\n操作无法撤销，可先在设置中保存药箱备份。",
            busy, { deletingMedicineId = null }) { viewModel.deleteMedicine(medicine) { deletingMedicineId = null } }
    }
    snapshot.batches.find { it.id == deletingBatchId }?.let { batch ->
        val medicine = snapshot.medicines.find { it.id == batch.medicineId }
        DeleteRecordDialog("删除此批次？",
            "${medicine?.name.orEmpty()}\n有效期：${batch.expiryDate?.let(ExpiryDates::display) ?: "未确认"}\n数量：${batch.quantity} ${medicine?.packageUnit.orEmpty()}\n\n仅删除此批次，保留药品与其他批次，并重新计算库存和提醒。操作无法撤销，可先保存备份。",
            busy, { deletingBatchId = null }) { viewModel.deleteBatch(batch) { deletingBatchId = null } }
    }
    pendingImport?.let { backup ->
        AlertDialog(onDismissRequest = { if (!busy) viewModel.cancelImport() }, title = { Text("恢复这份药箱备份？") },
            text = { Text("备份日期：${backup.exportedAt.take(10)}\n药品：${backup.medicines.size} 种\n批次：${backup.batches.size} 个\n\n确认后会替换当前药箱的全部记录。建议先保存当前药箱备份。") },
            confirmButton = { TextButton(onClick = viewModel::restoreImport, enabled = !busy) { Text("确认替换并恢复") } },
            dismissButton = { TextButton(onClick = viewModel::cancelImport, enabled = !busy) { Text("取消") } })
    }
}
