package app.mauri.cucuto.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mauri.cucuto.core.data.db.DecisionLogEntity
import app.mauri.cucuto.core.data.repository.DecisionLogRepository
import app.mauri.cucuto.core.data.routing.PublishState
import app.mauri.cucuto.core.data.routing.RoutingPublisher
import app.mauri.cucuto.core.data.routing.RoutingSink
import app.mauri.cucuto.core.data.routing.SinkConnection
import app.mauri.cucuto.core.data.settings.RoutingSettings
import app.mauri.cucuto.provider.ModuleEventSink
import app.mauri.cucuto.provider.ReceivedStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Everything the Diagnostics screen shows. */
data class DiagnosticsState(
    val enabled: Boolean = true,
    val connection: SinkConnection = SinkConnection.Disconnected,
    val publish: PublishState = PublishState.Idle,
    val moduleStatus: ReceivedStatus? = null,
    val decisions: List<DecisionLogEntity> = emptyList(),
)

/** Feedback for the last button the user pressed. */
sealed interface DiagnosticsMessage {
    data object None : DiagnosticsMessage

    data object TestPosted : DiagnosticsMessage

    data object NotificationsBlocked : DiagnosticsMessage

    /** Android would mute a test posted this soon after the previous one (notification cooldown). */
    data class TestTooSoon(
        val seconds: Int,
    ) : DiagnosticsMessage

    data object NoSender : DiagnosticsMessage

    data class VoiceAssigned(
        val label: String,
    ) : DiagnosticsMessage

    data class Failed(
        val reason: String,
    ) : DiagnosticsMessage
}

@HiltViewModel
class DiagnosticsViewModel
    @Inject
    constructor(
        sink: RoutingSink,
        publisher: RoutingPublisher,
        events: ModuleEventSink,
        decisionLog: DecisionLogRepository,
        private val settings: RoutingSettings,
        private val notifier: SelfTestNotifier,
        private val testVoice: TestVoice,
    ) : ViewModel() {
        val state: StateFlow<DiagnosticsState> =
            combine(
                settings.enabled,
                sink.connection,
                publisher.state,
                events.status,
                decisionLog.observeRecent(RECENT_DECISIONS),
            ) { enabled, connection, publish, status, decisions ->
                DiagnosticsState(enabled, connection, publish, status, decisions)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosticsState())

        /** Whether the debug-only "test voice" action is offered. */
        val testVoiceAvailable: Boolean = testVoice.available

        private val _message = MutableStateFlow<DiagnosticsMessage>(DiagnosticsMessage.None)
        val message: StateFlow<DiagnosticsMessage> = _message.asStateFlow()

        fun setEnabled(enabled: Boolean) {
            viewModelScope.launch { settings.setEnabled(enabled) }
        }

        fun postTestNotification() =
            run {
                when (val result = notifier.post()) {
                    SelfTestNotifier.Result.Posted -> DiagnosticsMessage.TestPosted
                    SelfTestNotifier.Result.Blocked -> DiagnosticsMessage.NotificationsBlocked
                    is SelfTestNotifier.Result.TooSoon -> DiagnosticsMessage.TestTooSoon(result.seconds)
                }
            }

        fun assignTestVoice() =
            run {
                when (val result = testVoice.assignToLatestSender()) {
                    is TestVoice.Result.Assigned -> DiagnosticsMessage.VoiceAssigned(result.label)
                    TestVoice.Result.NoSender -> DiagnosticsMessage.NoSender
                    TestVoice.Result.Unavailable -> DiagnosticsMessage.Failed("debug builds only")
                }
            }

        private fun run(action: suspend () -> DiagnosticsMessage) {
            viewModelScope.launch {
                _message.value =
                    try {
                        action()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        DiagnosticsMessage.Failed(e.message ?: e.javaClass.simpleName)
                    }
            }
        }

        private companion object {
            const val RECENT_DECISIONS = 50
        }
    }
