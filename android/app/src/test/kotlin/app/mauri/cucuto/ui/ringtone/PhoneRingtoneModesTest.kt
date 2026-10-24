package app.mauri.cucuto.ui.ringtone

import app.mauri.cucuto.core.data.db.ContactRingtoneWriteEntity
import app.mauri.cucuto.core.data.db.PhoneRingtoneEntity
import app.mauri.cucuto.core.data.db.RingtoneChoice
import app.mauri.cucuto.core.data.db.RingtoneMode
import app.mauri.cucuto.core.ringtone.ContactPhoneRingtone
import app.mauri.cucuto.core.ringtone.variants.RingtoneVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneRingtoneModesTest {
    @Test
    fun oneVersionNeedsNoRule() {
        assertEquals(emptyList<RingtoneMode>(), PhoneRingtoneViewModel.modesFor(listOf(RingtoneVariant("a.ogg"))))
        assertEquals(
            emptyList<RingtoneMode>(),
            PhoneRingtoneViewModel.modesFor(listOf(RingtoneVariant("a.ogg", "A"), RingtoneVariant("a.ogg", "B"))),
        )
    }

    @Test
    fun tagsUnlockTimeAndSeason() {
        val plain = listOf(RingtoneVariant("a.ogg"), RingtoneVariant("b.ogg"))
        assertEquals(
            listOf(RingtoneMode.PACK, RingtoneMode.RANDOM, RingtoneMode.FIXED),
            PhoneRingtoneViewModel.modesFor(plain),
        )
        val tagged =
            listOf(
                RingtoneVariant("a.ogg", tags = listOf("Day")),
                RingtoneVariant("b.ogg", tags = listOf("night", "winter")),
            )
        assertEquals(
            listOf(RingtoneMode.PACK, RingtoneMode.RANDOM, RingtoneMode.TIME, RingtoneMode.SEASON, RingtoneMode.FIXED),
            PhoneRingtoneViewModel.modesFor(tagged),
        )
    }

    private fun contact(
        choice: RingtoneChoice?,
        userChanged: Boolean,
        linked: Boolean = true,
    ) = ContactPhoneRingtone(
        enabled = true,
        contactId = 1,
        linkedLookupKey = if (linked) "ada" else null,
        linkedName = "Ada",
        ringtone = choice?.let { PhoneRingtoneEntity(contactId = 1, updatedAt = 0, choice = it) },
        character = null,
        write = ContactRingtoneWriteEntity("ada", 1, null, userChanged = userChanged, writtenAt = 0),
        problem = null,
        syncing = false,
    )

    /** #372: contact detail says "Set in Contacts" only while Cucuto respects a change made there. */
    @Test
    fun aChangeInContactsIsRespectedOnlyForChoicesCucutoWrites() {
        assertTrue(PhoneRingtoneViewModel.setInContacts(contact(RingtoneChoice.CHARACTER, userChanged = true)))
        assertTrue(PhoneRingtoneViewModel.setInContacts(contact(RingtoneChoice.ANDROID_DEFAULT, userChanged = true)))
        assertFalse(PhoneRingtoneViewModel.setInContacts(contact(RingtoneChoice.UNCHANGED, userChanged = true)))
        assertFalse(PhoneRingtoneViewModel.setInContacts(contact(null, userChanged = true)))
        assertFalse(PhoneRingtoneViewModel.setInContacts(contact(RingtoneChoice.CHARACTER, userChanged = false)))
        assertFalse(
            PhoneRingtoneViewModel.setInContacts(contact(RingtoneChoice.CHARACTER, userChanged = true, linked = false)),
        )
    }

    @Test
    fun aRespectedChangeAsksForActionAndNamesNoCharacter() {
        val state =
            PhoneRingtoneUiState(
                loading = false,
                enabled = true,
                contactId = 1,
                linked = true,
                using = true,
                characterName = "K.K.",
                variants = listOf(VariantChip("a.ogg", "A")),
                choice = RingtoneChoice.CHARACTER,
                setInContacts = true,
            )
        assertTrue(state.needsAction)
        assertEquals(app.mauri.cucuto.core.ringtone.sync.RingtoneProblem.ChangedElsewhere, state.shownProblem)
        assertFalse(state.copy(setInContacts = false).needsAction)
    }
}
