package app.medicinecabinet.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import app.medicinecabinet.security.*

data class LookupSettings(val enabled: Boolean = false, val configured: Boolean = false,
    val mxnzpEnabled: Boolean = false, val mxnzpConfigured: Boolean = false,
    val aliyunProtection: CredentialProtection = CredentialProtection.EMPTY,
    val mxnzpProtection: CredentialProtection = CredentialProtection.EMPTY)

class MxnzpCredentials(val appId: String, val appSecret: String)

// 点击保存时捕获开关意图，后台加密完成不能覆盖用户后来关闭的选择。
class LookupSaveIntent internal constructor(internal val switchKey: String, internal val revision: Long)

class LookupPreferences(context: Context, cipher: CredentialCipher = credentialCipher(context)) {
    private val preferences = context.getSharedPreferences("cabinet-lookup", Context.MODE_PRIVATE)
    private val secure = SecureCredentials(preferences, "cabinet-lookup", cipher)
    private val state = MutableStateFlow(readSettings())
    val settings = state.asStateFlow()

    private fun readCredentialSettings(): LookupSettings {
        val aliyun = secure.read(listOf("appcode"))
        val mxnzp = secure.read(listOf("mxnzp-id", "mxnzp-secret"))
        val configured = !aliyun.values["appcode"].isNullOrBlank()
        val mxnzpConfigured = !mxnzp.values["mxnzp-id"].isNullOrBlank() && !mxnzp.values["mxnzp-secret"].isNullOrBlank()
        return LookupSettings(false, configured, false, mxnzpConfigured,
            aliyun.protection, mxnzp.protection)
    }
    private fun withLatestSwitches(settings: LookupSettings) = settings.copy(
        enabled = preferences.getBoolean("enabled", false) && settings.configured,
        mxnzpEnabled = preferences.getBoolean("mxnzp-enabled", false) && settings.mxnzpConfigured)
    private fun readSettings(): LookupSettings {
        val credentials = readCredentialSettings()
        return synchronized(switchLock) { withLatestSwitches(credentials) }
    }
    fun refresh() {
        val credentials = readCredentialSettings()
        // 加密读取不占用开关锁；发布界面状态时重新读取最新开关，避免迟到的刷新覆盖关闭状态。
        synchronized(switchLock) { state.value = withLatestSwitches(credentials) }
    }

    fun beginAppCodeSave() = beginSave("enabled")
    fun beginMxnzpSave() = beginSave("mxnzp-enabled")
    private fun beginSave(key: String) = synchronized(switchLock) {
        LookupSaveIntent(key, preferences.getLong(revisionKey(key), 0))
    }
    private fun finishSave(intent: LookupSaveIntent) {
        synchronized(switchLock) {
            if (preferences.getLong(revisionKey(intent.switchKey), 0) == intent.revision)
                preferences.edit().putBoolean(intent.switchKey, true).apply()
        }
    }

    fun setEnabled(enabled: Boolean) = synchronized(switchLock) {
        preferences.edit().putBoolean("enabled", enabled && state.value.configured)
            .putLong(revisionKey("enabled"), preferences.getLong(revisionKey("enabled"), 0) + 1).apply()
        state.value = state.value.copy(enabled = enabled && state.value.configured)
    }

    fun saveAppCode(value: String, intent: LookupSaveIntent = beginAppCodeSave()) {
        val code = value.trim()
        require(intent.switchKey == "enabled")
        require(Regex("[A-Za-z0-9_-]{16,128}").matches(code)) { "请填写阿里云云市场的有效 AppCode。" }
        try {
            secure.save(mapOf("appcode" to code))
            finishSave(intent)
        } finally { refresh() }
    }

    internal fun configuredAppCode(): String? = secure.read(listOf("appcode")).values["appcode"]?.takeIf { it.isNotBlank() }
    internal fun enabledAppCode(): String? = if (preferences.getBoolean("enabled", false)) configuredAppCode() else null

    fun setMxnzpEnabled(enabled: Boolean) = synchronized(switchLock) {
        preferences.edit().putBoolean("mxnzp-enabled", enabled && state.value.mxnzpConfigured)
            .putLong(revisionKey("mxnzp-enabled"), preferences.getLong(revisionKey("mxnzp-enabled"), 0) + 1).apply()
        state.value = state.value.copy(mxnzpEnabled = enabled && state.value.mxnzpConfigured)
    }

    fun saveMxnzp(appId: String, appSecret: String, intent: LookupSaveIntent = beginMxnzpSave()) {
        val id = appId.trim()
        val secret = appSecret.trim()
        require(intent.switchKey == "mxnzp-enabled")
        require(Regex("[A-Za-z0-9_-]{8,128}").matches(id) && Regex("[A-Za-z0-9_-]{8,256}").matches(secret))
        try {
            secure.save(mapOf("mxnzp-id" to id, "mxnzp-secret" to secret))
            finishSave(intent)
        } finally { refresh() }
    }

    internal fun configuredMxnzp(): MxnzpCredentials? {
        val values = secure.read(listOf("mxnzp-id", "mxnzp-secret")).values
        val id = values["mxnzp-id"] ?: return null
        val secret = values["mxnzp-secret"] ?: return null
        return MxnzpCredentials(id, secret)
    }
    internal fun enabledMxnzp(): MxnzpCredentials? = if (preferences.getBoolean("mxnzp-enabled", false)) configuredMxnzp() else null

    companion object {
        private val switchLock = Any()
        private fun revisionKey(key: String) = "switch-revision-$key"
    }
}
