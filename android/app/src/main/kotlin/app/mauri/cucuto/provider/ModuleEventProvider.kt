package app.mauri.cucuto.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import app.mauri.cucuto.xposed.events.DecisionEvent
import app.mauri.cucuto.xposed.events.EventContract
import app.mauri.cucuto.xposed.events.ModuleStatus
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Receives the module's decision events (issue #17): `call("report", extras)` from `system_server`, batched and
 * rate-limited on the module side (ADR 0010). Only uid 1000 may call; everything else is refused, so no app can
 * forge decisions or seen senders. The call returns as soon as the batch is handed to [ModuleEventSink], which
 * writes to the database in the background.
 */
class ModuleEventProvider : ContentProvider() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SinkEntryPoint {
        fun moduleEventSink(): ModuleEventSink
    }

    override fun onCreate(): Boolean = true

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

        val context = requireNotNull(context).applicationContext
        EntryPointAccessors
            .fromApplication(
                context,
                SinkEntryPoint::class.java,
            ).moduleEventSink()
            .accept(events, status)
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
