package app.medicinecabinet.data

import app.medicinecabinet.domain.*
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** 全部使用模拟连接，覆盖网关头和 HTTP 450 返回体，不消耗真实账号额度。 */
class AliGatewayErrorsTest {
    private val code = "04006381333931"
    private val foundBody = """{"showapi_res_code":0,"showapi_res_body":{"ret_code":0,"code":"4006381333931","goodsName":"示例资料"}}"""

    @Test fun `documented gateway codes show distinct reasons and safe codes`() = runBlocking {
        val cases = mapOf("A400AC" to AliGatewayReason.INVALID_APPCODE, "A401AC" to AliGatewayReason.INVALID_APPCODE,
            "A400IK" to AliGatewayReason.INVALID_APPKEY, "I403AA" to AliGatewayReason.UNAUTHORIZED,
            "B403MQ" to AliGatewayReason.QUOTA_EXHAUSTED, "B403ME" to AliGatewayReason.SUBSCRIPTION_EXPIRED,
            "B403OD" to AliGatewayReason.PROVIDER_OVERDUE, "T429ID" to AliGatewayReason.THROTTLED,
            "D504TO" to AliGatewayReason.BACKEND_TIMEOUT, "I404NF" to AliGatewayReason.API_NOT_FOUND,
            "A403PR" to AliGatewayReason.PLUGIN_AUTH, "N502RE" to AliGatewayReason.RESPONSE_TRANSPORT)
        for ((errorCode, reason) in cases) {
            val httpStatus = errorCode.substring(1, 4).toInt()
            val client = AliBarcodeClient { _, _ -> BarcodeHttpResponse(httpStatus, gatewayCode = errorCode) }
            val result = client.lookup(code, "fixture-only-key") as ProductLookupResult.Manual
            assertEquals(reason.outcome, result.attempts.single().outcome)
            assertEquals(errorCode, result.attempts.single().errorCode)
            assertEquals(httpStatus, result.attempts.single().httpStatus)
            assertEquals(reason, result.attempts.single().gatewayReason)
            assertTrue(result.explanation.contains(reason.explanation))
            assertTrue(result.explanation.contains("错误码 $errorCode"))
            assertFalse(result.attempts.single().usableConnection)
        }
    }

    @Test fun `legacy fixed gateway names also remain distinguishable`() = runBlocking {
        for ((name, reason) in mapOf("Quota Exhausted" to AliGatewayReason.QUOTA_EXHAUSTED,
            "Quota Expired" to AliGatewayReason.SUBSCRIPTION_EXPIRED, "User Arrears" to AliGatewayReason.USER_ARREARS,
            "Unauthorized" to AliGatewayReason.UNAUTHORIZED, "Invalid AppSecret" to AliGatewayReason.INVALID_SECRET)) {
            val connection = FakeConnection(403, mapOf("X-Ca-Error-Code" to name))
            val result = AliBarcodeClient(defaultBarcodeTransport { connection }).lookup(code, "fixture-only-key")
            assertEquals(reason, result.attempts.single().gatewayReason)
            assertEquals(name, result.attempts.single().errorCode)
            assertFalse(connection.bodyRead)
            assertTrue(connection.disconnected)
        }
    }

