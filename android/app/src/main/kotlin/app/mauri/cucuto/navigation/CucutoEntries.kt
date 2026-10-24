package app.mauri.cucuto.navigation

import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import app.mauri.cucuto.BuildConfig
import app.mauri.cucuto.contact.ContactDetailScreen
import app.mauri.cucuto.daynight.DayNightScreen
import app.mauri.cucuto.diagnostics.DiagnosticsScreen
import app.mauri.cucuto.home.AssignVoiceScreen
import app.mauri.cucuto.home.HomeScreen
import app.mauri.cucuto.packs.ui.PackDetailScreen
import app.mauri.cucuto.packs.ui.PacksImportReview
import app.mauri.cucuto.packs.ui.PacksScreen
import app.mauri.cucuto.picker.PickerScreen
import app.mauri.cucuto.sample.SampleChatScreen
import app.mauri.cucuto.ui.contacts.AddFromContactsScreen
import app.mauri.cucuto.ui.contacts.ConfirmLinkScreen
import app.mauri.cucuto.ui.creator.CreatorFinishScreen
import app.mauri.cucuto.ui.creator.CreatorScreen
import app.mauri.cucuto.ui.developer.DeveloperScreen
import app.mauri.cucuto.ui.discover.DiscoverImportReview
import app.mauri.cucuto.ui.discover.DiscoverRepositories
import app.mauri.cucuto.ui.discover.DiscoverRepositoryForm
import app.mauri.cucuto.ui.discover.DiscoverScreen
import app.mauri.cucuto.ui.gallery.GalleryScreen
import app.mauri.cucuto.ui.haptics.HapticEditorScreen
import app.mauri.cucuto.ui.haptics.HapticTarget
import app.mauri.cucuto.ui.onboarding.OnboardingScreen
import app.mauri.cucuto.ui.ringtone.RingtonePickerScreen
import app.mauri.cucuto.ui.settings.BackupExportScreen
import app.mauri.cucuto.ui.settings.LicensesScreen
import app.mauri.cucuto.ui.settings.SettingsScreen
import app.mauri.cucuto.ui.settings.restore.RestoreScreen
import app.mauri.cucuto.ui.status.SetupScreen

/**
 * Every screen of the app, by destination. Feature screens are self-contained composables in their own package;
 * each one gets one `entry` here, as small as possible (contract of #153: other agents add only their own entry).
 * Screens navigate with `onNavigate` (tab roots switch tabs, anything else is pushed) and close with `onBack`.
 * A screen that is a step of its parent's work declares the parent with [childOf] and shares its view models
 * (ADR 0055): the import review, a pack's detail, the repository screens, the backup options, "Check and finish" and
 * "Assign a voice". [setupNeedsAttention] is read while Settings composes, so its banner follows the shell's badge (#180).
 */
