package app.medicinecabinet.data

import android.content.Context
import app.medicinecabinet.BuildConfig
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import app.medicinecabinet.security.*

data class ServerCacheSettings(val enabled: Boolean = false, val configured: Boolean = false, val address: String = "",
    val available: Boolean = false, val protection: CredentialProtection = CredentialProtection.EMPTY)

// 普通类避免自动生成包含令牌的 toString；令牌不进入可观察的界面状态。
class ServerCacheCredentials(val address: String, val token: String) {
    val partition: String get() = MessageDigest.getInstance("SHA-256")
        .digest(address.toByteArray()).joinToString("") { "%02x".format(it) }
}

class ServerCachePreferences(context: Context, private val builtInAddress: String = BuildConfig.CATALOG_SERVER_URL,
    private val serviceAvailable: Boolean = BuildConfig.CATALOG_SERVER_AVAILABLE,
    private val featureEnabled: Boolean = BuildConfig.CATALOG_SERVER_ENABLED,
    private val allowCustomAddress: Boolean = false, cipher: CredentialCipher = credentialCipher(context)) {
    private val preferences = context.getSharedPreferences("cabinet-server", Context.MODE_PRIVATE)
    private val secure = SecureCredentials(preferences, "cabinet-server", cipher)
    private val state = MutableStateFlow(readSettings())
    val settings = state.asStateFlow()
    fun refresh() { state.value = readSettings() }

    private fun readSettings(): ServerCacheSettings {
        // 构建开关优先于旧版认证与地址，不删除用户配置，也不允许旧配置绕过禁用。
        if (!featureEnabled) return ServerCacheSettings()
        val fixedAddress = runCatching { normalizeAddress(builtInAddress) }.getOrDefault("")
        val address = if (allowCustomAddress) preferences.getString("address", fixedAddress).orEmpty() else fixedAddress
        val available = if (allowCustomAddress) address.isNotEmpty() else serviceAvailable && address.isNotEmpty()
        // 旧地址的访客认证不发送到新的固定服务；云端管理认证从不保存到本机。
        val tokenAddress = preferences.getString("address", fixedAddress).orEmpty()
        val token = if (available) secure.read(listOf("token")) else CredentialValues(protection = CredentialProtection.EMPTY)
        val configured = available && tokenAddress == address &&
            Regex("[A-Za-z0-9_-]{32,256}").matches(token.values["token"].orEmpty())
        return ServerCacheSettings(preferences.getBoolean("enabled", available) && available, configured, address, available, token.protection)
    }

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("enabled", enabled && state.value.available).apply()
        state.value = state.value.copy(enabled = enabled && state.value.available)
    }

    internal fun save(address: String, token: String) {
        val normalized = normalizeAddress(address)
        val value = token.trim()
        require(Regex("[A-Za-z0-9_-]{32,256}").matches(value))
        secure.save(mapOf("token" to value), strings = mapOf("address" to normalized), booleans = mapOf("enabled" to true))
        state.value = readSettings()
    }

    internal fun credentials(): ServerCacheCredentials? {
        val settings = readSettings()
        return if (settings.enabled && settings.configured) secure.read(listOf("token")).values["token"]
            ?.let { ServerCacheCredentials(settings.address, it) } else null
    }

    internal fun automaticAddress(): String? = readSettings().takeIf { it.enabled }?.address
    internal fun saveAutomaticToken(address: String, token: String) {
        // 只接受内置目标的自动注册结果，等待期间被关闭或切换时不重新启用。
        if (automaticAddress() != address) return
        require(Regex("[A-Za-z0-9_-]{32,256}").matches(token))
        secure.save(mapOf("token" to token), strings = mapOf("address" to address))
        state.value = readSettings()
    }

    companion object {
        fun normalizeAddress(value: String): String {
            val uri = URI(value.trim())
            require(uri.scheme?.lowercase() == "https" && !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                uri.rawPath.orEmpty() in listOf("", "/") && (uri.port == -1 || uri.port in 1..65535))
            return URI("https", null, uri.host.lowercase(), uri.port, null, null, null).toASCIIString()
        }
    }
}
