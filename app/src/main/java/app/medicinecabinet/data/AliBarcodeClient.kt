package app.medicinecabinet.data

import app.medicinecabinet.domain.*
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

data class BarcodeHttpResponse(val status: Int, val body: String = "",
    val gatewayCode: String? = null, val gatewayReason: AliGatewayReason? = null,
    val hasGatewayError: Boolean = false)

fun interface BarcodeTransport {
    fun get(url: URL, headers: Map<String, String>): BarcodeHttpResponse
}

fun defaultBarcodeTransport(connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) = BarcodeTransport { url, headers ->
    val connection = connectionFactory(url)
    try {
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 6_000
        connection.readTimeout = 8_000
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        connection.setRequestProperty("Accept", "application/json")
        val status = connection.responseCode
        val rawGatewayCode = connection.getHeaderField("X-Ca-Error-Code")
        val gatewayCode = AliGatewayErrors.safeCode(rawGatewayCode)
        val gatewayReason = AliGatewayErrors.reason(gatewayCode)
            ?: AliGatewayErrors.reasonFromMessage(connection.getHeaderField("X-Ca-Error-Message"))
        val hasGatewayError = !rawGatewayCode.isNullOrBlank() || gatewayReason != null
        // 450 的业务原因在返回体里；其他网关错误只保留经过白名单识别的原因。
        if (hasGatewayError || status !in listOf(200, 450)) BarcodeHttpResponse(status,
            gatewayCode = gatewayCode, gatewayReason = gatewayReason, hasGatewayError = hasGatewayError)
        else (if (status == 450) connection.errorStream else connection.inputStream)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 256 * 1024)
                output.write(buffer, 0, count)
            }
            BarcodeHttpResponse(status, output.toString(Charsets.UTF_8.name()))
        } ?: BarcodeHttpResponse(status)
    } finally { connection.disconnect() }
}

class AliBarcodeClient(private val transport: BarcodeTransport = defaultBarcodeTransport()) {
    suspend fun lookup(code: String, appCode: String): ProductLookupResult = withContext(Dispatchers.IO) {
        if (!BarcodeParser.validGtin(code)) return@withContext ProductLookupResult.Manual("此代码不能用于商品条码查询，请核对包装后填写。")
        try {
            val queryCode = if (code.length == 14 && code.startsWith('0')) code.drop(1) else code
            // 固定 HTTPS 服务，不跟随重定向；认证放请求头，避免进入网址和查询日志。
            val response = transport.get(URL("https://ali-barcode.showapi.com/barcode?code=$queryCode"),
                mapOf("Authorization" to "APPCODE $appCode"))
            val reason = response.gatewayReason ?: AliGatewayErrors.reason(response.gatewayCode)
            when {
                reason != null -> apiFailure(BarcodeService.ALIYUN, reason.outcome,
                    response.status, response.gatewayCode, reason)
                response.hasGatewayError -> apiFailure(BarcodeService.ALIYUN, ApiOutcome.SERVICE_ERROR, response.status)
                response.status == 200 -> decode(response.body, code)
                response.status == 450 -> decodeBusinessFailure(response.body, code)
                else -> apiHttpFailure(BarcodeService.ALIYUN, response.status)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SocketTimeoutException) {
            apiFailure(BarcodeService.ALIYUN, ApiOutcome.TIMEOUT)
        } catch (_: kotlinx.serialization.SerializationException) {
            apiFailure(BarcodeService.ALIYUN, ApiOutcome.INVALID_RESPONSE, 200)
        } catch (_: IllegalArgumentException) {
            apiFailure(BarcodeService.ALIYUN, ApiOutcome.INVALID_RESPONSE)
        } catch (_: Exception) {
            // 不显示服务商原始错误或异常消息，以免带出请求内容和认证信息。
            apiFailure(BarcodeService.ALIYUN, ApiOutcome.NETWORK_ERROR)
        }
    }

    /** 450 表示业务失败，只读取错误；不能把矛盾的成功字段用于预填。 */
    private fun decodeBusinessFailure(text: String, requestedCode: String): ProductLookupResult {
        val result = try { decode(text, requestedCode, 450) }
        catch (_: Exception) { return apiFailure(BarcodeService.ALIYUN, ApiOutcome.SERVICE_ERROR, 450) }
        return if (result is ProductLookupResult.Manual && result.attempts.single().outcome != ApiOutcome.INVALID_RESPONSE) result
        else apiFailure(BarcodeService.ALIYUN, ApiOutcome.SERVICE_ERROR, 450)
    }

    internal fun decode(text: String, requestedCode: String, httpStatus: Int = 200): ProductLookupResult {
        val envelope = Json.parseToJsonElement(text).jsonObject
        fun JsonObject.field(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
        val unwrappedFailure = httpStatus == 450 && "showapi_res_code" !in envelope && "ret_code" in envelope
        val status = if (unwrappedFailure) "0" else envelope.field("showapi_res_code")
        if (safeApiCode(status) == null) return apiFailure(BarcodeService.ALIYUN, ApiOutcome.INVALID_RESPONSE, httpStatus)
        if (status != "0") return apiFailure(BarcodeService.ALIYUN, when (status) {
            "-2", "-1009" -> ApiOutcome.RATE_LIMIT
            "-1004", "-1006", "-1007", "-1008", "-1010", "-1011", "-1012", "-1013", "-1014" -> ApiOutcome.AUTH_ERROR
            "-3" -> ApiOutcome.TIMEOUT
            "-4" -> ApiOutcome.INVALID_RESPONSE
            else -> ApiOutcome.SERVICE_ERROR
        }, httpStatus, status)
        val body = if (unwrappedFailure) envelope else envelope["showapi_res_body"] as? JsonObject
            ?: return apiFailure(BarcodeService.ALIYUN, ApiOutcome.INVALID_RESPONSE, httpStatus)
        val resultCode = body.field("ret_code")
        if (safeApiCode(resultCode) == null) return apiFailure(BarcodeService.ALIYUN, ApiOutcome.INVALID_RESPONSE, httpStatus)
        if (resultCode != "0") {
            // ret_code=-1 也可能是参数或服务错误，不能一律显示为未收录。
            val remark = body.field("remark").take(200)
            val outcome = if (resultCode == "-1") when {
                "未查到" in remark || "未收录" in remark || "现在可支持 UPC EAN-13 EAN-8" in remark -> ApiOutcome.NOT_FOUND
                "不正确" in remark || "无效" in remark -> ApiOutcome.INVALID_BARCODE
                else -> ApiOutcome.SERVICE_ERROR
            } else ApiOutcome.SERVICE_ERROR
            return apiFailure(BarcodeService.ALIYUN, outcome, httpStatus, resultCode)
        }
        val returnedCode = body.field("code")
        if (!BarcodeParser.validGtin(returnedCode) || returnedCode.padStart(14, '0') != requestedCode.padStart(14, '0')) {
            return apiFailure(BarcodeService.ALIYUN, ApiOutcome.INVALID_RESPONSE, httpStatus)
        }
        val name = body.field("goodsName").ifBlank { body.field("name") }
        val specification = body.field("spec")
        if (name.isBlank() || name.length > 80 || specification.length > 120) {
            return apiFailure(BarcodeService.ALIYUN, ApiOutcome.INVALID_RESPONSE, httpStatus)
        }
        val product = ProductInfo(name, specification, manufacturer = body.field("manuName").take(200),
            approval = body.field("approval").take(100))
        return ProductLookupResult.Found(product, "阿里云 · 万维易源",
            attempts = listOf(ApiAttempt(BarcodeService.ALIYUN, ApiOutcome.FOUND, httpStatus)))
    }
}
