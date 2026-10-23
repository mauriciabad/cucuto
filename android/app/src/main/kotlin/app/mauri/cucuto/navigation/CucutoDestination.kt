package app.mauri.cucuto.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Navigation 3 keys for every screen in the brief ("UX, game feel and Material 3 Expressive"). Keys are
 * serializable so the back stack survives process death.
 */
@Serializable
sealed interface CucutoDestination : NavKey {
    /** Status and onboarding: the start destination. */
    @Serializable
    data object Status : CucutoDestination

    /** Contacts list with recent senders. */
    @Serializable
    data object Home : CucutoDestination

    /** Character picker, optionally assigning to [contactKey]. */
    @Serializable
    data class Picker(
        val contactKey: String? = null,
    ) : CucutoDestination

    /** Detail of one contact, keyed by `package + conversation key`. */
    @Serializable
    data class ContactDetail(
        val contactKey: String,
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

    /** Settings and diagnostics. */
    @Serializable
    data object Settings : CucutoDestination

    /** Module status, the decision log and the self-test notification (#17). A real screen, not a placeholder. */
    @Serializable
    data object Diagnostics : CucutoDestination
}
