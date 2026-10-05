package app.medicinecabinet.data

import app.medicinecabinet.domain.ReleaseVersion
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class ReleaseUpdateReason {
    AVAILABLE, CURRENT, NO_RELEASE, NO_APK, RATE_LIMITED, TIMEOUT, DNS_FAILURE,
    TLS_FAILURE, NETWORK_FAILURE, SERVICE_UNAVAILABLE, INVALID_RESPONSE, HTTP_ERROR
}

data class ReleaseUpdateResult(val reason: ReleaseUpdateReason, val versionName: String? = null,
    val releaseUrl: String? = null, val httpStatus: Int? = null)

fun interface ReleaseUpdateSource {
    suspend fun check(currentVersion: String): ReleaseUpdateResult
}

data class ReleaseUpdateHttpResponse(val status: Int, val body: String = "", val rateLimited: Boolean = false)

fun interface ReleaseUpdateTransport {
    suspend fun get(url: URL): ReleaseUpdateHttpResponse
}

const val RELEASES_URL = "https://github.com/1115yt/medicine-cabinet/releases"
const val LATEST_RELEASE_API_URL = "https://api.github.com/repos/1115yt/medicine-cabinet/releases/latest"
private const val MAX_RELEASE_BYTES = 256 * 1024

/** 只访问固定公开接口，不发送认证、药箱资料，也不跟随重定向或读取错误正文。 */
fun defaultReleaseUpdateTransport(connectionFactory: (URL) -> HttpURLConnection = {
    it.openConnection() as HttpURLConnection
}) = ReleaseUpdateTransport { url ->
    require(url.toExternalForm() == LATEST_RELEASE_API_URL)
    withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val connection = connectionFactory(url)
            // 取消时立即关闭连接，解除阻塞读；迟到响应不进入下一轮检查。
            continuation.invokeOnCancellation { connection.disconnect() }
            try {
                continuation.context.ensureActive()
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 6_000
                connection.readTimeout = 8_000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
                connection.setRequestProperty("User-Agent", "MedicineCabinet-UpdateCheck")
                continuation.context.ensureActive()
                val status = connection.responseCode
                continuation.context.ensureActive()
                val limited = status == 429 || (status == 403 &&
                    (connection.getHeaderField("X-RateLimit-Remaining") == "0" ||
                        !connection.getHeaderField("Retry-After").isNullOrBlank()))
                val response = if (status != 200) ReleaseUpdateHttpResponse(status, rateLimited = limited)
                else {
                    require(connection.contentLengthLong <= MAX_RELEASE_BYTES)
                    connection.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            continuation.context.ensureActive()
                            val count = input.read(buffer)
                            continuation.context.ensureActive()
                            if (count < 0) break
                            require(output.size() + count <= MAX_RELEASE_BYTES)
                            output.write(buffer, 0, count)
                        }
                        ReleaseUpdateHttpResponse(status, output.toString(Charsets.UTF_8.name()))
                    }
                }
                continuation.resume(response)
            } catch (failure: Exception) {
                if (continuation.isActive) continuation.resumeWithException(failure)
            } finally { connection.disconnect() }
        }
    }
}

class GitHubReleaseUpdateClient(private val transport: ReleaseUpdateTransport = defaultReleaseUpdateTransport()) : ReleaseUpdateSource {
    override suspend fun check(currentVersion: String): ReleaseUpdateResult = withContext(Dispatchers.IO) {
        val current = ReleaseVersion.parseVersionName(currentVersion)
            ?: return@withContext ReleaseUpdateResult(ReleaseUpdateReason.INVALID_RESPONSE)
        try {
            val response = transport.get(URL(LATEST_RELEASE_API_URL))
            when {
                response.status == 404 -> ReleaseUpdateResult(ReleaseUpdateReason.NO_RELEASE, httpStatus = 404)
                response.status == 429 || (response.status == 403 && response.rateLimited) ->
                    ReleaseUpdateResult(ReleaseUpdateReason.RATE_LIMITED, httpStatus = response.status)
                response.status in 500..599 -> ReleaseUpdateResult(ReleaseUpdateReason.SERVICE_UNAVAILABLE, httpStatus = response.status)
                response.status != 200 -> ReleaseUpdateResult(ReleaseUpdateReason.HTTP_ERROR, httpStatus = response.status)
                else -> decode(response.body, current)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: SocketTimeoutException) { ReleaseUpdateResult(ReleaseUpdateReason.TIMEOUT) }
        catch (_: UnknownHostException) { ReleaseUpdateResult(ReleaseUpdateReason.DNS_FAILURE) }
        catch (_: SSLException) { ReleaseUpdateResult(ReleaseUpdateReason.TLS_FAILURE) }
        catch (_: kotlinx.serialization.SerializationException) { ReleaseUpdateResult(ReleaseUpdateReason.INVALID_RESPONSE) }
        catch (_: IllegalArgumentException) { ReleaseUpdateResult(ReleaseUpdateReason.INVALID_RESPONSE) }
        catch (_: Exception) { ReleaseUpdateResult(ReleaseUpdateReason.NETWORK_FAILURE) }
    }

    private fun decode(text: String, current: ReleaseVersion): ReleaseUpdateResult {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_RELEASE_BYTES)
        val release = Json.parseToJsonElement(text) as? JsonObject
            ?: return ReleaseUpdateResult(ReleaseUpdateReason.INVALID_RESPONSE, httpStatus = 200)
        if (!release.strictFalse("draft") || !release.strictFalse("prerelease")) {
            return ReleaseUpdateResult(ReleaseUpdateReason.INVALID_RESPONSE, httpStatus = 200)
        }
        val version = release.string("tag_name")?.let(ReleaseVersion::parseStableTag)
            ?: return ReleaseUpdateResult(ReleaseUpdateReason.INVALID_RESPONSE, httpStatus = 200)
        val releaseUrl = "$RELEASES_URL/tag/v${version.versionName}"
        if (release.string("html_url") != releaseUrl) {
            return ReleaseUpdateResult(ReleaseUpdateReason.INVALID_RESPONSE, httpStatus = 200)
        }
        val assets = release["assets"] as? JsonArray
        val names = listOf("arm64-v8a", "universal").map { "medicine-cabinet-${version.versionName}-$it.apk" }
        val complete = names.all { name -> assets?.any { asset ->
            val objectValue = asset as? JsonObject ?: return@any false
            val size = objectValue["size"] as? JsonPrimitive
            objectValue.string("name") == name && objectValue.string("state") == "uploaded" &&
                size?.isString == false && (size.longOrNull ?: 0) > 0
        } == true }
        return ReleaseUpdateResult(when {
            !complete -> ReleaseUpdateReason.NO_APK
            version > current -> ReleaseUpdateReason.AVAILABLE
            else -> ReleaseUpdateReason.CURRENT
        }, version.versionName, releaseUrl, 200)
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.strictFalse(key: String): Boolean =
        (get(key) as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == false } == true
}
