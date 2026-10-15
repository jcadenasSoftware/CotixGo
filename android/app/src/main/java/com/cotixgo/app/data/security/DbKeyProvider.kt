package com.cotixgo.app.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Passphrase aleatoria de la base de datos, envuelta por una clave
 * AES/GCM en AndroidKeyStore. La passphrase nunca se deriva de la
 * contraseña de la cuenta y nunca sale del dispositivo ni se envía al
 * servidor: solo se guarda el blob cifrado (IV + ciphertext) en
 * almacenamiento interno. La clave Keystore no exige autenticación del
 * usuario (UX B: el bloqueo de interfaz es una barrera aparte, no
 * autenticación del almacén), por lo que la sincronización en segundo
 * plano sigue disponible.
 */
object DbKeyProvider {

    class KeyUnavailableException(cause: Throwable?) :
        Exception("Clave local de base de datos no disponible", cause)

    private const val ALIAS = "cotixgo_db_key_v1"
    private const val BLOB = "dbkey.bin"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val PASS_LEN = 32

    private fun blobFile(context: Context): File = File(context.filesDir, BLOB)

    private fun secretKey(): SecretKey {
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
                // UX B: sin binding de autenticación — el bloqueo de la app
                // es una barrera de interfaz, no del almacén.
                .setUserAuthenticationRequired(false)
                .setInvalidatedByBiometricEnrollment(false)
                .build(),
        )
        return gen.generateKey()
    }

    private fun wrap(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = c.iv
        val ct = c.doFinal(plain)
        return iv + ct
    }

    private fun unwrap(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, blob, 0, IV_LEN))
        return c.doFinal(blob, IV_LEN, blob.size - IV_LEN)
    }

    /** Devuelve la passphrase de la base; la crea si no existe. */
    @Synchronized
    fun getOrCreatePassphrase(context: Context): ByteArray {
        val f = blobFile(context)
        if (f.exists()) {
            try {
                return unwrap(f.readBytes())
            } catch (e: Exception) {
                throw KeyUnavailableException(e)
            }
        }
        val pass = ByteArray(PASS_LEN).also { SecureRandom().nextBytes(it) }
        f.writeBytes(wrap(pass))
        return pass
    }

    /**
     * true si existe el blob envuelto. Su ausencia junto a una base
     * cifrada equivale a pérdida de clave: una passphrase nueva no puede
     * abrir una base creada con otra, por lo que regenerar en ese estado
     * produciría fallos de apertura fuera del flujo informado de resync.
     */
    fun hasPassphraseBlob(context: Context): Boolean = blobFile(context).exists()

    @Synchronized
    fun deletePassphrase(context: Context) {
        blobFile(context).delete()
    }
}
