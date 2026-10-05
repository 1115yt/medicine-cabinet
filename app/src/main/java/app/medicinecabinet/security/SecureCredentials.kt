package app.medicinecabinet.security

import android.content.Context
import android.content.SharedPreferences
import app.medicinecabinet.CabinetApplication
import java.util.UUID

enum class CredentialProtection(val message: String) {
    EMPTY("尚未保存认证"), ENCRYPTED("认证已加密保存在本机"),
    CLEANUP_PENDING("认证已加密，旧明文清理待重试；重新打开应用可重试。"),
    MIGRATION_PENDING("旧认证加密尚未完成，已保留配置；重新打开应用可重试，完成前暂停使用。"),
    SAVE_PENDING("认证保存尚未确认，暂不使用该组认证；请重新填写并保存。"),
    UNAVAILABLE("认证无法解密，请重新填写；旧密文仍保留，药箱记录不受影响。"),
}

// 不自动生成含认证的 toString，也不向界面状态传递明文。
class CredentialValues(val values: Map<String, String> = emptyMap(), val protection: CredentialProtection)
class CredentialStorageException : Exception("认证未能确认保存，请重试并核对设置状态；未确认的新认证不会用于查询。")

fun credentialCipher(context: Context): CredentialCipher =
    (context.applicationContext as? CabinetApplication)?.credentialCipher ?: androidCredentialCipher()

