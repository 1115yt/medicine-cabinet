package app.medicinecabinet.reminders

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 每日、前台及手动工作共用同一把锁；读取、发送和确认不能被另一轮检查插入。 */
internal object ReminderCheckCoordinator {
    private val mutex = Mutex()

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }
}
