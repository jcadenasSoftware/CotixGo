package com.cotixgo.app.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cifrado simétrico AES/GCM con AndroidKeyStore para secretos pequeños
 * de sesión (token). Sustituye a `androidx.security:security-crypto`
 * (deprecada) usando las APIs directas de `javax.crypto` +
 * `AndroidKeyStore`, como recomienda la documentación oficial.
 */
object SessionCrypto {

    private const val ALIAS = "cotixgo_session_key"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .setInvalidatedByBiometricEnrollment(false)
                .build(),
        )
        return gen.generateKey()
    }

    /** Base64(IV + ciphertext); null si [plain] es null. */
    fun encrypt(plain: String?): String? {
        plain ?: return null
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    /** null si el valor no puede descifrarse (clave invalidada, blob corrupto). */
    fun decryptOrNull(encoded: String?): String? {
        encoded ?: return null
        return try {
            val blob = Base64.decode(encoded, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 0, IV_LEN))
            String(c.doFinal(blob, IV_LEN, blob.size - IV_LEN), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
