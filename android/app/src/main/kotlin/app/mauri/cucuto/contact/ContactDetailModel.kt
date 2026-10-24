package app.mauri.cucuto.contact

import androidx.compose.runtime.Immutable
import app.mauri.cucuto.core.data.chats.Chat
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.db.DecisionLogEntity
import app.mauri.cucuto.core.data.db.DecisionOutcome
import app.mauri.cucuto.core.data.db.GroupPolicy
import app.mauri.cucuto.core.data.routing.HapticJson
import app.mauri.cucuto.core.data.voices.VoiceCharacter
import app.mauri.cucuto.core.data.voices.VoicePack
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.xposed.events.Outcome
import app.mauri.cucuto.xposed.route.AlertRouter
import app.mauri.cucuto.xposed.route.Targets
import java.io.File

/**
 * One clip of the chat's character, with its per-contact toggle.
 *
 * @property path The clip's path from `pack.json` (what the toggle stores).
 * @property canToggle False for the last enabled clip: switching it off would silence the voice.
 */
@Immutable
data class ClipRow(
    val path: String,
    val name: String,
    val file: File,
    val enabled: Boolean,
    val canToggle: Boolean,
)

/** Where the chat's vibration comes from (the haptic override editor, #33, changes it). */
enum class HapticSource {
    /** The character's own vibration from its pack. */
    Character,

    /** Per contact: each clip's own envelope ("haptics follow the voice"). */
    FollowVoice,

    /** Per contact: no vibration. */
    Off,

    /** Per contact: a custom pattern. */
    Custom,
}

/**
 * One entry of the decision log for this chat.
 *
 * @property simulated Played by "Simulate message", not by a real message.
 */
@Immutable
data class DecisionRow(
    val id: Long,
    val timestamp: Long,
    val outcome: DecisionOutcome,
    val clipName: String?,
    val simulated: Boolean,
    val perSender: Boolean,
)

/** What the last "Simulate message" tap did. */
sealed interface SimulationStatus {
    data object Idle : SimulationStatus

    /** Choosing the path or posting. */
    data object Working : SimulationStatus

    /** The module got the notification at [atMillis]; its report shows up in the decision log. */
    data class Sent(
        val atMillis: Long,
    ) : SimulationStatus

    data class PlayedInApp(
        val clipName: String,
        val haptic: HapticPlayback,
    ) : SimulationStatus

    /** A real message would keep WhatsApp's tone, for [outcome]'s reason. */
    data class WhatsAppTone(
        val outcome: Outcome,
    ) : SimulationStatus

    data class TooSoon(
        val seconds: Int,
    ) : SimulationStatus

    data object Blocked : SimulationStatus

    data object Failed : SimulationStatus
}

/**
 * Everything contact detail shows.
 *
 * @property chat The chat as the directory knows it, or null if it is unknown (not seen, no contact).
 * @property character The assigned character, or null (no voice, or its pack is gone).
 * @property settings Per-contact settings, or null when the chat has no contact yet.
 * @property path How "Simulate message" will play, once known.
 * @property report The module's decision for the last simulation sent through it, once it arrives.
 */
@Immutable
data class ContactDetailState(
    val chatKey: ChatKey,
    val loading: Boolean = true,
    val chat: Chat? = null,
    val character: VoiceCharacter? = null,
    val packName: String? = null,
    val settings: ChatSettings? = null,
    val clips: List<ClipRow> = emptyList(),
    val decisions: List<DecisionRow> = emptyList(),
    val path: SimulatePath? = null,
    val simulation: SimulationStatus = SimulationStatus.Idle,
    val report: DecisionRow? = null,
    val speaking: Boolean = false,
    val playingClip: String? = null,
    val failed: Boolean = false,
) {
    val isGroup: Boolean get() = chat?.isGroup ?: chatKey.isGroupKey

    /** Assigned to a character whose pack is no longer installed. */
    val voiceMissing: Boolean get() = chat?.voice != null && character == null

    val canSimulate: Boolean get() = character != null && clips.any { it.enabled }

    val hapticSource: HapticSource get() = hapticSource(settings?.hapticOverride)

    /** The group's policy; null for 1:1 chats. */
    val groupPolicy: GroupPolicy? get() = if (isGroup) settings?.groupPolicy ?: GroupPolicy.GROUP else null
}

