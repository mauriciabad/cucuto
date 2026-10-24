package app.mauri.cucuto.navigation

import androidx.navigation3.runtime.NavKey
import app.mauri.cucuto.core.data.chats.ChatKey
import kotlinx.serialization.Serializable

/**
 * Navigation 3 keys for every screen in the brief ("UX, game feel and Material 3 Expressive"). Keys are
 * serializable so the back stacks survive process death.
 *
 * [Home], [Packs], [Discover] and [Settings] are the roots of the four top-level tabs ([TopLevelTab]); navigating to
 * one of them switches tabs. Every other key is pushed onto the current tab's back stack (ADR 0027).
 */
@Serializable
sealed interface CucutoDestination : NavKey {
    /** Tab root: chats and recent senders, plus the setup card until every check is green. */
    @Serializable
    data object Home : CucutoDestination

    /** Tab root: installed packs. */
    @Serializable
    data object Packs : CucutoDestination

    /** Tab root: pack repositories (#36). */
    @Serializable
    data object Discover : CucutoDestination

    /** Tab root: settings (#28). */
    @Serializable
    data object Settings : CucutoDestination

    /** Settings → Setup: the four status checks (#24), the setup guide and Diagnostics. */
    @Serializable
    data object Setup : CucutoDestination

    /** The setup guide reopened from Setup. First run shows it outside the tabs ([AppStage.Onboarding]). */
    @Serializable
    data object Onboarding : CucutoDestination

    /** Character picker assigning a voice to [chat], or only previewing characters when it is null. */
    @Serializable
    data class Picker(
        val chat: ChatKey? = null,
    ) : CucutoDestination

    /** Detail of one chat, keyed by `package + conversation key` (#26). */
    @Serializable
    data class ContactDetail(
        val chat: ChatKey,
    ) : CucutoDestination

    /** Haptic editor for a character or a per-contact override (#32). */
    @Serializable
    data class HapticEditor(
        val contactKey: String? = null,
    ) : CucutoDestination

    /** Module status, the decision log and the self-test notification (#17). */
    @Serializable
    data object Diagnostics : CucutoDestination

    /** Settings → Developer (debug builds only): the design gallery and test tools. */
    @Serializable
    data object Developer : CucutoDestination

    /** Design gallery (debug builds only): theme, shapes and feedback building blocks (#22, #23). */
    @Serializable
    data object Gallery : CucutoDestination
}
