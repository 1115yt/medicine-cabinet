package app.medicinecabinet.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import app.medicinecabinet.BuildConfig
import app.medicinecabinet.TestCabinetApplication
import app.medicinecabinet.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestCabinetApplication::class)
class LocalProductCacheTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val code = "04006381333931"
    private val product = ProductInfo("本机条码示例", "10片", "盒", "厂家示例")

    @Test fun `release disables server access even with legacy enabled preferences`() = runBlocking {
        val previous = context.getSharedPreferences("cabinet-server", Context.MODE_PRIVATE)
        previous.edit().putString("address", "https://cache.example.com")
            .putString("token", "fixture_legacy_token_for_verification_123456")
            .putBoolean("enabled", true).commit()
        val preferences = ServerCachePreferences(context, featureEnabled = false)
        assertFalse(preferences.settings.value.enabled)
        assertFalse(preferences.settings.value.available)
        assertNull(preferences.automaticAddress())
        assertNull(preferences.credentials())
        val forbidden = ServerCacheClient { _, _, _, _ ->
            fail("本版不应查询、注册或上传到服务器")
            BarcodeHttpResponse(500)
        }
        val cache = ServerProductCache(context, preferences, ServerCacheStore(context), forbidden) {
            fail("本版不应安排上传")
        }
        assertNull(cache.lookup(code))
        cache.remember(code, product, "mxnzp")
        assertTrue(previous.getBoolean("enabled", false))
        assertTrue(previous.contains("token"))
    }

    @Test fun `local API candidate is reused without another API call or upload queue`() = runBlocking {
        val preferences = LookupPreferences(context).apply { saveMxnzp("fixture-id", "fixture-secret") }
        var calls = 0
        val api = MxnzpBarcodeClient { _, _ ->
            calls++
            BarcodeHttpResponse(200, """{"code":1,"data":{"barcode":"4006381333931","goodsName":"本机条码示例","standard":"10片","supplier":"厂家示例"}}""")
        }
        val cache = LocalProductCache(context)
        val lookup = ProductLookup(BundledCatalog(context.assets), preferences, mxnzpClient = api, cache = cache)
        assertEquals(product, (lookup.lookup(code) as ProductLookupResult.Found).product)
        preferences.setMxnzpEnabled(false)
        val second = lookup.lookup(code) as ProductLookupResult.Found
        assertEquals(product, second.product)
        assertEquals("本机条码记忆", second.source)
        assertEquals(1, calls)
        assertEquals(product, LocalProductCache(context).lookup(code)!!.product)
        assertEquals(0, cache.store.status.value.pendingCount)
        assertTrue(cache.store.pending(LocalProductCache.PARTITION).isEmpty())
        assertTrue((context.applicationContext as TestCabinetApplication).repository.snapshot().medicines.isEmpty())
    }

    @Test fun `manual review updates only local identity and preserves packaging metadata`() = runBlocking {
        val cache = LocalProductCache(context)
        cache.remember(code, product, "mxnzp")
        val medicine = Medicine("local-fixture", "核对后的本机名称", "20片", code, packageUnit = "瓶")
        cache.rememberConfirmed(medicine)
        cache.rememberConfirmed(medicine)
        val found = LocalProductCache(context).lookup(code)!!
        assertEquals("核对后的本机名称", found.product.name)
        assertEquals("20片", found.product.specification)
        assertEquals("瓶", found.product.packageUnit)
        assertEquals("厂家示例", found.product.manufacturer)
        assertEquals("manual", found.origin)
        assertEquals(0, cache.store.status.value.pendingCount)
    }

    @Test fun `local cache is isolated from old pending uploads`() = runBlocking {
        val previous = ServerCacheStore(context)
        val entry = CachedProduct("legacy-server", code, product, "mxnzp")
        previous.put(entry)
        val cache = LocalProductCache(context)
        assertNull(cache.lookup(code))
        cache.remember(code, product.copy(name = "仅保存在本机"), "manual")
        assertEquals(entry, previous.pending("legacy-server").single())
        assertEquals(1, previous.status.value.pendingCount)
        assertEquals(0, cache.store.status.value.pendingCount)
        assertEquals("仅保存在本机", cache.lookup(code)!!.product.name)
    }

    @Test fun `unknown codes do not create a local candidate`() = runBlocking {
        val cache = LocalProductCache(context)
        cache.remember("https://example.com/trace", product, "manual")
        assertNull(cache.lookup("https://example.com/trace"))
        assertNull(cache.lookup(code))
    }

    @Test fun `upload scheduling is disabled and old worker exits successfully`() = runBlocking {
        ServerCachePreferences(context).setEnabled(false)
        ServerCacheWorker.schedule(context)
        assertTrue(WorkManager.getInstance(context).getWorkInfosForUniqueWork("cabinet-catalog-upload").get().isEmpty())
        val worker = TestListenableWorkerBuilder<ServerCacheWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
    }
}
