package app.medicinecabinet.data

import android.content.Context
import app.medicinecabinet.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface ProductCache {
    suspend fun lookup(code: String): ProductLookupResult.Found?
    suspend fun remember(code: String, product: ProductInfo, source: String)
}

class ServerProductCache(context: Context, val preferences: ServerCachePreferences = ServerCachePreferences(context),
    val store: ServerCacheStore = ServerCacheStore(context), private val client: ServerCacheClient = ServerCacheClient(),
    private val uploadImmediately: Boolean = true,
    private val enqueue: () -> Unit = { ServerCacheWorker.schedule(context) }) : ProductCache {

    suspend fun checkConnection(address: String): ServerHealthResult {
        val settings = preferences.settings.value
        if (!settings.available || settings.address != address) return ServerHealthResult(ServerHealthReason.NOT_CONFIGURED)
        if (!settings.enabled) return ServerHealthResult(ServerHealthReason.DISABLED)
        return client.health(address)
    }

    override suspend fun lookup(code: String): ProductLookupResult.Found? = withContext(Dispatchers.IO) {
        if (!BarcodeParser.validGtin(code)) return@withContext null
        val address = preferences.automaticAddress() ?: return@withContext null
        try {
            store.find(ServerCacheCredentials(address, "").partition, code)?.let { entry ->
                return@withContext ProductLookupResult.Found(entry.product, "联网资料的本机缓存")
            }
            val credentials = ensureCredentials(preferences, client) ?: return@withContext null
            val result = client.lookup(code, credentials) as? ProductLookupResult.Found ?: return@withContext null
            // 保存服务器命中副本，断网时也能复用；只保留协议允许的身份字段。
            try { store.put(CachedProduct(credentials.partition, code.padStart(14, '0'), result.product, result.origin, pending = false)) }
            catch (_: Exception) { /* 本机副本写入失败不丢失服务器已查到的资料。 */ }
            result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    override suspend fun remember(code: String, product: ProductInfo, source: String): Unit = withContext(Dispatchers.IO) {
        val address = preferences.automaticAddress() ?: return@withContext
        if (!BarcodeParser.validGtin(code)) return@withContext
        val partition = ServerCacheCredentials(address, "").partition
        if (store.find(partition, code)?.product == product) return@withContext
        // 先落盘再安排后台工作；上传失败不会阻塞录入或引发再次付费查询。
        store.put(CachedProduct(partition, code.padStart(14, '0'), product, source))
        enqueue()
        if (uploadImmediately) uploadScope.launch {
            try {
                val credentials = ensureCredentials(preferences, client) ?: return@launch
                if (preferences.credentials()?.partition == credentials.partition) {
                    val entry = store.find(credentials.partition, code) ?: return@launch
                    CacheUploader.pushPending(entry, credentials, store, client) {
                        preferences.credentials()?.partition == credentials.partition
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* 后台队列保留任务，稍后补传。 */ }
        }
    }

    suspend fun rememberConfirmed(medicine: Medicine): Unit = withContext(Dispatchers.IO) {
        val address = preferences.automaticAddress() ?: return@withContext
        if (!BarcodeParser.validGtin(medicine.barcode)) return@withContext
        val previous = store.find(ServerCacheCredentials(address, "").partition, medicine.barcode)?.product
        remember(medicine.barcode, ProductInfo(medicine.name, medicine.specification, medicine.packageUnit,
            previous?.manufacturer.orEmpty(), previous?.approval.orEmpty()), "manual")
    }

    companion object {
        private val uploadScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        private val enrollmentMutex = Mutex()
        internal suspend fun ensureCredentials(preferences: ServerCachePreferences, client: ServerCacheClient): ServerCacheCredentials? = enrollmentMutex.withLock {
            preferences.credentials()?.let { return@withLock it }
            val address = preferences.automaticAddress() ?: return@withLock null
            val token = client.enroll(address) ?: return@withLock null
            preferences.saveAutomaticToken(address, token)
            preferences.credentials()
        }
    }
}