/** 同组认证原子写入，核对密文可解密后清理旧明文；加密不可用时不回退为明文调用。 */
class SecureCredentials(private val preferences: SharedPreferences, private val namespace: String,
    private val cipher: CredentialCipher) {
    fun read(keys: List<String>): CredentialValues = synchronized(lock) {
        try { readLocked(keys) }
        catch (_: Exception) { CredentialValues(protection = CredentialProtection.UNAVAILABLE) }
    }

    private fun readLocked(keys: List<String>): CredentialValues {
        // commit 失败不代表内存未改变；跨配置实例共享阻断状态，恢复落盘确认前不采用认证。
        val identity = groupIdentity(keys)
        pendingSaves[identity]?.let { pending ->
            if (preferences.getString(generationKey(keys), null) in pending.generations) {
                if (!restoreConfirmed(keys, pending.previous))
                    return CredentialValues(protection = CredentialProtection.SAVE_PENDING)
            }
        }
        // 其他设置的 apply 可能把整个内存映射写盘；持久标记不能依赖仅存于进程的恢复快照。
        if (preferences.getBoolean(unconfirmedKey(keys), false))
            return CredentialValues(protection = CredentialProtection.SAVE_PENDING)
        val encrypted = keys.associateWith { preferences.getString(encryptedKey(it), null) }
        if (encrypted.values.any { it != null }) {
            if (encrypted.values.any { it == null }) return CredentialValues(protection = CredentialProtection.UNAVAILABLE)
            return try {
                val values = encrypted.mapValues { (key, value) -> cipher.decrypt(value!!, "$namespace:$key") }
                CredentialValues(values, if (cleanupLegacy(keys)) CredentialProtection.ENCRYPTED else CredentialProtection.CLEANUP_PENDING)
            } catch (_: Exception) { CredentialValues(protection = CredentialProtection.UNAVAILABLE) }
        }
        val legacy = keys.associateWith { preferences.getString(it, null) }
        if (legacy.values.any { it.isNullOrBlank() }) return CredentialValues(protection = CredentialProtection.EMPTY)
        return try {
            val values = legacy.mapValues { it.value!! }
            CredentialValues(values, save(values))
        } catch (_: CredentialStorageException) { CredentialValues(protection = CredentialProtection.MIGRATION_PENDING) }
    }

    fun save(values: Map<String, String>, strings: Map<String, String> = emptyMap(), booleans: Map<String, Boolean> = emptyMap()) = synchronized(lock) {
        val keys = values.keys.toList()
        var previous: Map<String, Any?>? = null
        var attemptedCommit = false
        try {
            pendingSaves[groupIdentity(keys)]?.let { pending ->
                if (preferences.getString(generationKey(keys), null) in pending.generations)
                    check(restoreConfirmed(keys, pending.previous))
            }
            // 生成和往返验证全部完成前，不改现有配置。
            val encrypted = values.mapValues { (key, value) -> cipher.encrypt(value, "$namespace:$key") }
            check(encrypted.all { (key, value) -> cipher.decrypt(value, "$namespace:$key") == values[key] })
            val affected = encrypted.keys.map(::encryptedKey) + values.keys + strings.keys + booleans.keys + unconfirmedKey(keys)
            val before = preferences.all
            previous = affected.associateWith { before[it] }
            val editor = preferences.edit()
            encrypted.forEach { (key, value) -> editor.putString(encryptedKey(key), value) }
            strings.forEach { (key, value) -> editor.putString(key, value) }
            booleans.forEach { (key, value) -> editor.putBoolean(key, value) }
            editor.putBoolean(unconfirmedKey(keys), true)
            editor.putString(generationKey(keys), UUID.randomUUID().toString())
            attemptedCommit = true
            check(editor.commit())
            check(values.all { (key, value) -> cipher.decrypt(preferences.getString(encryptedKey(key), null)!!, "$namespace:$key") == value })
            // 两阶段确认：候选密文与阻断标记一起持久化，再单独确认可用于查询。
            val confirmation = preferences.edit()
            confirmation.remove(unconfirmedKey(keys))
            confirmation.putString(generationKey(keys), UUID.randomUUID().toString())
            check(confirmation.commit())
            check(!preferences.getBoolean(unconfirmedKey(keys), false))
            pendingSaves.remove(groupIdentity(keys))
            if (cleanupLegacy(keys)) CredentialProtection.ENCRYPTED else CredentialProtection.CLEANUP_PENDING
        } catch (_: Exception) {
            if (attemptedCommit && previous != null) restoreConfirmed(keys, previous)
            throw CredentialStorageException()
        }
    }

    /** 恢复前一次认证；写入新代次以强制持久化，失败时保留进程内阻断状态。 */
    private fun restoreConfirmed(keys: List<String>, previous: Map<String, Any?>): Boolean {
        val generation = UUID.randomUUID().toString()
        // 某些失败在修改内存前发生，另一些发生在修改后；两种内存代次都需要阻断。
        val beforeGeneration = runCatching { preferences.getString(generationKey(keys), null) }.getOrNull()
        pendingSaves[groupIdentity(keys)] = PendingSave(setOf(beforeGeneration, generation), previous)
        return try {
            val editor = preferences.edit()
            previous.forEach { (key, value) ->
                when (value) {
                    null -> editor.remove(key)
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    else -> error("认证配置类型无法恢复")
                }
            }
            editor.putString(generationKey(keys), generation)
            val restored = editor.commit() && previous.all { (key, value) -> preferences.all[key] == value }
            if (restored) pendingSaves.remove(groupIdentity(keys)) else armUnconfirmed(keys)
            restored
        } catch (_: Exception) {
            armUnconfirmed(keys)
            false
        }
    }

    private fun armUnconfirmed(keys: List<String>) {
        // 确认提交失败也可能已在内存清掉标记；apply 先恢复内存阻断，后续任何设置写盘仍带标记。
        runCatching {
            val editor = preferences.edit()
            editor.putBoolean(unconfirmedKey(keys), true)
            editor.apply()
        }
    }

    private fun groupIdentity(keys: List<String>) = "$namespace:${keys.sorted().joinToString(",")}"
    private fun generationKey(keys: List<String>) = "credential-save-generation-${keys.sorted().joinToString(",")}"
    private fun unconfirmedKey(keys: List<String>) = "credential-save-unconfirmed-${keys.sorted().joinToString(",")}"

    private fun cleanupLegacy(keys: List<String>): Boolean {
        val identifiers = keys.map { "$namespace:$it" }
        if (keys.none(preferences::contains) && identifiers.none(pendingCleanup::contains)) return true
        val editor = preferences.edit()
        keys.forEach(editor::remove)
        // commit 失败仍可能已改变内存视图；强制生成新的写入代次，不能将内存无旧字段当作磁盘已清理。
        editor.putLong("credential-cleanup-generation", preferences.getLong("credential-cleanup-generation", 0) + 1)
        val saved = editor.commit()
        if (saved) pendingCleanup.removeAll(identifiers.toSet()) else pendingCleanup.addAll(identifiers)
        return saved
    }
    companion object {
        internal fun encryptedKey(key: String) = "encrypted-$key"
        private val lock = Any()
        private val pendingCleanup = mutableSetOf<String>()
        private class PendingSave(val generations: Set<String?>, val previous: Map<String, Any?>)
        private val pendingSaves = mutableMapOf<String, PendingSave>()
    }
}
