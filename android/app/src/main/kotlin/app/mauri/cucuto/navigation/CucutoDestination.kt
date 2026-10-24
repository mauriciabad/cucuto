package app.mauri.cucuto.navigation

import androidx.navigation3.runtime.NavKey
import app.mauri.cucuto.core.data.chats.ChatKey
import kotlinx.serialization.Serializable

/**
 * Navigation 3 keys for every screen in the brief ("UX, game feel and Material 3 Expressive"). Keys are
 * serializable so the back stack survives process death.
 */
@Serializable
sealed interface CucutoDestination : NavKey {
    /** Status: the start destination once onboarding is done. */
    @Serializable
    data object Status : CucutoDestination

    /** First-run onboarding (#24): the start destination until it is finished or skipped; reopened from Status. */
    @Serializable
    data object Onboarding : CucutoDestination

    /** Contacts list with recent senders. */
    @Serializable
    data object Home : CucutoDestination

    /** Character picker assigning a voice to [chat], or only previewing characters when it is null. */
    @Serializable
    data class Picker(
        val chat: ChatKey? = null,
    ) : CucutoDestination

    /** Detail of one chat, keyed by `package + conversation key`. */
    @Serializable
    data class ContactDetail(
        val chat: ChatKey,
    ) : CucutoDestination

    /** Installed packs. */
    @Serializable
    data object Packs : CucutoDestination

    /** Pack repositories. */
    @Serializable
    data object Discover : CucutoDestination

    /** Haptic editor for a character or a per-contact override. */
    @Serializable
    data class HapticEditor(
        val contactKey: String? = null,
    ) : CucutoDestination

    /** Day and night: the place sunrise and sunset are computed for (#118, ADR 0037). */
    @Serializable
    data object DayNight : CucutoDestination

    /** Settings and diagnostics. */
    @Serializable
    data object Settings : CucutoDestination

    /** Module status, the decision log and the self-test notification (#17). A real screen, not a placeholder. */
    @Serializable
    data object Diagnostics : CucutoDestination

    /** Design gallery (debug builds only): theme, shapes and feedback building blocks (#22, #23). */
    @Serializable
    data object Gallery : CucutoDestination
}
