package app.mauri.cucuto.core.routing

/**
 * Signal and Molly route a person by their Android contact (ADR 0081). Their notifications carry a `Person` whose URI
 * is the contact's lookup URI (`content://com.android.contacts/contacts/lookup/<lookupKey>/<contactId>`), so the
 * module keys those conversations as `contact:<lookupKey>`, and the app derives the same key from every chat linked to
 * that Android contact. Shared by the module (inside `system_server`) and the app, so both spell it the same way.
 */
public object ContactKeys {
    /** Signal's package. */
    public const val SIGNAL: String = "org.thoughtcrime.securesms"

    /** Molly's package, the Signal fork (Molly-FOSS was merged into it). */
    public const val MOLLY: String = "im.molly.app"

    /** The packages whose notifications name the Android contact. */
    public val PACKAGES: List<String> = listOf(SIGNAL, MOLLY)

    /** Prefix of a contact key. Nothing WhatsApp or Signal uses as a shortcut ID starts with it. */
    public const val PREFIX: String = "contact:"

    /** What a contact lookup URI starts with (`ContactsContract.Contacts.CONTENT_LOOKUP_URI`). */
    public const val LOOKUP_URI_PREFIX: String = "content://com.android.contacts/contacts/lookup/"

    /** Longest key accepted, as for every other conversation key. */
    public const val MAX_KEY_LENGTH: Int = 256

    /** Whether [packageName] is Signal or Molly. */
    public fun isSignalFamily(packageName: String): Boolean = packageName == SIGNAL || packageName == MOLLY

    /** The key of the Android contact [lookupKey]. */
    public fun of(lookupKey: String): String = PREFIX + lookupKey

    /** The lookup key inside [key], or null when [key] is not a contact key. */
    public fun lookupKeyOf(key: String): String? =
        if (key.length > PREFIX.length && key.startsWith(PREFIX)) key.substring(PREFIX.length) else null

    /**
     * The contact key of a `Person` URI, or null when it is not a contact lookup URI. The lookup key segment is kept
     * as written, not decoded: `Contacts.getLookupUri` appends the lookup key as an already encoded segment, so the
     * segment is the lookup key itself. Never throws.
     */
    public fun fromPersonUri(uri: String?): String? {
        if (uri == null || !uri.startsWith(LOOKUP_URI_PREFIX)) return null
        val start = LOOKUP_URI_PREFIX.length
        var end = uri.indexOf('/', start)
        if (end < 0) end = uri.length
        val query = uri.indexOf('?', start)
        if (query in start until end) end = query
        if (end <= start || end - start + PREFIX.length > MAX_KEY_LENGTH) return null
        return PREFIX + uri.substring(start, end)
    }
}
