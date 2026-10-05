package app.medicinecabinet.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.medicinecabinet.TestCabinetApplication
import app.medicinecabinet.domain.*
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestCabinetApplication::class)
class ApiUsageTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val code = "04006381333931"
    private val aliFound = """{"showapi_res_code":0,"showapi_res_body":{"ret_code":0,"code":"4006381333931","goodsName":"查询示例","spec":"10片"}}"""
    private val mxFound = """{"code":1,"data":{"barcode":"4006381333931","goodsName":"查询示例","standard":"10片"}}"""
    private fun configured() = LookupPreferences(context).apply {
        saveMxnzp("fixture-id", "fixture-secret")
        saveAppCode("fixture-appcode-12345")
    }

    @Test fun `fallback preserves each result and counts actual provider requests once`() = runBlocking {
        val usage = ApiUsageStore(context)
        val queries = BarcodeApiQueries(configured(), usage) { url, _ ->
            if (url.host == "www.mxnzp.com") BarcodeHttpResponse(200, """{"code":20004,"msg":"fixture-secret"}""")
            else BarcodeHttpResponse(200, aliFound)
        }
        val lookup = ProductLookup(BundledCatalog(context.assets), configured(), cache = LocalProductCache(context), apiQueries = queries)
        val result = lookup.lookup(code) as ProductLookupResult.Found
        assertEquals(listOf(BarcodeService.MXNZP, BarcodeService.ALIYUN), result.attempts.map { it.service })
        assertEquals(listOf(ApiOutcome.AUTH_ERROR, ApiOutcome.FOUND), result.attempts.map { it.outcome })
        assertEquals("20004", result.attempts.first().errorCode)
        assertEquals(1L, usage.usage.value.getValue(BarcodeService.MXNZP).totalRequests)
        assertEquals(1L, usage.usage.value.getValue(BarcodeService.ALIYUN).totalRequests)
        assertTrue(lookup.lookup(code).attempts.isEmpty())
        assertEquals(1L, usage.usage.value.getValue(BarcodeService.MXNZP).totalRequests)
        assertEquals(1L, usage.usage.value.getValue(BarcodeService.ALIYUN).totalRequests)
    }

    @Test fun `when both APIs miss both not collected outcomes remain visible`() = runBlocking {
        val usage = ApiUsageStore(context)
        val preferences = configured()
        val queries = BarcodeApiQueries(preferences, usage) { url, _ ->
            if (url.host == "www.mxnzp.com") BarcodeHttpResponse(200, """{"code":10036}""")
            else BarcodeHttpResponse(200, """{"showapi_res_code":0,"showapi_res_body":{"ret_code":-1,"remark":"未查到相关信息！"}}""")
        }
        val result = ProductLookup(BundledCatalog(context.assets), preferences, apiQueries = queries).lookup(code)
        assertTrue(result is ProductLookupResult.Manual)
        assertEquals(listOf(ApiOutcome.NOT_FOUND, ApiOutcome.NOT_FOUND), result.attempts.map { it.outcome })
        assertEquals(listOf("10036", "-1"), result.attempts.map { it.errorCode })
        assertEquals(2L, usage.usage.value.values.sumOf { it.totalRequests })
    }

    @Test fun `MXNZP success prevents an unnecessary Ali request and local reuse shows no API source`() = runBlocking {
        val preferences = configured()
        val usage = ApiUsageStore(context)
        val queries = BarcodeApiQueries(preferences, usage) { url, _ ->
            assertEquals("www.mxnzp.com", url.host)
            BarcodeHttpResponse(200, mxFound)
        }
        val lookup = ProductLookup(BundledCatalog(context.assets), preferences, cache = LocalProductCache(context), apiQueries = queries)
        assertEquals(BarcodeService.MXNZP, lookup.lookup(code).attempts.single().service)
        assertTrue(lookup.lookup(code).attempts.isEmpty())
        assertEquals(1L, usage.usage.value.getValue(BarcodeService.MXNZP).totalRequests)
        assertEquals(0L, usage.usage.value.getValue(BarcodeService.ALIYUN).totalRequests)
    }

    @Test fun `bundled matches invalid codes and disabled providers do not consume API counts`() = runBlocking {
        val preferences = configured()
        val usage = ApiUsageStore(context)
        val queries = BarcodeApiQueries(preferences, usage) { _, _ -> fail("此情形不应联网"); BarcodeHttpResponse(500) }
        val lookup = ProductLookup(BundledCatalog(context.assets), preferences, apiQueries = queries)
        assertTrue(lookup.lookup("06902401045076") is ProductLookupResult.Found)
        assertTrue(lookup.lookup("trace-invalid").attempts.isEmpty())
        preferences.setEnabled(false)
        preferences.setMxnzpEnabled(false)
        assertTrue(lookup.lookup(code) is ProductLookupResult.Manual)
        assertEquals(0L, usage.usage.value.values.sumOf { it.totalRequests })
    }

    @Test fun `connection test bypasses catalogs uses only specified saved provider and saves no medicine`() = runBlocking {
        val preferences = configured().apply { setEnabled(false); setMxnzpEnabled(false) }
        val usage = ApiUsageStore(context)
        val hosts = mutableListOf<String>()
        val queries = BarcodeApiQueries(preferences, usage) { url, _ ->
            hosts.add(url.host)
            BarcodeHttpResponse(200, if (url.host == "www.mxnzp.com") """{"code":10036}"""
                else """{"showapi_res_code":0,"showapi_res_body":{"ret_code":-1,"remark":"未查到相关信息！"}}""")
        }
        val result = queries.lookup(BarcodeService.MXNZP, "06902401045076", testing = true)!!
        assertEquals(ApiOutcome.NOT_FOUND, result.attempts.single().outcome)
        val saved = ApiUsageStore(context).usage.value.getValue(BarcodeService.MXNZP)
        assertTrue(saved.connectionTest!!.usableConnection)
        assertTrue(saved.connectionTestAt.isNotBlank())
        assertEquals(1L, saved.totalRequests)
        assertEquals(0L, usage.usage.value.getValue(BarcodeService.ALIYUN).totalRequests)
        val ali = queries.lookup(BarcodeService.ALIYUN, "06902401045076", testing = true)!!
        assertEquals(ApiOutcome.NOT_FOUND, ali.attempts.single().outcome)
        assertEquals(listOf("www.mxnzp.com", "ali-barcode.showapi.com"), hosts)
        assertEquals(1L, usage.usage.value.getValue(BarcodeService.MXNZP).totalRequests)
        assertEquals(1L, usage.usage.value.getValue(BarcodeService.ALIYUN).totalRequests)
        assertTrue(usage.usage.value.getValue(BarcodeService.ALIYUN).connectionTest!!.usableConnection)
        assertTrue((context.applicationContext as TestCabinetApplication).repository.snapshot().medicines.isEmpty())
    }

    @Test fun `daily counts roll over while cumulative counts and tests survive recreation`() {
        var day = LocalDate.of(2026, 10, 4)
        val usage = ApiUsageStore(context, { day }, { "2026-10-04 10:00" })
        usage.requestStarted(BarcodeService.ALIYUN)
        usage.complete(ApiAttempt(BarcodeService.ALIYUN, ApiOutcome.AUTH_ERROR, 401), testing = true)
        day = day.plusDays(1)
        usage.refresh()
        assertEquals(0L, usage.usage.value.getValue(BarcodeService.ALIYUN).todayRequests)
        usage.requestStarted(BarcodeService.ALIYUN)
        val restored = ApiUsageStore(context, { day }).usage.value.getValue(BarcodeService.ALIYUN)
        assertEquals(2L, restored.totalRequests)
        assertEquals(1L, restored.todayRequests)
        assertEquals(ApiOutcome.AUTH_ERROR, restored.connectionTest!!.outcome)
        usage.invalidateConnectionTest(BarcodeService.ALIYUN)
        assertNull(usage.usage.value.getValue(BarcodeService.ALIYUN).connectionTest)
        assertEquals(2L, usage.usage.value.getValue(BarcodeService.ALIYUN).totalRequests)
    }

    @Test fun `unconfigured tests invalid input and cancelled requests keep truthful counts`() = runBlocking {
        val preferences = LookupPreferences(context)
        val usage = ApiUsageStore(context)
        val queries = BarcodeApiQueries(preferences, usage) { _, _ -> throw CancellationException("测试取消") }
        assertNull(queries.lookup(BarcodeService.MXNZP, code, testing = true))
        assertEquals(0L, usage.usage.value.getValue(BarcodeService.MXNZP).totalRequests)
        preferences.saveMxnzp("fixture-id", "fixture-secret")
        assertTrue(queries.lookup(BarcodeService.MXNZP, "invalid", testing = true)!!.attempts.isEmpty())
        assertEquals(0L, usage.usage.value.getValue(BarcodeService.MXNZP).totalRequests)
        try { queries.lookup(BarcodeService.MXNZP, code, testing = true); fail("取消应继续传播") }
        catch (_: CancellationException) { }
        assertEquals(1L, usage.usage.value.getValue(BarcodeService.MXNZP).totalRequests)
        assertEquals(ApiOutcome.CANCELLED, usage.usage.value.getValue(BarcodeService.MXNZP).connectionTest!!.outcome)
    }

    @Test fun `usage stores service result and counters without barcode product or authentication`() = runBlocking {
        val usage = ApiUsageStore(context)
        val queries = BarcodeApiQueries(configured(), usage) { _, _ -> BarcodeHttpResponse(200, mxFound) }
        queries.lookup(BarcodeService.MXNZP, code, testing = true)
        val stored = context.getSharedPreferences("cabinet-api-usage", Context.MODE_PRIVATE).all.toString()
        for (privateValue in listOf(code, "查询示例", "fixture-id", "fixture-secret", "fixture-appcode-12345")) {
            assertFalse("统计不能包含资料或认证：$privateValue", stored.contains(privateValue))
        }
    }

    @Test fun `Ali gateway diagnostics persist without changing MXNZP counts or retaining raw messages`() = runBlocking {
        val usage = ApiUsageStore(context)
        val queries = BarcodeApiQueries(configured(), usage) { _, _ ->
            BarcodeHttpResponse(403, "fixture-only-private-response", "B403MQ", AliGatewayReason.QUOTA_EXHAUSTED)
        }
        queries.lookup(BarcodeService.ALIYUN, code, testing = true)
        val restored = ApiUsageStore(context).usage.value
        assertEquals(1L, restored.getValue(BarcodeService.ALIYUN).totalRequests)
        assertEquals(0L, restored.getValue(BarcodeService.MXNZP).totalRequests)
        assertEquals("B403MQ", restored.getValue(BarcodeService.ALIYUN).connectionTest!!.errorCode)
        assertEquals(AliGatewayReason.QUOTA_EXHAUSTED, restored.getValue(BarcodeService.ALIYUN).connectionTest!!.gatewayReason)
        assertTrue(restored.getValue(BarcodeService.ALIYUN).connectionTest!!.description(true).contains("次数耗尽"))
        assertFalse(context.getSharedPreferences("cabinet-api-usage", Context.MODE_PRIVATE).all.toString().contains("fixture-only-private-response"))
    }
}
