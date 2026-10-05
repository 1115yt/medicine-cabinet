package app.medicinecabinet.reminders

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import app.medicinecabinet.CabinetApplication
import app.medicinecabinet.MainActivity
import app.medicinecabinet.R
import app.medicinecabinet.domain.AlertReason
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

object ReminderScheduler {
    const val CHANNEL_ID = "cabinet_reminders"
    const val SOURCE_KEY = "reminder-check-source"
    fun dismissCurrentNotification(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(100)
    }
    fun scheduleDaily(context: Context) {
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
            .setInputData(workDataOf(SOURCE_KEY to ReminderCheckSource.DAILY.name)).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "cabinet-daily-review", ExistingPeriodicWorkPolicy.KEEP, request,
        )
    }
    fun checkSoon(context: Context, source: ReminderCheckSource = ReminderCheckSource.RECHECK) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "cabinet-current-review", if (source == ReminderCheckSource.MANUAL) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ReminderWorker>().setInputData(workDataOf(SOURCE_KEY to source.name)).build(),
        )
    }
    fun notificationsAllowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context,
            Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            context.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val application = applicationContext as CabinetApplication
        val repository = application.repository
        val diagnostics = application.reminderDiagnostics
        // 旧周期任务没有来源字段，仍归入每日检查；前台补查单独记录，避免掩盖周期检查未运行。
        val source = ReminderCheckSource.entries.find { it.name == inputData.getString(ReminderScheduler.SOURCE_KEY) }
            ?: ReminderCheckSource.DAILY
        var startedRecord: ReminderCheckRecord? = null
        var postedCount = 0
        return try {
            ReminderCheckCoordinator.run {
                // 取得门禁后才算实际开始；等待或取消等待不覆盖正在执行工作的诊断。
                val record = diagnostics.start(id.toString(), source)
                startedRecord = record
                // 等待中的工作进入锁后重新读取，不复用另一轮发送前取得的提醒列表。
                val today = LocalDate.now()
                repository.refresh(today)
                if (!repository.snapshot().settings.enabled) {
                    diagnostics.finish(record, ReminderCheckResult.DISABLED)
                    return@run Result.success()
                }
                if (!ReminderScheduler.notificationsAllowed(applicationContext)) {
                    diagnostics.finish(record, ReminderCheckResult.BLOCKED)
                    return@run Result.success()
                }
                val due = repository.dueNotifications(today)
                if (due.isEmpty()) {
                    diagnostics.finish(record, ReminderCheckResult.EMPTY)
                    return@run Result.success()
                }
                val manager = applicationContext.getSystemService(NotificationManager::class.java)
                ReminderNotifications.ensureChannel(applicationContext)
                val intent = Intent(applicationContext, MainActivity::class.java).putExtra("openShopping", true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                val pending = PendingIntent.getActivity(applicationContext, 10, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val nearExpiry = due.count { AlertReason.EXPIRING in it.reasons || AlertReason.EXPIRED in it.reasons }
                val lowStock = due.count { AlertReason.LOW_STOCK in it.reasons }
                val notification = NotificationCompat.Builder(applicationContext, ReminderScheduler.CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("药箱有 ${due.size} 种药品需要整理")
                    .setContentText("临期或过期 $nearExpiry 种，库存不足 $lowStock 种。点击查看补货清单。")
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .build()
                // 发送前再次确认，处理用户在本轮检查期间撤回授权的情况。
                if (!ReminderScheduler.notificationsAllowed(applicationContext)) {
                    diagnostics.finish(record, ReminderCheckResult.BLOCKED)
                    return@run Result.success()
                }
                manager.notify(100, notification)
                postedCount = due.size
                repository.acknowledgeNotifications(due, today)
                diagnostics.finish(record, ReminderCheckResult.POSTED, postedCount)
                Result.success()
            }
        } catch (cancelled: CancellationException) {
            startedRecord?.let { diagnostics.finish(it, ReminderCheckResult.CANCELLED, postedCount) }
            throw cancelled
        } catch (_: SecurityException) {
            // 授权可能在检查后撤回，保留待提醒状态，供下次授权后重试。
            startedRecord?.let { diagnostics.finish(it,
                if (postedCount > 0) ReminderCheckResult.POSTED_RETRY else ReminderCheckResult.BLOCKED, postedCount) }
            if (postedCount > 0) Result.retry() else Result.success()
        } catch (_: Exception) {
            startedRecord?.let { diagnostics.finish(it,
                if (postedCount > 0) ReminderCheckResult.POSTED_RETRY else ReminderCheckResult.RETRY, postedCount) }
            Result.retry()
        }
    }
}
