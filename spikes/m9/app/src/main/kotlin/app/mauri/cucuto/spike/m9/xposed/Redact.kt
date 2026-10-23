package app.mauri.cucuto.spike.m9.xposed

import android.net.Uri
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Privacy guard for everything the M9 spike logs.
 *
 * Calls carry phone numbers and contact names; WhatsApp call notifications carry JIDs and names.
 * None of that is ever logged. Values that must be correlated within one boot (is this the same
 * call as the previous line?) become a salted, truncated digest; a fresh salt per process makes
 * them meaningless after a reboot.
 */
internal object Redact {

    private val salt: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    fun token(value: String?): String {
        if (value == null) return "<null>"
        if (value.isEmpty()) return "<empty>"
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        md.update(value.toByteArray())
        return "<" + md.digest().take(3).joinToString("") { "%02x".format(it) } + ">"
    }

    /** A call handle (`tel:`, `sip:`, an app-defined scheme) becomes its scheme only. */
    fun handle(uri: Uri?): String = if (uri == null) "<null>" else "${uri.scheme}:<redacted>"

    /** JIDs keep only their domain (`@lid`, `@g.us`, `@s.whatsapp.net`), which is what the spike needs. */
    fun jid(value: String?): String {
        if (value == null) return "<null>"
        val at = value.lastIndexOf('@')
        return if (at <= 0) "<id>" else "<jid${value.substring(at)}>"
    }

    /**
     * Ringtone URIs keep scheme and authority (whose provider plays it) plus whether a
     * `vibration_uri` parameter is present. Paths are tokenised: a media ID is not personal, but a
     * file name chosen by the owner might be.
     */
    fun uri(value: Uri?): String {
        if (value == null) return "<null>"
        val vib = runCatching { value.getQueryParameter("vibration_uri") != null }.getOrDefault(false)
        val own = value.authority?.startsWith("app.mauri.cucuto") == true
        val path = if (own) value.path else token(value.path)
        return "${value.scheme}://${value.authority}$path" + if (vib) " +vibration_uri" else ""
    }

    /** Notification channel IDs can embed a JID or a UUID per chat; keep only a recognisable prefix. */
    fun channel(id: String?): String {
        if (id == null) return "<null>"
        val known = listOf("voip_notification", "individual_chat_defaults", "group_chat_defaults", "silent_notifications")
        return known.firstOrNull { id.startsWith(it) }?.let { "$it*" } ?: "<other:${token(id)}>"
    }
}
