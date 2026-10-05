package app.medicinecabinet.ui.components

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.medicinecabinet.reminders.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ReminderHealthCard(health: ReminderHealth, history: ReminderHistory, testResult: TestNotificationResult?,
    onTest: () -> Unit, onCheck: () -> Unit, onNotificationSettings: () -> Unit) {
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("通知自检", style = MaterialTheme.typography.titleLarge)
            Text("先确认通知能显示，再查看后台是否实际运行。返回系统设置后会刷新检测状态。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HealthLine("通知权限", if (health.permissionGranted) "已允许" else "未允许", !health.permissionGranted)
            HealthLine("应用通知", if (health.appNotificationsEnabled) "已开启" else "已关闭", !health.appNotificationsEnabled)
            HealthLine("药箱整理提醒类别", if (health.channelEnabled) "未被关闭" else "已关闭", !health.channelEnabled)
            HealthLine("电池优化", when (health.batteryExempt) {
                true -> "已排除系统电池优化"; false -> "仍受系统电池优化影响"; null -> "无法读取，请在系统设置核对"
            }, health.batteryExempt != true)
            HealthLine("系统后台限制", when (health.backgroundRestricted) {
                true -> "已限制，请允许后台运行"; false -> "未检测到系统后台限制"; null -> "无法读取，请在系统设置核对"
            }, health.backgroundRestricted != false)
            Text("自启动及厂商额外省电限制无法完整检测，需在手机设置中核对。勿扰或静默可能不弹出、不响铃，测试时请查看通知栏。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onNotificationSettings, modifier = Modifier.fillMaxWidth()) { Text("检查系统通知设置") }
            OutlinedButton(onClick = {
                try { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                catch (_: ActivityNotFoundException) {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")))
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("检查系统电池设置") }
            Button(onClick = onTest, modifier = Modifier.fillMaxWidth()) { Text("发送测试通知") }
            testResult?.let { Text(it.message, color = if (it == TestNotificationResult.POSTED)
                MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error) }
            HorizontalDivider()
            Text("实际检查记录", style = MaterialTheme.typography.titleMedium)
            val latest = history.latest
            Text(if (latest == null) "尚未记录检查，请点击“立即检查药箱”。" else buildString {
                append("最近一次：${checkTime(latest.startedAt)} · ${latest.source.label}\n${latest.result.label}")
                if (latest.count > 0) append("（${latest.count} 种药品）")
            })
            Text(history.daily?.let { "最近每日后台检查：${checkTime(it.startedAt)}\n${it.result.label}" }
                ?: "每日后台检查：尚未记录运行。前台补查不代表每日后台检查已运行。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onCheck, modifier = Modifier.fillMaxWidth()) { Text("立即检查药箱") }
            Text("每日检查可能被省电策略延后。记录表示任务实际执行或通知提交，不表示你已看到通知；本次无需新提醒时不会重复通知。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HealthLine(label: String, value: String, needsAttention: Boolean) {
    Text("$label：$value", color = if (needsAttention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
}

private fun checkTime(millis: Long): String = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
    .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
