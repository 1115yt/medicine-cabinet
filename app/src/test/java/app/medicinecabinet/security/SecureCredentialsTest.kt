package app.medicinecabinet.security

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import app.medicinecabinet.TestCabinetApplication
import app.medicinecabinet.data.LookupPreferences
import app.medicinecabinet.data.ServerCachePreferences
import app.medicinecabinet.domain.InterfaceSize
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 只用虚构认证与进程内测试密钥；覆盖实际 AES-GCM、持久化和兼容迁移。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestCabinetApplication::class)
class SecureCredentialsTest {
    private val app get() = ApplicationProvider.getApplicationContext<TestCabinetApplication>()
    private val code = "fixture-appcode-12345"
    private val id = "fixture-mxnzp-id"
    private val secret = "fixture-mxnzp-secret"
    private val token = "fixture_shared_token_for_security_test_123456"
    private fun prefs(name: String) = app.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val failingCipher = object : CredentialCipher {
        override fun encrypt(value: String, associatedData: String): String = error("虚构加密失败")
        override fun decrypt(value: String, associatedData: String): String = error("虚构解密失败")
    }

    @Test fun `new credentials and visitor token are encrypted and reload correctly`() {
        LookupPreferences(app).apply { saveAppCode(code); saveMxnzp(id, secret) }
        val lookup = prefs("cabinet-lookup")
        assertFalse(lookup.contains("appcode"))
        assertFalse(lookup.contains("mxnzp-id"))
        assertFalse(lookup.contains("mxnzp-secret"))
        assertFalse(lookup.all.toString().contains(secret))
        assertFalse(lookup.all.toString().contains(code))
        val reloaded = LookupPreferences(app)
        assertEquals(code, reloaded.enabledAppCode())
        assertEquals(secret, reloaded.enabledMxnzp()!!.appSecret)
        assertEquals(CredentialProtection.ENCRYPTED, reloaded.settings.value.aliyunProtection)
        assertFalse(reloaded.settings.value.toString().contains(secret))
        val server = ServerCachePreferences(app, "https://catalog.example.com", true, true)
        server.saveAutomaticToken("https://catalog.example.com", token)
        assertFalse(prefs("cabinet-server").contains("token"))
        assertFalse(prefs("cabinet-server").all.toString().contains(token))
        assertEquals(token, ServerCachePreferences(app, "https://catalog.example.com", true, true).credentials()!!.token)
    }

    @Test fun `legacy credentials migrate and preserve independent switches and server address`() {
        prefs("cabinet-lookup").edit().putString("appcode", code).putString("mxnzp-id", id)
            .putString("mxnzp-secret", secret).putBoolean("enabled", false).putBoolean("mxnzp-enabled", true).commit()
        val lookup = LookupPreferences(app)
        assertTrue(lookup.settings.value.configured)
        assertFalse(lookup.settings.value.enabled)
        assertEquals(secret, lookup.enabledMxnzp()!!.appSecret)
        assertEquals(code, lookup.configuredAppCode())
        assertFalse(prefs("cabinet-lookup").contains("appcode"))
        prefs("cabinet-server").edit().putString("address", "https://legacy.example.com").putString("token", token)
            .putBoolean("enabled", false).commit()
        val server = ServerCachePreferences(app, "https://catalog.example.com", true, true)
        assertFalse(server.settings.value.enabled)
        assertFalse(server.settings.value.configured)
        assertEquals("https://legacy.example.com", prefs("cabinet-server").getString("address", null))
        assertFalse(prefs("cabinet-server").contains("token"))
        assertNull(server.credentials())
    }

    @Test fun `failed migration keeps plaintext without using it and can retry`() {
        val legacy = prefs("cabinet-lookup")
        legacy.edit().putString("appcode", code).putBoolean("enabled", true).commit()
        val failed = LookupPreferences(app, failingCipher)
        assertEquals(CredentialProtection.MIGRATION_PENDING, failed.settings.value.aliyunProtection)
        assertFalse(failed.settings.value.enabled)
        assertNull(failed.enabledAppCode())
        assertEquals(code, legacy.getString("appcode", null))
        assertFalse(legacy.contains(SecureCredentials.encryptedKey("appcode")))
        val retry = LookupPreferences(app)
        assertEquals(code, retry.enabledAppCode())
        assertFalse(legacy.contains("appcode"))
    }

