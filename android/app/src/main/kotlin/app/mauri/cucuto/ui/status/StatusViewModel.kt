package app.mauri.cucuto.ui.status

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mauri.cucuto.core.data.repository.InstalledPackRepository
import app.mauri.cucuto.core.data.routing.FrameworkReport
import app.mauri.cucuto.core.data.routing.RoutingSink
import app.mauri.cucuto.core.data.routing.SinkConnection
import app.mauri.cucuto.core.data.routing.XposedFramework
import app.mauri.cucuto.core.data.settings.OnboardingSettings
import app.mauri.cucuto.diagnostics.SelfTestNotifier
import app.mauri.cucuto.diagnostics.TestVoice
import app.mauri.cucuto.provider.ModuleEventSink
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
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
 * Gathers the inputs of the four status checks (#24): the framework link and what LSPosed says about the module
 * (scope, running processes), the module's own reports from `system_server`, the installed messengers and packs.
 * Framework and package answers are re-read on [refresh] (the screens call it on resume, since scope and installed
 * apps change outside Cucuto); everything else is observed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatusViewModel
    @Inject
    constructor(
        sink: RoutingSink,
        events: ModuleEventSink,
        installedPacks: InstalledPackRepository,
        private val framework: XposedFramework,
        private val messengers: MessengerDetector,
        private val onboarding: OnboardingSettings,
        private val notifier: SelfTestNotifier,
    ) : ViewModel() {
        private val refreshes = MutableStateFlow(0)

        private data class Probe(
            val connection: SinkConnection,
            val framework: FrameworkReport?,
            val messengers: Map<String, Boolean>,
        )

        private val probes: Flow<Probe> =
            combine(sink.connection, refreshes) { connection, _ -> connection }
                .mapLatest { connection ->
                    Probe(
                        connection = connection,
                        framework = if (connection is SinkConnection.Connected) framework.report() else null,
                        messengers = messengers.installed(),
                    )
                }

        private val packCount: Flow<Int> =
            installedPacks.observeAll().map { packs -> packs.count { it.packId != TestVoice.PACK_ID } }

        val state: StateFlow<StatusUiState> =
            combine(probes, events.status, packCount) { probe, status, packs ->
                StatusUiState(
                    StatusChecks.evaluate(
                        StatusInputs(
                            connection = probe.connection,
                            framework = probe.framework,
                            moduleStatus = status,
                            bootTimeMillis = System.currentTimeMillis() - SystemClock.elapsedRealtime(),
                            messengers = probe.messengers,
                            packCount = packs,
                        ),
                    ),
                )
            }.stateIn(viewModelScope, SharingStarted.Eagerly, StatusUiState())

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
