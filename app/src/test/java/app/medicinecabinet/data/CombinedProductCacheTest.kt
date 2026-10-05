package app.medicinecabinet.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.medicinecabinet.TestCabinetApplication
import app.medicinecabinet.BuildConfig
import app.medicinecabinet.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestCabinetApplication::class)
class CombinedProductCacheTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val code = "04006381333931"
    private val product = ProductInfo("共享测试资料", "10片", "盒", "测试厂家", "测试批准号")
    private val token = "fixture_shared_token_for_verification_123456"
    private fun preferences() = ServerCachePreferences(context, "https://catalog.example.com", true, true).apply {
        saveAutomaticToken("https://catalog.example.com", token)
    }
    private fun combined(prefs: ServerCachePreferences = preferences(),
        transport: CacheTransport = CacheTransport { _, _, _, _ -> fail("不应发出网络请求"); BarcodeHttpResponse(500) }) =
        CombinedProductCache(LocalProductCache(context), ServerProductCache(context, prefs, ServerCacheStore(context),
            ServerCacheClient(transport), uploadImmediately = false, enqueue = {}))

    @Test fun `application ID namespace and code package are consistent`() {
        assertEquals("app.medicinecabinet", BuildConfig.APPLICATION_ID)
        assertEquals("app.medicinecabinet", TestCabinetApplication::class.java.`package`!!.name)
    }
    @Test fun `local match skips D1 enrollment and lookup`() = runBlocking {
        val cache = combined()
        cache.local.remember(code, product, "manual")
        assertEquals(product, cache.lookup(code)!!.product)
        assertEquals(0, cache.shared.store.status.value.pendingCount)
    }
    @Test fun `D1 match stops paid API lookup and reuses local shared copy`() = runBlocking {
        var calls = 0
        val cache = combined(transport = CacheTransport { method, url, _, _ ->
            assertEquals("GET", method)
            assertEquals("/v1/catalog/$code", url.path)
            calls++
            BarcodeHttpResponse(200, """{"barcode":"$code","name":"共享测试资料","specification":"10片","packageUnit":"盒","manufacturer":"测试厂家","approval":"测试批准号","source":"manual","verified":false}""")
        })
        val lookupPrefs = LookupPreferences(context).apply { saveMxnzp("fixture-id", "fixture-secret") }
        val lookup = ProductLookup(BundledCatalog(context.assets), lookupPrefs,
            mxnzpClient = MxnzpBarcodeClient { _, _ -> fail("D1 命中不能调用收费服务"); BarcodeHttpResponse(500) }, cache = cache)
        assertEquals(product, (lookup.lookup(code) as ProductLookupResult.Found).product)
        assertEquals(product, (lookup.lookup(code) as ProductLookupResult.Found).product)
        assertEquals(1, calls)
    }
    @Test fun `API results are stored locally and queue only allowed identity fields`() = runBlocking {
        val cache = combined()
        cache.remember(code, product, "aliyun")
        assertEquals(product, cache.local.lookup(code)!!.product)
        val entry = cache.shared.store.pending(preferences().credentials()!!.partition).single()
        assertEquals(product, entry.product)
        assertEquals("aliyun", entry.source)
        assertEquals(1, cache.shared.store.status.value.pendingCount)
        assertEquals(0, cache.local.store.status.value.pendingCount)
    }
    @Test fun `manual confirmation preserves metadata and replaces pending candidate`() = runBlocking {
        val cache = combined()
        cache.remember(code, product, "mxnzp")
        cache.rememberConfirmed(Medicine("fixture", "用户核对后的名称", "20片", code, packageUnit = "瓶"))
        val entry = cache.shared.store.pending(preferences().credentials()!!.partition).single()
        assertEquals("用户核对后的名称", entry.product.name)
        assertEquals("测试厂家", entry.product.manufacturer)
        assertEquals("测试批准号", entry.product.approval)
        assertEquals("manual", entry.source)
    }
    @Test fun `disabled shared service keeps new records local and preserves pending queue`() = runBlocking {
        val prefs = preferences()
        val cache = combined(prefs)
        cache.remember(code, product, "manual")
        prefs.setEnabled(false)
        cache.remember(code, product.copy(name = "关闭共享后的核对资料"), "manual")
        assertEquals("关闭共享后的核对资料", cache.lookup(code)!!.product.name)
        assertEquals(product, cache.shared.store.pending(ServerCacheCredentials("https://catalog.example.com", "").partition).single().product)
    }
    @Test fun `legacy target cannot replace fixed D1 endpoint or leak old token`() {
        val old = context.getSharedPreferences("cabinet-server", Context.MODE_PRIVATE)
        old.edit().putString("address", "https://legacy.example.com").putString("token", token).commit()
        val prefs = ServerCachePreferences(context, "https://catalog.example.com", true, true)
        assertEquals("https://catalog.example.com", prefs.automaticAddress())
        assertNull(prefs.credentials())
        assertFalse(prefs.settings.value.toString().contains(token))
        prefs.saveAutomaticToken("https://catalog.example.com", token)
        assertEquals("https://catalog.example.com", prefs.credentials()!!.address)
    }
}
