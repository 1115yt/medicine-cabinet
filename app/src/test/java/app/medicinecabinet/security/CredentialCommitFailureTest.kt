package app.medicinecabinet.security

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import app.medicinecabinet.TestCabinetApplication
import app.medicinecabinet.data.LookupPreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 独立模拟内存和磁盘，提交可在内存改变后失败；只使用虚构认证。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestCabinetApplication::class)
class CredentialCommitFailureTest {
    private val app get() = ApplicationProvider.getApplicationContext<TestCabinetApplication>()
    private val originalCode = "fixture-original-appcode-12345"
    private val replacementCode = "fixture-replacement-appcode-12345"

    private fun context(preferences: SharedPreferences) = object : ContextWrapper(app) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            if (name == "cabinet-lookup") preferences else super.getSharedPreferences(name, mode)
    }

    private class MemoryAndDiskPreferences(private val memory: SharedPreferences, val disk: SharedPreferences) : SharedPreferences by memory {
        var failures = 0
        var updateMemoryOnFailure = true
        var successfulCommitsBeforeFailure = 0
        val failureMemoryChanges = mutableListOf<Boolean>()
        override fun edit(): SharedPreferences.Editor {
            val editor = memory.edit()
            return object : SharedPreferences.Editor by editor {
                // 必须保留 Editor 包装器；链式调用返回底层对象会绕过模拟磁盘提交。
                override fun putString(key: String?, value: String?): SharedPreferences.Editor { editor.putString(key, value); return this }
                override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { editor.putBoolean(key, value); return this }
                override fun putLong(key: String?, value: Long): SharedPreferences.Editor { editor.putLong(key, value); return this }
                override fun putInt(key: String?, value: Int): SharedPreferences.Editor { editor.putInt(key, value); return this }
                override fun remove(key: String?): SharedPreferences.Editor { editor.remove(key); return this }
                override fun clear(): SharedPreferences.Editor { editor.clear(); return this }
                override fun commit(): Boolean {
                    val fail = failures > 0 && successfulCommitsBeforeFailure == 0
                    if (successfulCommitsBeforeFailure > 0) successfulCommitsBeforeFailure--
                    if (fail) failures--
                    val updateMemory = if (fail && failureMemoryChanges.isNotEmpty()) failureMemoryChanges.removeAt(0)
                        else updateMemoryOnFailure
                    if (!fail || updateMemory) check(editor.commit())
                    if (fail) return false
                    persist()
                    return true
                }
                override fun apply() { editor.apply(); persist() }
            }
        }
        private fun persist() {
            val editor = disk.edit().clear()
            memory.all.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Long -> editor.putLong(key, value)
                    is Int -> editor.putInt(key, value)
                    else -> error("虚构配置类型不受支持")
                }
            }
            check(editor.commit())
        }
    }
    private fun storage(name: String) = MemoryAndDiskPreferences(
        app.getSharedPreferences("$name-memory", Context.MODE_PRIVATE),
        app.getSharedPreferences("$name-disk", Context.MODE_PRIVATE))

    @Test fun `failed save restores confirmed credential before any instance can use replacement`() {
        val storage = storage("confirmed-restore")
        val preferences = LookupPreferences(context(storage), app.credentialCipher)
        preferences.saveAppCode(originalCode)
        val other = LookupPreferences(context(storage), app.credentialCipher)
        storage.failures = 1
        assertTrue(runCatching { preferences.saveAppCode(replacementCode) }.exceptionOrNull() is CredentialStorageException)
        assertEquals(originalCode, preferences.enabledAppCode())
        assertEquals(originalCode, other.enabledAppCode())
        other.refresh()
        assertEquals(CredentialProtection.ENCRYPTED, other.settings.value.aliyunProtection)
        assertEquals(originalCode, LookupPreferences(context(storage.disk), app.credentialCipher).enabledAppCode())
    }

    @Test fun `unconfirmed rollback blocks grouped credentials across same process instances and preserves old disk state`() {
        val storage = storage("unconfirmed-pair")
        val preferences = LookupPreferences(context(storage), app.credentialCipher).apply {
            saveAppCode(originalCode)
            saveMxnzp("fixture-original-id", "fixture-original-secret")
        }
        val other = LookupPreferences(context(storage), app.credentialCipher)
        storage.failures = 100
        assertTrue(runCatching { preferences.saveMxnzp("fixture-replacement-id", "fixture-replacement-secret") }
            .exceptionOrNull() is CredentialStorageException)
        assertEquals(CredentialProtection.SAVE_PENDING, preferences.settings.value.mxnzpProtection)
        assertFalse(preferences.settings.value.mxnzpEnabled)
        assertNull(preferences.configuredMxnzp())
        assertNull(other.enabledMxnzp())
        other.refresh()
        assertEquals(CredentialProtection.SAVE_PENDING, other.settings.value.mxnzpProtection)
        assertEquals(originalCode, other.enabledAppCode())
        assertEquals("fixture-original-secret", app.credentialCipher.decrypt(
            storage.disk.getString(SecureCredentials.encryptedKey("mxnzp-secret"), null)!!, "cabinet-lookup:mxnzp-secret"))
        storage.failures = 0
        preferences.refresh()
        assertEquals("fixture-original-secret", preferences.enabledMxnzp()!!.appSecret)
        assertEquals(CredentialProtection.ENCRYPTED, preferences.settings.value.mxnzpProtection)
        preferences.saveMxnzp("fixture-replacement-id", "fixture-replacement-secret")
        assertEquals("fixture-replacement-secret", preferences.enabledMxnzp()!!.appSecret)
    }

    @Test fun `failed first save does not become configured and separate disk view sees no replacement`() {
        val storage = storage("unconfirmed-first")
        val preferences = LookupPreferences(context(storage), app.credentialCipher)
        storage.failures = 100
        assertTrue(runCatching { preferences.saveAppCode(replacementCode) }.exceptionOrNull() is CredentialStorageException)
        assertFalse(preferences.settings.value.configured)
        assertEquals(CredentialProtection.SAVE_PENDING, preferences.settings.value.aliyunProtection)
        assertNull(LookupPreferences(context(storage), app.credentialCipher).configuredAppCode())
        val restarted = LookupPreferences(context(storage.disk), app.credentialCipher)
        assertFalse(restarted.settings.value.configured)
        assertNull(restarted.enabledAppCode())
    }

    @Test fun `failure before memory update is blocked until previous configuration can be confirmed`() {
        val storage = storage("unmodified-failure")
        val preferences = LookupPreferences(context(storage), app.credentialCipher).apply { saveAppCode(originalCode) }
        storage.updateMemoryOnFailure = false
        storage.failures = 100
        assertTrue(runCatching { preferences.saveAppCode(replacementCode) }.exceptionOrNull() is CredentialStorageException)
        assertNull(preferences.enabledAppCode())
        assertEquals(CredentialProtection.SAVE_PENDING, preferences.settings.value.aliyunProtection)
        storage.failures = 0
        preferences.refresh()
        assertEquals(originalCode, preferences.enabledAppCode())
    }

    @Test fun `legacy migration failure retains plaintext without adopting unconfirmed ciphertext`() {
        val storage = storage("unconfirmed-legacy")
        storage.edit().putString("secret", "fixture-original-secret").commit()
        storage.failures = 100
        val secure = SecureCredentials(storage, "unconfirmed-legacy", app.credentialCipher)
        val migrated = secure.read(listOf("secret"))
        assertTrue(migrated.values.isEmpty())
        assertEquals(CredentialProtection.MIGRATION_PENDING, migrated.protection)
        assertEquals("fixture-original-secret", storage.getString("secret", null))
        assertEquals(CredentialProtection.SAVE_PENDING, secure.read(listOf("secret")).protection)
        assertFalse(storage.disk.contains(SecureCredentials.encryptedKey("secret")))
        storage.failures = 0
        val retry = secure.read(listOf("secret"))
        assertEquals(CredentialProtection.ENCRYPTED, retry.protection)
        assertEquals("fixture-original-secret", retry.values["secret"])
        assertFalse(storage.contains("secret"))
    }

    @Test fun `repeated failed save never treats an unconfirmed memory credential as previous confirmed value`() {
        val storage = storage("repeated-unconfirmed")
        val preferences = LookupPreferences(context(storage), app.credentialCipher).apply { saveAppCode(originalCode) }
        storage.failures = 100
        storage.failureMemoryChanges += true
        storage.updateMemoryOnFailure = false
        assertTrue(runCatching { preferences.saveAppCode(replacementCode) }.exceptionOrNull() is CredentialStorageException)
        assertNull(preferences.configuredAppCode())
        assertTrue(runCatching { preferences.saveAppCode("fixture-another-appcode-12345") }.exceptionOrNull() is CredentialStorageException)
        assertNull(preferences.configuredAppCode())
        storage.failures = 0
        preferences.refresh()
        assertEquals(originalCode, preferences.enabledAppCode())
        assertEquals(originalCode, LookupPreferences(context(storage.disk), app.credentialCipher).enabledAppCode())
    }

    private fun forgetProcessCoordination() {
        val field = SecureCredentials::class.java.getDeclaredField("pendingSaves").apply { isAccessible = true }
        (field.get(null) as MutableMap<*, *>).clear()
    }

    @Test fun `later switch apply cannot adopt failed credential after process coordination is lost`() {
        val storage = storage("lost-coordination")
        val preferences = LookupPreferences(context(storage), app.credentialCipher).apply { saveAppCode(originalCode) }
        val other = LookupPreferences(context(storage), app.credentialCipher)
        storage.failures = 100
        storage.failureMemoryChanges += true
        storage.updateMemoryOnFailure = false
        assertTrue(runCatching { preferences.saveAppCode(replacementCode) }.exceptionOrNull() is CredentialStorageException)
        assertNull(preferences.configuredAppCode())
        // 另一实例修改开关时，SharedPreferences 将包括候选密文的整个内存映射写入磁盘。
        other.setEnabled(false)
        assertEquals(replacementCode, app.credentialCipher.decrypt(
            storage.disk.getString(SecureCredentials.encryptedKey("appcode"), null)!!, "cabinet-lookup:appcode"))
        forgetProcessCoordination()
        val reconstructed = LookupPreferences(context(storage.disk), app.credentialCipher)
        assertEquals(CredentialProtection.SAVE_PENDING, reconstructed.settings.value.aliyunProtection)
        assertFalse(reconstructed.settings.value.configured)
        reconstructed.setEnabled(true)
        assertNull(reconstructed.enabledAppCode())
        assertNull(reconstructed.configuredAppCode())
        // 丢失旧恢复快照后不自动猜测候选是否可用；用户明确重新填写、成功确认才能解除。
        reconstructed.saveAppCode(originalCode)
        assertEquals(originalCode, reconstructed.enabledAppCode())
        assertEquals(CredentialProtection.ENCRYPTED, reconstructed.settings.value.aliyunProtection)
    }

    @Test fun `failed confirmation clearing marker stays blocked after later apply and coordination loss`() {
        val storage = storage("lost-confirmation-coordination")
        val preferences = LookupPreferences(context(storage), app.credentialCipher).apply { saveAppCode(originalCode) }
        val other = LookupPreferences(context(storage), app.credentialCipher)
        // 候选密文成功写盘；确认提交先清内存标记再失败，随后旧配置恢复在修改内存前失败。
        storage.successfulCommitsBeforeFailure = 1
        storage.failures = 100
        storage.failureMemoryChanges += true
        storage.updateMemoryOnFailure = false
        assertTrue(runCatching { preferences.saveAppCode(replacementCode) }.exceptionOrNull() is CredentialStorageException)
        assertNull(preferences.configuredAppCode())
        other.setEnabled(false)
        assertEquals(replacementCode, app.credentialCipher.decrypt(
            storage.disk.getString(SecureCredentials.encryptedKey("appcode"), null)!!, "cabinet-lookup:appcode"))
        forgetProcessCoordination()
        val reconstructed = LookupPreferences(context(storage.disk), app.credentialCipher)
        assertEquals(CredentialProtection.SAVE_PENDING, reconstructed.settings.value.aliyunProtection)
        assertFalse(reconstructed.settings.value.configured)
        reconstructed.setEnabled(true)
        assertNull(reconstructed.enabledAppCode())
        assertNull(reconstructed.configuredAppCode())
    }
}
