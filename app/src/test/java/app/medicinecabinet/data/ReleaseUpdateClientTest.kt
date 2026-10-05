package app.medicinecabinet.data

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** 全部通过模拟传输检查，不连接 GitHub，不消耗扫码服务额度。 */
class ReleaseUpdateClientTest {
    @Test fun `first formal release updates historical version using only the fixed public endpoint`() = runBlocking {
        val urls = mutableListOf<String>()
        val client = GitHubReleaseUpdateClient { url ->
            urls += url.toExternalForm()
            ReleaseUpdateHttpResponse(200, body())
        }
        val result = client.check("0.1.11")
        assertEquals(ReleaseUpdateResult(ReleaseUpdateReason.AVAILABLE, "1.0.0", "$RELEASES_URL/tag/v1.0.0", 200), result)
        assertEquals(listOf(LATEST_RELEASE_API_URL), urls)
    }

    @Test fun `equal or older latest release does not offer a downgrade`() = runBlocking {
        val client = GitHubReleaseUpdateClient { ReleaseUpdateHttpResponse(200, body()) }
        assertEquals(ReleaseUpdateReason.CURRENT, client.check("1.0.0").reason)
        assertEquals(ReleaseUpdateReason.CURRENT, client.check("1.0.1").reason)
        assertEquals(ReleaseUpdateReason.CURRENT, client.check("2.0.0").reason)
    }

    @Test fun `numeric minor carry is newer even when lexical order differs`() = runBlocking {
        val client = GitHubReleaseUpdateClient { ReleaseUpdateHttpResponse(200, body("1.10.0")) }
        assertEquals(ReleaseUpdateReason.AVAILABLE, client.check("1.9.9").reason)
    }

    @Test fun `invalid current version prevents any request`() = runBlocking {
        var calls = 0
        val client = GitHubReleaseUpdateClient { calls++; ReleaseUpdateHttpResponse(200, body()) }
        assertEquals(ReleaseUpdateReason.INVALID_RESPONSE, client.check("v1.0.0").reason)
        assertEquals(0, calls)
    }

    @Test fun `missing formal release has its own status instead of claiming current`() = runBlocking {
        val result = GitHubReleaseUpdateClient { ReleaseUpdateHttpResponse(404) }.check("1.0.0")
        assertEquals(ReleaseUpdateResult(ReleaseUpdateReason.NO_RELEASE, httpStatus = 404), result)
        assertNull(result.versionName)
        assertNull(result.releaseUrl)
    }

    @Test fun `HTTP failures are separate from release and package availability`() = runBlocking {
        listOf(
            Triple(429, false, ReleaseUpdateReason.RATE_LIMITED),
            Triple(403, true, ReleaseUpdateReason.RATE_LIMITED),
            Triple(403, false, ReleaseUpdateReason.HTTP_ERROR),
            Triple(500, false, ReleaseUpdateReason.SERVICE_UNAVAILABLE),
            Triple(503, false, ReleaseUpdateReason.SERVICE_UNAVAILABLE),
            Triple(302, false, ReleaseUpdateReason.HTTP_ERROR),
            Triple(401, false, ReleaseUpdateReason.HTTP_ERROR),
        ).forEach { (status, limited, expected) ->
            val result = GitHubReleaseUpdateClient { ReleaseUpdateHttpResponse(status, "private-fixture", limited) }.check("1.0.0")
            assertEquals(expected, result.reason)
            assertEquals(status, result.httpStatus)
            assertFalse(result.toString().contains("private-fixture"))
        }
    }

    @Test fun `network failures retain safe distinct reasons`() = runBlocking {
        listOf(
            SocketTimeoutException("private-fixture") to ReleaseUpdateReason.TIMEOUT,
            UnknownHostException("private-fixture") to ReleaseUpdateReason.DNS_FAILURE,
            SSLException("private-fixture") to ReleaseUpdateReason.TLS_FAILURE,
            IllegalStateException("private-fixture") to ReleaseUpdateReason.NETWORK_FAILURE,
        ).forEach { (failure, expected) ->
            val result = GitHubReleaseUpdateClient { throw failure }.check("1.0.0")
            assertEquals(expected, result.reason)
            assertFalse(result.toString().contains("private-fixture"))
        }
    }

    @Test fun `cancellation propagates without becoming a failure result`() = runBlocking {
        try {
            GitHubReleaseUpdateClient { throw CancellationException("fixture") }.check("1.0.0")
            fail("取消必须向调用方传播。")
        } catch (_: CancellationException) { }
    }

    @Test fun `release flags must be explicit JSON false booleans`() = runBlocking {
        for (field in listOf("draft", "prerelease")) {
            for (invalid in listOf(JsonPrimitive(true), JsonPrimitive("false"), JsonPrimitive(0), JsonNull)) {
                assertInvalid(rewriteBody(field, invalid))
            }
            assertInvalid(rewriteBody(field, null))
        }
    }

