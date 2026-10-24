package app.mauri.cucuto.core.routing

/**
 * The names both sides agree on. The app writes the encoded table with
 * `XposedService.getRemotePreferences(PREFERENCES_GROUP).edit().putString(KEY_TABLE, …)`; the module reads it with
 * `XposedInterface.getRemotePreferences(PREFERENCES_GROUP).getString(KEY_TABLE, null)` and listens for changes.
 */
public object RoutingContract {
    /** Remote Preferences group that holds the routing table. */
    public const val PREFERENCES_GROUP: String = "routing"

    /** String preference with the table encoded by [RoutingCodec]. */
    public const val KEY_TABLE: String = "table"

    /** Long preference with the table's [RoutingTable.generation], for cheap staleness checks and diagnostics. */
    public const val KEY_GENERATION: String = "generation"

    /**
     * libxposed remote file with the same encoded table, framed by [RoutingFile]. The module re-reads it when the
     * app sends [ACTION_TABLE_PUBLISHED]: after an app update LSPosed stops delivering preference changes to the
     * running module until the next reboot, but remote files stay readable (ADR 0024).
     */
    public const val REMOTE_FILE: String = "routing.table"

    /** Broadcast the app sends to `system_server` after every publication, so the module re-reads [REMOTE_FILE]. */
    public const val ACTION_TABLE_PUBLISHED: String = "app.mauri.cucuto.action.ROUTING_TABLE_PUBLISHED"

    /** Package of the receivers registered by `system_server` with its system context: the nudge targets it only. */
    public const val SYSTEM_PACKAGE: String = "android"

    /** Authority of the app's read-only clip provider (`<applicationId>.clips`). */
    public const val CLIP_AUTHORITY: String = "app.mauri.cucuto.clips"

    /**
     * Encoded tables above this size are refused by the publisher. Remote Preferences travel through Binder, whose
     * transaction buffer is 1 MiB per process and shared, and a Java string can take two bytes per character in a
     * parcel. A heavy user (500 contacts, 176 distinct voices of 12 clips) needs about half of this limit.
     */
    public const val MAX_ENCODED_CHARS: Int = 256 * 1024

    /**
     * The clip URI the module hands to the notification sound player:
     * `content://app.mauri.cucuto.clips/<packId>/<characterId>/<file>`, where `file` is the clip's path from
     * `pack.json`. Pack IDs, character IDs and pack paths only use URI-safe characters (pack format "IDs" and
     * `common.schema.json#/$defs/path`), so nothing needs escaping.
     */
    public fun clipUri(
        packId: String,
        characterId: String,
        file: String,
        authority: String = CLIP_AUTHORITY,
    ): String = "content://$authority/$packId/$characterId/$file"
}
