package app.medicinecabinet.domain

/** 网关原始消息可能包含认证；只保存已识别的原因，并使用固定中文说明。 */
enum class AliGatewayReason(val outcome: ApiOutcome, val explanation: String) {
    INVALID_APPCODE(ApiOutcome.AUTH_ERROR, "AppCode 无效或未获授权，请核对阿里云云市场的 AppCode"),
    MISSING_AUTH(ApiOutcome.AUTH_ERROR, "请求缺少认证，请重新保存阿里云 AppCode"),
    INVALID_APPKEY(ApiOutcome.AUTH_ERROR, "AppKey 无效；本应用使用 AppCode，请核对所填认证类型"),
    INVALID_SECRET(ApiOutcome.AUTH_ERROR, "AppSecret 或签名不正确；请核对认证配置"),
    PLUGIN_AUTH(ApiOutcome.AUTH_ERROR, "接口需要网关插件授权，请联系服务商处理"),
    UNAUTHORIZED(ApiOutcome.AUTH_ERROR, "尚未获得该接口的授权，请核对已订购的服务和 AppCode"),
    AUTH_EXPIRED(ApiOutcome.AUTH_ERROR, "接口授权已过期，请在阿里云核对授权有效期"),
    QUOTA_EXHAUSTED(ApiOutcome.RATE_LIMIT, "已订购的 API 调用次数耗尽，请在阿里云查看额度或续购"),
    SUBSCRIPTION_EXPIRED(ApiOutcome.AUTH_ERROR, "API 订购已过期，请在阿里云续购或重新申请"),
    INVALID_SUBSCRIPTION(ApiOutcome.AUTH_ERROR, "API 订购关系无效，请核对订单及服务授权"),
    USER_ARREARS(ApiOutcome.AUTH_ERROR, "调用账号已欠费，请在阿里云核对账号并续费"),
    PROVIDER_OVERDUE(ApiOutcome.SERVICE_ERROR, "API 服务提供方欠费，请联系服务商处理"),
    THROTTLED(ApiOutcome.RATE_LIMIT, "触发网关频率或流量限制，请稍后重试；持续出现时联系服务商"),
    ACCESS_DENIED(ApiOutcome.ACCOUNT_RESTRICTED, "访问被网关策略限制，请联系服务商核对访问权限"),
    INTERNET_DISABLED(ApiOutcome.ACCOUNT_RESTRICTED, "此接口禁止公网访问，请联系服务商处理"),
    DOMAIN_BLOCKED(ApiOutcome.SERVICE_ERROR, "接口域名或分组被停用，请联系服务商处理"),
    API_NOT_FOUND(ApiOutcome.SERVICE_ERROR, "请求的接口或服务资源不存在，请更新应用或联系服务商"),
    INVALID_REQUEST(ApiOutcome.SERVICE_ERROR, "请求参数、格式或接口配置不符合要求，请更新应用或联系服务商"),
    BACKEND_CONNECTION(ApiOutcome.SERVICE_ERROR, "网关无法连接服务商后端，请稍后重试或联系服务商"),
    BACKEND_TIMEOUT(ApiOutcome.TIMEOUT, "网关或服务商后端响应超时，请稍后重试"),
    BACKEND_RESPONSE(ApiOutcome.INVALID_RESPONSE, "服务商后端返回异常，请稍后重试或联系服务商"),
    RESPONSE_TRANSPORT(ApiOutcome.SERVICE_ERROR, "网关响应传输异常，请稍后重试或联系服务商"),
    SERVICE_BUSY(ApiOutcome.SERVICE_ERROR, "网关或服务商后端暂不可用，请稍后重试"),
    GATEWAY_CONFIG(ApiOutcome.SERVICE_ERROR, "网关服务配置异常，请联系服务商处理"),
}

