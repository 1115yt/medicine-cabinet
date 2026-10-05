package app.medicinecabinet.data

import app.medicinecabinet.domain.*
import kotlinx.coroutines.CancellationException

class ProductLookup(private val catalog: BundledCatalog, private val preferences: LookupPreferences,
    private val client: AliBarcodeClient = AliBarcodeClient(), private val mxnzpClient: MxnzpBarcodeClient = MxnzpBarcodeClient(),
    private val cache: ProductCache? = null, private val apiQueries: BarcodeApiQueries? = null) {
    suspend fun lookup(code: String): ProductLookupResult {
        if (!BarcodeParser.validGtin(code)) return ProductLookupResult.Manual("已识别代码。追溯码或链接无法直接查药名，请核对包装后填写。")
        var catalogFailed = false
        val candidates = try { catalog.lookup(code) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { catalogFailed = true; emptyList() }
        if (candidates.size == 1) return ProductLookupResult.Found(candidates.single(), "内置资料库 · 2023-09")
        if (candidates.size > 1) return ProductLookupResult.Manual("此条码有多份不同资料，请手动核对名称和规格。")
        val cached = try { cache?.lookup(code) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        if (cached != null) return cached
        val mxnzpResult = if (apiQueries != null) apiQueries.lookup(BarcodeService.MXNZP, code)
            else preferences.enabledMxnzp()?.let { mxnzpClient.lookup(code, it) }
        if (mxnzpResult is ProductLookupResult.Found) return remember(code, mxnzpResult, "mxnzp")
        val aliResult = if (apiQueries != null) apiQueries.lookup(BarcodeService.ALIYUN, code)
            else preferences.enabledAppCode()?.let { client.lookup(code, it) }
        if (aliResult != null) {
            // 回退到阿里云后仍保留 MXNZP 的结果，让用户知道两家是否都发起过请求。
            val combined = aliResult.withAttempts(mxnzpResult?.attempts.orEmpty() + aliResult.attempts)
            return if (combined is ProductLookupResult.Found) remember(code, combined, "aliyun") else combined
        }
        if (mxnzpResult != null) return mxnzpResult
        return ProductLookupResult.Manual(if (catalogFailed) "内置资料读取失败，请核对包装后手动填写。"
            else "内置资料库未收录此条码，请核对包装后填写；可在设置中配置补充查询。")
    }

    private suspend fun remember(code: String, result: ProductLookupResult.Found, source: String): ProductLookupResult.Found {
        try { cache?.remember(code, result.product, source) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* 缓存失败不丢失已经查到的候选资料。 */ }
        return result
    }
}
