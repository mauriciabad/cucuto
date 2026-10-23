package app.mauri.cucuto.packs

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.mauri.cucuto.core.pack.ImportSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Zip files handed to Cucuto by other apps: "Open with" (`ACTION_VIEW`) and the share sheet (`ACTION_SEND`), see
 * ADR 0007. The activity [offer]s each file ([importSourceOf]); the Packs screen [take]s it and imports it, so a file
 * opened while the app is elsewhere waits until the screen is up.
 */
@Singleton
class IncomingImports
    @Inject
    constructor() {
        private val _pending = MutableStateFlow<ImportSource?>(null)

        /** The file waiting to be imported, if any. */
        val pending: StateFlow<ImportSource?> = _pending.asStateFlow()

        /** Queues [source], replacing a file that was not taken yet. */
        fun offer(source: ImportSource) {
            _pending.value = source
        }

        /** Hands the waiting file over to exactly one caller. */
        fun take(): ImportSource? = _pending.getAndUpdate { null }
    }

/**
 * The file of a VIEW or SEND [intent], or null when it carries none Cucuto may read. Only the file's bytes are used:
 * nothing in the intent is forwarded or trusted, and the importer decides from the contents what the file is.
 */
fun importSourceOf(
    context: Context,
    intent: Intent?,
): ImportSource? {
    val uri =
        when (intent?.action) {
            Intent.ACTION_VIEW -> {
                intent.data
            }

            Intent.ACTION_SEND -> {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    ?: intent.clipData
                        ?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)
                        ?.uri
            }

            else -> {
                null
            }
        } ?: return null
    if (!isAcceptable(context, uri)) return null
    return ImportSource.of(context.applicationContext.contentResolver, uri)
}

/**
 * Accepts `content:` and `file:` URIs, except ones that point back into Cucuto itself (its own providers or private
 * files): another app must not be able to make Cucuto read its own data as a "pack".
 */
private fun isAcceptable(
    context: Context,
    uri: Uri,
): Boolean =
    when (uri.scheme) {
        ContentResolver.SCHEME_CONTENT -> {
            val authority = uri.authority.orEmpty()
            authority.isNotEmpty() && !authority.startsWith(context.packageName)
        }

        ContentResolver.SCHEME_FILE -> {
            val file = uri.path?.let { runCatching { File(it).canonicalFile }.getOrNull() }
            val ownData = File(context.applicationInfo.dataDir).canonicalFile
            file != null && !file.toPath().startsWith(ownData.toPath())
        }

        else -> {
            false
        }
    }
