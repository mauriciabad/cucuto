package app.mauri.cucuto.core.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

class BackupCryptoTest {
    @Test
    fun `argon2id matches the reference implementation`() {
        // phc-winner-argon2 src/test.c: argon2id v1.3, t=2, m=2^16 KiB, p=1, "password", "somesalt".
        val key =
            BackupCrypto.deriveKey(
                "password".toCharArray(),
                "somesalt".encodeToByteArray(),
                KdfParameters(memoryKiB = 65_536, iterations = 2, parallelism = 1),
            )
        assertEquals("09316115d5cf24ed5a15a31a3ba326e5cf32edc24702987c02b6566f61913cf7", key.toHex())
    }

    @Test
    fun `passphrases are normalized to NFC`() {
        val salt = ByteArray(16) { it.toByte() }
        val composed = BackupCrypto.deriveKey("caf\u00e9".toCharArray(), salt, KdfParameters.MINIMUM)
        val decomposed = BackupCrypto.deriveKey("cafe\u0301".toCharArray(), salt, KdfParameters.MINIMUM)
        assertArrayEquals(composed, decomposed)
    }

    @Test
    fun `streaming GCM is standard AES-256-GCM with the backup AAD`() {
        val key = Random(1).nextBytes(32)
        val nonce = Random(2).nextBytes(12)
        // Larger than the stream buffer, and not a multiple of the block size.
        val plain = Random(3).nextBytes(200_003)
        val ours =
            ByteArrayOutputStream()
                .also {
                    BackupCrypto.encrypt(key, nonce, ByteArrayInputStream(plain), it)
                }.toByteArray()

        val jca = Cipher.getInstance("AES/GCM/NoPadding")
        jca.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        jca.updateAAD("cucuto-backup-v1".encodeToByteArray())
        assertArrayEquals(jca.doFinal(plain), ours)

        val back = ByteArrayOutputStream().also { BackupCrypto.decrypt(key, nonce, ByteArrayInputStream(ours), it) }
        assertArrayEquals(plain, back.toByteArray())
    }

    @Test
    fun `a wrong key or a changed byte fails`() {
        val key = Random(1).nextBytes(32)
        val nonce = Random(2).nextBytes(12)
        val sealed =
            ByteArrayOutputStream()
                .also { BackupCrypto.encrypt(key, nonce, ByteArrayInputStream("hello".encodeToByteArray()), it) }
                .toByteArray()
        val wrongKey = key.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertThrows(BackupDecryptionException::class.java) {
            BackupCrypto.decrypt(wrongKey, nonce, ByteArrayInputStream(sealed), ByteArrayOutputStream())
        }
        val tampered = sealed.copyOf().also { it[2] = (it[2] + 1).toByte() }
        assertThrows(BackupDecryptionException::class.java) {
            BackupCrypto.decrypt(key, nonce, ByteArrayInputStream(tampered), ByteArrayOutputStream())
        }
        assertThrows(BackupDecryptionException::class.java) {
            BackupCrypto.decrypt(key, nonce, ByteArrayInputStream(sealed.copyOf(10)), ByteArrayOutputStream())
        }
    }
}
