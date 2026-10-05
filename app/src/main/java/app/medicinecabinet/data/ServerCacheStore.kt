package app.medicinecabinet.data

import android.content.Context
import app.medicinecabinet.domain.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class CacheSyncStatus(val pendingCount: Int = 0, val message: String = "")

/** 资料副本使用独立私有配置；本机缓存与共享上传队列按名称隔离。 */
class ServerCacheStore(context: Context, preferencesName: String = "cabinet-catalog-cache") {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(readStatus())
    val status = state.asStateFlow()
    private val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> state.value = readStatus() }
    init { preferences.registerOnSharedPreferenceChangeListener(listener) }

    private fun readEntries(): List<CachedProduct> = Json.decodeFromString(preferences.getString("entries", "[]").orEmpty())
    private fun readStatus() = try { CacheSyncStatus(readEntries().count { it.pending }, preferences.getString("message", "").orEmpty()) }
        catch (_: Exception) { CacheSyncStatus(message = "本机缓存读取失败，个人药箱不受影响。") }

    fun find(partition: String, code: String): CachedProduct? = synchronized(lock) {
        readEntries().find { it.partition == partition && it.barcode == code.padStart(14, '0') }
    }

    fun pending(partition: String): List<CachedProduct> = synchronized(lock) {
        readEntries().filter { it.partition == partition && it.pending }
    }

    fun put(entry: CachedProduct) = synchronized(lock) {
        require(BarcodeParser.validGtin(entry.barcode) && ServerCacheClient.validProduct(entry.product) &&
            entry.source in listOf("mxnzp", "aliyun", "manual"))
        val entries = readEntries().filterNot { it.partition == entry.partition && it.barcode == entry.barcode }.toMutableList()
        if (entries.size >= MAX_RECORDS) {
            val disposable = entries.indexOfFirst { !it.pending }
            require(disposable >= 0) { "本机上传队列已满，请先完成上传。" }
            entries.removeAt(disposable)
        }
        entries.add(entry)
        write(entries, if (entry.pending) "查询资料已缓存到本机，将在网络可用时上传。" else "")
    }

    fun complete(entry: CachedProduct, result: CachePushResult) = synchronized(lock) {
        val entries = readEntries().toMutableList()
        val index = entries.indexOfFirst { it == entry }
        if (index < 0) return@synchronized
        val attempts = entry.attempts + 1
        entries[index] = entry.copy(pending = result == CachePushResult.RETRY, attempts = attempts)
        val message = when (result) {
            CachePushResult.ACCEPTED -> "条码基础资料已保存到服务器。"
            CachePushResult.CONFLICT -> "服务器已保留不同资料候选，已有缓存未被覆盖。"
            CachePushResult.RETRY -> if (attempts >= MAX_ATTEMPTS) "上传多次失败，资料已留在本机，可点击重新上传。"
                else "服务器暂时不可用，资料已留在本机，稍后重试上传。"
            CachePushResult.REJECTED -> "服务器拒绝上传，请检查认证和服务器配置后重新上传。"
        }
        // 认证或协议错误保留待上传资料，自动重试停止，手动重试可恢复。
        if (result == CachePushResult.REJECTED) entries[index] = entry.copy(attempts = MAX_ATTEMPTS)
        write(entries, message)
    }

    fun resetAttempts(partition: String) = synchronized(lock) {
        write(readEntries().map { if (it.partition == partition && it.pending) it.copy(attempts = 0) else it }, "已安排重新上传。")
    }

    private fun write(entries: List<CachedProduct>, message: String) {
        check(preferences.edit().putString("entries", Json.encodeToString(entries)).putString("message", message).commit())
        state.value = readStatus()
    }

    companion object {
        private val lock = Any()
        const val MAX_RECORDS = 200
        const val MAX_ATTEMPTS = 5
    }
}
