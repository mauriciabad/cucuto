package app.mauri.cucuto.navigation

import androidx.navigation3.runtime.NavKey
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.ui.haptics.HapticTarget
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

    /** The ringtone picker (#53) for [chat]'s phone ringtone, opened from its contact detail. */
    @Serializable
    data class RingtonePicker(
        val chat: ChatKey,
    ) : CucutoDestination

    /** The pack creator (#47); with [sourcePackId], a copy of that installed pack to edit ("duplicate and edit"). */
    @Serializable
    data class Creator(
        val sourcePackId: String? = null,
    ) : CucutoDestination

    /**
     * Haptic editor (#32) for [target]: a chat's custom vibration (#33) or a creator character's pattern (#185), or a
     * scratch pad that only previews when null.
     */
    @Serializable
    data class HapticEditor(
        val target: HapticTarget? = null,
    ) : CucutoDestination

    /** Day and night: the place sunrise and sunset are computed for (#118, ADR 0037). */
    @Serializable
    data object DayNight : CucutoDestination

    /**
     * Restore from the backup at [backupUri] (a document picked with the system file picker): passphrase, packs,
     * conflicts and the LSPosed checklist (ADR 0028). Pushed from Settings or Home's first-run card. The URI is all it
     * needs, so a restore interrupted by process death opens the file again.
     */
    @Serializable
    data class Restore(
        val backupUri: String,
    ) : CucutoDestination

    /** Settings → Back up now: the manual backup options (full backup, password, repository passwords). */
    @Serializable
    data object BackupExport : CucutoDestination

    /** Settings → About → Licenses. */
    @Serializable
    data object Licenses : CucutoDestination

    /** The import review of the pack or bundle being imported [from] Packs or Discover (#27, #36). */
    @Serializable
    data class ImportReview(
        val from: ImportFrom,
    ) : CucutoDestination

    /** Where an import runs: each tab keeps its own, in its root's view model. */
    @Serializable
    enum class ImportFrom(
        val root: CucutoDestination,
    ) {
        Packs(CucutoDestination.Packs),
        Discover(CucutoDestination.Discover),
    }

    /** Everything about one installed pack (#27): its characters, where it came from, who uses it, remove. */
    @Serializable
    data class PackDetail(
        val packId: String,
    ) : CucutoDestination

    /** Discover → add a repository or a pack link, or sign in to a saved repository (#36). */
    @Serializable
    data object RepositoryForm : CucutoDestination

    /** Discover → the saved repositories: on or off, sign in, remove (#36). */
    @Serializable
    data object Repositories : CucutoDestination

    /** The creator's "Check and finish" (#47): the checks, then install, save or share. Above [Creator]. */
    @Serializable
    data class CreatorFinish(
        val sourcePackId: String? = null,
    ) : CucutoDestination

    /** Home → Assign a voice: every chat without a voice, to pick one for the character picker. */
    @Serializable
    data object AssignVoice : CucutoDestination

    /** "Add a chat from contacts" (ADR 0065): the explainer, then the WhatsApp entries of Android's contacts. */
    @Serializable
    data object AddFromContacts : CucutoDestination

    /** Home → "Who is this?" (ADR 0065): which person added from contacts the recent sender [chat] is. */
    @Serializable
    data class ConfirmLink(
        val chat: ChatKey,
    ) : CucutoDestination

    /** Home → Try a sample chat: a demo chat that plays a voice in the app, never reaching the module (ADR 0075). */
    @Serializable
    data object SampleChat : CucutoDestination

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
