package app.mauri.cucuto.core.backup

import app.mauri.cucuto.core.data.db.AssignmentEntity
import app.mauri.cucuto.core.data.db.ContactEntity
import app.mauri.cucuto.core.data.db.ContactRingtoneWriteEntity
import app.mauri.cucuto.core.data.db.ContactWithKeys
import app.mauri.cucuto.core.data.db.PhoneRingtoneEntity
import app.mauri.cucuto.core.data.db.RingtoneChoice
import app.mauri.cucuto.core.data.db.RingtoneMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** How a phone ringtone row becomes a backup entry (ADR 0057, ADR 0063). */
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
    fun `android default and leave as is are written as a ringtone choice, without a character ringtone`() {
        val ada =
            ContactWithKeys(
                ContactEntity(id = 1, displayName = "Ada", shuffleSeed = 1, createdAt = 1, updatedAt = 1),
                emptyList(),
            )
        for (
        (choice, expected) in
        listOf(
            RingtoneChoice.ANDROID_DEFAULT to BackupRingtoneChoice.ANDROID_DEFAULT,
            RingtoneChoice.UNCHANGED to BackupRingtoneChoice.UNCHANGED,
        )
        ) {
            val row = PhoneRingtoneEntity(contactId = 1, updatedAt = 1, followsVoice = false, choice = choice)
            val contact = BackupSnapshot.contact(ada, voice, row, write)
            assertEquals(expected, contact.ringtoneChoice)
            assertNull(contact.ringtone)
        }
        val character = BackupSnapshot.contact(ada, voice, PhoneRingtoneEntity(contactId = 1, updatedAt = 1), write)
        assertNull(character.ringtoneChoice)
        assertEquals("frog", character.ringtone!!.characterId)
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
    fun `a ringtone kept while the voice has no ringtones names the kept character and still follows`() {
        val row =
            PhoneRingtoneEntity(
                contactId = 1,
                packId = "com.example.songs",
                characterId = "bubblegum",
                updatedAt = 1,
                followsVoice = true,
            )
        val entry = BackupSnapshot.ringtone(row, voice, null)!!
        assertEquals("com.example.songs" to "bubblegum", entry.packId to entry.characterId)
        assertEquals(true, entry.followsVoice)
    }

    @Test
    fun `a ringtone without a message voice is backed up`() {
        val row =
            PhoneRingtoneEntity(contactId = 1, packId = "com.example.songs", characterId = "bubblegum", updatedAt = 1)
        val entry = BackupSnapshot.ringtone(row, null, null)!!
        assertEquals("bubblegum", entry.characterId)
        assertNull(entry.followsVoice)
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
