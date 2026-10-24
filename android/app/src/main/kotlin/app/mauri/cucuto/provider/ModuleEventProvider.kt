package app.mauri.cucuto.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import app.mauri.cucuto.core.data.routing.PublishedTableFile
import app.mauri.cucuto.core.routing.RoutingContract
import app.mauri.cucuto.xposed.events.DecisionEvent
import app.mauri.cucuto.xposed.events.EventContract
import app.mauri.cucuto.xposed.events.ModuleStatus
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.FileNotFoundException

/**
 * The app's side of the module channel (ADR 0010), for `system_server` only: uid 1000 may call; everything else is
 * refused.
 *
 * - `call("report", extras)` receives the module's decision events (issue #17), batched and rate-limited on the
 *   module side, so no app can forge decisions or seen senders. The call returns as soon as the batch is handed to
 *   [ModuleEventSink], which writes to the database in the background.
 * - `openFile(content://app.mauri.cucuto.events/routing, "r")` serves the latest routing table the app published
 *   ([PublishedTableFile]), framed like the libxposed remote file. The module pulls it when nudged and the framework
 *   service did not carry the table (ADR 0048). The table holds contact keys, so only the system may read it.
 */
class ModuleEventProvider : ContentProvider() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SinkEntryPoint {
        fun moduleEventSink(): ModuleEventSink

        fun publishedTable(): PublishedTableFile
    }

    override fun onCreate(): Boolean = true

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        if (!isSystemCaller(Binder.getCallingUid())) {
            throw SecurityException("Only the system may read Cucuto's routing table")
        }
        if (uri.pathSegments != listOf(RoutingContract.PROVIDER_PATH) || mode != "r") {
            throw FileNotFoundException("Not served: ${uri.path} ($mode)")
        }
        val table = entryPoint().publishedTable()
        // Throws FileNotFoundException when the app has not published anything yet.
        val descriptor = ParcelFileDescriptor.open(table.file, ParcelFileDescriptor.MODE_READ_ONLY)
        table.noteServed()
        return descriptor
    }

    private fun entryPoint(): SinkEntryPoint =
        EntryPointAccessors.fromApplication(requireNotNull(context).applicationContext, SinkEntryPoint::class.java)

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle? {
        if (!isSystemCaller(Binder.getCallingUid())) {
            throw SecurityException("Only the system may report Cucuto decisions")
        }
        if (method != EventContract.METHOD_REPORT || extras == null) return null
        if (extras.getInt(EventContract.EXTRA_VERSION, 0) != EventContract.VERSION) return null

        val events =
            extras
                .getParcelableArrayList(EventContract.EXTRA_EVENTS, Bundle::class.java)
                .orEmpty()
                .take(EventContract.MAX_EVENTS_PER_CALL)
                .mapNotNull { bundle -> DecisionEvent.fromMap(bundle.reader()) }
        val status = extras.getBundle(EventContract.EXTRA_STATUS)?.let { ModuleStatus.fromMap(it.reader()) }

        entryPoint().moduleEventSink().accept(events, status)
        return Bundle().apply { putInt(EventContract.RESULT_ACCEPTED, events.size) }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = throw UnsupportedOperationException()

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException()

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException()

    companion object {
        /** uid 1000 in any user: `system_server`, where the module runs. */
        fun isSystemCaller(uid: Int): Boolean = uid >= 0 && uid % 100_000 == ClipAccess.SYSTEM_APP_ID

        /** The module writes only strings, longs, ints and booleans. */
        @Suppress("DEPRECATION")
        private fun Bundle.reader(): (String) -> Any? = { key -> get(key) }
    }
}
