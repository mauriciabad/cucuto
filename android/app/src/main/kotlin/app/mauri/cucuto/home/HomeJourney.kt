package app.mauri.cucuto.home

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.settings.OnboardingSettings
import app.mauri.cucuto.core.pack.ImportSource
import app.mauri.cucuto.packs.IncomingImports
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The next step of the first-run journey on Home (ADR 0027): onboarding, then importing the demo packs, then giving
 * the first recent sender a voice, then one celebration. Shown above the chat list.
 */
@Immutable
sealed interface JourneyStep {
    /** Nothing to guide: loading, or the journey is over. */
    data object None : JourneyStep

    /** No pack installed: offer the demo packs. */
    data object ImportPacks : JourneyStep

    /** Packs, but nobody has messaged yet: say so, and offer to preview the characters. */
    data object WaitForMessages : JourneyStep

    /** Packs and chats, but no voice yet: invite the user to give [chat] (the newest sender) one. */
    data class GiveFirstVoice(
        val chat: ChatRow,
    ) : JourneyStep

    /** The first chat that got a voice: celebrate it once, until dismissed. */
    data class FirstVoice(
        val chat: ChatRow,
    ) : JourneyStep
}

/**
 * Picks the journey step from what Home shows. [firstVoiceCelebrated] is null while it loads; [celebrating] is the
 * chat whose celebration is on screen right now (it stays until dismissed, even after the flag is saved).
 *
 * The first voice is celebrated only while exactly one chat has a voice, so people who assigned voices before this
 * journey existed are not congratulated out of the blue.
 */
fun homeJourney(
    state: HomeState,
    firstVoiceCelebrated: Boolean?,
    celebrating: ChatKey?,
): JourneyStep {
    if (state.loading || firstVoiceCelebrated == null) return JourneyStep.None
    if (celebrating != null) {
        return state.voiced.firstOrNull { it.key == celebrating }?.let(JourneyStep::FirstVoice) ?: JourneyStep.None
    }
    return when {
        !state.hasPacks -> {
            JourneyStep.ImportPacks
        }

        state.voiced.isEmpty() -> {
            val next = state.recent.firstOrNull() ?: state.unassigned.firstOrNull()
            if (next == null) JourneyStep.WaitForMessages else JourneyStep.GiveFirstVoice(next)
        }

        !firstVoiceCelebrated && state.voiced.size == 1 && state.voiced.single().character != null -> {
            JourneyStep.FirstVoice(state.voiced.single())
        }

        else -> {
            JourneyStep.None
        }
    }
}

/** First-run journey state that outlives Home's screen: the saved "first voice celebrated" flag, the demo import. */
@HiltViewModel
class HomeJourneyViewModel
    @Inject
    constructor(
        private val onboarding: OnboardingSettings,
        private val incoming: IncomingImports,
    ) : ViewModel() {
        val firstVoiceCelebrated: StateFlow<Boolean?> =
            onboarding.firstVoiceCelebrated.stateIn(viewModelScope, SharingStarted.Eagerly, null)

        private val _celebrating = MutableStateFlow<ChatKey?>(null)

        /** The chat whose first-voice celebration is on screen. */
        val celebrating: StateFlow<ChatKey?> = _celebrating.asStateFlow()

        /** Starts the one-time celebration for [chat] and saves that it happened. */
        fun celebrateFirstVoice(chat: ChatKey) {
            if (_celebrating.value != null) return
            _celebrating.value = chat
            viewModelScope.launch { onboarding.setFirstVoiceCelebrated() }
        }

        fun dismissCelebration() {
            _celebrating.value = null
        }

        /** Hands the picked demo pack file to the Packs tab, which opens and reviews it (the same path as "Open with"). */
        fun importDemoPacks(source: ImportSource) {
            incoming.offer(source)
        }
    }

/**
 * The system file picker for pack zips, opening in Downloads, where the website (and `adb push`) puts the demo packs.
 * No storage permission and nothing bundled in the APK: the user picks the file, as with any pack.
 */
class OpenPackFromDownloads : ActivityResultContracts.OpenDocument() {
    override fun createIntent(
        context: Context,
        input: Array<String>,
    ): Intent =
        super
            .createIntent(context, input)
            .putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildDocumentUri(DOWNLOADS, DOWNLOADS_ROOT),
            )

    private companion object {
        const val DOWNLOADS = "com.android.providers.downloads.documents"
        const val DOWNLOADS_ROOT = "downloads"
    }
}