    @Test fun `corrupt ciphertext does not fall back to stale plaintext`() {
        val preferences = prefs("cabinet-lookup")
        preferences.edit().putString(SecureCredentials.encryptedKey("appcode"), "v1:invalid:invalid")
            .putString("appcode", code).putBoolean("enabled", true).commit()
        val lookup = LookupPreferences(app)
        assertEquals(CredentialProtection.UNAVAILABLE, lookup.settings.value.aliyunProtection)
        assertNull(lookup.configuredAppCode())
        assertEquals(code, preferences.getString("appcode", null))
        lookup.saveAppCode("fixture-replacement-12345")
        assertEquals("fixture-replacement-12345", lookup.enabledAppCode())
        assertFalse(preferences.contains("appcode"))
    }

    @Test fun `random IV and associated data prevent replay between fields`() {
        val cipher = app.credentialCipher
        val first = cipher.encrypt(secret, "catalog:secret")
        val second = cipher.encrypt(secret, "catalog:secret")
        assertNotEquals(first, second)
        assertEquals(secret, cipher.decrypt(first, "catalog:secret"))
        assertTrue(runCatching { cipher.decrypt(first, "catalog:id") }.isFailure)
        assertTrue(runCatching { cipher.decrypt(first, "server:secret") }.isFailure)
        assertTrue(runCatching { cipher.decrypt(first.replace("v1:", "v9:"), "catalog:secret") }.isFailure)
        val parts = first.split(':')
        val changed = parts[2].let { (if (it[0] == 'A') "B" else "A") + it.drop(1) }
        assertTrue(runCatching { cipher.decrypt("v1:${parts[1]}:$changed", "catalog:secret") }.isFailure)
        assertTrue(runCatching { cipher.decrypt("a".repeat(8193), "catalog:secret") }.isFailure)
    }

    @Test fun `failed grouped encryption cannot partially replace saved pair`() {
        val lookup = LookupPreferences(app).apply { saveMxnzp(id, secret) }
        val cipher = object : CredentialCipher by app.credentialCipher {
            override fun encrypt(value: String, associatedData: String): String {
                if (associatedData.endsWith("mxnzp-secret")) error("虚构失败")
                return app.credentialCipher.encrypt(value, associatedData)
            }
        }
        val failed = LookupPreferences(app, cipher)
        val error = runCatching { failed.saveMxnzp("fixture-replacement-id", "fixture-replacement-secret") }.exceptionOrNull()
        assertTrue(error is CredentialStorageException)
        assertFalse(error.toString().contains(secret))
        assertEquals(id, lookup.configuredMxnzp()!!.appId)
        assertEquals(secret, lookup.configuredMxnzp()!!.appSecret)
    }

    @Test fun `missing key keeps ciphertext and safe status instead of plaintext retry`() {
        LookupPreferences(app).saveAppCode(code)
        val previous = prefs("cabinet-lookup").getString(SecureCredentials.encryptedKey("appcode"), null)
        val unavailable = LookupPreferences(app, failingCipher)
        assertEquals(CredentialProtection.UNAVAILABLE, unavailable.settings.value.aliyunProtection)
        assertNull(unavailable.enabledAppCode())
        assertEquals(previous, prefs("cabinet-lookup").getString(SecureCredentials.encryptedKey("appcode"), null))
        assertEquals(code, LookupPreferences(app).enabledAppCode())
    }

    @Test fun `credential access respects latest disabled flag from another instance`() {
        val first = LookupPreferences(app).apply { saveAppCode(code); saveMxnzp(id, secret) }
        val second = LookupPreferences(app)
        second.setEnabled(false)
        second.setMxnzpEnabled(false)
        assertNull(first.enabledAppCode())
        assertNull(first.enabledMxnzp())
        first.refresh()
        assertFalse(first.settings.value.enabled)
        assertFalse(first.settings.value.mxnzpEnabled)
    }

