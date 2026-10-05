package app.medicinecabinet.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.medicinecabinet.BuildConfig
import app.medicinecabinet.TestCabinetApplication
import app.medicinecabinet.domain.*
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestCabinetApplication::class)
class ServerCacheTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val code = "04006381333931"
    private val token = "fixture_token_for_local_verification_only_12345"
    private val product = ProductInfo("条码示例", "10片", "盒", "厂家示例")
    private val cachedBody = """{"barcode":"04006381333931","name":"条码示例","specification":"10片","packageUnit":"盒","manufacturer":"厂家示例","approval":"","source":"aliyun","verified":false}"""

    // 使用专用模拟目标，不访问实际共享服务。
    private fun configuredPreferences() = ServerCachePreferences(context, featureEnabled = true, allowCustomAddress = true).apply { save("https://cache.example.com/", token) }

    @Test fun `server configuration accepts only HTTPS origin and never exposes token`() {
        val preferences = configuredPreferences()
        assertEquals("https://cache.example.com", preferences.settings.value.address)
        assertFalse(preferences.settings.value.toString().contains(token))
        for (url in listOf("http://cache.example.com", "https://user:secret@cache.example.com",
            "https://cache.example.com/path", "https://cache.example.com?token=secret", "https://cache.example.com#data")) {
            assertTrue(runCatching { preferences.save(url, token) }.isFailure)
        }
        preferences.setEnabled(false)
        assertNull(preferences.credentials())
    }

    @Test fun `server GET uses bearer header validates product and barcode`() = runBlocking {
        val client = ServerCacheClient { method, url, headers, body ->
            assertEquals("GET", method); assertEquals("https", url.protocol)
            assertEquals("/v1/catalog/$code", url.path); assertNull(url.query); assertNull(body)
            assertEquals("Bearer $token", headers["Authorization"])
            BarcodeHttpResponse(200, cachedBody)
        }
        val found = client.lookup(code, configuredPreferences().credentials()!!) as ProductLookupResult.Found
        assertEquals(product, found.product)
        assertEquals("aliyun", found.origin)
        val mismatch = ServerCacheClient { _, _, _, _ -> BarcodeHttpResponse(200, cachedBody.replace(code, "06902538005141")) }
        assertTrue(mismatch.lookup(code, configuredPreferences().credentials()!!) is ProductLookupResult.Manual)
    }

    @Test fun `server POST contains only allowed identity fields without API credentials`() = runBlocking {
        val client = ServerCacheClient { method, url, headers, body ->
            assertEquals("POST", method); assertEquals("/v1/catalog/cache", url.path)
            assertEquals("Bearer $token", headers["Authorization"])
            val value = Json.parseToJsonElement(body!!).jsonObject
            assertEquals(setOf("barcode", "name", "specification", "packageUnit", "manufacturer", "approval", "source"), value.keys)
            assertFalse(body.contains(token))
            assertEquals("mxnzp", value.getValue("source").jsonPrimitive.content)
            BarcodeHttpResponse(201, """{"status":"stored","barcode":"$code"}""")
        }
        assertEquals(CachePushResult.ACCEPTED, client.push(code, product, "mxnzp", configuredPreferences().credentials()!!))
    }

    @Test fun `health checks only the HTTPS readiness endpoint without authentication or product data`() = runBlocking {
        var calls = 0
        val client = ServerCacheClient { method, url, headers, body ->
            calls++
            assertEquals("GET", method)
            assertEquals("https://cache.example.com/health", url.toString())
            assertTrue(headers.isEmpty())
            assertNull(body)
            BarcodeHttpResponse(200, """{"status":"ok","schema":1}""")
        }
        assertEquals(ServerHealthResult(ServerHealthReason.CONNECTED, 200), client.health("https://cache.example.com"))
        for (address in listOf("", "http://cache.example.com", "https://user:secret@cache.example.com", "https://cache.example.com/path")) {
            assertEquals(ServerHealthReason.NOT_CONFIGURED, client.health(address).reason)
        }
        assertEquals(1, calls)
    }

    @Test fun `health does not accept a generic website or an unready schema as connected`() = runBlocking {
        for (body in listOf("", "<html>ok</html>", "{}", """{"status":"ok","schema":0}""",
            """{"status":"ok","schema":"1"}""", """{"status":"failed","schema":1}""")) {
            val client = ServerCacheClient { _, _, _, _ -> BarcodeHttpResponse(200, body) }
            assertEquals(ServerHealthResult(ServerHealthReason.INVALID_RESPONSE, 200), client.health("https://cache.example.com"))
        }
        for ((status, reason) in listOf(429 to ServerHealthReason.RATE_LIMITED,
            503 to ServerHealthReason.SERVICE_UNAVAILABLE, 403 to ServerHealthReason.HTTP_ERROR,
            302 to ServerHealthReason.HTTP_ERROR)) {
            val client = ServerCacheClient { _, _, _, _ -> BarcodeHttpResponse(status, "fixture-private-message") }
            val result = client.health("https://cache.example.com")
            assertEquals(ServerHealthResult(reason, status), result)
            assertFalse(result.toString().contains("fixture-private-message"))
        }
    }

    @Test fun `health distinguishes DNS timeout certificate and network failures without raw messages`() = runBlocking {
        for ((error, reason) in listOf(java.net.UnknownHostException("fixture-private-message") to ServerHealthReason.DNS_FAILURE,
            java.net.SocketTimeoutException("fixture-private-message") to ServerHealthReason.TIMEOUT,
            javax.net.ssl.SSLException("fixture-private-message") to ServerHealthReason.TLS_FAILURE,
            IOException("fixture-private-message") to ServerHealthReason.NETWORK_FAILURE)) {
            val client = ServerCacheClient { _, _, _, _ -> throw error }
            val result = client.health("https://cache.example.com")
            assertEquals(reason, result.reason)
            assertNull(result.httpStatus)
            assertFalse(result.toString().contains("fixture-private-message"))
        }
    }

    @Test fun `disabled sharing cannot check health or enroll a guest`() = runBlocking {
        val preferences = configuredPreferences()
        preferences.setEnabled(false)
        val client = ServerCacheClient { _, _, _, _ -> fail("关闭时不能检测连接或注册访客"); BarcodeHttpResponse(500) }
        val cache = ServerProductCache(context, preferences, ServerCacheStore(context), client, uploadImmediately = false) {}
        assertEquals(ServerHealthReason.DISABLED, cache.checkConnection("https://cache.example.com").reason)
        assertEquals(ServerHealthReason.NOT_CONFIGURED, cache.checkConnection("https://other.example.com").reason)
    }

    @Test fun `failed upload is retained locally and never spends API quota twice`() = runBlocking {
        val preferences = LookupPreferences(context).apply { saveMxnzp("fixture-id", "fixture-secret") }
        val serverPreferences = configuredPreferences()
        var serverCalls = 0
        var apiCalls = 0
        var scheduled = 0
        val client = ServerCacheClient { _, _, _, _ -> serverCalls++; throw IOException("fixture-secret") }
        val store = ServerCacheStore(context)
        val cache = ServerProductCache(context, serverPreferences, store, client, uploadImmediately = false) { scheduled++ }
        val mxnzp = MxnzpBarcodeClient { _, _ ->
            apiCalls++
            BarcodeHttpResponse(200, """{"code":1,"data":{"barcode":"4006381333931","goodsName":"条码示例","standard":"10片","supplier":"厂家示例"}}""")
        }
        val lookup = ProductLookup(BundledCatalog(context.assets), preferences, mxnzpClient = mxnzp, cache = cache)
        assertTrue(lookup.lookup(code) is ProductLookupResult.Found)
        assertEquals(1, apiCalls); assertEquals(1, scheduled); assertEquals(1, store.status.value.pendingCount)
        val entry = store.pending(serverPreferences.credentials()!!.partition).single()
        assertEquals(CachePushResult.RETRY, client.push(code, entry.product, entry.source, serverPreferences.credentials()!!))
        store.complete(entry, CachePushResult.RETRY)
        assertTrue(lookup.lookup(code) is ProductLookupResult.Found)
        assertEquals(1, apiCalls)
        assertEquals(2, serverCalls) // 一次缓存查询、一次失败上传；第二次扫码复用本机缓存。
        assertTrue((context.applicationContext as TestCabinetApplication).repository.snapshot().medicines.isEmpty())
    }

    @Test fun `second device uses server cache before any API call`() = runBlocking {
        val preferences = LookupPreferences(context).apply { saveMxnzp("fixture-id", "fixture-secret") }
        val credentials = configuredPreferences().credentials()!!
        var remoteProduct: ProductInfo? = null
        var apiCalls = 0
        var uploads = 0
        val remoteCache = object : ProductCache {
            override suspend fun lookup(code: String): ProductLookupResult.Found? = remoteProduct?.let { ProductLookupResult.Found(it, "服务器缓存") }
            override suspend fun remember(code: String, product: ProductInfo, source: String) { uploads++; remoteProduct = product }
        }
        val api = MxnzpBarcodeClient { _, _ ->
            apiCalls++
            BarcodeHttpResponse(200, """{"code":1,"data":{"barcode":"4006381333931","goodsName":"条码示例"}}""")
        }
        val firstDevice = ProductLookup(BundledCatalog(context.assets), preferences, mxnzpClient = api, cache = remoteCache)
        val secondDevice = ProductLookup(BundledCatalog(context.assets), preferences, mxnzpClient = api, cache = remoteCache)
        assertTrue(firstDevice.lookup(code) is ProductLookupResult.Found)
        assertEquals("服务器缓存", (secondDevice.lookup(code) as ProductLookupResult.Found).source)
        assertEquals(1, apiCalls); assertEquals(1, uploads)
        assertEquals("https://cache.example.com", credentials.address)
    }

    @Test fun `local bundled matches and unknown codes never access cache server`() = runBlocking {
        val forbidden = object : ProductCache {
            override suspend fun lookup(code: String): ProductLookupResult.Found? { fail("不应查服务器"); return null }
            override suspend fun remember(code: String, product: ProductInfo, source: String) { fail("不应上传") }
        }
        val lookup = ProductLookup(BundledCatalog(context.assets), LookupPreferences(context), cache = forbidden)
        assertTrue(lookup.lookup("06902401045076") is ProductLookupResult.Found)
        assertTrue(lookup.lookup("https://example.com/trace") is ProductLookupResult.Manual)
    }

    @Test fun `configuration change partitions old pending data and disabled cache makes no requests`() = runBlocking {
        val preferences = configuredPreferences()
        val store = ServerCacheStore(context)
        val previous = preferences.credentials()!!.partition
        store.put(CachedProduct(previous, code, product, "mxnzp"))
        preferences.save("https://second.example.com", token)
        assertTrue(store.pending(preferences.credentials()!!.partition).isEmpty())
        preferences.setEnabled(false)
        val forbidden = ServerCacheClient { _, _, _, _ -> fail("关闭后不能联网"); BarcodeHttpResponse(500) }
        val cache = ServerProductCache(context, preferences, store, forbidden, uploadImmediately = false) { fail("关闭后不能安排上传") }
        assertNull(cache.lookup(code))
        cache.remember(code, product, "mxnzp")
        assertEquals(1, store.pending(previous).size)
    }

    // 固定域名更新只影响共享入口；模拟注册验证认证隔离和旧队列保留，不访问云端。
    @Test fun `custom domain update enrolls a new guest without moving old pending data`() = runBlocking {
        val oldAddress = "https://medicine-cabinet-catalog.2635178231.workers.dev"
        val previous = ServerCachePreferences(context, oldAddress, true, featureEnabled = true)
        previous.saveAutomaticToken(oldAddress, token)
        val oldPartition = previous.credentials()!!.partition
        val store = ServerCacheStore(context)
        store.put(CachedProduct(oldPartition, code, product, "mxnzp"))

        val current = ServerCachePreferences(context, featureEnabled = true)
        assertEquals("https://medicine-api.eecld.icu", BuildConfig.CATALOG_SERVER_URL)
        assertEquals(BuildConfig.CATALOG_SERVER_URL, current.settings.value.address)
        assertTrue(current.settings.value.enabled)
        assertFalse(current.settings.value.configured)
        assertNull(current.credentials())
        var enrollments = 0
        val freshToken = "fixture_new_guest_for_local_verification_only_12345"
        val client = ServerCacheClient { method, url, headers, body ->
            assertEquals("POST", method)
            assertEquals("https://medicine-api.eecld.icu/v1/clients", url.toString())
            assertTrue(headers.isEmpty())
            assertNull(body)
            enrollments++
            BarcodeHttpResponse(201, """{"token":"$freshToken"}""")
        }
        val credentials = ServerProductCache.ensureCredentials(current, client)!!
        assertEquals(freshToken, credentials.token)
        assertNotEquals(oldPartition, credentials.partition)
        assertTrue(store.pending(credentials.partition).isEmpty())
        assertEquals(1, store.pending(oldPartition).size)
        ServerProductCache.ensureCredentials(current, client)
        assertEquals(1, enrollments)

        val cache = ServerProductCache(context, current, store, client, uploadImmediately = false) {}
        cache.remember(code, product, "manual")
        assertEquals("manual", store.pending(credentials.partition).single().source)
        assertEquals("mxnzp", store.pending(oldPartition).single().source)
        assertEquals(1, enrollments)
    }

    @Test fun `custom domain update retains a disabled sharing preference`() = runBlocking {
        val oldAddress = "https://medicine-cabinet-catalog.2635178231.workers.dev"
        val previous = ServerCachePreferences(context, oldAddress, true, featureEnabled = true)
        previous.saveAutomaticToken(oldAddress, token)
        previous.setEnabled(false)
        val current = ServerCachePreferences(context, featureEnabled = true)
        assertEquals("https://medicine-api.eecld.icu", current.settings.value.address)
        assertFalse(current.settings.value.enabled)
        val forbidden = ServerCacheClient { _, _, _, _ ->
            fail("关闭共享时，域名更新不能注册新访客")
            BarcodeHttpResponse(500)
        }
        assertNull(ServerProductCache.ensureCredentials(current, forbidden))
    }

    @Test fun `upload handles conflict retry and rejection without returning secrets`() = runBlocking {
        val credentials = configuredPreferences().credentials()!!
        for ((status, expected) in listOf(429 to CachePushResult.RETRY, 503 to CachePushResult.RETRY, 401 to CachePushResult.REJECTED, 302 to CachePushResult.REJECTED)) {
            val client = ServerCacheClient { _, _, _, _ -> BarcodeHttpResponse(status, "fixture-secret") }
            assertEquals(expected, client.push(code, product, "mxnzp", credentials))
        }
        val conflict = ServerCacheClient { _, _, _, _ -> BarcodeHttpResponse(202, """{"status":"conflict_preserved","barcode":"$code"}""") }
        assertEquals(CachePushResult.CONFLICT, conflict.push(code, product, "mxnzp", credentials))
        val store = ServerCacheStore(context)
        val entry = CachedProduct(credentials.partition, code, product, "mxnzp")
        store.put(entry)
        store.complete(entry, CachePushResult.REJECTED)
        assertEquals(ServerCacheStore.MAX_ATTEMPTS, store.pending(credentials.partition).single().attempts)
        store.resetAttempts(credentials.partition)
        store.complete(store.pending(credentials.partition).single(), CachePushResult.CONFLICT)
        assertEquals(0, store.status.value.pendingCount)
    }

    @Test fun `manual confirmed identity is queued once without batch or location fields`() = runBlocking {
        val preferences = configuredPreferences()
        val store = ServerCacheStore(context)
        var scheduled = 0
        val forbidden = ServerCacheClient { _, _, _, _ -> fail("队列验证不应联网"); BarcodeHttpResponse(500) }
        val cache = ServerProductCache(context, preferences, store, forbidden, uploadImmediately = false) { scheduled++ }
        val medicine = Medicine(id = "fixture-id", name = "核对后的手动示例", specification = "12片", barcode = code,
            packageUnit = "瓶", lowStockThreshold = 3)
        cache.rememberConfirmed(medicine)
        cache.rememberConfirmed(medicine)
        val pending = store.pending(preferences.credentials()!!.partition).single()
        assertEquals("manual", pending.source)
        assertEquals("核对后的手动示例", pending.product.name)
        assertEquals("瓶", pending.product.packageUnit)
        assertEquals(1, scheduled)
        assertEquals(0, (context.applicationContext as TestCabinetApplication).repository.snapshot().batches.size)
    }

    @Test fun `built in target enrolls without user configuration and retains data when unavailable`() = runBlocking {
        val preferences = ServerCachePreferences(context, "https://cache.example.com", true, featureEnabled = true)
        var enrollments = 0
        val client = ServerCacheClient { method, url, headers, body ->
            assertEquals("POST", method); assertEquals("/v1/clients", url.path)
            assertTrue(headers.isEmpty()); assertNull(body)
            enrollments++
            BarcodeHttpResponse(201, """{"token":"$token"}""")
        }
        val credentials = ServerProductCache.ensureCredentials(preferences, client)!!
        assertEquals("https://cache.example.com", credentials.address)
        assertEquals(token, credentials.token)
        ServerProductCache.ensureCredentials(preferences, client)
        assertEquals(1, enrollments)
        val store = ServerCacheStore(context)
        val offline = ServerCacheClient { _, _, _, _ -> throw IOException("offline") }
        val cache = ServerProductCache(context, preferences, store, offline, uploadImmediately = false) {}
        cache.remember(code, product, "mxnzp")
        assertEquals(product, cache.lookup(code)!!.product)
        assertEquals(1, store.status.value.pendingCount)
    }

    @Test fun `foreground and background uploader do not send the same entry twice`() = runBlocking {
        val preferences = configuredPreferences()
        val credentials = preferences.credentials()!!
        val store = ServerCacheStore(context)
        val entry = CachedProduct(credentials.partition, code, product, "manual")
        store.put(entry)
        var posts = 0
        val client = ServerCacheClient { _, _, _, _ ->
            posts++
            BarcodeHttpResponse(201, """{"status":"stored","barcode":"$code"}""")
        }
        assertEquals(CachePushResult.ACCEPTED, CacheUploader.pushPending(entry, credentials, store, client))
        assertNull(CacheUploader.pushPending(entry, credentials, store, client))
        assertEquals(1, posts)
    }
}
