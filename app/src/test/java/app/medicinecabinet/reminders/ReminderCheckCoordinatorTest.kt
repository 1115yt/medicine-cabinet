package app.medicinecabinet.reminders

import android.Manifest
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import app.medicinecabinet.TestCabinetApplication
import app.medicinecabinet.domain.Medicine
import app.medicinecabinet.domain.ShoppingItem
import app.medicinecabinet.domain.StockBatch
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 使用独立内存药箱和可控协程门禁，不借助真实设备、定时睡眠或磁盘故障推测并发结果。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestCabinetApplication::class)
class ReminderCheckCoordinatorTest {
    private val app get() = ApplicationProvider.getApplicationContext<TestCabinetApplication>()
    private val today get() = LocalDate.now()

    private suspend fun seed() {
        app.repository.saveRecord(Medicine("fixture-medicine", "虚构提醒药品"),
            StockBatch("fixture-batch", "fixture-medicine", 1, today.plusDays(2).toString()))
    }

    // 模拟两种工作的同一临界区；读取和回执走真实 Room，提交动作仅使用本机计数器。
    private suspend fun simulateCheck(posts: AtomicInteger,
        afterRead: suspend (List<ShoppingItem>) -> Unit): Int = ReminderCheckCoordinator.run {
        val checkDate = today
        app.repository.refresh(checkDate)
        val due = app.repository.dueNotifications(checkDate)
        afterRead(due)
        if (due.isNotEmpty()) {
            posts.incrementAndGet()
            app.repository.acknowledgeNotifications(due, checkDate)
        }
        due.size
    }

