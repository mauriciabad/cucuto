package app.mauri.cucuto.ui.status

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mauri.cucuto.core.data.settings.OnboardingSettings
import app.mauri.cucuto.diagnostics.SelfTestNotifier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the status card and the onboarding flow show. [report] is null until every source has answered once. */
data class StatusUiState(
    val report: StatusReport? = null,
)

/** Feedback for the "Post test notification" button. */
sealed interface SelfTestMessage {
    data object None : SelfTestMessage

    data object Posted : SelfTestMessage

    data object Blocked : SelfTestMessage

    data class TooSoon(
        val seconds: Int,
    ) : SelfTestMessage

    data class Failed(
        val reason: String,
    ) : SelfTestMessage
}

/**
 * The status card and the onboarding flow (#24): the live [StatusReports] report, re-probed on [refresh] (the
 * screens call it on resume, since scope and installed apps change outside Cucuto), the celebration when it turns
 * green, and the self-test notification.
 */
@HiltViewModel
class StatusViewModel
    @Inject
    constructor(
        reports: StatusReports,
        private val onboarding: OnboardingSettings,
        private val notifier: SelfTestNotifier,
    ) : ViewModel() {
        private val refreshes = MutableStateFlow(0)

        val state: StateFlow<StatusUiState> =
            reports
                .reports(refreshes)
                .map(::StatusUiState)
                .stateIn(viewModelScope, SharingStarted.Eagerly, StatusUiState())

        /** Whether the card has ever been green (it then leaves Home for Settings → Setup); null while loading. */
        val everGreen: StateFlow<Boolean?> = onboarding.celebrated.stateIn(viewModelScope, SharingStarted.Eagerly, null)

        private val celebrationChannel = Channel<Unit>(Channel.CONFLATED)

        /**
         * One event each time the card turns green while this screen is alive, and the very first time it is ever
         * green. Opening the app on an already green card stays quiet.
         */
        val celebrations: Flow<Unit> = celebrationChannel.receiveAsFlow()

        private val _selfTest = MutableStateFlow<SelfTestMessage>(SelfTestMessage.None)
        val selfTest: StateFlow<SelfTestMessage> = _selfTest.asStateFlow()

        init {
            viewModelScope.launch {
                var sawNotReady = false
                state.mapNotNull { it.report?.allPassed }.distinctUntilChanged().collect { ready ->
                    if (!ready) {
                        sawNotReady = true
                    } else if (sawNotReady || !onboarding.celebrated.first()) {
                        onboarding.setCelebrated()
                        celebrationChannel.send(Unit)
                    }
                }
            }
        }

        fun refresh() {
            refreshes.value++
        }

        fun postSelfTest() {
            viewModelScope.launch {
                _selfTest.value =
                    try {
                        when (val result = notifier.post()) {
                            SelfTestNotifier.Result.Posted -> SelfTestMessage.Posted
                            SelfTestNotifier.Result.Blocked -> SelfTestMessage.Blocked
                            is SelfTestNotifier.Result.TooSoon -> SelfTestMessage.TooSoon(result.seconds)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        SelfTestMessage.Failed(e.message ?: e.javaClass.simpleName)
                    }
            }
        }
    }
