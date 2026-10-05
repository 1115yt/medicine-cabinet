package app.medicinecabinet.data

import android.content.Context
import app.medicinecabinet.domain.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class ApiUsage(val totalRequests: Long = 0, val todayRequests: Long = 0,
    val lastResult: ApiAttempt? = null, val lastResultAt: String = "",
    val connectionTest: ApiAttempt? = null, val connectionTestAt: String = "")

/** 独立本机统计，不进入药箱备份；次数代表发起请求，不代表服务商扣费。 */
class ApiUsageStore(context: Context, private val today: () -> LocalDate = { LocalDate.now() },
    private val timestamp: () -> String = { LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) }) {
    private val preferences = context.getSharedPreferences("cabinet-api-usage", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val usage = state.asStateFlow()

    private fun key(service: BarcodeService, field: String) = "${service.name}-$field"
    private fun result(service: BarcodeService, field: String): ApiAttempt? = try {
        preferences.getString(key(service, field), null)?.let { Json.decodeFromString<ApiAttempt>(it) }
    } catch (_: Exception) { null }

    private fun read(): Map<BarcodeService, ApiUsage> = BarcodeService.entries.associateWith { service ->
        ApiUsage(preferences.getLong(key(service, "total"), 0),
            if (preferences.getString(key(service, "day"), "") == today().toString())
                preferences.getLong(key(service, "today"), 0) else 0,
            result(service, "result"), preferences.getString(key(service, "result-at"), "").orEmpty(),
            result(service, "test"), preferences.getString(key(service, "test-at"), "").orEmpty())
    }

    fun refresh() = synchronized(lock) { state.value = read() }

    /** 在进入 HTTP 传输前计数；失败、超时和连接检测均属于实际请求尝试。 */
    fun requestStarted(service: BarcodeService) = synchronized(lock) {
        val current = read().getValue(service)
        check(preferences.edit().putLong(key(service, "total"), current.totalRequests + 1)
            .putString(key(service, "day"), today().toString())
            .putLong(key(service, "today"), current.todayRequests + 1).commit()) { "本机 API 次数保存失败。" }
        state.value = read()
    }

    fun complete(attempt: ApiAttempt, testing: Boolean) = synchronized(lock) {
        val updated = preferences.edit().putString(key(attempt.service, "result"), Json.encodeToString(attempt))
            .putString(key(attempt.service, "result-at"), timestamp())
        if (testing) updated.putString(key(attempt.service, "test"), Json.encodeToString(attempt))
            .putString(key(attempt.service, "test-at"), timestamp())
        check(updated.commit()) { "本机 API 状态保存失败。" }
        state.value = read()
    }

    /** 替换认证后旧检测不能继续代表新认证；请求总数保持原值。 */
    fun invalidateConnectionTest(service: BarcodeService) = synchronized(lock) {
        check(preferences.edit().remove(key(service, "test")).remove(key(service, "test-at"))
            .remove(key(service, "result")).remove(key(service, "result-at")).commit()) { "本机 API 状态保存失败。" }
        state.value = read()
    }

    companion object { private val lock = Any() }
}
