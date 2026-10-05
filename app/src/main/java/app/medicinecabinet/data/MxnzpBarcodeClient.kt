package app.medicinecabinet.data

import app.medicinecabinet.domain.*
import java.net.URL
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class MxnzpBarcodeClient(private val transport: BarcodeTransport = defaultBarcodeTransport()) {
    suspend fun lookup(code: String, credentials: MxnzpCredentials): ProductLookupResult = withContext(Dispatchers.IO) {
        if (!BarcodeParser.validGtin(code)) return@withContext ProductLookupResult.Manual("此代码不能用于商品条码查询，请手动核对包装。")
        try {
            val queryCode = if (code.length == 14 && code.startsWith('0')) code.drop(1) else code
            // 服务商支持请求头认证，网址中仅包含商品条码。
            val response = transport.get(URL("https://www.mxnzp.com/api/barcode/goods/details?barcode=$queryCode"),
                mapOf("app_id" to credentials.appId, "app_secret" to credentials.appSecret))
            if (response.status != 200) return@withContext apiHttpFailure(BarcodeService.MXNZP, response.status)
            decode(response.body, code)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SocketTimeoutException) {
            apiFailure(BarcodeService.MXNZP, ApiOutcome.TIMEOUT)
        } catch (_: kotlinx.serialization.SerializationException) {
            apiFailure(BarcodeService.MXNZP, ApiOutcome.INVALID_RESPONSE, 200)
        } catch (_: IllegalArgumentException) {
            apiFailure(BarcodeService.MXNZP, ApiOutcome.INVALID_RESPONSE)
        } catch (_: Exception) {
            apiFailure(BarcodeService.MXNZP, ApiOutcome.NETWORK_ERROR)
        }
    }

    internal fun decode(text: String, requestedCode: String): ProductLookupResult {
        val envelope = Json.parseToJsonElement(text).jsonObject
        fun JsonObject.field(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
        val status = envelope.field("code")
        if (safeApiCode(status) == null) return apiFailure(BarcodeService.MXNZP, ApiOutcome.INVALID_RESPONSE, 200)
        if (status != "1") return apiFailure(BarcodeService.MXNZP, when (status) {
            "20004", "20005" -> ApiOutcome.AUTH_ERROR
            "20003" -> ApiOutcome.ACCOUNT_RESTRICTED
            "101", "102", "103", "104", "110" -> ApiOutcome.RATE_LIMIT
            "10036" -> ApiOutcome.NOT_FOUND
            "100041", "10032", "10039" -> ApiOutcome.INVALID_BARCODE
            else -> ApiOutcome.SERVICE_ERROR
        }, 200, status)
        val body = envelope["data"] as? JsonObject ?: return apiFailure(BarcodeService.MXNZP, ApiOutcome.INVALID_RESPONSE, 200)
        val returnedCode = body.field("barcode")
        if (!BarcodeParser.validGtin(returnedCode) || returnedCode.padStart(14, '0') != requestedCode.padStart(14, '0')) {
            return apiFailure(BarcodeService.MXNZP, ApiOutcome.INVALID_RESPONSE, 200)
        }
        val name = body.field("goodsName")
        val spec = body.field("standard")
        if (name.isBlank() || name.length > 80 || spec.length > 120) return apiFailure(BarcodeService.MXNZP, ApiOutcome.INVALID_RESPONSE, 200)
        return ProductLookupResult.Found(ProductInfo(name, spec, manufacturer = body.field("supplier").take(200)), "MXNZP",
            attempts = listOf(ApiAttempt(BarcodeService.MXNZP, ApiOutcome.FOUND, 200)))
    }
}
