package app.mauri.cucuto.core.data.chats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The linking rules of ADR 0065, every collision case. Made-up names, keys and lookup keys only. */
class ChatLinkRulesTest {
    private val ana = WaitingChat(contactId = 1, packageName = WA, name = "Ana Test", lookupKey = "lk-ana")
    private val anaSighting = UnlinkedSighting(ChatKey(WA, "100000000000001@lid"), "Ana Test")

    /** Contacts hold exactly these WhatsApp entries. */
    private fun entries(
        vararg people: Pair<String, String>,
        packageName: String = WA,
    ) = ChatLinkRules.namesakesOf(
        people.mapIndexed { i, (lookupKey, name) ->
            WhatsAppContact(lookupKey, name, packageName, listOf("3460000000$i@s.whatsapp.net"))
        },
    )

    @Test
    fun `an exact unique match links, ignoring case, surrounding spaces and Unicode form`() {
        val decomposed = "Jose\u0301 Test" // "José" with a combining accent
        val jose = WaitingChat(2, WA, "Jos\u00e9 Test", "lk-jose")
        val sightings =
            listOf(
                UnlinkedSighting(ChatKey(WA, "100000000000001@lid"), "  ana TEST "),
                UnlinkedSighting(ChatKey(WA, "100000000000002@lid"), decomposed),
            )
        val plan =
            ChatLinkRules.plan(listOf(ana, jose), sightings, entries("lk-ana" to "Ana Test", "lk-jose" to "José Test"))

        assertEquals(mapOf(sightings[0].key to 1L, sightings[1].key to 2L), plan.links)
        assertTrue(plan.questions.isEmpty())
    }

    @Test
    fun `accents and inner spaces count, so a near miss is a plain recent sender`() {
        val sightings =
            listOf(
                UnlinkedSighting(ChatKey(WA, "100000000000001@lid"), "Ána Test"),
                UnlinkedSighting(ChatKey(WA, "100000000000002@lid"), "Ana  Test"),
            )
        val plan = ChatLinkRules.plan(listOf(ana), sightings, entries("lk-ana" to "Ana Test"))

        assertEquals(LinkPlan(), plan)
    }

    @Test
    fun `two waiting chats with the same name are asked, never guessed`() {
        val otherAna = WaitingChat(2, WA, "Ana Test", "lk-ana-2")
        val plan =
            ChatLinkRules.plan(
                listOf(ana, otherAna),
                listOf(anaSighting),
                entries("lk-ana" to "Ana Test", "lk-ana-2" to "Ana Test"),
            )

        assertTrue(plan.links.isEmpty())
        assertEquals(mapOf(anaSighting.key to listOf(1L, 2L)), plan.questions)
    }

    @Test
    fun `two senders with the same name and one waiting chat ask about both`() {
        val second = UnlinkedSighting(ChatKey(WA, "100000000000002@lid"), "Ana Test")
        val plan = ChatLinkRules.plan(listOf(ana), listOf(anaSighting, second), entries("lk-ana" to "Ana Test"))

        assertTrue(plan.links.isEmpty())
        assertEquals(mapOf(anaSighting.key to listOf(1L), second.key to listOf(1L)), plan.questions)
    }

    @Test
    fun `a namesake in the contacts that was not added blocks the link`() {
        val plan =
            ChatLinkRules.plan(
                listOf(ana),
                listOf(anaSighting),
                entries("lk-ana" to "Ana Test", "lk-someone-else" to "ana test"),
            )

        assertTrue(plan.links.isEmpty())
        assertEquals(listOf(1L), plan.questions[anaSighting.key])
    }

    @Test
    fun `a namesake whose chat is already linked is no obstacle`() {
        val people =
            listOf(
                WhatsAppContact("lk-ana", "Ana Test", WA, listOf("34600000001@s.whatsapp.net")),
                WhatsAppContact("lk-linked", "Ana Test", WA, listOf("34600000002@s.whatsapp.net")),
            )
        val plan =
            ChatLinkRules.plan(
                listOf(ana),
                listOf(anaSighting),
                ChatLinkRules.namesakesOf(people, settled = setOf(WA to "lk-linked")),
            )

        assertEquals(mapOf(anaSighting.key to 1L), plan.links)
    }

    @Test
    fun `without the contacts permission every match is asked`() {
        val plan = ChatLinkRules.plan(listOf(ana), listOf(anaSighting), namesakes = { _, _ -> null })

        assertTrue(plan.links.isEmpty())
        assertEquals(listOf(1L), plan.questions[anaSighting.key])
        assertEquals(plan, ChatLinkRules.plan(listOf(ana), listOf(anaSighting), namesakes = null))
    }

    @Test
    fun `a waiting chat with no Android link cannot be checked, so it is asked`() {
        val restored = ana.copy(lookupKey = null)
        val plan = ChatLinkRules.plan(listOf(restored), listOf(anaSighting), entries("lk-ana" to "Ana Test"))

        assertTrue(plan.links.isEmpty())
        assertEquals(listOf(1L), plan.questions[anaSighting.key])
    }

    @Test
    fun `WhatsApp and WhatsApp Business are matched separately`() {
        val business = UnlinkedSighting(ChatKey(WA_BUSINESS, "100000000000009@lid"), "Ana Test")
        val plan = ChatLinkRules.plan(listOf(ana), listOf(business), entries("lk-ana" to "Ana Test"))

        assertEquals(LinkPlan(), plan)
    }

    @Test
    fun `missing and blank names never match`() {
        assertNull(ChatLinkRules.nameKey(null))
        assertNull(ChatLinkRules.nameKey("   "))
        val plan =
            ChatLinkRules.plan(
                listOf(ana.copy(name = null), WaitingChat(2, WA, " ", "lk-blank")),
                listOf(UnlinkedSighting(ChatKey(WA, "1@lid"), null), UnlinkedSighting(ChatKey(WA, "2@lid"), " ")),
                namesakes = { _, _ -> emptySet() },
            )

        assertEquals(LinkPlan(), plan)
    }

    private companion object {
        const val WA = ChatKey.WHATSAPP
        const val WA_BUSINESS = ChatKey.WHATSAPP_BUSINESS
    }
}
