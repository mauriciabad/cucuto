package app.mauri.cucuto.xposed.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shapes taken from the supervised M0 run; the identifiers are fake. */
class ConversationKeysTest {
    private val lid = "123456789012345@lid"
    private val group = "120363000000000001@g.us"

    private fun snapshot(
        shortcutId: String? = null,
        shortcutInfoId: String? = null,
        channelId: String? = null,
        title: String? = null,
        conversationTitle: String? = null,
        shortcutLabel: String? = null,
        isGroupConversation: Boolean = false,
        senderKey: String? = null,
        senderName: String? = null,
    ) = NotificationSnapshot(
        packageName = "com.whatsapp",
        channelId = channelId,
        shortcutId = shortcutId,
        shortcutInfoId = shortcutInfoId,
        shortcutLabel = shortcutLabel,
        title = title,
        conversationTitle = conversationTitle,
        isGroupConversation = isGroupConversation,
        senderKey = senderKey,
        senderName = senderName,
    )

    @Test
    fun oneToOneSummaryIsKeyedByItsShortcutId() {
        val id =
            ConversationKeys.identify(
                snapshot(
                    shortcutId = lid,
                    shortcutInfoId = lid,
                    channelId = "individual_chat_defaults_5",
                    title = "Ana",
                ),
            )
        assertEquals(listOf(lid), id.keys)
        assertFalse(id.isGroup)
        assertEquals("Ana", id.displayName)
        assertNull(id.senderKey)
    }

    @Test
    fun groupIsDetectedFromTheChannelPrefix() {
        val id = ConversationKeys.identify(snapshot(shortcutId = group, channelId = "group_chat_defaults_6"))
        assertTrue(id.isGroup)
    }

    @Test
    fun channelPrefixWinsOverOtherSignals() {
        // The suffix number changes between installs; only the prefix matters.
        val id =
            ConversationKeys.identify(
                snapshot(shortcutId = group, channelId = "individual_chat_defaults_12", isGroupConversation = true),
            )
        assertFalse(id.isGroup)
    }

    @Test
    fun groupFallsBackToTheJidDomainThenTheMessagingStyleFlag() {
        assertTrue(ConversationKeys.identify(snapshot(shortcutId = group, channelId = "other")).isGroup)
        assertTrue(ConversationKeys.identify(snapshot(shortcutId = lid, isGroupConversation = true)).isGroup)
        assertFalse(ConversationKeys.identify(snapshot(shortcutId = lid)).isGroup)
    }

    @Test
    fun bothShortcutSourcesAreKeptWhenTheyDiffer() {
        val phone = "15550000001@s.whatsapp.net"
        assertEquals(
            listOf(lid, phone),
            ConversationKeys.candidateKeys(snapshot(shortcutId = lid, shortcutInfoId = phone)),
        )
        assertEquals(listOf(phone), ConversationKeys.candidateKeys(snapshot(shortcutInfoId = phone)))
    }

    @Test
    fun titleStandsInOnlyWithoutAnyShortcut() {
        assertEquals(
            listOf(lid),
            ConversationKeys.candidateKeys(snapshot(shortcutId = lid, conversationTitle = "Family")),
        )
        assertEquals(
            listOf("Family"),
            ConversationKeys.candidateKeys(snapshot(conversationTitle = "Family", title = "Bo")),
        )
        assertEquals(listOf("Bo"), ConversationKeys.candidateKeys(snapshot(title = " Bo ")))
        assertEquals(emptyList<String>(), ConversationKeys.candidateKeys(snapshot(title = "   ")))
    }

    @Test
    fun blankAndOversizedValuesAreIgnored() {
        val huge = "x".repeat(ConversationKeys.MAX_KEY_LENGTH + 1)
        assertEquals(listOf(lid), ConversationKeys.candidateKeys(snapshot(shortcutId = huge, shortcutInfoId = lid)))
        assertEquals(
            emptyList<String>(),
            ConversationKeys.candidateKeys(snapshot(shortcutId = "", shortcutInfoId = " ")),
        )
    }

    @Test
    fun senderIsOnlyReportedForGroups() {
        val inGroup =
            ConversationKeys.identify(
                snapshot(shortcutId = group, channelId = "group_chat_defaults_6", senderKey = lid, senderName = "Ana"),
            )
        assertEquals(lid, inGroup.senderKey)
        assertEquals("Ana", inGroup.senderName)

        val oneToOne = ConversationKeys.identify(snapshot(shortcutId = lid, senderKey = lid, senderName = "Ana"))
        assertNull(oneToOne.senderKey)
        assertNull(oneToOne.senderName)
    }

    @Test
    fun theRawGroupRuleMatchesTheSnapshotRule() {
        // RecordReader decides with the raw values before it opens the newest message (only groups need a sender).
        val cases =
            listOf(
                Triple("group_chat_defaults_6", lid, false),
                Triple("individual_chat_defaults_5", group, true),
                Triple(null, group, false),
                Triple(null, lid, true),
                Triple(null, lid, false),
                Triple("silent_notifications_7", null, false),
            )
        for ((channel, key, flag) in cases) {
            val expected =
                ConversationKeys.isGroup(
                    snapshot(shortcutId = key, channelId = channel, isGroupConversation = flag),
                    key,
                )
            assertEquals("$channel $key $flag", expected, ConversationKeys.isGroup(channel, key, flag))
        }
        assertTrue(ConversationKeys.isGroup("group_chat_defaults_6", lid, false))
        assertFalse(ConversationKeys.isGroup("individual_chat_defaults_5", group, true))
    }

    @Test
    fun displayNamePrefersTheShortcutLabel() {
        assertEquals(
            "Label",
            ConversationKeys.displayName(snapshot(shortcutLabel = "Label", conversationTitle = "C", title = "T")),
        )
        assertEquals("C", ConversationKeys.displayName(snapshot(conversationTitle = "C", title = "T")))
        assertEquals("T", ConversationKeys.displayName(snapshot(title = "T")))
        assertNull(ConversationKeys.displayName(snapshot()))
    }
}
