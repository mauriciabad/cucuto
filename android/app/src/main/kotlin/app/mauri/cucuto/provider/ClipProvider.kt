package app.mauri.cucuto.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import app.mauri.cucuto.diagnostics.SelfTestSound
import java.io.File
import java.io.FileNotFoundException

/**
 * Read-only clip provider (issue #16): `content://app.mauri.cucuto.clips/<packId>/<characterId>/<clip path>`.
 *
 * The module swaps WhatsApp's notification sound for one of these URIs, and SystemUI's ringtone player opens it
 * (M0). Since ADR 0018 this is the fallback: clips that [MediaClipMirror] copied to shared storage are published as
 * `file://` paths, which SystemUI opens without starting this process. The provider is exported because SystemUI
 * must reach it, so it guards itself:
 *
 * - **Callers:** only the system (uid 1000) and `com.android.systemui`, checked on every call with
 *   `Binder.getCallingUid()` and `PackageManager.getPackagesForUid()` ([ClipAccess]). Everyone else, including
 *   this app's own process, gets a `SecurityException`.
 * - **Read-only:** mode `r` only; insert, update and delete are refused.
 * - **Paths:** only audio files inside an installed pack's active version, validated segment by segment and by
 *   canonical path ([ClipPaths]), so `..`, encoded separators and symlinks cannot escape `files/packs/`.
 */
class ClipProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Cucuto clips are read-only")
        enforceCaller()
        val file = locate(uri) ?: throw FileNotFoundException("No such clip")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? {
        if (!callerAllowed()) return null
        val target = ClipPaths.parse(uri.pathSegments) ?: return null
        return when (target) {
            ClipTarget.SelfTest -> ClipPaths.mimeType(ClipPaths.SELF_TEST_FILE)
            is ClipTarget.PackClip -> ClipPaths.mimeType(target.path.last())
        }
    }

    /** Some players ask for the display name and size before opening. */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        enforceCaller()
        val file = locate(uri) ?: throw FileNotFoundException("No such clip")
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns, 1).apply {
            addRow(
                Array<Any?>(columns.size) { i ->
                    when (columns[i]) {
                        OpenableColumns.DISPLAY_NAME -> file.name
                        OpenableColumns.SIZE -> file.length()
                        else -> null
                    }
                },
            )
        }
    }

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = throw UnsupportedOperationException("Cucuto clips are read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Cucuto clips are read-only")

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Cucuto clips are read-only")

    private fun locate(uri: Uri): File? {
        val context = context ?: return null
        return when (val target = ClipPaths.parse(uri.pathSegments)) {
            null -> null
            ClipTarget.SelfTest -> SelfTestSound.ensureFile(context.filesDir)
            is ClipTarget.PackClip -> ClipPaths.resolve(File(context.filesDir, PACKS_DIR), target)
        }
    }

    private fun callerAllowed(): Boolean {
        val uid = Binder.getCallingUid()
        val packages =
            try {
                context?.packageManager?.getPackagesForUid(uid)
            } catch (_: RuntimeException) {
                null
            }
        return ClipAccess.isAllowedCaller(uid, packages)
    }

    private fun enforceCaller() {
        if (!callerAllowed()) {
            throw SecurityException("Cucuto clips are only served to the system's notification sound player")
        }
    }

    companion object {
        const val AUTHORITY: String = "app.mauri.cucuto.clips"

        /** Installed packs (ADR 0008). */
        const val PACKS_DIR: String = "packs"
    }
}
