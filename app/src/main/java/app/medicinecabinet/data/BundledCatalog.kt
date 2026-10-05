package app.medicinecabinet.data

import android.content.res.AssetManager
import app.medicinecabinet.domain.BarcodeParser
import app.medicinecabinet.domain.ProductInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class BundledCatalog(private val assets: AssetManager) {
    // 每次只读取一个条码分片，最多缓存四片；启动和列表动画不加载整个资料库。
    private val cache = object : LinkedHashMap<String, JsonObject>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JsonObject>?) = size > 4
    }

    suspend fun lookup(code: String): List<ProductInfo> = withContext(Dispatchers.IO) {
        if (!BarcodeParser.validGtin(code)) return@withContext emptyList()
        val canonical = code.padStart(14, '0')
        val suffix = canonical.takeLast(2)
        val bucket = synchronized(cache) {
            // Android 构建器会解压 .gz 资源并移除该后缀，最终 APK 以 ZIP 压缩分片。
            cache[suffix] ?: assets.open("medicine-catalog/$suffix.json").use { input ->
                input.bufferedReader(Charsets.UTF_8).use { reader ->
                    Json.parseToJsonElement(reader.readText()).jsonObject
                }
            }.also { cache[suffix] = it }
        }
        bucket[canonical]?.jsonArray?.map { entry ->
            val fields = entry.jsonArray.map { it.jsonPrimitive.content }
            require(fields.size == 5)
            ProductInfo(fields[0], fields[1], fields[2], fields[3], fields[4])
        } ?: emptyList()
    }
}
