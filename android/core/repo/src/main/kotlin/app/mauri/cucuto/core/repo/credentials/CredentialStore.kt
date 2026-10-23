package app.mauri.cucuto.core.repo.credentials

import android.content.Context
import app.mauri.cucuto.core.repo.RepositoryCredentials
import app.mauri.cucuto.core.repo.writeAtomically
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.Base64

/**
 * Repository credentials, keyed by the registered index URL (the `url` of `:core:data`'s repositories table).
 * Implementations never log secrets.
 */
interface CredentialStore {
    /** The credentials of [repositoryUrl], or null when there are none or they can no longer be decrypted. */
    suspend fun get(repositoryUrl: String): RepositoryCredentials?

    suspend fun put(
        repositoryUrl: String,
        credentials: RepositoryCredentials,
    )

    suspend fun remove(repositoryUrl: String)

    /** URLs that have stored credentials. */
    suspend fun repositoryUrls(): Set<String>
}

/**
 * A [CredentialStore] in one JSON file of encrypted entries. Each entry is the credentials as JSON, encrypted with
 * [cipher] (the Keystore key in the app) and bound to its URL as associated data. The app keeps the file in
 * `noBackupFilesDir`, which Android never backs up; Cucuto's own backup includes credentials only when the user opts
 * in and only inside a passphrase-encrypted backup (docs/pack-format.md, "Backups").
 */
class EncryptedCredentialStore(
    private val file: File,
    private val cipher: CredentialCipher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CredentialStore {
    @Serializable
    private data class StoreFile(
        val version: Int = 1,
        val entries: Map<String, String> = emptyMap(),
    )

    @Serializable
    private data class Secret(
        val type: String,
        val username: String? = null,
        val password: String? = null,
        val token: String? = null,
    ) {
        override fun toString() = "Secret(type=$type, ${RepositoryCredentials.REDACTED})"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    override suspend fun get(repositoryUrl: String): RepositoryCredentials? =
        locked {
            val sealed = read().entries[repositoryUrl] ?: return@locked null
            try {
                val plain = cipher.decrypt(Base64.getDecoder().decode(sealed), associatedData(repositoryUrl))
                val secret = json.decodeFromString(Secret.serializer(), plain.decodeToString())
                plain.fill(0)
                when (secret.type) {
                    BASIC -> RepositoryCredentials.Basic(secret.username.orEmpty(), secret.password.orEmpty())
                    BEARER -> RepositoryCredentials.Bearer(secret.token.orEmpty())
                    else -> null
                }
            } catch (e: GeneralSecurityException) {
                null // Key deleted or entry tampered with: the UI asks for the credentials again.
            } catch (e: IllegalArgumentException) {
                null
            } catch (e: SerializationException) {
                null
            }
        }

    override suspend fun put(
        repositoryUrl: String,
        credentials: RepositoryCredentials,
    ) = locked {
        val secret =
            when (credentials) {
                is RepositoryCredentials.Basic -> {
                    Secret(
                        BASIC,
                        username = credentials.username,
                        password = credentials.password,
                    )
                }

                is RepositoryCredentials.Bearer -> {
                    Secret(BEARER, token = credentials.token)
                }
            }
        val plain = json.encodeToString(Secret.serializer(), secret).encodeToByteArray()
        val sealed =
            try {
                cipher.encrypt(plain, associatedData(repositoryUrl))
            } finally {
                plain.fill(0)
            }
        val current = read()
        write(current.copy(entries = current.entries + (repositoryUrl to Base64.getEncoder().encodeToString(sealed))))
    }

    override suspend fun remove(repositoryUrl: String) =
        locked {
            val current = read()
            if (repositoryUrl in current.entries) write(current.copy(entries = current.entries - repositoryUrl))
        }

    override suspend fun repositoryUrls(): Set<String> = locked { read().entries.keys }

    private suspend fun <T> locked(block: () -> T): T = withContext(ioDispatcher) { mutex.withLock { block() } }

    private fun read(): StoreFile =
        try {
            if (file.isFile) json.decodeFromString(StoreFile.serializer(), file.readText()) else StoreFile()
        } catch (e: SerializationException) {
            StoreFile()
        } catch (e: IllegalArgumentException) {
            StoreFile()
        }

    private fun write(content: StoreFile) {
        val dir = file.absoluteFile.parentFile
        if (dir != null && !dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
        writeAtomically(file, json.encodeToString(StoreFile.serializer(), content).encodeToByteArray())
    }

    private fun associatedData(url: String) = "$AAD_PREFIX$url".encodeToByteArray()

    companion object {
        private const val BASIC = "basic"
        private const val BEARER = "bearer"
        private const val AAD_PREFIX = "cucuto-repo-credentials-v1\n"
        const val FILE_NAME = "repo-credentials.json"

        /** The app's store: `noBackupFilesDir/repo-credentials.json`, encrypted with the Keystore key. */
        fun create(context: Context): EncryptedCredentialStore =
            EncryptedCredentialStore(
                file = File(context.noBackupFilesDir, FILE_NAME),
                cipher = AesGcmCredentialCipher(AndroidKeystoreKeyProvider()),
            )
    }
}