    @Test fun `remote tag must be a canonical formal version`() = runBlocking {
        listOf("v0.1.11", "v1.0.10", "v1.00.0", "v01.0.0", "v1.0.0-rc", "1.0.0", "v1.0.0 ",
            "v2147483648.0.0").forEach { assertInvalid(rewriteBody("tag_name", JsonPrimitive(it))) }
        assertInvalid(rewriteBody("tag_name", JsonPrimitive(1)))
        assertInvalid(rewriteBody("tag_name", null))
    }

    @Test fun `browser URL must exactly match the canonical release in this repository`() = runBlocking {
        listOf("http://github.com/1115yt/medicine-cabinet/releases/tag/v1.0.0",
            "https://github.com/other/medicine-cabinet/releases/tag/v1.0.0",
            "https://github.com/1115yt/medicine-cabinet/releases/tag/v1.0.1",
            "$RELEASES_URL/tag/v1.0.0?redirect=fixture", "$RELEASES_URL/tag/v1.0.0#fragment",
            "https://user@github.com/1115yt/medicine-cabinet/releases/tag/v1.0.0",
            "https://github.com.evil.invalid/1115yt/medicine-cabinet/releases/tag/v1.0.0",
            "javascript:fixture").forEach { assertInvalid(rewriteBody("html_url", JsonPrimitive(it))) }
    }

    @Test fun `both fixed APK assets must be uploaded and nonempty`() = runBlocking {
        val assets = Json.parseToJsonElement(body()).jsonObject["assets"]!!.jsonArray
        listOf(JsonArray(emptyList()), JsonArray(listOf(assets[0])), JsonArray(listOf(assets[1])), JsonNull).forEach { missingAssets ->
            val result = GitHubReleaseUpdateClient { ReleaseUpdateHttpResponse(200, rewriteBody("assets", missingAssets)) }.check("0.1.11")
            assertEquals(ReleaseUpdateReason.NO_APK, result.reason)
            assertEquals("$RELEASES_URL/tag/v1.0.0", result.releaseUrl)
        }
        for ((field, invalid) in listOf("size" to JsonPrimitive(0), "size" to JsonPrimitive(-1),
            "size" to JsonPrimitive("123"), "state" to JsonPrimitive("new"), "name" to JsonPrimitive("other.apk"))) {
            val broken = JsonObject(assets[0].jsonObject.toMutableMap().apply { put(field, invalid) })
            val response = rewriteBody("assets", JsonArray(listOf(broken, assets[1])))
            assertEquals(ReleaseUpdateReason.NO_APK,
                GitHubReleaseUpdateClient { ReleaseUpdateHttpResponse(200, response) }.check("0.1.11").reason)
        }
    }

    @Test fun `malformed JSON and oversized body cannot confirm an update`() = runBlocking {
        listOf("{", "[]", "null", "\"fixture\"", " ".repeat(256 * 1024 + 1)).forEach { assertInvalid(it) }
    }

    @Test fun `default transport sets safe GET limits and always disconnects`() = runBlocking {
        val connection = FakeConnection(200, body())
        val response = defaultReleaseUpdateTransport { connection }.get(URL(LATEST_RELEASE_API_URL))
        assertEquals(200, response.status)
        assertEquals(body(), response.body)
        assertEquals("GET", connection.requestMethod)
        assertFalse(connection.instanceFollowRedirects)
        assertEquals(6000, connection.connectTimeout)
        assertEquals(8000, connection.readTimeout)
        assertEquals("application/vnd.github+json", connection.getRequestProperty("Accept"))
        assertEquals("2026-03-10", connection.getRequestProperty("X-GitHub-Api-Version"))
        assertNull(connection.getRequestProperty("Authorization"))
        assertTrue(connection.disconnected)
    }

    @Test fun `default transport never reads redirect or error bodies and distinguishes throttling headers`() = runBlocking {
        for (status in listOf(302, 403, 404, 429, 500)) {
            val connection = FakeConnection(status, "fixture")
            val response = defaultReleaseUpdateTransport { connection }.get(URL(LATEST_RELEASE_API_URL))
            assertEquals(status, response.status)
            assertEquals("", response.body)
            assertFalse(connection.inputRead)
            assertTrue(connection.disconnected)
        }
        val exhausted = FakeConnection(403, "fixture", mapOf("X-RateLimit-Remaining" to "0"))
        assertTrue(defaultReleaseUpdateTransport { exhausted }.get(URL(LATEST_RELEASE_API_URL)).rateLimited)
        val delayed = FakeConnection(403, "fixture", mapOf("Retry-After" to "30"))
        assertTrue(defaultReleaseUpdateTransport { delayed }.get(URL(LATEST_RELEASE_API_URL)).rateLimited)
    }

