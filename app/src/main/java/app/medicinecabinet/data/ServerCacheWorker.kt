package app.medicinecabinet.data

import android.content.Context
import androidx.work.*
import app.medicinecabinet.BuildConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class ServerCacheWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        // 注册与加密落盘也可能失败，必须与上传一样保留队列并有限重试。
        return try { uploadPending() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (runAttemptCount < 5) Result.retry() else Result.failure() }
    }

    private suspend fun uploadPending(): Result {
        if (!BuildConfig.CATALOG_SERVER_ENABLED) return Result.success()
        val preferences = ServerCachePreferences(applicationContext)
        val address = preferences.automaticAddress() ?: return Result.success()
        val store = ServerCacheStore(applicationContext)
        if (store.pending(ServerCacheCredentials(address, "").partition).none { it.attempts < ServerCacheStore.MAX_ATTEMPTS }) {
            return Result.success()
        }
        val credentials = ServerProductCache.ensureCredentials(preferences, ServerCacheClient()) ?: return (
            if (preferences.automaticAddress() == null) Result.success()
            else if (runAttemptCount < 5) Result.retry() else Result.failure())
        val client = ServerCacheClient()
        var needsRetry = false
        for (entry in store.pending(credentials.partition).filter { it.attempts < ServerCacheStore.MAX_ATTEMPTS }.take(20)) {
            // 关闭或更换服务器时停止发送，旧队列不发送到新的认证分区。
            if (preferences.credentials()?.partition != credentials.partition) return Result.success()
            val result = CacheUploader.pushPending(entry, credentials, store, client) {
                preferences.credentials()?.partition == credentials.partition
            } ?: continue
            if (result == CachePushResult.RETRY || result == CachePushResult.REJECTED) {
                if (result == CachePushResult.REJECTED) return Result.success()
                needsRetry = result == CachePushResult.RETRY
                break
            }
        }
        val remaining = store.pending(credentials.partition).any { it.attempts < ServerCacheStore.MAX_ATTEMPTS }
        return if ((needsRetry && runAttemptCount < 5) || (!needsRetry && remaining)) Result.retry() else Result.success()
    }

    companion object {
        private const val WORK_NAME = "cabinet-catalog-upload"
        fun schedule(context: Context) {
            if (!BuildConfig.CATALOG_SERVER_ENABLED || !ServerCachePreferences(context).settings.value.enabled) return
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ServerCacheWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
        fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME) }
    }
}
