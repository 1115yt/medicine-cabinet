package app.medicinecabinet

import android.app.Application
import androidx.work.Configuration
import app.medicinecabinet.data.CabinetDatabase
import app.medicinecabinet.data.CabinetRepository
import app.medicinecabinet.data.BundledCatalog
import app.medicinecabinet.data.LocalProductCache
import app.medicinecabinet.data.CombinedProductCache
import app.medicinecabinet.data.ServerProductCache
import app.medicinecabinet.data.ServerCachePreferences
import app.medicinecabinet.data.ServerCacheWorker
import app.medicinecabinet.data.GitHubReleaseUpdateClient
import app.medicinecabinet.data.ReleaseUpdateSource
import app.medicinecabinet.reminders.ReminderScheduler
import app.medicinecabinet.reminders.ReminderDiagnostics
import app.medicinecabinet.security.androidCredentialCipher

open class CabinetApplication : Application(), Configuration.Provider {
    // 按需初始化，避免首次安排提醒时依赖启动组件的执行顺序。
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()
    open val database by lazy { CabinetDatabase.create(this) }
    val repository by lazy { CabinetRepository(database) }
    val reminderDiagnostics by lazy { ReminderDiagnostics(this) }
    open val credentialCipher by lazy { androidCredentialCipher() }
    // 更新检查只读取仓库公开版本，与药箱和条码查询认证分开。
    open val releaseUpdates: ReleaseUpdateSource by lazy { GitHubReleaseUpdateClient() }
    val catalog by lazy { BundledCatalog(assets) }
    open val serverPreferences by lazy { ServerCachePreferences(this) }
    open val productCache by lazy {
        CombinedProductCache(LocalProductCache(this), ServerProductCache(this, serverPreferences))
    }
    override fun onCreate() {
        super.onCreate()
        ReminderScheduler.scheduleDaily(this)
        // 仅有待上传资料时补传，启动应用不为注册令牌发起空请求。
        if (serverPreferences.settings.value.enabled && productCache.shared.store.status.value.pendingCount > 0) {
            ServerCacheWorker.schedule(this)
        } else ServerCacheWorker.cancel(this)
    }
}
