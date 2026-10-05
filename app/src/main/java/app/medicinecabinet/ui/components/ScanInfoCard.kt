package app.medicinecabinet.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 扫码说明只展示使用方法；资料统计和导出日期留在开发文档中。 */
@Composable
fun ScanInfoCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("扫码资料", style = MaterialTheme.typography.titleLarge)
            Text("扫码后自动填写药品名称和规格，请核对包装信息后保存。",
                style = MaterialTheme.typography.bodyMedium)
            Text("支持离线查询；未找到资料时，可使用已启用的补充查询。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("共享资料开启时先查询已收录的条码；未取得资料才依次调用已启用的 MXNZP、阿里云。两家只统计各自实际发起的请求。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
