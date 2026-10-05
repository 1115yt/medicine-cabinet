package app.medicinecabinet.reminders

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.medicinecabinet.MainActivity
import app.medicinecabinet.R

data class ReminderHealth(val permissionGranted: Boolean = true, val appNotificationsEnabled: Boolean = true,
    val channelEnabled: Boolean = true, val batteryExempt: Boolean? = null, val backgroundRestricted: Boolean? = null) {
    val notificationsAllowed: Boolean get() = permissionGranted && appNotificationsEnabled && channelEnabled
}

enum class TestNotificationResult(val message: String) {
    POSTED("测试通知已提交给系统，请到通知栏核对；这不能证明后台检查一定按时运行。"),
    BLOCKED("系统通知未允许，请先检查通知权限及“药箱整理提醒”类别。"),
    FAILED("测试通知未能提交，请检查系统设置后重试。"),
}

object ReminderNotifications {
    const val TEST_ID = 101
    fun inspect(context: Context): ReminderHealth = ReminderHealth(
        permissionGranted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context,
            Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        appNotificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        channelEnabled = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(ReminderScheduler.CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE,
        batteryExempt = runCatching { context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName) }.getOrNull(),
        backgroundRestricted = if (Build.VERSION.SDK_INT >= 28) runCatching {
            context.getSystemService(ActivityManager::class.java).isBackgroundRestricted }.getOrNull() else null,
    )

    fun ensureChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(
            ReminderScheduler.CHANNEL_ID, "药箱整理提醒", NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "临期和库存不足的补货提醒" })
    }

    /** 使用与真实提醒相同的类别、独立通知 ID，不修改库存和通知去重记录。 */
    fun sendTest(context: Context): TestNotificationResult = try {
        ensureChannel(context)
        if (!inspect(context).notificationsAllowed) TestNotificationResult.BLOCKED else {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pending = PendingIntent.getActivity(context, TEST_ID, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle("家庭药箱 · 测试通知")
                .setContentText("看到这条通知，说明当前通知可以显示。后台检查仍可能受手机省电影响。")
                .setContentIntent(pending).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
            context.getSystemService(NotificationManager::class.java).notify(TEST_ID, notification)
            TestNotificationResult.POSTED
        }
    } catch (_: SecurityException) { TestNotificationResult.BLOCKED }
    catch (_: Exception) { TestNotificationResult.FAILED }
}
