package app.mauri.cucuto.core.backup

import kotlinx.serialization.Serializable
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.modes.GCMModeCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.KeyParameter
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64

/** `envelope.json` of an encrypted backup (`spec/backup.schema.json#/$defs/envelope`). */
@Serializable
data class BackupEnvelope(
    val formatVersion: Int = 1,
    val type: String = TYPE,
    val cipher: String = CIPHER,
    val kdf: EnvelopeKdf,
    /** Base64 of the 12-byte GCM nonce. */
    val nonce: String,
    val payload: String = PAYLOAD,
) {
    companion object {
        const val TYPE = "encrypted-backup"
        const val CIPHER = "AES-256-GCM"
        const val PAYLOAD = "payload.bin"
    }
}

@Serializable
data class EnvelopeKdf(
    val name: String = "argon2id",
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
    /** Base64 (padded) of the random salt. */
    val salt: String,
)

/** Argon2id cost parameters. */
data class KdfParameters(
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
) {
    companion object {
        /** What Cucuto writes (docs/pack-format.md#backups): 64 MiB, 3 passes, one lane. */
        val DEFAULT = KdfParameters(memoryKiB = 65_536, iterations = 3, parallelism = 1)

        /** The schema's floor (OWASP's minimum for Argon2id): 19 MiB and 2 passes. Tests use it to stay fast. */
        val MINIMUM = KdfParameters(memoryKiB = 19_456, iterations = 2, parallelism = 1)
    }
}

/**
 * The encryption of backups (docs/pack-format.md#backups): the key is Argon2id (v1.3) over the passphrase, 32 bytes;
 * the payload is AES-256-GCM with a 12-byte random nonce, a 128-bit tag appended to the ciphertext and the UTF-8 bytes
 * of `cucuto-backup-v1` as additional authenticated data.
 *
 * The passphrase is normalized to Unicode NFC before it is encoded as UTF-8, so the same passphrase typed on another
 * keyboard or platform derives the same key.
 *
 * GCM runs on Bouncy Castle's lightweight engine because it streams in both directions: the platform `Cipher` buffers
 * the whole ciphertext when decrypting, which does not fit a full backup in memory. Decrypted bytes are unauthenticated
 * until [decrypt] returns, so callers write them to a scratch file and use it only after success.
 */
object BackupCrypto {
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 12
    const val SALT_BYTES = 16
    const val TAG_BITS = 128
    val AAD: ByteArray get() = "cucuto-backup-v1".encodeToByteArray()

    private val random = SecureRandom()

    fun randomBytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)

    /** Derives the AES key from [passphrase]. */
    fun deriveKey(
        passphrase: CharArray,
        salt: ByteArray,
        parameters: KdfParameters,
    ): ByteArray {
        val password = Normalizer.normalize(String(passphrase), Normalizer.Form.NFC).encodeToByteArray()
        try {
            val generator = Argon2BytesGenerator()
            generator.init(
                Argon2Parameters
                    .Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withMemoryAsKB(parameters.memoryKiB)
                    .withIterations(parameters.iterations)
                    .withParallelism(parameters.parallelism)
                    .withSalt(salt)
                    .build(),
            )
            return ByteArray(KEY_BYTES).also { generator.generateBytes(password, it) }
        } finally {
            password.fill(0)
        }
    }

    /** Encrypts [input] into [output] (ciphertext then tag). */
    fun encrypt(
        key: ByteArray,
        nonce: ByteArray,
        input: InputStream,
        output: OutputStream,
    ) = process(gcm(true, key, nonce), input, output)

    /**
     * Decrypts [input] into [output]. Throws [BackupDecryptionException] when the key is wrong or the bytes were
     * changed; whatever was written to [output] must then be discarded.
     */
    fun decrypt(
        key: ByteArray,
        nonce: ByteArray,
        input: InputStream,
        output: OutputStream,
    ) {
        try {
            process(gcm(false, key, nonce), input, output)
        } catch (e: InvalidCipherTextException) {
            throw BackupDecryptionException("Wrong passphrase or damaged backup", e)
        }
    }

    internal fun encodeBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    internal fun decodeBase64(text: String): ByteArray? =
        try {
            Base64.getDecoder().decode(text)
        } catch (e: IllegalArgumentException) {
            null
        }

    private fun gcm(
        encrypt: Boolean,
        key: ByteArray,
        nonce: ByteArray,
    ): GCMModeCipher {
        require(key.size == KEY_BYTES) { "AES-256 needs a 32-byte key" }
        require(nonce.size == NONCE_BYTES) { "GCM needs a 12-byte nonce" }
        return GCMBlockCipher.newInstance(AESEngine.newInstance()).apply {
            init(encrypt, AEADParameters(KeyParameter(key), TAG_BITS, nonce, AAD))
        }
    }

    private fun process(
        cipher: GCMModeCipher,
        input: InputStream,
        output: OutputStream,
    ) {
        val buffer = ByteArray(BUFFER_BYTES)
        var out = ByteArray(BUFFER_BYTES + 64)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            val needed = cipher.getUpdateOutputSize(n)
            if (needed > out.size) out = ByteArray(needed)
            val produced = cipher.processBytes(buffer, 0, n, out, 0)
            output.write(out, 0, produced)
        }
        val final = ByteArray(cipher.getOutputSize(0))
        val produced = cipher.doFinal(final, 0)
        output.write(final, 0, produced)
    }

    private const val BUFFER_BYTES = 64 * 1024
}

/** The passphrase is wrong or the encrypted backup was changed (`BACKUP_DECRYPTION_FAILED`). */
class BackupDecryptionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