    @Test fun `default transport bounds unknown length streams and rejects another endpoint`() = runBlocking {
        val connection = FakeConnection(200, "x".repeat(256 * 1024 + 1))
        try {
            defaultReleaseUpdateTransport { connection }.get(URL(LATEST_RELEASE_API_URL))
            fail("过大的响应不得读入更新结果。")
        } catch (_: IllegalArgumentException) { }
        assertTrue(connection.disconnected)
        var called = false
        try {
            defaultReleaseUpdateTransport { called = true; connection }.get(URL("https://github.com/fixture"))
            fail("传输仅接受固定更新地址。")
        } catch (_: IllegalArgumentException) { }
        assertFalse(called)
    }

    @Test fun `cancelling blocked connection disconnects it before another check`() = runBlocking {
        // 同时覆盖响应头和正文阻塞；取消必须关闭连接，不能等到网络超时。
        for (blockDuringRead in listOf(false, true)) {
            val entered = CountDownLatch(1)
            val released = CountDownLatch(1)
            val exited = CountDownLatch(1)
            fun waitForDisconnect() {
                entered.countDown()
                try {
                    check(released.await(5, TimeUnit.SECONDS)) { "模拟连接未被及时关闭。" }
                } finally { exited.countDown() }
            }
            val blocked = object : FakeConnection(200, body()) {
                override fun getResponseCode(): Int {
                    if (!blockDuringRead) waitForDisconnect()
                    return super.getResponseCode()
                }
                override fun getInputStream(): InputStream {
                    val input = super.getInputStream()
                    return object : InputStream() {
                        override fun read(): Int = input.read()
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            waitForDisconnect()
                            return input.read(buffer, offset, length)
                        }
                        override fun close() { input.close() }
                    }
                }
                override fun disconnect() {
                    super.disconnect()
                    released.countDown()
                }
            }
            val healthy = FakeConnection(200, body())
            var connections = 0
            val client = GitHubReleaseUpdateClient(defaultReleaseUpdateTransport {
                if (++connections == 1) blocked else healthy
            })
            val first = async(Dispatchers.IO) { client.check("0.1.11") }
            try {
                assertTrue(withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) })
                first.cancel()
                assertTrue(blocked.disconnected)
                assertTrue(withContext(Dispatchers.IO) { exited.await(2, TimeUnit.SECONDS) })
                withTimeout(2_000) { first.join() }
                assertTrue(first.isCancelled)
                assertEquals(blockDuringRead, blocked.inputRead)
                assertEquals(ReleaseUpdateReason.AVAILABLE, client.check("0.1.11").reason)
                assertEquals(2, connections)
                assertTrue(healthy.disconnected)
            } finally { first.cancel(); blocked.disconnect() }
        }
    }

    private suspend fun assertInvalid(text: String) {
        val result = GitHubReleaseUpdateClient { ReleaseUpdateHttpResponse(200, text) }.check("0.1.11")
        assertEquals(ReleaseUpdateReason.INVALID_RESPONSE, result.reason)
        assertNull(result.releaseUrl)
    }

    private fun body(version: String = "1.0.0"): String = buildJsonObject {
        put("draft", false)
        put("prerelease", false)
        put("tag_name", "v$version")
        put("html_url", "$RELEASES_URL/tag/v$version")
        put("assets", buildJsonArray { listOf("arm64-v8a", "universal").forEach { abi ->
            add(buildJsonObject {
                put("name", "medicine-cabinet-$version-$abi.apk")
                put("state", "uploaded")
                put("size", 1234)
                // 更新检查不使用这个任意字段，只允许打开已核对的 Release 网页。
                put("browser_download_url", "https://fixture.invalid/unused.apk")
            })
        } })
    }.toString()

    private fun rewriteBody(field: String, value: JsonElement?): String =
        JsonObject(Json.parseToJsonElement(body()).jsonObject.toMutableMap().apply {
            if (value == null) remove(field) else put(field, value)
        }).toString()

    private open class FakeConnection(private val status: Int, private val body: String,
        private val headers: Map<String, String> = emptyMap()) : HttpURLConnection(URL(LATEST_RELEASE_API_URL)) {
        var inputRead = false
        var disconnected = false
        override fun getResponseCode(): Int = status
        override fun getHeaderField(name: String?): String? = headers[name]
        override fun getContentLengthLong(): Long = -1
        override fun getInputStream(): InputStream {
            inputRead = true
            return ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
        }
        override fun connect() { }
        override fun disconnect() { disconnected = true }
        override fun usingProxy(): Boolean = false
    }
}
