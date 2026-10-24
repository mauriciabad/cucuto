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

    /**
     * Broadcast the app sends to `system_server` after every publication, so the module re-reads [REMOTE_FILE], or
     * pulls the table from the app ([PROVIDER_PATH]) when the file does not have it (ADR 0048). Sent also when the
     * app has no framework service.
     */
    public const val ACTION_TABLE_PUBLISHED: String = "app.mauri.cucuto.action.ROUTING_TABLE_PUBLISHED"

    /** Long extra of [ACTION_TABLE_PUBLISHED]: the generation just published. Absent from apps before ADR 0048. */
    public const val EXTRA_GENERATION: String = "generation"

    /**
     * Path of the app's event provider (`content://app.mauri.cucuto.events/routing`) that `openFile` serves to the
     * system only: the latest table the app published, framed by [RoutingFile] (ADR 0048).
     */
    public const val PROVIDER_PATH: String = "routing"

    /** Package of the receivers registered by `system_server` with its system context: the nudge targets it only. */
    public const val SYSTEM_PACKAGE: String = "android"

    /** Authority of the app's read-only clip provider (`<applicationId>.clips`). */
    public const val CLIP_AUTHORITY: String = "app.mauri.cucuto.clips"

    /**
     * Largest table written to Remote Preferences. They travel through Binder, whose transaction buffer is 1 MiB per
     * process and shared, and a Java string can take two bytes per character in a parcel. A larger table goes to the
     * remote file and the app's provider only ([MAX_TABLE_BYTES]); Remote Preferences then get a copy without clip
     * envelopes at an older generation, or keep their table (ADR 0054).
     */
    public const val MAX_PREFERENCES_CHARS: Int = 256 * 1024

    /**
     * Largest table the publisher writes, in UTF-8 bytes: what the remote file and the app's provider carry. Both
     * pass a file descriptor, not a Binder parcel, so only the module's read limit ([RoutingFile.MAX_BYTES], the same
     * in every released module) bounds them. A heavy user with every feature on needs about a quarter of it.
     */
    public const val MAX_TABLE_BYTES: Int = 1024 * 1024

    /**
     * The clip provider's URI for a clip, used when the clip is not mirrored (ADR 0018):
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
