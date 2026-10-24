package app.mauri.cucuto.core.data.routing

import app.mauri.cucuto.core.routing.RoutingFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The app's own copy of the latest routing table it published, whatever the transport. The event provider serves it
 * to the module, which pulls it when the framework service is missing (ADR 0048). Production: [PublishedTableFile].
 */
interface PublishedTableStore {
    /** The encoded table last written, or null when there is none (or it is unreadable). */
    suspend fun read(): String?

    /** Replaces the stored table with [encoded], whose generation is [generation]. Throws on IO failure. */
    suspend fun write(
        encoded: String,
        generation: Long,
    )
}

/**
 * [PublishedTableStore] in one file, framed by [RoutingFile] like the libxposed remote file. Writes go to a
 * temporary file that is then renamed, so a descriptor the provider opened always reads one complete table: the old
 * one or the new one. Plain `java.io`, unit-tested on the JVM.
 */
class PublishedTableFile(
    /** The table file. Its folder is created on the first write. */
    val file: File,
) : PublishedTableStore {
    private val lock = Any()

    /** Generation of the stored table: null while unknown (not read yet in this process). */
    @Volatile private var generation: Long? = null

    private val _served = MutableStateFlow<Long?>(null)

    /** The generation the module last fetched from the provider in this process, or null if it did not. */
    val served: StateFlow<Long?> = _served.asStateFlow()

    override suspend fun read(): String? = withContext(Dispatchers.IO) { readNow() }

    override suspend fun write(
        encoded: String,
        generation: Long,
    ) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeBytes(RoutingFile.frame(encoded))
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            this@PublishedTableFile.generation = generation
        }
    }

    /** Records that the system opened the table (the provider calls this). Never throws. */
    fun noteServed() {
        try {
            _served.value = generation ?: readNow()?.let(::generationOf)?.also { generation = it }
        } catch (_: RuntimeException) {
            // Only diagnostics depend on this.
        }
    }

    private fun readNow(): String? =
        try {
            if (file.isFile) RoutingFile.unframe(file.readBytes()) else null
        } catch (_: IOException) {
            null
        }

    companion object {
        /** The generation in an encoded table's header (`cucuto-routing 1 <generation> …`, tab-separated), or null. */
        fun generationOf(encoded: String): Long? =
            encoded
                .substringBefore('\n')
                .split('\t')
                .getOrNull(2)
                ?.toLongOrNull()
    }
}
