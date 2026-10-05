package app.medicinecabinet

import android.content.Context
import androidx.room.Room
import androidx.work.*
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.medicinecabinet.reminders.ReminderWorker
import app.medicinecabinet.data.CabinetDatabase
import app.medicinecabinet.data.ServerCachePreferences
import app.medicinecabinet.data.ReleaseUpdateSource
import app.medicinecabinet.data.ReleaseUpdateResult
import app.medicinecabinet.data.ReleaseUpdateReason
import app.medicinecabinet.security.AesGcmCredentialCipher
import javax.crypto.KeyGenerator

/** 界面/存储测试控制调度；通知工作本身在 ReminderWorkerTest 中单独执行。 */
class TestCabinetApplication : CabinetApplication() {
    // 测试使用同一 AES-GCM 实现与进程内虚构密钥；不能替代设备 Android Keystore 验证。
    override val credentialCipher by lazy {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        AesGcmCredentialCipher { key }
    }
    // 全部界面测试禁用真实共享网络；共享行为另用模拟客户端单独核对。
    override val serverPreferences by lazy { ServerCachePreferences(this, featureEnabled = false) }
    // 界面测试仅用可控制的模拟结果，绝不连接真实更新接口。
    var updateProbe: suspend (String) -> ReleaseUpdateResult = {
        ReleaseUpdateResult(ReleaseUpdateReason.NO_RELEASE)
    }
    var updateRequests = 0
        private set
    override val releaseUpdates = ReleaseUpdateSource { currentVersion ->
        updateRequests++
        updateProbe(currentVersion)
    }
    // 每个测试独立持有内存药箱，避免页面测试之间带入之前的记录。
    override val database by lazy { Room.inMemoryDatabaseBuilder(this, CabinetDatabase::class.java).build() }
    override fun onCreate() {
        val executor = SynchronousExecutor()
        val configuration = Configuration.Builder()
            .setExecutor(executor)
            .setTaskExecutor(executor)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(context: Context, className: String, parameters: WorkerParameters): ListenableWorker? =
                    if (className == ReminderWorker::class.java.name) CompletedTestWorker(context, parameters) else null
            }).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(this, configuration)
    }

    override fun onTerminate() {
        WorkManager.getInstance(this).cancelAllWork().result.get()
        WorkManagerTestInitHelper.closeWorkDatabase()
        database.close()
        super.onTerminate()
    }
}

private class CompletedTestWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result = Result.success()
}