object AliGatewayErrors {
    // 只接收文档中的短代码或固定错误名，不展示未验证的响应文本。
    private val codes = buildMap {
        fun add(reason: AliGatewayReason, vararg values: String) = values.forEach { put(it, reason) }
        add(AliGatewayReason.INVALID_APPCODE, "A400AC", "A401AC", "Invalid AppCode")
        add(AliGatewayReason.MISSING_AUTH, "A400MA")
        add(AliGatewayReason.INVALID_APPKEY, "A400IK", "Invalid AppKey")
        add(AliGatewayReason.INVALID_SECRET, "A403IS", "Invalid AppSecret")
        add(AliGatewayReason.PLUGIN_AUTH, "A403PR")
        add(AliGatewayReason.UNAUTHORIZED, "I403AA", "Unauthorized")
        add(AliGatewayReason.AUTH_EXPIRED, "A403EP")
        add(AliGatewayReason.QUOTA_EXHAUSTED, "B403MQ", "Quota Exhausted")
        add(AliGatewayReason.SUBSCRIPTION_EXPIRED, "B403ME", "Quota Expired")
        add(AliGatewayReason.USER_ARREARS, "User Arrears")
        add(AliGatewayReason.INVALID_SUBSCRIPTION, "B403MI")
        add(AliGatewayReason.PROVIDER_OVERDUE, "B403OD", "B403MO")
        add(AliGatewayReason.THROTTLED, "T429ID", "T429IN", "T429GR", "T429PA", "T429PR", "T429SR", "T429MR")
        add(AliGatewayReason.ACCESS_DENIED, "A403IP", "A403VN", "A403AC", "A403CO")
        add(AliGatewayReason.INTERNET_DISABLED, "A403IN")
        add(AliGatewayReason.DOMAIN_BLOCKED, "B451DO", "B451GO")
        add(AliGatewayReason.API_NOT_FOUND, "I404DO", "I404NF", "I404NR", "I404SR")
        add(AliGatewayReason.INVALID_REQUEST, "I400HD", "I400MH", "I400BD", "I400PA", "I405UM", "I400RU",
            "I403PT", "I413RL", "I413UL", "I400CT", "I400SG", "I400I5", "I400NC", "I400MP", "I400IP")
        add(AliGatewayReason.BACKEND_CONNECTION, "D504RE", "D504IL", "D504CO", "D504CS", "X504VE")
        add(AliGatewayReason.BACKEND_TIMEOUT, "D504TO", "X504TO")
        add(AliGatewayReason.BACKEND_RESPONSE, "D502FC")
        add(AliGatewayReason.RESPONSE_TRANSPORT, "N502RE")
        add(AliGatewayReason.SERVICE_BUSY, "D503BB", "D503CB", "X503BZ", "X500ER")
        add(AliGatewayReason.GATEWAY_CONFIG, "I410GG", "X400PM", "X500ED", "X500AM", "X403DG", "I400JP", "A403FC")
    }

    fun safeCode(value: String?): String? = value?.trim()?.takeIf { it in codes }
    fun reason(code: String?): AliGatewayReason? = safeCode(code)?.let(codes::get)

    /** 兼容旧网关的固定英文错误名，只翻译已知名称，不展示其附带参数。 */
    fun reasonFromMessage(value: String?): AliGatewayReason? {
        val message = value?.trim()?.takeIf { it.length <= 4096 } ?: return null
        val names = listOf("Invalid AppCode" to AliGatewayReason.INVALID_APPCODE,
            "Invalid AppKey" to AliGatewayReason.INVALID_APPKEY,
            "Invalid AppSecret" to AliGatewayReason.INVALID_SECRET,
            "Invalid Signature" to AliGatewayReason.INVALID_SECRET,
            "Quota Exhausted" to AliGatewayReason.QUOTA_EXHAUSTED,
            "Quota Expired" to AliGatewayReason.SUBSCRIPTION_EXPIRED,
            "User Arrears" to AliGatewayReason.USER_ARREARS,
            "Unauthorized" to AliGatewayReason.UNAUTHORIZED,
            "Api Market Subscription quota exhausted" to AliGatewayReason.QUOTA_EXHAUSTED,
            "Api Market Subscription expired" to AliGatewayReason.SUBSCRIPTION_EXPIRED,
            "Api Market Subscription invalid" to AliGatewayReason.INVALID_SUBSCRIPTION,
            "Provider Account Overdue" to AliGatewayReason.PROVIDER_OVERDUE,
            "App authorization expired" to AliGatewayReason.AUTH_EXPIRED,
            "Need authorization" to AliGatewayReason.MISSING_AUTH,
            "Throttled by" to AliGatewayReason.THROTTLED,
            "Backend service request timeout" to AliGatewayReason.BACKEND_TIMEOUT,
            "Backend service connect failed" to AliGatewayReason.BACKEND_CONNECTION,
            "Service Busy" to AliGatewayReason.SERVICE_BUSY)
        return names.firstOrNull { (name, _) -> message.equals(name, ignoreCase = true) ||
            message.startsWith("$name ", ignoreCase = true) || message.startsWith("$name:", ignoreCase = true) }?.second
    }
}
