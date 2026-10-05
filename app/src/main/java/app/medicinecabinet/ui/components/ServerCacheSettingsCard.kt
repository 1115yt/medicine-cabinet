package app.medicinecabinet.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import app.medicinecabinet.data.CacheSyncStatus
import app.medicinecabinet.data.ServerCacheSettings
import app.medicinecabinet.data.ServerConnectionState
import app.medicinecabinet.data.ServerHealthReason
import app.medicinecabinet.security.CredentialProtection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ServerCacheSettingsCard(settings: ServerCacheSettings, status: CacheSyncStatus, busy: Boolean,
    onEnable: (Boolean) -> Unit, onRetry: () -> Unit,
    connection: ServerConnectionState = ServerConnectionState(), onCheck: () -> Unit = {}) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("共享条码资料", style = MaterialTheme.typography.titleLarge)
            Text("复用已收录的基础资料，减少重复查询。新查到的资料和核对后补充的条码信息会自动保存到共享缓存。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PreferenceToggle("使用共享资料", if (settings.available) "联网时自动共享基础资料，断网时暂存并在恢复后补传。"
                else "共享服务暂未启用，可继续使用内置资料和补充 API。", settings.enabled, !busy && settings.available, onEnable)
            HorizontalDivider()
            Text("服务连接", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (connection.checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                val failed = connection.result?.reason?.let { it != ServerHealthReason.CONNECTED } == true
                Text(connectionLabel(settings, connection), style = MaterialTheme.typography.bodyMedium,
                    color = if (failed && settings.enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
            }
            connection.checkedAt?.takeIf { settings.enabled }?.let { time ->
                Text("上次检测：${DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = onCheck, enabled = settings.available && settings.enabled && !busy && !connection.checking,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (connection.checking) "检测中…" else "检测连接")
            }
            Text("检测共享服务与数据库是否可用，不消耗 MXNZP、阿里云查询次数。结果表示上次检测时的连接状态。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (status.pendingCount > 0) {
                Text("待上传 ${status.pendingCount} 条基础资料", style = MaterialTheme.typography.titleSmall)
                OutlinedButton(onClick = onRetry, enabled = settings.enabled && !busy) { Text("重试上传") }
            }
            if (status.message.isNotBlank()) Text(status.message, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (settings.protection in listOf(CredentialProtection.SAVE_PENDING,
                    CredentialProtection.MIGRATION_PENDING, CredentialProtection.UNAVAILABLE,
                    CredentialProtection.CLEANUP_PENDING)) Text(settings.protection.message,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Text("仅共享条码、名称、规格、包装单位、厂家、批准文号和资料来源。库存、有效期、位置及 API 认证留在手机。共享资料仍需核对包装；关闭后停止新的查询和上传，未发送资料保留在本机。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun connectionLabel(settings: ServerCacheSettings, state: ServerConnectionState): String {
    if (!settings.available) return "共享服务暂不可用"
    if (!settings.enabled) return "共享已关闭，开启后可检测连接"
    if (state.checking) return "正在检测共享服务及数据库…"
    val result = state.result ?: return "尚未检测连接"
    val message = when (result.reason) {
        ServerHealthReason.CONNECTED -> "连接成功：共享服务及数据库可用"
        ServerHealthReason.NOT_CONFIGURED -> "共享服务暂不可用"
        ServerHealthReason.DISABLED -> "共享已关闭"
        ServerHealthReason.DNS_FAILURE -> "连接失败：无法解析服务域名，请检查网络"
        ServerHealthReason.TIMEOUT -> "连接超时，请检查网络后重试"
        ServerHealthReason.TLS_FAILURE -> "安全连接失败，请检查手机时间及网络"
        ServerHealthReason.NETWORK_FAILURE -> "连接失败，请检查网络后重试"
        ServerHealthReason.RATE_LIMITED -> "检测过于频繁，请稍后重试"
        ServerHealthReason.SERVICE_UNAVAILABLE -> "共享服务或数据库暂不可用，请稍后重试"
        ServerHealthReason.INVALID_RESPONSE -> "返回内容不符合预期，无法确认服务可用"
        ServerHealthReason.HTTP_ERROR -> "服务返回异常，请稍后重试"
    }
    return message + (result.httpStatus?.let { " · HTTP $it" } ?: "")
}