    @Test fun `transport gives documented code priority over misleading raw header message`() = runBlocking {
        val connection = FakeConnection(403, mapOf("X-Ca-Error-Code" to "B403MQ",
            "X-Ca-Error-Message" to "Invalid AppCode fixture-only-secret"), foundBody)
        val result = AliBarcodeClient(defaultBarcodeTransport { connection }).lookup(code, "fixture-only-key")
        assertEquals(AliGatewayReason.QUOTA_EXHAUSTED, result.attempts.single().gatewayReason)
        assertEquals("B403MQ", result.attempts.single().errorCode)
        assertFalse(result.toString().contains("fixture-only-secret"))
        assertFalse(connection.bodyRead)
        assertEquals("APPCODE fixture-only-key", connection.getRequestProperty("Authorization"))
        assertEquals("GET", connection.requestMethod)
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.disconnected)
    }

    @Test fun `known messages translate without retaining appended private values`() = runBlocking {
        for ((message, reason) in mapOf("Invalid AppCode: fixture-only-secret" to AliGatewayReason.INVALID_APPCODE,
            "User Arrears fixture-only-secret" to AliGatewayReason.USER_ARREARS,
            "Api Market Subscription expired fixture-only-secret" to AliGatewayReason.SUBSCRIPTION_EXPIRED)) {
            val connection = FakeConnection(403, mapOf("X-Ca-Error-Message" to message))
            val result = AliBarcodeClient(defaultBarcodeTransport { connection }).lookup(code, "fixture-only-key")
            assertEquals(reason, result.attempts.single().gatewayReason)
            assertNull(result.attempts.single().errorCode)
            assertFalse(Json.encodeToString(result.attempts.single()).contains("fixture-only-secret"))
        }
    }

    @Test fun `unknown gateway text is neither exposed nor treated as a successful product`() = runBlocking {
        val connection = FakeConnection(200, mapOf("X-Ca-Error-Code" to "fixture-only-secret",
            "X-Ca-Error-Message" to "fixture-only-private-message"), foundBody)
        val response = defaultBarcodeTransport { connection }.get(connection.url, emptyMap())
        assertTrue(response.hasGatewayError)
        assertFalse(response.toString().contains("fixture-only"))
        val result = AliBarcodeClient { _, _ -> response }.lookup(code, "fixture-only-key")
        assertEquals(ApiOutcome.SERVICE_ERROR, result.attempts.single().outcome)
        assertNull(result.attempts.single().errorCode)
        assertTrue(result is ProductLookupResult.Manual)
    }

    @Test fun `HTTP 450 reads error stream and retains actual not collected code`() = runBlocking {
        val connection = FakeConnection(450, body = """{"showapi_res_code":0,"showapi_res_body":{"ret_code":-1,"remark":"未查到相关信息！"}}""")
        val result = AliBarcodeClient(defaultBarcodeTransport { connection }).lookup(code, "fixture-only-key") as ProductLookupResult.Manual
        assertEquals(ApiOutcome.NOT_FOUND, result.attempts.single().outcome)
        assertEquals(450, result.attempts.single().httpStatus)
        assertEquals("-1", result.attempts.single().errorCode)
        assertTrue(result.attempts.single().usableConnection)
        assertTrue(result.explanation.contains("未收录"))
        assertTrue(result.explanation.contains("HTTP 450"))
        assertTrue(connection.errorBodyRead)
        assertFalse(connection.successBodyRead)
        assertTrue(connection.disconnected)
    }

    @Test fun `HTTP 450 unwrapped invalid barcode and outer service errors keep their codes`() = runBlocking {
        for ((body, expected, errorCode) in listOf(
            Triple("""{"ret_code":-1,"remark":"条码不正确！"}""", ApiOutcome.INVALID_BARCODE, "-1"),
            Triple("""{"showapi_res_code":-1009,"showapi_res_error":"fixture-only-secret"}""", ApiOutcome.RATE_LIMIT, "-1009"))) {
            val result = AliBarcodeClient { _, _ -> BarcodeHttpResponse(450, body) }.lookup(code, "fixture-only-key")
            assertEquals(expected, result.attempts.single().outcome)
            assertEquals(errorCode, result.attempts.single().errorCode)
            assertEquals(450, result.attempts.single().httpStatus)
            assertFalse(result.toString().contains("fixture-only-secret"))
        }
    }

    @Test fun `HTTP 450 unknown or contradictory responses do not invent a cause or prefill`() = runBlocking {
        for (body in listOf("", "<html>fixture-only-secret</html>", "{}", foundBody)) {
            val result = AliBarcodeClient { _, _ -> BarcodeHttpResponse(450, body) }.lookup(code, "fixture-only-key") as ProductLookupResult.Manual
            assertEquals(ApiOutcome.SERVICE_ERROR, result.attempts.single().outcome)
            assertEquals(450, result.attempts.single().httpStatus)
            assertNull(result.attempts.single().errorCode)
            assertTrue(result.explanation.contains("未取得可识别的具体原因"))
            assertFalse(result.explanation.contains("fixture-only"))
            assertFalse(result.attempts.single().usableConnection)
        }
    }

    @Test fun `transport bounds error response size and disconnects on overflow`() {
        val connection = FakeConnection(450, body = "x".repeat(256 * 1024 + 1))
        try {
            defaultBarcodeTransport { connection }.get(connection.url, emptyMap())
            fail("过大的响应必须拒绝")
        } catch (_: IllegalArgumentException) { }
        assertTrue(connection.disconnected)
    }

    @Test fun `gateway feedback survives serialization and old results still decode`() {
        val old = Json.decodeFromString<ApiAttempt>("""{"service":"ALIYUN","outcome":"NOT_FOUND","httpStatus":200,"errorCode":"-1"}""")
        assertNull(old.gatewayReason)
        assertTrue(old.usableConnection)
        val current = ApiAttempt(BarcodeService.ALIYUN, ApiOutcome.RATE_LIMIT, 403, "B403MQ", AliGatewayReason.QUOTA_EXHAUSTED)
        assertEquals(current, Json.decodeFromString<ApiAttempt>(Json.encodeToString(current)))
        assertFalse(ApiAttempt(BarcodeService.ALIYUN, ApiOutcome.AUTH_ERROR, 403, "fixture-only-secret").description().contains("fixture-only-secret"))
        assertFalse(ApiAttempt(BarcodeService.MXNZP, ApiOutcome.AUTH_ERROR, 403, "B403MQ").description().contains("B403MQ"))
    }

    private class FakeConnection(private val status: Int, private val responseHeaders: Map<String, String> = emptyMap(),
        private val body: String = "") : HttpURLConnection(URL("https://ali-barcode.showapi.com/barcode?code=4006381333931")) {
        var disconnected = false
        var errorBodyRead = false
        var successBodyRead = false
        val bodyRead get() = errorBodyRead || successBodyRead
        override fun getResponseCode() = status
        override fun getHeaderField(name: String): String? = responseHeaders[name]
        override fun getErrorStream(): InputStream { errorBodyRead = true; return ByteArrayInputStream(body.toByteArray()) }
        override fun getInputStream(): InputStream { successBodyRead = true; return ByteArrayInputStream(body.toByteArray()) }
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun connect() { }
    }
}
