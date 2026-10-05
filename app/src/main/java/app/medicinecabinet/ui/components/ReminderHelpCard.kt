package app.medicinecabinet.ui.components

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** 只打开本应用的系统设置，通知和电池策略由用户自行调整。 */
@Composable
fun ReminderHelpCard() {
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("后台提醒说明", style = MaterialTheme.typography.titleLarge)
            Text("每天后台检查药箱。省电、休眠或后台限制可能延迟通知，无法保证在固定时刻提醒。")
            Text("请在手机系统中允许本应用通知，并检查“药箱整理提醒”通知类别。电池设置建议选“不受限制”或“允许后台运行”；有自启动选项时可开启。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("强制停止后需重新打开应用。打开药箱也会重新检查提醒，待处理状态一直保留在应用内；解除省电限制仍不能保证准点。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")))
            }, modifier = Modifier.fillMaxWidth()) { Text("打开应用系统设置") }
        }
    }
}
