package app.medicinecabinet.domain

import kotlinx.serialization.Serializable

/** 缓存只保存 API 原始身份字段，与药箱库存和用户修正记录分离。 */
@Serializable
data class CachedProduct(val partition: String, val barcode: String, val product: ProductInfo,
    val source: String, val pending: Boolean = true, val attempts: Int = 0)
