package app.mauri.cucuto.ui.ringtone

import app.mauri.cucuto.core.data.db.RingtoneMode
import app.mauri.cucuto.core.ringtone.variants.RingtoneVariant
import org.junit.Assert.assertEquals
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
}