fun cucutoEntries(
    navigator: AppNavigator,
    setupNeedsAttention: () -> Boolean = { false },
): (NavKey) -> NavEntry<NavKey> {
    val navigate: (CucutoDestination) -> Unit = navigator::navigate
    val goBack: () -> Unit = { navigator.goBack() }
    return entryProvider {
        // Tab roots: no back arrow; system back goes to Home (or leaves the app from Home).
        entry<CucutoDestination.Home>(metadata = TabRootMetadata) { HomeScreen(onNavigate = navigate) }
        entry<CucutoDestination.Packs>(metadata = TabRootMetadata) { PacksScreen(onNavigate = navigate) }
        entry<CucutoDestination.Discover>(metadata = TabRootMetadata) { DiscoverScreen(onNavigate = navigate) }
        entry<CucutoDestination.Settings>(metadata = TabRootMetadata) {
            SettingsScreen(onNavigate = navigate, setupNeedsAttention = setupNeedsAttention())
        }

        // ChatKey.toString() hides the JID (ADR 0017), so the default content key would be the same for every chat.
        entry<CucutoDestination.Picker>(
            clazzContentKey = { "picker/${it.chat?.packageName}/${it.chat?.conversationKey}" },
        ) { key ->
            PickerScreen(
                chat = key.chat,
                onBack = goBack,
                // Only this picker, and only if the user has not left it already. From Home, a 1:1 chat goes on to
                // its ringtone (step 2, ADR 0073); groups have no phone ringtone.
                onDone = {
                    val chat = key.chat
                    if (key.ringtoneNext && chat != null && !chat.isGroupKey && navigator.current == key) {
                        navigator.replace(key, CucutoDestination.RingtonePicker(chat, step = true))
                    } else {
                        navigator.remove(key)
                    }
                },
                onOpenPacks = { navigate(CucutoDestination.Packs) },
                onOpenDiscover = { navigate(CucutoDestination.Discover) },
            )
        }
        entry<CucutoDestination.Setup> { SetupScreen(onNavigate = navigate, onBack = goBack) }
        // The setup guide reopened from Setup: finishing or skipping just closes it.
        entry<CucutoDestination.Onboarding> { OnboardingScreen(onFinish = goBack, resume = false) }
        entry<CucutoDestination.Diagnostics> { DiagnosticsScreen(onBack = goBack) }
        entry<CucutoDestination.DayNight> { DayNightScreen(onBack = goBack) }
        entry<CucutoDestination.Creator> { key ->
            CreatorScreen(
                sourcePackId = key.sourcePackId,
                onBack = goBack,
                onOpenFinish = { navigate(CucutoDestination.CreatorFinish(key.sourcePackId)) },
                onEditHaptic = { target -> navigate(CucutoDestination.HapticEditor(target)) },
            )
        }
        entry<CucutoDestination.CreatorFinish>(
            metadata = { childOf(CucutoDestination.Creator(it.sourcePackId)) },
        ) { key ->
            CreatorFinishScreen(
                onBack = goBack,
                onCheckMissing = { navigator.remove(key) },
                // Done: the pack is in the library, so the creator closes too.
                onLeaveCreator = { navigator.remove(CucutoDestination.Creator(key.sourcePackId)) },
            )
        }
        entry<CucutoDestination.HapticEditor>(
            clazzContentKey = { "haptics/${it.target.contentKey()}" },
        ) { key ->
            HapticEditorScreen(target = key.target, onBack = goBack, onDone = { navigator.remove(key) })
        }
        entry<CucutoDestination.ContactDetail>(
            clazzContentKey = { "contact/${it.chat.packageName}/${it.chat.conversationKey}" },
        ) { key ->
            ContactDetailScreen(
                chat = key.chat,
                onBack = goBack,
                onChangeCharacter = { navigate(CucutoDestination.Picker(key.chat)) },
                onEditPattern = { navigate(CucutoDestination.HapticEditor(HapticTarget.Chat(key.chat))) },
                onChooseRingtone = { navigate(CucutoDestination.RingtonePicker(key.chat)) },
            )
        }
        entry<CucutoDestination.RingtonePicker>(
            clazzContentKey = { "ringtone/${it.chat.packageName}/${it.chat.conversationKey}" },
        ) { key ->
            RingtonePickerScreen(
                chat = key.chat,
                onBack = goBack,
                onDone = { navigator.remove(key) },
                onOpenPacks = { navigate(CucutoDestination.Packs) },
                onOpenDiscover = { navigate(CucutoDestination.Discover) },
                step = key.step,
            )
        }

        // Settings: backup and restore, licenses (ADR 0055). A restore can also start from Home's first-run card.
        entry<CucutoDestination.BackupExport>(metadata = childOf(CucutoDestination.Settings)) { key ->
            BackupExportScreen(onBack = goBack, onDone = { navigator.remove(key) })
        }
        entry<CucutoDestination.Restore> { key ->
            RestoreScreen(
                backupUri = key.backupUri,
                onBack = goBack,
                onPickAnother = { uri -> navigator.replace(key, CucutoDestination.Restore(uri)) },
                onLeave = { destination -> navigator.replace(key, destination) },
            )
        }
        entry<CucutoDestination.Licenses> { LicensesScreen(onBack = goBack) }

        // Packs and Discover: the import review runs on the tab that started it, on that tab's root view model.
        entry<CucutoDestination.ImportReview>(
            metadata = { childOf(it.from.root) },
        ) { key ->
            when (key.from) {
                CucutoDestination.ImportFrom.Packs -> {
                    PacksImportReview(onBack = goBack, onDone = { navigator.remove(key) })
                }

                CucutoDestination.ImportFrom.Discover -> {
                    DiscoverImportReview(onBack = goBack, onDone = { navigator.remove(key) })
                }
            }
        }
        entry<CucutoDestination.PackDetail>(metadata = childOf(CucutoDestination.Packs)) { key ->
            PackDetailScreen(
                packId = key.packId,
                onBack = goBack,
                onPackMissing = { navigator.remove(key) },
                onDuplicate = { id -> navigate(CucutoDestination.Creator(id)) },
            )
        }
        entry<CucutoDestination.RepositoryForm>(metadata = childOf(CucutoDestination.Discover)) { key ->
            DiscoverRepositoryForm(onBack = goBack, onDone = { navigator.remove(key) })
        }
        entry<CucutoDestination.Repositories>(metadata = childOf(CucutoDestination.Discover)) {
            DiscoverRepositories(onBack = goBack, onNavigate = navigate)
        }

        // Home: every chat without a voice; picking one hands over to the character picker.
        entry<CucutoDestination.AssignVoice>(metadata = childOf(CucutoDestination.Home)) { key ->
            AssignVoiceScreen(
                onBack = goBack,
                onPick = { chat -> navigator.replace(key, CucutoDestination.Picker(chat, ringtoneNext = true)) },
                onAddFromContacts = { navigate(CucutoDestination.AddFromContacts) },
            )
        }
        // Chats from contacts (ADR 0065): one person saved opens their contact detail; several go back to the list.
        entry<CucutoDestination.AddFromContacts> { key ->
            AddFromContactsScreen(
                onBack = goBack,
                onDone = { saved ->
                    val only = saved.singleOrNull()
                    if (only != null) {
                        navigator.replace(key, CucutoDestination.ContactDetail(only))
                    } else {
                        navigator.remove(key)
                    }
                },
            )
        }
        entry<CucutoDestination.ConfirmLink>(
            clazzContentKey = { "confirm-link/${it.chat.packageName}/${it.chat.conversationKey}" },
            metadata = childOf(CucutoDestination.Home),
        ) { key ->
            ConfirmLinkScreen(chat = key.chat, onBack = goBack, onDone = { navigator.remove(key) })
        }

        // Home: the sample chat, a demo that plays in the app without the module (ADR 0075).
        entry<CucutoDestination.SampleChat> {
            SampleChatScreen(onBack = goBack, onOpenDiscover = { navigate(CucutoDestination.Discover) })
        }

        // Debug builds only: R8 drops these screens from release builds.
        if (BuildConfig.DEBUG) {
            entry<CucutoDestination.Developer> { DeveloperScreen(onNavigate = navigate, onBack = goBack) }
            entry<CucutoDestination.Gallery> { GalleryScreen(onBack = goBack) }
        }
    }
}

/** One saved state per edited thing: a chat, or a creator character. */
private fun HapticTarget?.contentKey(): String =
    when (this) {
        null -> "scratch"
        is HapticTarget.Chat -> "chat/${chat.packageName}/${chat.conversationKey}"
        is HapticTarget.CreatorCharacter -> "creator/$characterKey"
    }
