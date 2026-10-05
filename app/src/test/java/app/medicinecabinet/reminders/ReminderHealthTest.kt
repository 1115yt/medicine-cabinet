package app.medicinecabinet.reminders

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import app.medicinecabinet.TestCabinetApplication
import app.medicinecabinet.domain.Medicine
import app.medicinecabinet.domain.StockBatch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestCabinetApplication::class)
class ReminderHealthTest {
    private val app get() = ApplicationProvider.getApplicationContext<TestCabinetApplication>()
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private fun permit() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(true)
    }

    @Test fun `device health distinguishes permission application channel and battery restrictions`() {
        permit()
        val power = shadowOf(app.getSystemService(PowerManager::class.java))
        power.setIgnoringBatteryOptimizations(app.packageName, false)
        assertTrue(ReminderNotifications.inspect(app).notificationsAllowed)
        assertEquals(false, ReminderNotifications.inspect(app).batteryExempt)
        power.setIgnoringBatteryOptimizations(app.packageName, true)
        assertEquals(true, ReminderNotifications.inspect(app).batteryExempt)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(ReminderNotifications.inspect(app).permissionGranted)
        assertFalse(ReminderNotifications.inspect(app).notificationsAllowed)
        permit()
        shadowOf(manager).setNotificationsEnabled(false)
        assertFalse(ReminderNotifications.inspect(app).appNotificationsEnabled)
        assertFalse(ReminderNotifications.inspect(app).notificationsAllowed)
        permit()
        manager.createNotificationChannel(NotificationChannel(ReminderScheduler.CHANNEL_ID, "测试类别", NotificationManager.IMPORTANCE_NONE))
        assertFalse(ReminderNotifications.inspect(app).channelEnabled)
        assertEquals(TestNotificationResult.BLOCKED, ReminderNotifications.sendTest(app))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test @Config(sdk = [26]) fun `older system has no runtime notification request and reports unknown background restriction`() {
        shadowOf(manager).setNotificationsEnabled(true)
        val health = ReminderNotifications.inspect(app)
        assertTrue(health.permissionGranted)
        assertTrue(health.notificationsAllowed)
        assertNull(health.backgroundRestricted)
    }

    @Test fun `test notification uses real category and separate id without consuming reminder`() = runBlocking {
        permit()
        app.repository.saveRecord(Medicine("test-medicine", "虚构私有药品"),
            StockBatch("test-batch", "test-medicine", 1, LocalDate.now().plusDays(2).toString()))
        manager.notify(100, Notification.Builder(app, "fixture").setContentTitle("已有整理提醒").build())
        assertEquals(TestNotificationResult.POSTED, ReminderNotifications.sendTest(app))
        val test = shadowOf(manager).getNotification(ReminderNotifications.TEST_ID)
        assertEquals(ReminderScheduler.CHANNEL_ID, test.channelId)
        assertEquals(Notification.VISIBILITY_PRIVATE, test.visibility)
        assertFalse(test.extras.toString().contains("虚构私有药品"))
        assertEquals("已有整理提醒", shadowOf(manager).getNotification(100).extras.getString(Notification.EXTRA_TITLE))
        assertEquals(1, app.repository.dueNotifications(LocalDate.now()).size)
        assertNull(app.reminderDiagnostics.history.value.latest)
    }

    @Test fun `denied test notification leaves inventory and scheduled reminder untouched`() = runBlocking {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(TestNotificationResult.BLOCKED, ReminderNotifications.sendTest(app))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertNull(app.reminderDiagnostics.history.value.latest)
    }

    @Test fun `worker records actual periodic result and foreground check does not replace daily evidence`() = runBlocking {
        permit()
        val worker = TestListenableWorkerBuilder<ReminderWorker>(app).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        val daily = app.reminderDiagnostics.history.value.daily!!
        assertEquals(ReminderCheckSource.DAILY, daily.source)
        assertEquals(ReminderCheckResult.EMPTY, daily.result)
        assertTrue(daily.finishedAt >= daily.startedAt)
        Thread.sleep(2)
        TestListenableWorkerBuilder<ReminderWorker>(app)
            .setInputData(workDataOf(ReminderScheduler.SOURCE_KEY to ReminderCheckSource.RECHECK.name)).build().doWork()
        assertEquals(ReminderCheckSource.RECHECK, app.reminderDiagnostics.history.value.latest!!.source)
        assertEquals(daily, app.reminderDiagnostics.history.value.daily)
        assertEquals(app.reminderDiagnostics.history.value, ReminderDiagnostics(app).history.value)
    }

    @Test fun `worker reports notification blocked and posted outcomes accurately`() = runBlocking {
        permit()
        app.repository.saveRecord(Medicine("m", "虚构私有药品"), StockBatch("b", "m", 1, LocalDate.now().toString()))
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        TestListenableWorkerBuilder<ReminderWorker>(app).build().doWork()
        assertEquals(ReminderCheckResult.BLOCKED, app.reminderDiagnostics.history.value.latest!!.result)
        assertEquals(1, app.repository.dueNotifications(LocalDate.now()).size)
        permit()
        TestListenableWorkerBuilder<ReminderWorker>(app).build().doWork()
        val record = app.reminderDiagnostics.history.value.latest!!
        assertEquals(ReminderCheckResult.POSTED, record.result)
        assertEquals(1, record.count)
        val saved = app.getSharedPreferences("cabinet-reminder-checks", Context.MODE_PRIVATE).all.toString()
        assertFalse(saved.contains("虚构私有药品"))
    }

    @Test fun `late completion cannot overwrite newer check and daily record is retained independently`() {
        val diagnostics = app.reminderDiagnostics
        val old = diagnostics.start("old", ReminderCheckSource.DAILY, 1000)
        val newer = diagnostics.start("new", ReminderCheckSource.MANUAL, 2000)
        diagnostics.finish(newer, ReminderCheckResult.EMPTY, now = 3000)
        diagnostics.finish(old, ReminderCheckResult.RETRY, now = 4000)
        assertEquals("new", diagnostics.history.value.latest!!.id)
        assertEquals(ReminderCheckResult.EMPTY, diagnostics.history.value.latest!!.result)
        assertEquals("old", diagnostics.history.value.daily!!.id)
        assertEquals(ReminderCheckResult.RETRY, diagnostics.history.value.daily!!.result)
        assertEquals(diagnostics.history.value, ReminderDiagnostics(app).history.value)
    }

    @Test fun `stale interrupted record remains explicit rather than notification success`() {
        val diagnostics = app.reminderDiagnostics
        diagnostics.start("unfinished", ReminderCheckSource.DAILY, 1000)
        val restored = ReminderDiagnostics(app).history.value
        assertEquals(ReminderCheckResult.RUNNING, restored.latest!!.result)
        assertEquals(0L, restored.latest!!.finishedAt)
        assertFalse(restored.latest!!.result.label.contains("成功"))
        diagnostics.finish(restored.latest!!, ReminderCheckResult.CANCELLED, now = 2000)
        assertEquals(ReminderCheckResult.CANCELLED, diagnostics.history.value.latest!!.result)
    }

    @Test fun `clock rollback and reused work id cannot replace latest run with old completion`() {
        val diagnostics = app.reminderDiagnostics
        val old = diagnostics.start("periodic-id", ReminderCheckSource.DAILY, 5000)
        val current = diagnostics.start("periodic-id", ReminderCheckSource.DAILY, 1000)
        diagnostics.finish(old, ReminderCheckResult.RETRY, now = 6000)
        assertEquals(current, diagnostics.history.value.latest)
        diagnostics.finish(current, ReminderCheckResult.EMPTY, now = 2000)
        assertEquals(ReminderCheckResult.EMPTY, diagnostics.history.value.daily!!.result)
        val tied = diagnostics.start("new-manual", ReminderCheckSource.MANUAL, 1000)
        diagnostics.finish(current, ReminderCheckResult.POSTED, now = 3000)
        assertEquals(tied, diagnostics.history.value.latest)
    }

    @Test fun `database failure records retry instead of completed notification`() = runBlocking {
        permit()
        // 仅破坏本测试的内存表以模拟读取失败；Room 关闭后可能重新打开，不足以制造故障。
        app.database.openHelper.writableDatabase.execSQL("DROP TABLE medicines")
        val worker = TestListenableWorkerBuilder<ReminderWorker>(app).build()
        assertEquals(ListenableWorker.Result.retry(), worker.doWork())
        assertEquals(ReminderCheckResult.RETRY, app.reminderDiagnostics.history.value.latest!!.result)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test fun `cancelled check remains cancelled and does not turn into success or retry`() = runBlocking {
        permit()
        val worker = TestListenableWorkerBuilder<ReminderWorker>(app).build()
        val job = launch {
            currentCoroutineContext().cancel()
            worker.doWork()
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(ReminderCheckResult.CANCELLED, app.reminderDiagnostics.history.value.latest!!.result)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
}
