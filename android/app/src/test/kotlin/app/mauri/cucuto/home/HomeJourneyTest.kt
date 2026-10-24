package app.mauri.cucuto.home

import app.mauri.cucuto.core.data.chats.Chat
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.chats.ChatVoice
import app.mauri.cucuto.core.data.voices.VoiceCharacter
import app.mauri.cucuto.core.data.voices.VoicePack
import app.mauri.cucuto.core.routing.Haptic
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeJourneyTest {
    private val frog = VoiceCharacter(PACK, "frog", "Bleep the frog", null, null, null, emptyList(), Haptic.Keep)
    private val packs = listOf(VoicePack(PACK, "Chiptune critters", listOf(frog)))

    private fun chat(
        id: String,
        name: String,
        voice: ChatVoice? = null,
        seenAgo: Long = 60_000,
    ) = Chat(ChatKey(WA, id), null, name, false, voice, NOW - seenAgo, 1)

    private fun state(
        chats: List<Chat>,
        installed: List<VoicePack> = packs,
    ) = buildHomeState(chats, installed, query = "", packFilter = null, now = NOW)

    @Test
    fun nothingWhileLoading() {
        assertEquals(JourneyStep.None, homeJourney(HomeState(), firstVoiceCelebrated = false, celebrating = null))
        assertEquals(JourneyStep.None, homeJourney(state(emptyList()), firstVoiceCelebrated = null, celebrating = null))
    }

    @Test
    fun withoutPacksItOffersTheDemoPacks() {
        val step = homeJourney(state(listOf(chat(ADA, "Ada")), installed = emptyList()), false, null)
        assertEquals(JourneyStep.ImportPacks, step)
    }

    @Test
    fun withPacksAndNoChatsItWaitsForTheFirstMessage() {
        assertEquals(JourneyStep.WaitForMessages, homeJourney(state(emptyList()), false, null))
    }

    @Test
    fun thenItInvitesTheNewestRecentSender() {
        val home = state(listOf(chat(ADA, "Ada"), chat(BEA, "Bea", seenAgo = 3_600_000)))
        val step = homeJourney(home, false, null)
        assertEquals(JourneyStep.GiveFirstVoice(home.recent.first()), step)
        assertEquals("Ada", (step as JourneyStep.GiveFirstVoice).chat.chat.displayName)
    }

    @Test
    fun anOldSenderIsInvitedWhenNobodyIsRecent() {
        val old = chat(ADA, "Ada", seenAgo = HomeRules.RECENT_WINDOW_MS * 2)
        val home = state(listOf(old))
        assertEquals(JourneyStep.GiveFirstVoice(home.unassigned.single()), homeJourney(home, false, null))
    }

    @Test
    fun theFirstVoiceIsCelebratedOnce() {
        val home = state(listOf(chat(ADA, "Ada", ChatVoice(PACK, "frog")), chat(BEA, "Bea")))
        val first = homeJourney(home, firstVoiceCelebrated = false, celebrating = null)
        assertEquals(JourneyStep.FirstVoice(home.voiced.single()), first)
        assertEquals(JourneyStep.None, homeJourney(home, firstVoiceCelebrated = true, celebrating = null))
    }

    @Test
    fun theCelebrationStaysUntilDismissedEvenOnceSaved() {
        val home = state(listOf(chat(ADA, "Ada", ChatVoice(PACK, "frog"))))
        val adaKey = ChatKey(WA, ADA)
        assertEquals(
            JourneyStep.FirstVoice(home.voiced.single()),
            homeJourney(home, firstVoiceCelebrated = true, celebrating = adaKey),
        )
    }

    @Test
    fun peopleWhoAlreadyHadSeveralVoicesAreNotCongratulated() {
        val home =
            state(listOf(chat(ADA, "Ada", ChatVoice(PACK, "frog")), chat(BEA, "Bea", ChatVoice(PACK, "frog"))))
        assertEquals(JourneyStep.None, homeJourney(home, firstVoiceCelebrated = false, celebrating = null))
    }

    @Test
    fun aVoiceFromARemovedPackIsNotCelebrated() {
        val home = state(listOf(chat(ADA, "Ada", ChatVoice("gone.pack", "owl"))))
        assertEquals(JourneyStep.None, homeJourney(home, firstVoiceCelebrated = false, celebrating = null))
    }

    private companion object {
        const val WA = "com.whatsapp"
        const val PACK = "app.mauri.demo.chiptune-critters"
        const val ADA = "100000000000001@lid"
        const val BEA = "100000000000002@lid"
        const val NOW = 1_761_000_000_000L
    }
}