    @Test fun `queued check rereads receipts and posts nothing after the first check confirms`() = runBlocking {
        withTimeout(10000) {
            seed()
            val posts = AtomicInteger()
            val firstRead = CompletableDeferred<Unit>()
            val secondRead = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                simulateCheck(posts) { due ->
                    assertEquals(1, due.size)
                    firstRead.complete(Unit)
                    releaseFirst.await()
                }
            }
            firstRead.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                simulateCheck(posts) { due ->
                    assertTrue(due.isEmpty())
                    secondRead.complete(Unit)
                }
            }
            assertFalse(secondRead.isCompleted)
            assertEquals(0, posts.get())
            releaseFirst.complete(Unit)
            assertEquals(1, first.await())
            assertEquals(0, second.await())
            assertEquals(1, posts.get())
            assertTrue(app.repository.dueNotifications(today).isEmpty())
        }
    }

    @Test fun `failure before posting releases gate and preserves pending reminder for the waiting check`() = runBlocking {
        withTimeout(10000) {
            seed()
            val posts = AtomicInteger()
            val firstRead = CompletableDeferred<Unit>()
            val secondRead = CompletableDeferred<Unit>()
            val failFirst = CompletableDeferred<Unit>()
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    simulateCheck(posts) {
                        firstRead.complete(Unit)
                        failFirst.await()
                        // 只模拟临界区主动抛出异常，不声称真实通知或磁盘曾失败。
                        throw IllegalStateException("受控检查失败")
                    }
                }
            }
            firstRead.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                simulateCheck(posts) { due ->
                    assertEquals(1, due.size)
                    secondRead.complete(Unit)
                }
            }
            assertFalse(secondRead.isCompleted)
            failFirst.complete(Unit)
            assertTrue(first.await().exceptionOrNull() is IllegalStateException)
            assertEquals(1, second.await())
            assertEquals(1, posts.get())
            assertTrue(app.repository.dueNotifications(today).isEmpty())
        }
    }

    @Test fun `cancelled active check releases gate without consuming the pending reminder`() = runBlocking {
        withTimeout(10000) {
            seed()
            val posts = AtomicInteger()
            val firstRead = CompletableDeferred<Unit>()
            val secondRead = CompletableDeferred<Unit>()
            val first = launch(start = CoroutineStart.UNDISPATCHED) {
                simulateCheck(posts) {
                    firstRead.complete(Unit)
                    awaitCancellation()
                }
            }
            firstRead.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                simulateCheck(posts) { due ->
                    assertEquals(1, due.size)
                    secondRead.complete(Unit)
                }
            }
            assertFalse(secondRead.isCompleted)
            first.cancelAndJoin()
            assertTrue(first.isCancelled)
            assertEquals(1, second.await())
            assertEquals(1, posts.get())
        }
    }

    private fun permit() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(true)
    }

    private fun worker(source: ReminderCheckSource) = TestListenableWorkerBuilder<ReminderWorker>(app)
        .setInputData(workDataOf(ReminderScheduler.SOURCE_KEY to source.name)).build()

    @Test fun `real daily and foreground workers share gate and finish with posted then empty results`() = runBlocking {
        withTimeout(10000) {
            seed()
            permit()
            val gateHeld = CompletableDeferred<Unit>()
            val releaseGate = CompletableDeferred<Unit>()
            val holder = launch(start = CoroutineStart.UNDISPATCHED) {
                ReminderCheckCoordinator.run { gateHeld.complete(Unit); releaseGate.await() }
            }
            gateHeld.await()
            val daily = async(start = CoroutineStart.UNDISPATCHED) { worker(ReminderCheckSource.DAILY).doWork() }
            val foreground = async(start = CoroutineStart.UNDISPATCHED) { worker(ReminderCheckSource.RECHECK).doWork() }
            assertFalse(daily.isCompleted)
            assertFalse(foreground.isCompleted)
            assertNull(app.reminderDiagnostics.history.value.latest)
            assertTrue(shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
            releaseGate.complete(Unit)
            holder.join()
            assertEquals(ListenableWorker.Result.success(), daily.await())
            assertEquals(ListenableWorker.Result.success(), foreground.await())
            val history = app.reminderDiagnostics.history.value
            assertEquals(ReminderCheckResult.POSTED, history.daily!!.result)
            assertEquals(1, history.daily!!.count)
            assertEquals(ReminderCheckSource.RECHECK, history.latest!!.source)
            assertEquals(ReminderCheckResult.EMPTY, history.latest!!.result)
            assertEquals(0, history.latest!!.count)
            assertEquals(1, shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.size)
        }
    }

    @Test fun `cancelled waiting worker leaves previous diagnostic intact and later worker still proceeds`() = runBlocking {
        withTimeout(10000) {
            seed()
            permit()
            val diagnostics = app.reminderDiagnostics
            val previous = diagnostics.start("already-completed", ReminderCheckSource.DAILY, 1000)
            diagnostics.finish(previous, ReminderCheckResult.EMPTY, now = 2000)
            val previousHistory = diagnostics.history.value
            val gateHeld = CompletableDeferred<Unit>()
            val releaseGate = CompletableDeferred<Unit>()
            val holder = launch(start = CoroutineStart.UNDISPATCHED) {
                ReminderCheckCoordinator.run { gateHeld.complete(Unit); releaseGate.await() }
            }
            gateHeld.await()
            val waiting = launch(start = CoroutineStart.UNDISPATCHED) { worker(ReminderCheckSource.MANUAL).doWork() }
            assertFalse(waiting.isCompleted)
            assertEquals(previousHistory, diagnostics.history.value)
            waiting.cancelAndJoin()
            assertTrue(waiting.isCancelled)
            assertEquals(previousHistory, diagnostics.history.value)
            assertEquals(1, app.repository.dueNotifications(today).size)
            releaseGate.complete(Unit)
            holder.join()
            assertEquals(ListenableWorker.Result.success(), worker(ReminderCheckSource.RECHECK).doWork())
            assertEquals(ReminderCheckResult.POSTED, diagnostics.history.value.latest!!.result)
            assertEquals(previousHistory.daily, diagnostics.history.value.daily)
        }
    }
}