/** How many of the chat's decisions contact detail lists. */
const val RECENT_DECISIONS: Int = 8

/** The chat's character among the installed packs, with its pack's name. */
fun characterOf(
    chat: Chat?,
    packs: List<VoicePack>,
): Pair<VoiceCharacter, String>? {
    val voice = chat?.voice ?: return null
    val pack = packs.firstOrNull { it.packId == voice.packId } ?: return null
    val character = pack.characters.firstOrNull { it.id == voice.characterId } ?: return null
    return character to pack.name
}

/** The character's clips with the contact's toggles. The last enabled clip cannot be switched off. */
fun clipRows(
    character: VoiceCharacter?,
    disabled: List<String>,
): List<ClipRow> {
    if (character == null) return emptyList()
    val paths = character.clipPaths.takeIf { it.size == character.clips.size } ?: return emptyList()
    val off = disabled.toHashSet()
    val enabledCount = paths.count { it !in off }
    return paths.mapIndexed { i, path ->
        val enabled = path !in off
        ClipRow(
            path = path,
            name = clipName(path),
            file = character.clips[i],
            enabled = enabled,
            canToggle = !enabled || enabledCount > 1,
        )
    }
}

/** Reads the per-contact override like the routing table builder does ([HapticJson.fromOverride]). */
fun hapticSource(overrideJson: String?): HapticSource =
    when (HapticJson.fromOverride(overrideJson)) {
        null -> HapticSource.Character
        Haptic.Auto -> HapticSource.FollowVoice
        Haptic.Off -> HapticSource.Off
        is Haptic.Custom -> HapticSource.Custom
        Haptic.Keep -> HapticSource.Character
    }

/**
 * The decision log entries about [chat], newest first: real alerts of any of its keys or of its contact, and the
 * simulations of any of its keys (reported under Cucuto's own package).
 */
fun decisionsForChat(
    entries: List<DecisionLogEntity>,
    chatKey: ChatKey,
    chat: Chat?,
    limit: Int = RECENT_DECISIONS,
): List<DecisionRow> {
    val keys = chat?.keys?.toSet() ?: setOf(chatKey)
    val keyValues = keys.mapTo(HashSet()) { it.conversationKey }
    val contactId = chat?.contactId
    return entries
        .asSequence()
        .filter { entry ->
            val key = entry.conversationKey
            when {
                entry.isSimulation() -> key != null && key in keyValues
                contactId != null && entry.contactId == contactId -> true
                else -> key != null && ChatKey(entry.packageName, key) in keys
            }
        }.take(limit)
        .map { it.toRow() }
        .toList()
}

/**
 * The module's report for a simulation sent at [sentAtMillis]: the newest simulated row since then. A small margin
 * covers the two clocks (the app's and `system_server`'s wall clock are the same clock, read a few ms apart).
 */
fun reportFor(
    decisions: List<DecisionRow>,
    sentAtMillis: Long,
): DecisionRow? = decisions.firstOrNull { it.simulated && it.timestamp >= sentAtMillis - REPORT_MARGIN_MILLIS }

private const val REPORT_MARGIN_MILLIS = 2_000L

private fun DecisionLogEntity.isSimulation(): Boolean =
    packageName == Targets.SELF_PACKAGE && detail?.startsWith(AlertRouter.SIMULATED_DETAIL) == true

private fun DecisionLogEntity.toRow() =
    DecisionRow(
        id = id,
        timestamp = timestamp,
        outcome = outcome,
        clipName = clipUri?.let(::clipName),
        simulated = isSimulation(),
        perSender = detail?.contains("per-sender") == true,
    )
