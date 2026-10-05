package app.medicinecabinet.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReleaseUpdateMonitorTest {
    @Test fun `one in flight check prevents duplicate requests and allows later checks`() = runTest {
        val response = CompletableDeferred<ReleaseUpdateResult>()
        var calls = 0
        val monitor = ReleaseUpdateMonitor(this, { calls++; response.await() })
        monitor.check()
        assertTrue(monitor.status.value.checking)
        monitor.check()
        runCurrent()
        assertEquals(1, calls)
        response.complete(ReleaseUpdateResult(ReleaseUpdateReason.AVAILABLE, "1.0.0"))
        runCurrent()
        assertFalse(monitor.status.value.checking)
        assertEquals(ReleaseUpdateReason.AVAILABLE, monitor.status.value.result!!.reason)
        monitor.check()
        runCurrent()
        assertEquals(2, calls)
    }

    @Test fun `clear rejects a late response without replacing a newer check`() = runTest {
        val late = CompletableDeferred<ReleaseUpdateResult>()
        var calls = 0
        val monitor = ReleaseUpdateMonitor(this, {
            if (++calls == 1) withContext(NonCancellable) { late.await() }
            else ReleaseUpdateResult(ReleaseUpdateReason.TIMEOUT)
        })
        monitor.check()
        runCurrent()
        monitor.clear()
        assertEquals(ReleaseUpdateState(), monitor.status.value)
        monitor.check()
        runCurrent()
        late.complete(ReleaseUpdateResult(ReleaseUpdateReason.AVAILABLE, "1.0.0"))
        runCurrent()
        assertEquals(2, calls)
        assertEquals(ReleaseUpdateReason.TIMEOUT, monitor.status.value.result!!.reason)
    }

    @Test fun `unknown failures keep only a safe reason and do not block retry`() = runTest {
        var calls = 0
        val monitor = ReleaseUpdateMonitor(this, {
            if (++calls == 1) throw IllegalStateException("private-fixture")
            ReleaseUpdateResult(ReleaseUpdateReason.CURRENT, "1.0.0")
        })
        monitor.check()
        runCurrent()
        assertEquals(ReleaseUpdateReason.NETWORK_FAILURE, monitor.status.value.result!!.reason)
        assertFalse(monitor.status.value.toString().contains("private-fixture"))
        monitor.check()
        runCurrent()
        assertEquals(ReleaseUpdateReason.CURRENT, monitor.status.value.result!!.reason)
    }

    @Test fun `cancellation leaves an idle state without a false failure`() = runTest {
        val monitor = ReleaseUpdateMonitor(this, { throw CancellationException("fixture") })
        monitor.check()
        runCurrent()
        assertEquals(ReleaseUpdateState(), monitor.status.value)
    }

    @Test fun `cancelled owner scope cannot start or leave a stuck check`() = runTest {
        val owner = Job()
        val scope = CoroutineScope(coroutineContext + owner)
        var calls = 0
        val monitor = ReleaseUpdateMonitor(scope, { calls++; ReleaseUpdateResult(ReleaseUpdateReason.CURRENT) })
        owner.cancel()
        monitor.check()
        runCurrent()
        assertEquals(0, calls)
        assertEquals(ReleaseUpdateState(), monitor.status.value)
    }
}
