package app.mauri.cucuto.core.backup

import app.mauri.cucuto.core.data.db.AssignmentEntity
import app.mauri.cucuto.core.data.db.ContactRingtoneWriteEntity
import app.mauri.cucuto.core.data.db.PhoneRingtoneEntity
import app.mauri.cucuto.core.data.db.RingtoneMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** How a phone ringtone row becomes a backup entry (ADR 0057). */
class BackupSnapshotRingtoneTest {
    private val voice =
        AssignmentEntity(contactId = 1, packId = "app.mauri.demo.critters", characterId = "frog", updatedAt = 1)
    private val write =
        ContactRingtoneWriteEntity(
            lookupKey = "0r1-ABC",
            contactId = 1,
            originalRingtone = null,
            writtenUri = "content://media/external/audio/media/7",
            variant = "frog/ringtones/day.ogg",
            writtenAt = 1,
        )

    @Test
    fun `a ringtone that follows the voice is written with the voice's character and the flag`() {
        val entry = BackupSnapshot.ringtone(PhoneRingtoneEntity(contactId = 1, updatedAt = 1), voice, write)!!
        assertEquals("app.mauri.demo.critters" to "frog", entry.packId to entry.characterId)
        assertEquals(true, entry.followsVoice)
        assertEquals(BackupRingtoneRule(BackupRingtoneMode.PACK), entry.rule)
        // The default ringtone before Cucuto: no originalRingtone.
        assertEquals(BackupRingtoneUndo(null, "content://media/external/audio/media/7"), entry.undo)
    }

    @Test
    fun `a ringtone of its own leaves the flag out`() {
        val row =
            PhoneRingtoneEntity(
                contactId = 1,
                packId = "app.mauri.demo.kitchen",
                characterId = "kettle",
                mode = RingtoneMode.FIXED,
                variant = "kettle/ringtones/reel.ogg",
                updatedAt = 1,
            )
        val entry = BackupSnapshot.ringtone(row, voice, null)!!
        assertEquals("app.mauri.demo.kitchen" to "kettle", entry.packId to entry.characterId)
        assertNull(entry.followsVoice)
        assertEquals(BackupRingtoneRule(BackupRingtoneMode.FIXED, "kettle/ringtones/reel.ogg"), entry.rule)
        assertNull(entry.undo)
    }

    @Test
    fun `nothing to ring without a character and without a voice`() {
        assertNull(BackupSnapshot.ringtone(PhoneRingtoneEntity(contactId = 1, updatedAt = 1), null, null))
    }

    @Test
    fun `a ringtone the user changed in Contacts travels without its undo record`() {
        val entry =
            BackupSnapshot.ringtone(
                PhoneRingtoneEntity(contactId = 1, updatedAt = 1),
                voice,
                write.copy(userChanged = true),
            )!!
        assertNull(entry.undo)
        assertNull(
            BackupSnapshot
                .ringtone(
                    PhoneRingtoneEntity(contactId = 1, updatedAt = 1),
                    voice,
                    write.copy(writtenUri = null),
                )!!
                .undo,
        )
    }

    @Test
    fun `a fixed rule without its variant becomes the pack's rule, which the schema accepts`() {
        val entry =
            BackupSnapshot.ringtone(
                PhoneRingtoneEntity(contactId = 1, mode = RingtoneMode.FIXED, variant = null, updatedAt = 1),
                voice,
                null,
            )!!
        assertEquals(BackupRingtoneRule(BackupRingtoneMode.PACK), entry.rule)
    }
}
