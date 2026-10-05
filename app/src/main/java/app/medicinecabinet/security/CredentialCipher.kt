package app.medicinecabinet.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface CredentialCipher {
    fun encrypt(value: String, associatedData: String): String
    fun decrypt(value: String, associatedData: String): String
}

/** 每次加密由密码库生成随机 IV；附加认证数据阻止不同配置字段之间替换密文。 */
class AesGcmCredentialCipher(private val keyProvider: (Boolean) -> SecretKey) : CredentialCipher {
    override fun encrypt(value: String, associatedData: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..4096)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider(true))
        cipher.updateAAD(associatedData.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(bytes)
        return "v1:${encode(cipher.iv)}:${encode(encrypted)}"
    }

    override fun decrypt(value: String, associatedData: String): String {
        require(value.length <= 8192)
        val parts = value.split(':')
        require(parts.size == 3 && parts[0] == "v1")
        require(parts.drop(1).all { Regex("[A-Za-z0-9+/]+={0,2}").matches(it) })
        val iv = Base64.decode(parts[1], Base64.NO_WRAP)
        val encrypted = Base64.decode(parts[2], Base64.NO_WRAP)
        require(iv.size == 12 && encrypted.size in 17..4112)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(false), GCMParameterSpec(128, iv))
        cipher.updateAAD(associatedData.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }
    private fun encode(value: ByteArray) = Base64.encodeToString(value, Base64.NO_WRAP)
}

/** 设备专属密钥不可导出；不要求生物识别，以允许系统安排的后台补传。 */
fun androidCredentialCipher(): CredentialCipher = AesGcmCredentialCipher { create ->
    synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null)
        if (existing != null) existing as SecretKey else {
            check(create) { "认证加密密钥不可用" }
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).setUserAuthenticationRequired(false).build())
            }.generateKey()
        }
    }
}

private const val KEY_ALIAS = "medicine-cabinet-credentials-v1"
private val keyLock = Any()
