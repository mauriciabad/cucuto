package app.mauri.cucuto.spike.waring.xposed

/** Keeps the spike's logs free of personal data: URIs are reduced to their shape, names are never logged. */
object Redact {
    /**
     * `content://settings/system/ringtone` stays as it is (it is not personal); any other URI becomes its scheme and
     * authority (`content://media/…`, `file://…`, `android.resource://com.whatsapp/…`).
     */
    fun uri(value: String?): String {
        if (value == null) return "null"
        if (value in PUBLIC_URIS) return value
        val schemeEnd = value.indexOf("://")
        if (schemeEnd < 0) return "<${value.substringBefore(':', "?")}:…>"
        val rest = value.substring(schemeEnd + 3)
        val authority = rest.substringBefore('/')
        return value.substring(0, schemeEnd + 3) + authority + "/…"
    }

    private val PUBLIC_URIS =
        setOf(
            "content://settings/system/ringtone",
            "content://settings/system/notification_sound",
        )
}
