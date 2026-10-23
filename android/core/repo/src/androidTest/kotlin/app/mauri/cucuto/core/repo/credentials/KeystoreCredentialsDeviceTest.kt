package app.mauri.cucuto.core.repo.credentials

import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mauri.cucuto.core.repo.RepositoryCredentials
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/** The real Android Keystore key behind [EncryptedCredentialStore], on a device. Uses its own key alias and file. */
@RunWith(AndroidJUnit4::class)
class KeystoreCredentialsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val keys = AndroidKeystoreKeyProvider(alias = ALIAS)
    private val file = File(context.noBackupFilesDir, "repo-credentials-test.json")
    private val url = "https://private.example/repository.json"

    @After
    fun tearDown() {
        keys.delete()
        file.delete()
    }

    @Test
    fun keyIsHardwareBackedNonExportableAndGcmOnly() {
        val key: SecretKey = keys.key()
        assertNull("Keystore keys never expose their material", key.encoded)
        val factory = SecretKeyFactory.getInstance(key.algorithm, AndroidKeystoreKeyProvider.ANDROID_KEYSTORE)
        val info = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
        assertEquals(256, info.keySize)
        assertTrue(info.blockModes.contentEquals(arrayOf(KeyProperties.BLOCK_MODE_GCM)))
        assertTrue(
            "Expected TEE or StrongBox, got ${info.securityLevel}",
            info.securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ||
                info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX,
        )
    }

    @Test
    fun storeRoundTripsThroughTheKeystore() =
        runBlocking {
            val credentials = RepositoryCredentials.Basic("ana", "s3cret")
            val store = EncryptedCredentialStore(file, AesGcmCredentialCipher(keys))
            store.put(url, credentials)
            assertFalse(file.readText().contains("s3cret"))
            // A fresh provider (as after an app restart) loads the same key from the Keystore.
            val reopened =
                EncryptedCredentialStore(file, AesGcmCredentialCipher(AndroidKeystoreKeyProvider(alias = ALIAS)))
            assertEquals(credentials, reopened.get(url))
        }

    @Test
    fun deletingTheKeyMakesCredentialsUnreadable() =
        runBlocking {
            val store = EncryptedCredentialStore(file, AesGcmCredentialCipher(keys))
            store.put(url, RepositoryCredentials.Bearer("tok3n"))
            keys.delete()
            assertNull(store.get(url))
        }

    private companion object {
        const val ALIAS = "app.mauri.cucuto.repo.credentials.test"
    }
}
