package app.mauri.cucuto.backup

import app.mauri.cucuto.core.backup.RestoreReport
import app.mauri.cucuto.core.backup.RingtoneOutcome
import app.mauri.cucuto.core.ringtone.restore.RestoredLink

/**
 * The contacts of a restore whose Android contact must be found again on this phone (ADR 0013's amendment for #43):
 * every Android link the restore took from the backup, and every phone ringtone it wrote (one kept on this phone is
 * this phone's own). With both runs of a restore (the overwrite run after the conflicts), the last one wins for a
 * contact's ringtone, and a link reported by either run counts. Backup order, one entry per contact.
 */
internal fun restoredLinks(reports: List<RestoreReport>): List<RestoredLink> {
    val ringtones =
        reports
            .flatMap { it.ringtones }
            .filter { it.outcome != RingtoneOutcome.KEPT }
            .associateBy { it.contactId }
    val links =
        reports
            .flatMap { it.contacts }
            .filter { it.androidContact != null }
            .associateBy { it.contactId }
    return (links.keys + ringtones.keys).map { id ->
        val ringtone = ringtones[id]
        val link = links[id]
        RestoredLink(
            contactId = id,
            displayName = ringtone?.displayName ?: link?.displayName,
            androidName = (ringtone?.androidContact ?: link?.androidContact)?.displayName,
            ringtone = ringtone != null,
            originalRingtone = ringtone?.undo?.originalRingtone,
            writtenUri = ringtone?.undo?.writtenUri,
        )
    }
}
