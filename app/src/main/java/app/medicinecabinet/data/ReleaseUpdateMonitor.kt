package app.medicinecabinet.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ReleaseUpdateState(val checking: Boolean = false, val result: ReleaseUpdateResult? = null)

/** 手动检查阻止重复请求；清除状态或退出后，迟到响应不能恢复旧结果。 */
class ReleaseUpdateMonitor(private val scope: CoroutineScope,
    private val probe: suspend () -> ReleaseUpdateResult) {
    private val state = MutableStateFlow(ReleaseUpdateState())
    val status: StateFlow<ReleaseUpdateState> = state.asStateFlow()
    private var job: Job? = null
    private var generation = 0

    fun check() {
        if (!scope.isActive || state.value.checking) return
        val request = ++generation
        state.value = ReleaseUpdateState(checking = true)
        job = scope.launch {
            try {
                val result = probe()
                ensureActive()
                if (request == generation) state.value = ReleaseUpdateState(result = result)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (request == generation) state.value = ReleaseUpdateState(
                    result = ReleaseUpdateResult(ReleaseUpdateReason.NETWORK_FAILURE))
            } finally {
                if (request == generation && state.value.checking) state.value = ReleaseUpdateState()
            }
        }
    }

    fun clear() {
        generation++
        job?.cancel()
        job = null
        state.value = ReleaseUpdateState()
    }
}
