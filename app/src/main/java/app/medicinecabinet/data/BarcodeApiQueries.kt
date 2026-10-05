package app.medicinecabinet.data

import app.medicinecabinet.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 扫码和连接检测共用同一计数入口，检测直接查询指定服务，不从内置库或缓存取结果。 */
class BarcodeApiQueries(private val preferences: LookupPreferences, private val usage: ApiUsageStore,
    private val transport: BarcodeTransport = defaultBarcodeTransport()) {
    private fun tracked(service: BarcodeService) = BarcodeTransport { url, headers ->
        usage.requestStarted(service)
        transport.get(url, headers)
    }
    private val aliyun = AliBarcodeClient(tracked(BarcodeService.ALIYUN))
    private val mxnzp = MxnzpBarcodeClient(tracked(BarcodeService.MXNZP))

    suspend fun lookup(service: BarcodeService, code: String, testing: Boolean = false): ProductLookupResult? =
        withContext(Dispatchers.IO) {
            if (!BarcodeParser.validGtin(code)) return@withContext ProductLookupResult.Manual("请输入有效商品条码；本次没有发起 API 请求。")
            val result = try {
                when (service) {
                    BarcodeService.MXNZP -> {
                        val credentials = if (testing) preferences.configuredMxnzp() else preferences.enabledMxnzp()
                        if (credentials == null) return@withContext null
                        mxnzp.lookup(code, credentials)
                    }
                    BarcodeService.ALIYUN -> {
                        val appCode = if (testing) preferences.configuredAppCode() else preferences.enabledAppCode()
                        if (appCode == null) return@withContext null
                        aliyun.lookup(code, appCode)
                    }
                }
            } catch (cancelled: CancellationException) {
                usage.complete(ApiAttempt(service, ApiOutcome.CANCELLED), testing)
                throw cancelled
            }
            result.attempts.lastOrNull()?.let { usage.complete(it, testing) }
            result
        }
}
