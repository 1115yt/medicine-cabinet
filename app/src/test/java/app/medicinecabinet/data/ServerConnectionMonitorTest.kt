package app.medicinecabinet.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerConnectionMonitorTest {
    @Test fun `checking prevents duplicate requests and records completion time`() = runTest {
        val response = CompletableDeferred<ServerHealthResult>()
        var calls = 0
        val monitor = ServerConnectionMonitor(this, { address ->
            assertEquals("https://cache.example.com", address)
            calls++
            response.await()
        }, { 1234L })
        monitor.check("https://cache.example.com")
        assertTrue(monitor.status.value.checking)
        monitor.check("https://cache.example.com")
        runCurrent()
        assertEquals(1, calls)
        response.complete(ServerHealthResult(ServerHealthReason.CONNECTED, 200))
        runCurrent()
        assertFalse(monitor.status.value.checking)
        assertEquals(1234L, monitor.status.value.checkedAt)
        assertEquals(ServerHealthReason.CONNECTED, monitor.status.value.result!!.reason)
    }

    @Test fun `clear rejects a late successful result and permits a fresh check`() = runTest {
        val late = CompletableDeferred<ServerHealthResult>()
        var calls = 0
        val monitor = ServerConnectionMonitor(this, {
            calls++
            if (calls == 1) withContext(NonCancellable) { late.await() }
            else ServerHealthResult(ServerHealthReason.TIMEOUT)
        })
        monitor.check("https://cache.example.com")
        runCurrent()
        monitor.clear()
        assertEquals(ServerConnectionState(), monitor.status.value)
        monitor.check("https://cache.example.com")
        runCurrent()
        late.complete(ServerHealthResult(ServerHealthReason.CONNECTED, 200))
        runCurrent()
        assertEquals(2, calls)
        assertEquals(ServerHealthReason.TIMEOUT, monitor.status.value.result!!.reason)
    }

    @Test fun `unknown failure stores only a safe reason and retry can succeed`() = runTest {
        var calls = 0
        val monitor = ServerConnectionMonitor(this, {
            if (++calls == 1) throw IllegalStateException("fixture-private-message")
            ServerHealthResult(ServerHealthReason.CONNECTED, 200)
        })
        monitor.check("https://cache.example.com")
        runCurrent()
        assertEquals(ServerHealthReason.NETWORK_FAILURE, monitor.status.value.result!!.reason)
        assertFalse(monitor.status.value.toString().contains("fixture-private-message"))
        monitor.check("https://cache.example.com")
        runCurrent()
        assertEquals(ServerHealthReason.CONNECTED, monitor.status.value.result!!.reason)
    }

    @Test fun `cancellation does not become a connection failure`() = runTest {
        val monitor = ServerConnectionMonitor(this, { throw CancellationException("fixture") })
        monitor.check("https://cache.example.com")
        runCurrent()
        assertEquals(ServerConnectionState(), monitor.status.value)
    }
}
