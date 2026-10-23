package app.mauri.cucuto.spike.xposed

import android.net.Uri
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Privacy guard for everything this spike writes to logcat.
 *
 * The device is the owner's daily phone, so notification records carry real contacts and real
 * message text. Nothing here ever emits a name, a phone number, a JID or message content. Values
 * that need to be *correlated* (is this the same contact as the previous notification? is the
 * group summary keyed like its children?) are emitted as a salted, truncated digest instead.
 *
 * The salt is random per process, so the tokens are stable within one boot — enough to answer the
 * M0 questions — and meaningless afterwards. They cannot be reversed into a phone number.
 */
internal object Redact {

    private val salt: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    /** Stable-within-boot token for an arbitrary identifier. */
    fun token(value: String?): String {
        if (value == null) return "<null>"
        if (value.isEmpty()) return "<empty>"
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        md.update(value.toByteArray())
        val hex = md.digest().take(3).joinToString("") { "%02x".format(it) }
        return "<$hex>"
    }

    /**
     * Redacts a WhatsApp-style JID while keeping its *domain*, which is the part M0 actually needs
     * to answer (phone JID vs `@lid` vs group `@g.us`).
     *
     * `15550000001@s.whatsapp.net` -> `<jid:a1b2c3@s.whatsapp.net>`
     */
    fun jid(value: String?): String {
        if (value == null) return "<null>"
        val at = value.lastIndexOf('@')
        if (at <= 0) return "<id:${token(value).trim('<', '>')}>"
        val local = value.substring(0, at)
        val domain = value.substring(at) // keeps "@s.whatsapp.net" / "@lid" / "@g.us"
        return "<jid:${token(local).trim('<', '>')}$domain>"
    }

    /**
     * Channel IDs may embed a JID, so they go through the same treatment: any run of 6+ digits is
     * replaced, and anything that looks like a JID is reduced to its domain.
     */
    fun channelId(value: String?): String {
        if (value == null) return "<null>"
        if (value.contains('@')) return jid(value)
        val masked = value.replace(Regex("\\d{6,}")) { "<n:${token(it.value).trim('<', '>')}>" }
        return masked
    }

    /** Human text (contact names, conversation titles, message bodies) is never logged. */
    fun text(value: CharSequence?): String =
        if (value == null) "<null>" else "<text:${token(value.toString()).trim('<', '>')} len=${value.length}>"

    /**
     * Content URIs keep scheme and authority (we need to know *whose* provider it is) but the path
     * is tokenised, because WhatsApp's per-contact tones encode the contact in the path.
     */
    fun uri(value: Uri?): String {
        if (value == null) return "<null>"
        return when (value.scheme) {
            // Our own clip URIs are safe to show in full: no owner data in them.
            "content" -> if (value.authority?.startsWith("app.mauri.cucuto") == true) {
                value.toString()
            } else {
                "content://${value.authority}/${token(value.path).trim('<', '>')}"
            }
            "android.resource", "file" -> "${value.scheme}://${token(value.toString()).trim('<', '>')}"
            else -> "<uri:${token(value.toString()).trim('<', '>')}>"
        }
    }
}
