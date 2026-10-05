package app.medicinecabinet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.medicinecabinet.domain.ReminderSettings
import app.medicinecabinet.domain.InterfaceSize
import app.medicinecabinet.data.LookupSettings
import app.medicinecabinet.data.ApiUsage
import app.medicinecabinet.data.ServerCacheSettings
import app.medicinecabinet.data.CacheSyncStatus
import app.medicinecabinet.data.ServerConnectionState
import app.medicinecabinet.data.ReleaseUpdateState
import app.medicinecabinet.domain.BarcodeService
import app.medicinecabinet.ui.components.*
import app.medicinecabinet.ui.forms.InputField
import app.medicinecabinet.reminders.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(settings: ReminderSettings, showExpiryDays: Boolean, interfaceSize: InterfaceSize,
    notificationsAllowed: Boolean, busy: Boolean,
    onSave: (ReminderSettings) -> Unit, onRequestPermission: () -> Unit, onSystemSettings: () -> Unit,
    onExport: () -> Unit, onImport: () -> Unit, onExpiryDisplayChange: (Boolean) -> Unit,
    onInterfaceSizeChange: (InterfaceSize) -> Unit, lookupSettings: LookupSettings = LookupSettings(),
    onLookupEnabledChange: (Boolean) -> Unit = {}, onSaveLookupAppCode: (String) -> Unit = {},
    onMxnzpEnabledChange: (Boolean) -> Unit = {}, onSaveMxnzp: (String, String) -> Unit = { _, _ -> },
    apiUsage: Map<BarcodeService, ApiUsage> = emptyMap(), checkingService: BarcodeService? = null,
    onTestApi: (BarcodeService, String) -> Unit = { _, _ -> },
    sharedSettings: ServerCacheSettings = ServerCacheSettings(), sharedStatus: CacheSyncStatus = CacheSyncStatus(),
    onSharedEnabled: (Boolean) -> Unit = {}, onRetryShared: () -> Unit = {},
    sharedConnection: ServerConnectionState = ServerConnectionState(), onCheckShared: () -> Unit = {},
    reminderHealth: ReminderHealth = ReminderHealth(), reminderHistory: ReminderHistory = ReminderHistory(),
    testNotification: TestNotificationResult? = null, onTestNotification: () -> Unit = {}, onCheckReminders: () -> Unit = {},
    updateState: ReleaseUpdateState = ReleaseUpdateState(), onCheckUpdate: () -> Unit = {}) {
    var days by rememberSaveable(settings.expiryLeadDays) { mutableStateOf(settings.expiryLeadDays.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 16.dp, 20.dp, 40.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { PageHeading("设置", "把提醒调成适合你的节奏") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("显示方式", style = MaterialTheme.typography.titleLarge)
                    PreferenceToggle("显示剩余天数", "开启时显示“剩余 12 天”等天数；关闭后显示状态提示。有效期日期始终保留。",
                        showExpiryDays, !busy, onExpiryDisplayChange)
                    HorizontalDivider()
                    Text("界面大小", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        InterfaceSize.entries.forEach { size ->
                            FilterChip(selected = interfaceSize == size, onClick = { onInterfaceSizeChange(size) },
                                label = { Text(size.label) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                    Text("当前缩放 ${(interfaceSize.scale * 100).toInt()}% · 跟随系统字号，按钮保留足够的点击区域。",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("整理提醒", style = MaterialTheme.typography.titleLarge)
                    PreferenceToggle("本机通知", "状态出现时提醒，未处理每周再提醒一次", settings.enabled, !busy) {
                            onSave(settings.copy(enabled = it))
                            if (it && !notificationsAllowed) onRequestPermission()
                    }
                    Text("当前默认：剩余 0～${settings.expiryLeadDays} 天算临期", style = MaterialTheme.typography.titleMedium)
                    Text("包含到期当天，次日起显示过期。可设置 1～365 天；单个药品设置的提醒天数优先。",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    InputField("临期提前提醒天数", days, { days = it; error = null }, error, numeric = true)
                    Button(onClick = {
                        val number = days.toIntOrNull()
                        if (number == null || number !in 1..365) error = "请输入 1 至 365 天"
                        else onSave(settings.copy(expiryLeadDays = number))
                    }, enabled = !busy) { Text("保存提醒设置") }
                    Text("每天检查药箱；系统省电策略可能延后通知。应用内的待处理状态会持续保留。",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (!notificationsAllowed) item {
            InfoBanner("系统尚未允许通知，当前只能在应用内查看提醒。", true, "允许通知", onRequestPermission)
            TextButton(onClick = onSystemSettings) { Text("打开系统通知设置") }
        }
        item { ReminderHealthCard(reminderHealth, reminderHistory, testNotification, onTestNotification, onCheckReminders, onSystemSettings) }
        item { ReminderHelpCard() }
        item { ScanInfoCard() }
        item { ServerCacheSettingsCard(sharedSettings, sharedStatus, busy, onSharedEnabled, onRetryShared, sharedConnection, onCheckShared) }
        item { LookupSettingsCard(lookupSettings, busy || checkingService != null, onLookupEnabledChange, onSaveLookupAppCode,
            apiUsage[BarcodeService.ALIYUN] ?: ApiUsage(), checkingService == BarcodeService.ALIYUN) {
            onTestApi(BarcodeService.ALIYUN, it)
        } }
        item { MxnzpSettingsCard(lookupSettings, busy || checkingService != null, onMxnzpEnabledChange, onSaveMxnzp,
            apiUsage[BarcodeService.MXNZP] ?: ApiUsage(), checkingService == BarcodeService.MXNZP) {
            onTestApi(BarcodeService.MXNZP, it)
        } }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("备份与恢复", style = MaterialTheme.typography.titleLarge)
                    Text("药箱数据保存在当前设备。换手机前，先保存一份备份。",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilledTonalButton(onClick = onExport, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.FileDownload, null); Spacer(Modifier.width(8.dp)); Text("保存药箱备份")
                    }
                    OutlinedButton(onClick = onImport, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.FileUpload, null); Spacer(Modifier.width(8.dp)); Text("从备份恢复")
                    }
                    Text("备份包含药品和库存记录，请自行保管。恢复前会显示摘要，并要求确认替换当前药箱。",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { AboutCard(updateState, onCheckUpdate) }
    }
}
