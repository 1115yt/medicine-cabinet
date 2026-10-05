package app.medicinecabinet.data

import app.medicinecabinet.domain.CachedProduct
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 前台即时上传与后台补传共用锁，避免同一条资料重复发送。 */
internal object CacheUploader {
    private val mutex = Mutex()
    suspend fun pushPending(entry: CachedProduct, credentials: ServerCacheCredentials, store: ServerCacheStore,
        client: ServerCacheClient, canUpload: () -> Boolean = { true }): CachePushResult? = mutex.withLock {
        if (!canUpload()) return@withLock null
        val current = store.find(credentials.partition, entry.barcode) ?: return@withLock null
        if (!current.pending || current != entry) return@withLock null
        val result = client.push(entry.barcode, entry.product, entry.source, credentials)
        store.complete(entry, result)
        result
    }
}
