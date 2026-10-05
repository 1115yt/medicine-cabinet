package app.medicinecabinet.data

import app.medicinecabinet.domain.*
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BarcodeClientsTest {
    private val code = "04006381333931"
    private val aliBody = """{"showapi_res_code":0,"showapi_res_body":{"ret_code":"0","code":"4006381333931","goodsName":"资料示例","spec":"10片","manuName":"厂家示例","validity":"24个月"}}"""
    private val mxnzpBody = """{"code":1,"data":{"barcode":"4006381333931","goodsName":"商品示例","standard":"10片","supplier":"厂家示例"}}"""

    @Test fun `Ali uses a fixed HTTPS host and headers and prefills only identity`() = runBlocking {
        val client = AliBarcodeClient { url, headers ->
            assertEquals("https", url.protocol)
            assertEquals("ali-barcode.showapi.com", url.host)
            assertEquals("/barcode", url.path)
            assertEquals("code=4006381333931", url.query)
            assertEquals("APPCODE fixture-only-appcode", headers["Authorization"])
            BarcodeHttpResponse(200, aliBody)
        }
        val result = client.lookup(code, "fixture-only-appcode") as ProductLookupResult.Found
        assertEquals("资料示例", result.product.name)
        assertEquals("10片", result.product.specification)
    }
    @Test fun `Ali accepts number ret codes and rejects mismatched products`() = runBlocking {
        val client = AliBarcodeClient { _, _ -> BarcodeHttpResponse(200, aliBody.replace("\"0\"", "0")) }
        assertTrue(client.lookup(code, "fixture-key") is ProductLookupResult.Found)
        val mismatch = AliBarcodeClient { _, _ -> BarcodeHttpResponse(200, aliBody.replace("4006381333931", "6902538005141")) }
        assertTrue(mismatch.lookup(code, "fixture-key") is ProductLookupResult.Manual)
    }
    @Test fun `Ali errors never expose server messages or credentials`() = runBlocking {
        val client = AliBarcodeClient { _, _ -> throw IOException("fixture-secret") }
        val result = client.lookup(code, "fixture-secret") as ProductLookupResult.Manual
        assertFalse(result.explanation.contains("fixture-secret"))
        val unauthorized = AliBarcodeClient { _, _ -> BarcodeHttpResponse(403, "fixture-secret") }
        assertTrue((unauthorized.lookup(code, "fixture-secret") as ProductLookupResult.Manual).explanation.contains("认证"))
    }
    @Test fun `MXNZP uses header authentication with no secret in the URL`() = runBlocking {
        val client = MxnzpBarcodeClient { url, headers ->
            assertEquals("https", url.protocol)
            assertEquals("www.mxnzp.com", url.host)
            assertEquals("/api/barcode/goods/details", url.path)
            assertEquals("barcode=4006381333931", url.query)
            assertEquals("fixture-id", headers["app_id"])
            assertEquals("fixture-secret", headers["app_secret"])
            BarcodeHttpResponse(200, mxnzpBody)
        }
        val result = client.lookup(code, MxnzpCredentials("fixture-id", "fixture-secret")) as ProductLookupResult.Found
        assertEquals("商品示例", result.product.name)
        assertEquals("10片", result.product.specification)
        assertEquals("厂家示例", result.product.manufacturer)
    }
    @Test fun `MXNZP handles not collected unsupported and add success as non results`() = runBlocking {
        for ((status, expected) in listOf("10036" to "未收录", "100041" to "不支持", "10037" to "服务返回错误")) {
            val client = MxnzpBarcodeClient { _, _ -> BarcodeHttpResponse(200, """{"code":$status,"msg":"fixture-secret"}""") }
            val result = client.lookup(code, MxnzpCredentials("fixture-id", "fixture-secret")) as ProductLookupResult.Manual
            assertTrue(result.explanation.contains(expected))
            assertFalse(result.explanation.contains("fixture-secret"))
        }
    }
    @Test fun `MXNZP rejects a wrong barcode and malformed result`() = runBlocking {
        val wrong = MxnzpBarcodeClient { _, _ -> BarcodeHttpResponse(200, mxnzpBody.replace("4006381333931", "6902538005141")) }
        assertTrue(wrong.lookup(code, MxnzpCredentials("fixture-id", "fixture-secret")) is ProductLookupResult.Manual)
        val malformed = MxnzpBarcodeClient { _, _ -> BarcodeHttpResponse(200, "not json") }
        assertTrue(malformed.lookup(code, MxnzpCredentials("fixture-id", "fixture-secret")) is ProductLookupResult.Manual)
    }
    @Test fun `unknown trace codes and links never reach either server`() = runBlocking {
        val transport = BarcodeTransport { _, _ -> fail("未知代码不应联网"); BarcodeHttpResponse(500) }
        for (invalid in listOf("81001234567890123456", "https://example.com/trace", "invalid")) {
            assertTrue(AliBarcodeClient(transport).lookup(invalid, "fixture-key") is ProductLookupResult.Manual)
            assertTrue(MxnzpBarcodeClient(transport).lookup(invalid, MxnzpCredentials("fixture-id", "fixture-secret")) is ProductLookupResult.Manual)
        }
    }

    @Test fun `MXNZP classifies documented credentials quota account and unknown errors without raw messages`() = runBlocking {
        val cases = listOf("20004" to ApiOutcome.AUTH_ERROR, "20005" to ApiOutcome.AUTH_ERROR,
            "20003" to ApiOutcome.ACCOUNT_RESTRICTED, "101" to ApiOutcome.RATE_LIMIT, "102" to ApiOutcome.RATE_LIMIT,
            "103" to ApiOutcome.RATE_LIMIT, "104" to ApiOutcome.RATE_LIMIT, "110" to ApiOutcome.RATE_LIMIT,
            "10036" to ApiOutcome.NOT_FOUND, "10037" to ApiOutcome.SERVICE_ERROR, "999999" to ApiOutcome.SERVICE_ERROR)
        for ((status, outcome) in cases) {
            val client = MxnzpBarcodeClient { _, _ -> BarcodeHttpResponse(200, """{"code":$status,"msg":"fixture-private-content"}""") }
            val result = client.lookup(code, MxnzpCredentials("fixture-id", "fixture-secret"))
            assertEquals(outcome, result.attempts.single().outcome)
            assertEquals(status, result.attempts.single().errorCode)
            assertFalse((result as ProductLookupResult.Manual).explanation.contains("fixture-private-content"))
            assertEquals(outcome == ApiOutcome.NOT_FOUND, result.attempts.single().usableConnection)
        }
    }

    @Test fun `Ali keeps outer error codes distinct from not collected and invalid products`() = runBlocking {
        for ((status, outcome) in listOf("-2" to ApiOutcome.RATE_LIMIT, "-1009" to ApiOutcome.RATE_LIMIT,
            "-1004" to ApiOutcome.AUTH_ERROR, "-1007" to ApiOutcome.AUTH_ERROR,
            "-3" to ApiOutcome.TIMEOUT, "-4" to ApiOutcome.INVALID_RESPONSE, "-1" to ApiOutcome.SERVICE_ERROR)) {
            val client = AliBarcodeClient { _, _ -> BarcodeHttpResponse(200, """{"showapi_res_code":$status,"showapi_res_error":"fixture-private-content"}""") }
            val result = client.lookup(code, "fixture-key")
            assertEquals(outcome, result.attempts.single().outcome)
            assertEquals(status, result.attempts.single().errorCode)
            assertFalse((result as ProductLookupResult.Manual).explanation.contains("fixture-private-content"))
        }
        val missing = AliBarcodeClient { _, _ -> BarcodeHttpResponse(200,
            """{"showapi_res_code":0,"showapi_res_body":{"ret_code":-1,"remark":"未查到相关信息！"}}""") }
        assertEquals(ApiOutcome.NOT_FOUND, missing.lookup(code, "fixture-key").attempts.single().outcome)
        val unknown = AliBarcodeClient { _, _ -> BarcodeHttpResponse(200,
            """{"showapi_res_code":0,"showapi_res_body":{"ret_code":-1,"remark":"fixture-private-content"}}""") }
        assertEquals(ApiOutcome.SERVICE_ERROR, unknown.lookup(code, "fixture-key").attempts.single().outcome)
    }

    @Test fun `HTTP errors network failures timeouts and invalid JSON stay distinct`() = runBlocking {
        for ((status, outcome) in listOf(401 to ApiOutcome.AUTH_ERROR, 403 to ApiOutcome.AUTH_ERROR,
            429 to ApiOutcome.RATE_LIMIT, 500 to ApiOutcome.SERVICE_ERROR, 504 to ApiOutcome.TIMEOUT)) {
            val response = BarcodeTransport { _, _ -> BarcodeHttpResponse(status, "fixture-secret") }
            for (result in listOf(AliBarcodeClient(response).lookup(code, "fixture-key"),
                MxnzpBarcodeClient(response).lookup(code, MxnzpCredentials("fixture-id", "fixture-secret")))) {
                assertEquals(outcome, result.attempts.single().outcome)
                assertEquals(status, result.attempts.single().httpStatus)
                assertFalse((result as ProductLookupResult.Manual).explanation.contains("fixture-secret"))
            }
        }
        val offline = AliBarcodeClient { _, _ -> throw IOException("fixture-secret") }
        assertEquals(ApiOutcome.NETWORK_ERROR, offline.lookup(code, "fixture-key").attempts.single().outcome)
        val timeout = MxnzpBarcodeClient { _, _ -> throw java.net.SocketTimeoutException("fixture-secret") }
        assertEquals(ApiOutcome.TIMEOUT, timeout.lookup(code, MxnzpCredentials("fixture-id", "fixture-secret")).attempts.single().outcome)
        val malformed = AliBarcodeClient { _, _ -> BarcodeHttpResponse(200, "not json") }
        assertEquals(ApiOutcome.INVALID_RESPONSE, malformed.lookup(code, "fixture-key").attempts.single().outcome)
    }

    @Test fun `only numeric error codes can appear in feedback`() = runBlocking {
        val malformed = MxnzpBarcodeClient { _, _ -> BarcodeHttpResponse(200, """{"code":"fixture-secret","msg":"fixture-secret"}""") }
        val result = malformed.lookup(code, MxnzpCredentials("fixture-id", "fixture-secret")) as ProductLookupResult.Manual
        assertNull(result.attempts.single().errorCode)
        assertFalse(result.explanation.contains("fixture-secret"))
    }
}
