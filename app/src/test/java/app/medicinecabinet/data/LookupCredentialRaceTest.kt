package app.medicinecabinet.data

import androidx.test.core.app.ApplicationProvider
import app.medicinecabinet.TestCabinetApplication
import app.medicinecabinet.security.CredentialCipher
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 受控暂停虚构认证加密，核对先保存、后关闭及不同配置实例的时序。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestCabinetApplication::class)
class LookupCredentialRaceTest {
    private val app get() = ApplicationProvider.getApplicationContext<TestCabinetApplication>()

    private fun duringEncryption(save: (LookupPreferences) -> Unit, change: (LookupPreferences) -> Unit): LookupPreferences {
        val started = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val cipher = object : CredentialCipher by app.credentialCipher {
            override fun encrypt(value: String, associatedData: String): String {
                started.countDown()
                check(proceed.await(10, TimeUnit.SECONDS))
                return app.credentialCipher.encrypt(value, associatedData)
            }
        }
        val preferences = LookupPreferences(app, cipher)
        val failure = AtomicReference<Throwable?>()
        val thread = Thread {
            try { save(preferences) } catch (error: Throwable) { failure.set(error) }
        }
        thread.start()
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS))
            change(preferences)
        } finally { proceed.countDown() }
        thread.join(15000)
        assertFalse(thread.isAlive)
        assertNull(failure.get())
        return preferences
    }

    @Test fun `Aliyun save respects a later disabled switch during encryption`() {
        LookupPreferences(app).saveAppCode("fixture-original-appcode-12345")
        val preferences = duringEncryption(
            { it.saveAppCode("fixture-replacement-appcode-12345") },
            { it.setEnabled(false); assertFalse(it.settings.value.enabled) })
        assertFalse(preferences.settings.value.enabled)
        assertNull(preferences.enabledAppCode())
        assertEquals("fixture-replacement-appcode-12345", preferences.configuredAppCode())
        assertFalse(LookupPreferences(app).settings.value.enabled)
    }

    @Test fun `MXNZP save respects another instance disabling without changing Aliyun`() {
        val original = LookupPreferences(app).apply {
            saveAppCode("fixture-original-appcode-12345")
            saveMxnzp("fixture-original-id", "fixture-original-secret")
        }
        val preferences = duringEncryption(
            { it.saveMxnzp("fixture-replacement-id", "fixture-replacement-secret") },
            { original.setMxnzpEnabled(false) })
        assertFalse(preferences.settings.value.mxnzpEnabled)
        assertNull(preferences.enabledMxnzp())
        assertEquals("fixture-replacement-secret", preferences.configuredMxnzp()!!.appSecret)
        original.refresh()
        assertFalse(original.settings.value.mxnzpEnabled)
        assertEquals("fixture-original-appcode-12345", original.enabledAppCode())
    }

    @Test fun `save tickets preserve disabling before background encryption starts`() {
        val preferences = LookupPreferences(app).apply {
            saveAppCode("fixture-original-appcode-12345")
            saveMxnzp("fixture-original-id", "fixture-original-secret")
        }
        val aliyunIntent = preferences.beginAppCodeSave()
        val mxnzpIntent = preferences.beginMxnzpSave()
        val other = LookupPreferences(app)
        other.setEnabled(false)
        other.setMxnzpEnabled(false)
        preferences.saveAppCode("fixture-replacement-appcode-12345", aliyunIntent)
        preferences.saveMxnzp("fixture-replacement-id", "fixture-replacement-secret", mxnzpIntent)
        assertFalse(preferences.settings.value.enabled)
        assertFalse(preferences.settings.value.mxnzpEnabled)
        assertNull(preferences.enabledAppCode())
        assertNull(preferences.enabledMxnzp())
    }

    @Test fun `first credential save cannot undo a later explicit disabled intent`() {
        val intent = LookupPreferences(app).beginAppCodeSave()
        val preferences = duringEncryption(
            { it.saveAppCode("fixture-first-appcode-12345", intent) },
            { it.setEnabled(false) })
        assertTrue(preferences.settings.value.configured)
        assertFalse(preferences.settings.value.enabled)
    }

    @Test fun `latest explicit enable remains enabled after replacement`() {
        LookupPreferences(app).saveMxnzp("fixture-original-id", "fixture-original-secret")
        val preferences = duringEncryption(
            { it.saveMxnzp("fixture-replacement-id", "fixture-replacement-secret") },
            { it.setMxnzpEnabled(false); it.setMxnzpEnabled(true) })
        assertTrue(preferences.settings.value.mxnzpEnabled)
        assertEquals("fixture-replacement-secret", preferences.enabledMxnzp()!!.appSecret)
    }
}