    @Test fun `legacy cleanup failure is explicit and retried after persisted ciphertext verification`() {
        val underlying = prefs("cleanup-fixture")
        underlying.edit().putString("secret", secret).commit()
        var commits = 0
        val failingCleanup = object : SharedPreferences by underlying {
            override fun edit(): SharedPreferences.Editor {
                val editor = underlying.edit()
                return object : SharedPreferences.Editor by editor {
                    // 候选密文、保存确认各成功一次，之后只注入旧明文清理失败。
                    override fun commit(): Boolean = if (++commits > 2) false else editor.commit()
                }
            }
        }
        val first = SecureCredentials(failingCleanup, "cleanup-fixture", app.credentialCipher).read(listOf("secret"))
        assertEquals(CredentialProtection.CLEANUP_PENDING, first.protection)
        assertEquals(secret, first.values["secret"])
        assertTrue(underlying.contains("secret"))
        assertTrue(underlying.contains(SecureCredentials.encryptedKey("secret")))
        val retry = SecureCredentials(underlying, "cleanup-fixture", app.credentialCipher).read(listOf("secret"))
        assertEquals(CredentialProtection.ENCRYPTED, retry.protection)
        assertFalse(underlying.contains("secret"))
    }

    @Test fun `failed disk write retains legacy credentials for retry`() {
        val underlying = prefs("disk-fixture")
        underlying.edit().putString("secret", secret).commit()
        val failedDisk = object : SharedPreferences by underlying {
            override fun edit(): SharedPreferences.Editor {
                val editor = underlying.edit()
                return object : SharedPreferences.Editor by editor { override fun commit() = false }
            }
        }
        val result = SecureCredentials(failedDisk, "disk-fixture", app.credentialCipher).read(listOf("secret"))
        assertEquals(CredentialProtection.MIGRATION_PENDING, result.protection)
        assertTrue(result.values.isEmpty())
        assertEquals(secret, underlying.getString("secret", null))
        assertFalse(underlying.contains(SecureCredentials.encryptedKey("secret")))
    }

    @Test fun `failed cleanup with changed memory cannot be misreported as durable cleanup`() {
        val underlying = prefs("memory-cleanup-fixture")
        underlying.edit().putString("secret", secret).commit()
        var commits = 0
        val memoryOnlyCleanup = object : SharedPreferences by underlying {
            override fun edit(): SharedPreferences.Editor {
                val editor = underlying.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun commit(): Boolean {
                        editor.commit()
                        return ++commits <= 2
                    }
                }
            }
        }
        val store = SecureCredentials(memoryOnlyCleanup, "memory-cleanup-fixture", app.credentialCipher)
        assertEquals(CredentialProtection.CLEANUP_PENDING, store.read(listOf("secret")).protection)
        assertFalse(underlying.contains("secret"))
        assertEquals(CredentialProtection.CLEANUP_PENDING, store.read(listOf("secret")).protection)
        assertTrue(commits >= 4)
        assertEquals(CredentialProtection.ENCRYPTED,
            SecureCredentials(underlying, "memory-cleanup-fixture", app.credentialCipher).read(listOf("secret")).protection)
    }

    @Test fun `wrong preference type and incomplete encrypted pairs are handled safely`() {
        val underlying = prefs("type-fixture")
        underlying.edit().putInt(SecureCredentials.encryptedKey("secret"), 1).commit()
        assertEquals(CredentialProtection.UNAVAILABLE,
            SecureCredentials(underlying, "type-fixture", app.credentialCipher).read(listOf("secret")).protection)
        val pair = prefs("cabinet-lookup")
        pair.edit().putString(SecureCredentials.encryptedKey("mxnzp-id"), app.credentialCipher.encrypt(id, "cabinet-lookup:mxnzp-id"))
            .putString("mxnzp-secret", secret).commit()
        assertNull(LookupPreferences(app).configuredMxnzp())
        assertTrue(pair.contains("mxnzp-secret"))
    }

    @Test fun `credentials and diagnostic history remain absent from medicine backup`() = runBlocking {
        LookupPreferences(app).apply { saveAppCode(code); saveMxnzp(id, secret) }
        ServerCachePreferences(app, "https://catalog.example.com", true, true).saveAutomaticToken("https://catalog.example.com", token)
        val exported = app.repository.exportBackup(true, InterfaceSize.STANDARD)
        for (value in listOf(code, id, secret, token, "encrypted-appcode", "cabinet-reminder-checks")) assertFalse(exported.contains(value))
    }
}
