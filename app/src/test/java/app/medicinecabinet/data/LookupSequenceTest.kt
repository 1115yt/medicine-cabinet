package app.medicinecabinet.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.medicinecabinet.TestCabinetApplication
import app.medicinecabinet.domain.*
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 用受控响应核对查询先后次序，避免只根据最终结果推断是否并发或混计。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestCabinetApplication::class)
class LookupSequenceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val code = "04006381333931"
    private val mxFound = """{"code":1,"data":{"barcode":"4006381333931","goodsName":"顺序测试","standard":"10片"}}"""
    private val aliFound = """{"showapi_res_code":0,"showapi_res_body":{"ret_code":0,"code":"4006381333931","goodsName":"顺序测试","spec":"10片"}}"""

    private fun configured() = LookupPreferences(context).apply {
        saveMxnzp("fixture-id", "fixture-secret")
        saveAppCode("fixture-appcode-12345")
    }

    @Test fun `Ali waits for MXNZP miss to finish before its request and count start`() {
        assertMxnzpFinishesFirst(BarcodeHttpResponse(200, """{"code":10036}"""), fallback = true)
    }

    @Test fun `delayed MXNZP success prevents Ali request and count completely`() {
        assertMxnzpFinishesFirst(BarcodeHttpResponse(200, mxFound), fallback = false)
    }

    private fun assertMxnzpFinishesFirst(response: BarcodeHttpResponse, fallback: Boolean) = runBlocking {
        val preferences = configured()
        val usage = ApiUsageStore(context)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val mxStarted = CountDownLatch(1)
        val releaseMx = CountDownLatch(1)
        val aliStarted = CountDownLatch(1)
        val queries = BarcodeApiQueries(preferences, usage) { url, _ ->
            when (url.host) {
                "www.mxnzp.com" -> {
                    events.add("mx-start")
                    mxStarted.countDown()
                    check(releaseMx.await(10, TimeUnit.SECONDS)) { "测试未释放 MXNZP 响应。" }
                    events.add("mx-response")
                    response
                }
                "ali-barcode.showapi.com" -> {
                    events.add("ali-start")
                    aliStarted.countDown()
                    assertTrue("仅在 MXNZP 未查到可用资料后回退。", fallback)
                    assertEquals("阿里云启动前，MXNZP 结果应已处理完成。", ApiOutcome.NOT_FOUND,
                        usage.usage.value.getValue(BarcodeService.MXNZP).lastResult?.outcome)
                    BarcodeHttpResponse(200, aliFound)
                }
                else -> error("不应请求其他地址。")
            }
        }
        val lookup = ProductLookup(BundledCatalog(context.assets), preferences, apiQueries = queries)
        val pending = async(Dispatchers.Default) { lookup.lookup(code) }
        try {
            assertTrue("应先发起 MXNZP 请求。", mxStarted.await(10, TimeUnit.SECONDS))
            // 第一家明确仍未返回，第二家此时不能启动，也不能提前增加次数。
            assertFalse("MXNZP 等待时不能同时调用阿里云。", aliStarted.await(200, TimeUnit.MILLISECONDS))
            assertFalse(pending.isCompleted)
            assertEquals(listOf("mx-start"), events.toList())
            assertCounts(usage, mx = 1, ali = 0)
        } finally {
            releaseMx.countDown()
        }
        val result = withTimeout(10_000) { pending.await() }
        assertTrue(result is ProductLookupResult.Found)
        assertEquals(if (fallback) listOf("mx-start", "mx-response", "ali-start")
            else listOf("mx-start", "mx-response"), events.toList())
        assertEquals(if (fallback) listOf(BarcodeService.MXNZP, BarcodeService.ALIYUN)
            else listOf(BarcodeService.MXNZP), result.attempts.map { it.service })
        assertCounts(usage, mx = 1, ali = if (fallback) 1 else 0)
    }

    @Test fun `enabling one provider only counts that provider and distinct counts survive recreation`() = runBlocking {
        val preferences = configured()
        val usage = ApiUsageStore(context)
        val hosts = mutableListOf<String>()
        val queries = BarcodeApiQueries(preferences, usage) { url, _ ->
            hosts.add(url.host)
            BarcodeHttpResponse(200, if (url.host == "www.mxnzp.com") mxFound else aliFound)
        }
        // 此测试不接缓存，让每次调用都实际经过传输入口。
        val lookup = ProductLookup(BundledCatalog(context.assets), preferences, apiQueries = queries)
        preferences.setMxnzpEnabled(false)
        repeat(3) { assertEquals(BarcodeService.ALIYUN, lookup.lookup(code).attempts.single().service) }
        assertCounts(usage, mx = 0, ali = 3)
        preferences.setMxnzpEnabled(true)
        preferences.setEnabled(false)
        repeat(2) { assertEquals(BarcodeService.MXNZP, lookup.lookup(code).attempts.single().service) }
        assertEquals(List(3) { "ali-barcode.showapi.com" } + List(2) { "www.mxnzp.com" }, hosts)
        assertCounts(usage, mx = 2, ali = 3)
        assertCounts(ApiUsageStore(context), mx = 2, ali = 3)
    }

    @Test fun `pending local cache lookup starts no API and a cached hit ends the query`() = runBlocking {
        val preferences = configured()
        val usage = ApiUsageStore(context)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cached = ProductLookupResult.Found(ProductInfo("本机资料"), "本机条码记忆")
        val cache = object : ProductCache {
            override suspend fun lookup(code: String): ProductLookupResult.Found {
                started.complete(Unit)
                release.await()
                return cached
            }
            override suspend fun remember(code: String, product: ProductInfo, source: String) {
                fail("本机命中不需要重新缓存 API 结果。")
            }
        }
        val queries = BarcodeApiQueries(preferences, usage) { _, _ -> error("本机命中不应联网。") }
        val lookup = ProductLookup(BundledCatalog(context.assets), preferences, cache = cache, apiQueries = queries)
        val pending = async(Dispatchers.Default) { lookup.lookup(code) }
        try {
            withTimeout(10_000) { started.await() }
            assertFalse(pending.isCompleted)
            assertCounts(usage, mx = 0, ali = 0)
        } finally {
            release.complete(Unit)
        }
        assertEquals(cached, withTimeout(10_000) { pending.await() })
        assertCounts(usage, mx = 0, ali = 0)
    }

    @Test fun `bundled hit precedes local cache and all network queries`() = runBlocking {
        val preferences = configured()
        val usage = ApiUsageStore(context)
        val cache = object : ProductCache {
            override suspend fun lookup(code: String): ProductLookupResult.Found? = error("内置命中后不应继续查缓存。")
            override suspend fun remember(code: String, product: ProductInfo, source: String) = error("内置命中不应记作 API 缓存。")
        }
        val queries = BarcodeApiQueries(preferences, usage) { _, _ -> error("内置命中不应联网。") }
        val result = ProductLookup(BundledCatalog(context.assets), preferences, cache = cache, apiQueries = queries)
            .lookup("06902401045076")
        assertTrue(result is ProductLookupResult.Found)
        assertTrue(result.attempts.isEmpty())
        assertCounts(usage, mx = 0, ali = 0)
    }

    private fun assertCounts(usage: ApiUsageStore, mx: Long, ali: Long) {
        for ((service, count) in mapOf(BarcodeService.MXNZP to mx, BarcodeService.ALIYUN to ali)) {
            assertEquals("${service.label}累计次数", count, usage.usage.value.getValue(service).totalRequests)
            assertEquals("${service.label}今日次数", count, usage.usage.value.getValue(service).todayRequests)
        }
    }
}
