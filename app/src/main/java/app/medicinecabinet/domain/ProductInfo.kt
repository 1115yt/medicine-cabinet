package app.medicinecabinet.domain

import kotlinx.serialization.Serializable

/** 只用于预填包装身份资料，有效期和数量始终由本批次单独录入。 */
@Serializable
data class ProductInfo(
    val name: String,
    val specification: String = "",
    val packageUnit: String = "盒",
    val manufacturer: String = "",
    val approval: String = "",
)

sealed interface ProductLookupResult {
    val attempts: List<ApiAttempt>
    data class Found(val product: ProductInfo, val source: String, val origin: String = "",
        override val attempts: List<ApiAttempt> = emptyList()) : ProductLookupResult
    data class Manual(val explanation: String, override val attempts: List<ApiAttempt> = emptyList()) : ProductLookupResult
}
