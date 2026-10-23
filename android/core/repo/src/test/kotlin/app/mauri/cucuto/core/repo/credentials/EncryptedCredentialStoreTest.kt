package app.mauri.cucuto.core.repo.credentials

import app.mauri.cucuto.core.repo.RepositoryAccess
import app.mauri.cucuto.core.repo.RepositoryCredentials
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** The JVM stand-in for the Keystore: same cipher and file format, with a software key. */
class InMemoryKeyProvider : SecretKeyProvider {
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    override fun key(): SecretKey = key
}

class EncryptedCredentialStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val urlA = "https://a.example/repository.json"
    private val urlB = "https://b.example/repository.json"
    private val basic = RepositoryCredentials.Basic("ana", "pässword: with colon")
    private val bearer = RepositoryCredentials.Bearer("abc.DEF-123_~+/=")

    private fun file() = File(tmp.root, "no_backup/repo-credentials.json")

    private fun store(keys: SecretKeyProvider = InMemoryKeyProvider()) =
        EncryptedCredentialStore(file(), AesGcmCredentialCipher(keys))

    @Test
    fun roundTripsAndRemoves() =
        runTest {
            val keys = InMemoryKeyProvider()
            val store = store(keys)
            assertNull(store.get(urlA))
            store.put(urlA, basic)
            store.put(urlB, bearer)
            assertEquals(basic, store.get(urlA))
            assertEquals(bearer, store.get(urlB))
            // A new instance (app restart) reads the same file with the same key.
            assertEquals(basic, store(keys).get(urlA))
            assertEquals(setOf(urlA, urlB), store.repositoryUrls())
            store.remove(urlA)
            assertNull(store.get(urlA))
            assertEquals(setOf(urlB), store.repositoryUrls())
        }

    @Test
    fun fileNeverHoldsSecretsInPlainText() =
        runTest {
            store().apply {
                put(urlA, basic)
                put(urlB, bearer)
            }
            val text = file().readText()
            assertFalse(text.contains("ana"))
            assertFalse(text.contains("colon"))
            assertFalse(text.contains("abc.DEF"))
            assertTrue(text.contains(urlA)) // URLs are the lookup keys, not secrets
        }

    @Test
    fun entriesAreBoundToTheirUrl() =
        runTest {
            val keys = InMemoryKeyProvider()
            store(keys).put(urlA, basic)
            // Move A's ciphertext under B's URL: the associated data no longer matches.
            file().writeText(file().readText().replace(urlA, urlB))
            assertNull(store(keys).get(urlB))
        }

    @Test
    fun anotherKeyCannotDecrypt() =
        runTest {
            store(InMemoryKeyProvider()).put(urlA, basic)
            assertNull(store(InMemoryKeyProvider()).get(urlA))
        }

    @Test
    fun corruptFileReadsAsEmpty() =
        runTest {
            file().parentFile!!.mkdirs()
            file().writeText("{ not json")
            val store = store()
            assertNull(store.get(urlA))
            store.put(urlA, bearer)
            assertEquals(bearer, store.get(urlA))
        }

    @Test
    fun cipherRejectsTamperingAndRandomizesIvs() {
        val cipher = AesGcmCredentialCipher(InMemoryKeyProvider())
        val aad = "ctx".encodeToByteArray()
        val one = cipher.encrypt("secret".encodeToByteArray(), aad)
        val two = cipher.encrypt("secret".encodeToByteArray(), aad)
        assertFalse(one.contentEquals(two))
        assertEquals("secret", cipher.decrypt(one, aad).decodeToString())
        val tampered = one.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertTrue(runCatching { cipher.decrypt(tampered, aad) }.exceptionOrNull() is GeneralSecurityException)
        assertTrue(
            runCatching {
                cipher.decrypt(one, "other".encodeToByteArray())
            }.exceptionOrNull() is GeneralSecurityException,
        )
        assertTrue(
            runCatching { cipher.decrypt(byteArrayOf(9, 12), aad) }.exceptionOrNull() is GeneralSecurityException,
        )
    }

    @Test
    fun credentialsNeverPrintTheirSecrets() {
        val access = RepositoryAccess("https://a.example/repository.json?sig=xyz".toHttpUrl(), basic)
        for (text in listOf(basic.toString(), bearer.toString(), access.toString(), listOf(basic, bearer).toString())) {
            assertFalse(
                text,
                text.contains("ana") || text.contains("colon") || text.contains("abc.DEF") || text.contains("xyz"),
            )
        }
        assertEquals("Basic(username=██, password=██)", basic.toString())
    }

    @Test
    fun headersAreWellFormed() {
        assertEquals("Basic YW5hOnDDpHNzd29yZDogd2l0aCBjb2xvbg==", basic.authorizationHeader())
        assertEquals("Bearer abc.DEF-123_~+/=", bearer.authorizationHeader())
        assertTrue(runCatching { RepositoryCredentials.Bearer("a\r\nX-Evil: 1") }.isFailure)
        assertTrue(runCatching { RepositoryCredentials.Bearer("") }.isFailure)
        assertTrue(runCatching { RepositoryCredentials.Basic("a:b", "c") }.isFailure)
        assertTrue(runCatching { RepositoryCredentials.Basic("a", "c\n") }.isFailure)
    }
}
