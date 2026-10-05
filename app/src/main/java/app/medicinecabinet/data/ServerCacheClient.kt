package app.medicinecabinet.data

import app.medicinecabinet.domain.*
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

fun interface CacheTransport {
    fun request(method: String, url: URL, headers: Map<String, String>, body: String?): BarcodeHttpResponse
}

enum class CachePushResult { ACCEPTED, CONFLICT, RETRY, REJECTED }

fun defaultCacheTransport() = CacheTransport { method, url, headers, body ->
    val connection = url.openConnection() as HttpURLConnection
    try {
        connection.requestMethod = method
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 4_000
        connection.readTimeout = 6_000
        connection.setRequestProperty("Accept", "application/json")
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        if (body != null) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            require(bytes.size <= 8192)
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
        }
        val status = connection.responseCode
        if (status !in 200..299) BarcodeHttpResponse(status)
        else connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 32 * 1024)
                output.write(buffer, 0, count)
            }
            BarcodeHttpResponse(status, output.toString(Charsets.UTF_8.name()))
        }
    } finally { connection.disconnect() }
}

class ServerCacheClient(private val transport: CacheTransport = defaultCacheTransport()) {
    /** 健康检测无认证、无药品正文，也不注册访客或调用补充 API。 */
    suspend fun health(address: String): ServerHealthResult = withContext(Dispatchers.IO) {
        if (runCatching { ServerCachePreferences.normalizeAddress(address) }.getOrNull() != address || address.isBlank()) {
            return@withContext ServerHealthResult(ServerHealthReason.NOT_CONFIGURED)
        }
        try {
            val response = transport.request("GET", URL("$address/health"), emptyMap(), null)
            val reason = when {
                response.status == 429 -> ServerHealthReason.RATE_LIMITED
                response.status in 500..599 -> ServerHealthReason.SERVICE_UNAVAILABLE
                response.status != 200 -> ServerHealthReason.HTTP_ERROR
                else -> {
                    val data = runCatching { Json.parseToJsonElement(response.body).jsonObject }.getOrNull()
                    val status = data?.get("status") as? JsonPrimitive
                    val schema = data?.get("schema") as? JsonPrimitive
                    if (status?.isString == true && status.content == "ok" && schema?.isString == false && schema.intOrNull == 1) {
                        ServerHealthReason.CONNECTED
                    } else ServerHealthReason.INVALID_RESPONSE
                }
            }
            ServerHealthResult(reason, response.status)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: UnknownHostException) { ServerHealthResult(ServerHealthReason.DNS_FAILURE) }
        catch (_: SocketTimeoutException) { ServerHealthResult(ServerHealthReason.TIMEOUT) }
        catch (_: SSLException) { ServerHealthResult(ServerHealthReason.TLS_FAILURE) }
        catch (_: Exception) { ServerHealthResult(ServerHealthReason.NETWORK_FAILURE) }
    }

    suspend fun enroll(address: String): String? = withContext(Dispatchers.IO) {
        try {
            require(ServerCachePreferences.normalizeAddress(address) == address)
            val response = transport.request("POST", URL("$address/v1/clients"), emptyMap(), null)
            if (response.status != 201) return@withContext null
            Json.parseToJsonElement(response.body).jsonObject.text("token").takeIf { Regex("[A-Za-z0-9_-]{32,256}").matches(it) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
    suspend fun lookup(code: String, credentials: ServerCacheCredentials): ProductLookupResult = withContext(Dispatchers.IO) {
        if (!BarcodeParser.validGtin(code)) return@withContext ProductLookupResult.Manual("此代码不能用于条码缓存查询。")
        try {
            val response = transport.request("GET", URL("${credentials.address}/v1/catalog/${code.padStart(14, '0')}"),
                mapOf("Authorization" to "Bearer ${credentials.token}"), null)
            if (response.status != 200) return@withContext ProductLookupResult.Manual("服务器缓存未命中或暂不可用。")
            val data = Json.parseToJsonElement(response.body).jsonObject
            val returned = data.text("barcode")
            require(BarcodeParser.validGtin(returned) && returned.padStart(14, '0') == code.padStart(14, '0'))
            val product = ProductInfo(data.text("name"), data.text("specification"), data.text("packageUnit"),
                data.text("manufacturer"), data.text("approval"))
            require(validProduct(product) && data.text("source") in listOf("mxnzp", "aliyun", "manual"))
            ProductLookupResult.Found(product, "服务器缓存", data.text("source"))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ProductLookupResult.Manual("服务器缓存暂不可用，将继续补充查询。") }
    }

    suspend fun push(code: String, product: ProductInfo, source: String, credentials: ServerCacheCredentials): CachePushResult =
        withContext(Dispatchers.IO) {
            if (!BarcodeParser.validGtin(code) || !validProduct(product) || source !in listOf("mxnzp", "aliyun", "manual")) {
                return@withContext CachePushResult.REJECTED
            }
            try {
                val body = buildJsonObject {
                    put("barcode", code.padStart(14, '0')); put("name", product.name)
                    put("specification", product.specification); put("packageUnit", product.packageUnit)
                    put("manufacturer", product.manufacturer); put("approval", product.approval); put("source", source)
                }.toString()
                val response = transport.request("POST", URL("${credentials.address}/v1/catalog/cache"),
                    mapOf("Authorization" to "Bearer ${credentials.token}"), body)
                when {
                    response.status in listOf(200, 201, 202) -> {
                        val data = Json.parseToJsonElement(response.body).jsonObject
                        require(data.text("barcode") == code.padStart(14, '0'))
                        when (data.text("status")) {
                            "stored", "unchanged" -> CachePushResult.ACCEPTED
                            "conflict_preserved" -> CachePushResult.CONFLICT
                            else -> CachePushResult.RETRY
                        }
                    }
                    response.status == 429 || response.status >= 500 -> CachePushResult.RETRY
                    else -> CachePushResult.REJECTED
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { CachePushResult.RETRY }
        }

    companion object {
        internal fun validProduct(product: ProductInfo): Boolean = product.name.isNotBlank() && product.packageUnit.isNotBlank() &&
            listOf(product.name to 80, product.specification to 120, product.packageUnit to 8,
                product.manufacturer to 200, product.approval to 100).all { (text, max) ->
                text.length <= max && text.none { it.code < 32 }
            }
    }
}

private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
