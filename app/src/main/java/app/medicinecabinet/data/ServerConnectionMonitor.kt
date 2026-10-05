package app.medicinecabinet.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ServerHealthReason {
    CONNECTED, NOT_CONFIGURED, DISABLED, DNS_FAILURE, TIMEOUT, TLS_FAILURE,
    NETWORK_FAILURE, RATE_LIMITED, SERVICE_UNAVAILABLE, INVALID_RESPONSE, HTTP_ERROR
}

data class ServerHealthResult(val reason: ServerHealthReason, val httpStatus: Int? = null)
data class ServerConnectionState(val checking: Boolean = false, val result: ServerHealthResult? = null,
    val checkedAt: Long? = null)

/** 检测结果只保留安全状态；重复点击不并发发起请求，关闭共享后不接受旧结果。 */
class ServerConnectionMonitor(private val scope: CoroutineScope,
    private val probe: suspend (String) -> ServerHealthResult,
    private val clock: () -> Long = System::currentTimeMillis) {
    private val state = MutableStateFlow(ServerConnectionState())
    val status = state.asStateFlow()
    private var job: Job? = null
    private var generation = 0

    fun check(address: String) {
        if (state.value.checking) return
        val request = ++generation
        state.value = ServerConnectionState(checking = true)
        job = scope.launch {
            try {
                val result = probe(address)
                ensureActive()
                if (request == generation) state.value = ServerConnectionState(result = result, checkedAt = clock())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (request == generation) state.value = ServerConnectionState(
                    result = ServerHealthResult(ServerHealthReason.NETWORK_FAILURE), checkedAt = clock())
            } finally {
                if (request == generation && state.value.checking) state.value = ServerConnectionState()
            }
        }
    }

    fun clear() {
        generation++
        job?.cancel()
        job = null
        state.value = ServerConnectionState()
    }
}
