package app.mauri.cucuto.core.repo.credentials

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts small secrets with authenticated encryption. [associatedData] binds a ciphertext to its context (the
 * repository URL), so ciphertexts cannot be swapped between entries. [decrypt] throws [GeneralSecurityException]
 * for a wrong key, wrong associated data or tampered bytes.
 */
interface CredentialCipher {
    fun encrypt(
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray

    fun decrypt(
        ciphertext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray
}

/** Supplies the AES key. The Android implementation keeps it in the Keystore; tests use an in-memory key. */
fun interface SecretKeyProvider {
    fun key(): SecretKey
}

/**
 * AES-256-GCM with a random 96-bit IV chosen by the cipher (Keystore keys refuse caller-chosen IVs) and a 128-bit tag.
 * Format: `[version 1][IV length][IV][ciphertext and tag]`.
 */
class AesGcmCredentialCipher(
    private val keys: SecretKeyProvider,
) : CredentialCipher {
    override fun encrypt(
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keys.key())
        cipher.updateAAD(associatedData)
        val sealed = cipher.doFinal(plaintext)
        val iv = cipher.iv
        check(iv.size in 12..255) { "Unexpected IV length" }
        return byteArrayOf(FORMAT_VERSION, iv.size.toByte()) + iv + sealed
    }

    override fun decrypt(
        ciphertext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        if (ciphertext.size < 2 || ciphertext[0] != FORMAT_VERSION) throw GeneralSecurityException("Unknown format")
        val ivLength = ciphertext[1].toInt() and 0xFF
        if (ivLength < 12 || ciphertext.size < 2 + ivLength + TAG_BITS / 8) throw GeneralSecurityException("Truncated")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keys.key(), GCMParameterSpec(TAG_BITS, ciphertext, 2, ivLength))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(ciphertext, 2 + ivLength, ciphertext.size - 2 - ivLength)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val FORMAT_VERSION: Byte = 1
    }
}

/**
 * An AES-256-GCM key in the Android Keystore, created on first use. The key material never enters the app process
 * and is not exportable, so it is never part of a backup or a device transfer. It works only while the device is
 * unlocked, which is always the case when the app talks to repositories (no background jobs).
 *
 * Jetpack Security (`security-crypto`) is deprecated "in favour of existing platform APIs and direct use of Android
 * Keystore" (https://developer.android.com/jetpack/androidx/releases/security), which is what this does
 * (https://developer.android.com/privacy-and-security/keystore).
 */
class AndroidKeystoreKeyProvider(
    private val alias: String = DEFAULT_ALIAS,
) : SecretKeyProvider {
    @Volatile private var cached: SecretKey? = null

    override fun key(): SecretKey = cached ?: synchronized(this) { cached ?: loadOrCreate().also { cached = it } }

    private fun loadOrCreate(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUnlockedDeviceRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    /** Deletes the key; every credential encrypted with it becomes unreadable. */
    fun delete() {
        synchronized(this) {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(alias)
            cached = null
        }
    }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "app.mauri.cucuto.repo.credentials.v1"
    }
}
