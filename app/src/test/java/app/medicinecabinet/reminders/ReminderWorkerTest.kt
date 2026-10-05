package app.medicinecabinet.reminders

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import app.medicinecabinet.TestCabinetApplication
import app.medicinecabinet.domain.*
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestCabinetApplication::class)
class ReminderWorkerTest {
    private fun application() = ApplicationProvider.getApplicationContext<TestCabinetApplication>()
    private suspend fun seed(application: TestCabinetApplication) {
        application.repository.saveRecord(Medicine("m", "测试私有药品"),
            StockBatch("b", "m", 1, LocalDate.now().plusDays(5).toString()))
    }
    private suspend fun execute(application: TestCabinetApplication): ListenableWorker.Result =
        TestListenableWorkerBuilder<ReminderWorker>(application).build().doWork()

    @Test fun `allowed notifications post private summary and suppress repeats`() = runBlocking {
        val app = application()
        val manager = app.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(true)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        seed(app)
        assertEquals(ListenableWorker.Result.success(), execute(app))
        val notification = shadowOf(manager).getNotification(100)
        assertNotNull(notification)
        assertFalse(notification.extras.getString(Notification.EXTRA_TITLE)!!.contains("测试私有药品"))
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertTrue(app.repository.dueNotifications(LocalDate.now()).isEmpty())
        manager.cancelAll()
        execute(app)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
    @Test fun `permission denial keeps reminder pending`() = runBlocking {
        val app = application()
        val manager = app.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(true)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        seed(app)
        assertEquals(ListenableWorker.Result.success(), execute(app))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertEquals(1, app.repository.dueNotifications(LocalDate.now()).size)
    }
    @Test fun `disabled setting keeps reminder pending without notification`() = runBlocking {
        val app = application()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(true)
        seed(app)
        app.repository.updateSettings(ReminderSettings(enabled = false))
        assertEquals(ListenableWorker.Result.success(), execute(app))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertEquals(1, app.repository.dueNotifications(LocalDate.now()).size)
    }
    @Test fun `daily scheduling retains exactly one periodic request`() {
        val app = application()
        ReminderScheduler.scheduleDaily(app)
        ReminderScheduler.scheduleDaily(app)
        assertEquals(1, WorkManager.getInstance(app).getWorkInfosForUniqueWork("cabinet-daily-review").get().size)
    }
    @Test fun `blocked notification channel keeps reminder pending`() = runBlocking {
        val app = application()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(true)
        manager.createNotificationChannel(NotificationChannel(ReminderScheduler.CHANNEL_ID,
            "测试通知渠道", NotificationManager.IMPORTANCE_NONE))
        seed(app)
        assertEquals(ListenableWorker.Result.success(), execute(app))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertEquals(1, app.repository.dueNotifications(LocalDate.now()).size)
    }
}
