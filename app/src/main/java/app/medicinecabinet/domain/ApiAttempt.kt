package app.medicinecabinet.domain

import kotlinx.serialization.Serializable

enum class BarcodeService(val label: String) { MXNZP("MXNZP"), ALIYUN("阿里云") }

enum class ApiOutcome {
    FOUND, NOT_FOUND, AUTH_ERROR, ACCOUNT_RESTRICTED, RATE_LIMIT, SERVICE_ERROR, NETWORK_ERROR,
    TIMEOUT, INVALID_RESPONSE, INVALID_BARCODE, CANCELLED,
}

/** 只记录服务及结果，不保存条码、认证或服务商原始错误文案。 */
@Serializable
data class ApiAttempt(val service: BarcodeService, val outcome: ApiOutcome,
    val httpStatus: Int? = null, val errorCode: String? = null, val gatewayReason: AliGatewayReason? = null) {
    val usableConnection: Boolean get() = outcome == ApiOutcome.FOUND || outcome == ApiOutcome.NOT_FOUND

    fun description(testing: Boolean = false): String = buildString {
        append(service.label).append("：")
        val aliReason = if (service == BarcodeService.ALIYUN) gatewayReason ?: AliGatewayErrors.reason(errorCode) else null
        append(aliReason?.explanation ?: when (outcome) {
            ApiOutcome.FOUND -> if (testing) "连接成功，认证可用" else "查询成功，已预填资料"
            ApiOutcome.NOT_FOUND -> if (testing) "连接成功，测试条码未收录" else "未查到此条码，服务暂未收录"
            ApiOutcome.AUTH_ERROR -> "认证或访问权限未通过，请核对认证和服务有效期"
            ApiOutcome.ACCOUNT_RESTRICTED -> "账号访问被限制或冻结，请联系服务商"
            ApiOutcome.RATE_LIMIT -> "请求受限，请核对服务额度、频率或服务商限制"
            ApiOutcome.SERVICE_ERROR -> if (service == BarcodeService.ALIYUN && httpStatus == 450)
                "服务已响应，但业务查询失败；未取得可识别的具体原因，请核对条码或联系服务商"
                else "服务返回错误，请按错误码核对服务状态"
            ApiOutcome.NETWORK_ERROR -> "网络连接失败，请检查网络后重试"
            ApiOutcome.TIMEOUT -> "连接或响应超时，请稍后重试"
            ApiOutcome.INVALID_RESPONSE -> "已收到响应，但资料格式异常或条码不一致"
            ApiOutcome.INVALID_BARCODE -> "不支持此条码参数或格式，请核对包装"
            ApiOutcome.CANCELLED -> "查询已取消，请求可能已经送出"
        })
        if (httpStatus != null) append(" · HTTP ").append(httpStatus)
        val displayCode = errorCode?.let(::safeApiCode) ?: if (service == BarcodeService.ALIYUN)
            AliGatewayErrors.safeCode(errorCode) else null
        if (displayCode != null) append(" · 错误码 ").append(displayCode)
    }
}

/** 仅允许短数字错误码进入界面，防止原始响应带出认证等内容。 */
fun safeApiCode(value: String): String? = value.takeIf { Regex("-?[0-9]{1,8}").matches(it) }

fun ProductLookupResult.withAttempts(attempts: List<ApiAttempt>): ProductLookupResult = when (this) {
    is ProductLookupResult.Found -> copy(attempts = attempts)
    is ProductLookupResult.Manual -> copy(attempts = attempts)
}

fun apiFailure(service: BarcodeService, outcome: ApiOutcome, httpStatus: Int? = null,
    code: String? = null, gatewayReason: AliGatewayReason? = null): ProductLookupResult.Manual {
    val safeCode = code?.let(::safeApiCode) ?: if (service == BarcodeService.ALIYUN) AliGatewayErrors.safeCode(code) else null
    val attempt = ApiAttempt(service, outcome, httpStatus, safeCode,
        gatewayReason?.takeIf { service == BarcodeService.ALIYUN })
    return ProductLookupResult.Manual(attempt.description(), listOf(attempt))
}

fun apiHttpFailure(service: BarcodeService, status: Int) = apiFailure(service, when (status) {
    401, 403 -> ApiOutcome.AUTH_ERROR
    429 -> ApiOutcome.RATE_LIMIT
    408, 504 -> ApiOutcome.TIMEOUT
    else -> ApiOutcome.SERVICE_ERROR
}, status)
