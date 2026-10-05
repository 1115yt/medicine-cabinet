package app.medicinecabinet.data

import app.medicinecabinet.domain.Medicine
import app.medicinecabinet.domain.ProductInfo
import app.medicinecabinet.domain.ProductLookupResult
import kotlinx.coroutines.CancellationException

/** 本机优先，D1 失败可继续外部查询；共享失败不影响已保存的药箱或本机记忆。 */
class CombinedProductCache(val local: LocalProductCache, val shared: ServerProductCache) : ProductCache {
    override suspend fun lookup(code: String): ProductLookupResult.Found? = local.lookup(code) ?: shared.lookup(code)

    override suspend fun remember(code: String, product: ProductInfo, source: String) {
        local.remember(code, product, source)
        shareSafely { shared.remember(code, product, source) }
    }

    suspend fun rememberConfirmed(medicine: Medicine) {
        local.rememberConfirmed(medicine)
        // 使用本机候选的包装字段，避免用户核对后丢掉厂家与批准文号。
        val product = local.lookup(medicine.barcode)?.product ?: return
        shareSafely { shared.remember(medicine.barcode, product, "manual") }
    }

    private suspend fun shareSafely(action: suspend () -> Unit) {
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* 共享队列问题不撤销用户已经保存的药箱记录。 */ }
    }
}
