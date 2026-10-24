package app.mauri.cucuto.xposed.events

import android.net.Uri
import android.os.ParcelFileDescriptor
import app.mauri.cucuto.core.routing.RoutingContract
import app.mauri.cucuto.core.routing.RoutingFile

/**
 * Pulls the routing table from the app (ADR 0048): `openFile` on `content://app.mauri.cucuto.events/routing`, once
 * [AppProvider] verified that the provider is Cucuto's (package and signing certificate). The app answers only uid
 * 1000 and returns its last published table, framed by [RoutingFile].
 *
 * Background thread only, never on the notification path. The client is unstable, so the app's process dying
 * mid-read does not affect `system_server`; the read stops at [RoutingFile.MAX_BYTES] + 1 bytes (anything larger is
 * rejected by [RoutingFile.unframe]). The module calls this only after the app's own nudge, when the app is already
 * running, and at boot when the remote file does not exist.
 */
internal class ProviderTableReader(
    private val provider: AppProvider,
) {
    private val uri: Uri = Uri.parse("content://${EventContract.AUTHORITY}/${RoutingContract.PROVIDER_PATH}")

    /**
     * The framed table. Throws [DeliveryRefused] (not Cucuto's provider, or unreachable), `FileNotFoundException`
     * (nothing published yet) or whatever the Binder call or the read throws.
     */
    fun read(): ByteArray {
        val ctx = provider.verified()
        val client =
            ctx.contentResolver.acquireUnstableContentProviderClient(uri)
                ?: throw DeliveryRefused("the event provider could not be reached", unavailable = true)
        return client.use {
            val descriptor = it.openFile(uri, "r") ?: throw DeliveryRefused("the event provider returned no table")
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                input.readNBytes(RoutingFile.MAX_BYTES + 1)
            }
        }
    }
}
