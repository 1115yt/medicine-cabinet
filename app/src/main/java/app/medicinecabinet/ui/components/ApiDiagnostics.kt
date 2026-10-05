package app.medicinecabinet.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.medicinecabinet.data.ApiUsage
import app.medicinecabinet.domain.*

@Composable
fun ApiDiagnostics(service: BarcodeService, usage: ApiUsage, configured: Boolean, busy: Boolean,
    checking: Boolean, onTest: (String) -> Unit) {
    var asking by remember { mutableStateOf(false) }
    HorizontalDivider()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (checking) "正在检测 ${service.label}…" else usage.connectionTest?.description(testing = true) ?: "尚未检测连接",
            color = if (usage.connectionTest?.usableConnection == true) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (usage.connectionTestAt.isNotBlank()) Text("上次检测：${usage.connectionTestAt}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        usage.lastResult?.let { Text("最近请求 · ${it.description()}", style = MaterialTheme.typography.bodyMedium) }
        Text("${service.label} · 本机今日 ${usage.todayRequests} 次 · 累计 ${usage.totalRequests} 次", style = MaterialTheme.typography.titleSmall)
        Text("包含查询失败和连接检测；不代表服务商扣费次数或账号剩余额度。",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { asking = true }, enabled = configured && !busy && !checking,
            modifier = Modifier.heightIn(min = 48.dp)) { Text(if (checking) "正在检测…" else "检测${service.label}连接") }
    }
    if (asking) AlertDialog(onDismissRequest = { asking = false }, title = { Text("检测${service.label}连接") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("将向${service.label}发送 1 次真实查询，可能消耗额度或产生费用。只检测这一家服务，查询结果不会添加到药箱。")
                HorizontalDivider()
                Text("测试条码：$CONNECTION_TEST_BARCODE", style = MaterialTheme.typography.titleSmall)
                Text("这是公开接口文档中的示例条码；即使服务端未收录，也能根据响应判断连接状态。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }, confirmButton = { TextButton(onClick = {
            asking = false
            onTest(CONNECTION_TEST_BARCODE)
        }, enabled = !busy && !checking) { Text("确认发送 1 次查询") } },
        dismissButton = { TextButton(onClick = { asking = false }) { Text("取消") } })
}

private const val CONNECTION_TEST_BARCODE = "6902538005141"

/** 只展示本次真实联网查询；内置资料及本机命中不带服务来源提示。 */
@Composable
fun ApiResultCard(attempts: List<ApiAttempt>) {
    if (attempts.isEmpty()) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("本次 API 查询", style = MaterialTheme.typography.titleSmall)
            attempts.forEach { attempt ->
                Text(attempt.description(), style = MaterialTheme.typography.bodyMedium,
                    color = if (attempt.outcome == ApiOutcome.FOUND) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            }
            Text("本次发起 ${attempts.size} 次请求；次数已计入设置页的本机统计。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(attempts.groupingBy { it.service }.eachCount().entries.joinToString(" · ") { (service, count) ->
                "${service.label} +$count 次"
            }, style = MaterialTheme.typography.titleSmall)
        }
    }
}
