package app.medicinecabinet.data

import android.content.Context
import app.medicinecabinet.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 独立的本机条码记忆；共享及补传由组合缓存另行处理。 */
class LocalProductCache(context: Context,
    internal val store: ServerCacheStore = ServerCacheStore(context, "cabinet-local-catalog")) : ProductCache {

    override suspend fun lookup(code: String): ProductLookupResult.Found? = withContext(Dispatchers.IO) {
        if (!BarcodeParser.validGtin(code)) return@withContext null
        store.find(PARTITION, code)?.let { ProductLookupResult.Found(it.product, "本机条码记忆", it.source) }
    }

    override suspend fun remember(code: String, product: ProductInfo, source: String): Unit = withContext(Dispatchers.IO) {
        if (!BarcodeParser.validGtin(code)) return@withContext
        val previous = store.find(PARTITION, code)
        if (previous?.product == product && previous.source == source) return@withContext
        store.put(CachedProduct(PARTITION, code.padStart(14, '0'), product, source, pending = false))
    }

    suspend fun rememberConfirmed(medicine: Medicine): Unit = withContext(Dispatchers.IO) {
        if (!BarcodeParser.validGtin(medicine.barcode)) return@withContext
        val previous = store.find(PARTITION, medicine.barcode)?.product
        remember(medicine.barcode, ProductInfo(medicine.name, medicine.specification, medicine.packageUnit,
            previous?.manufacturer.orEmpty(), previous?.approval.orEmpty()), "manual")
    }

    companion object {
        internal const val PARTITION = "device-local"
    }
}
