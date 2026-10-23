package app.mauri.cucuto.provider

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import app.mauri.cucuto.core.data.db.DecisionLogEntity
import app.mauri.cucuto.core.data.db.DecisionOutcome
import app.mauri.cucuto.core.data.di.ApplicationScope
import app.mauri.cucuto.core.data.repository.DecisionLogRepository
import app.mauri.cucuto.core.data.repository.SeenSenderRepository
import app.mauri.cucuto.xposed.events.DecisionEvent
import app.mauri.cucuto.xposed.events.ModuleStatus
import app.mauri.cucuto.xposed.route.Targets
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** The module's last health report, with when the app received it. */
data class ReceivedStatus(
    val receivedAtMillis: Long,
    val status: ModuleStatus,
)

/**
 * Stores what the module reports: each decision in the decision log, each conversation (except the self-test) in
 * the "recent senders" feed, and the module's status for the Diagnostics screen. Raw keys and names stay in the
 * app's private database and are never logged.
 */
@Singleton
class ModuleEventSink
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val decisionLog: DecisionLogRepository,
        private val seenSenders: SeenSenderRepository,
        @param:ApplicationScope private val scope: CoroutineScope,
    ) {
        private val _status = MutableStateFlow(loadStatus())

        /** The module's last report, or null if it has never reported (not enabled, or no alert since install). */
        val status: StateFlow<ReceivedStatus?> = _status.asStateFlow()

        fun accept(
            events: List<DecisionEvent>,
            status: ModuleStatus?,
        ) {
            if (status != null) saveStatus(ReceivedStatus(System.currentTimeMillis(), status))
            if (events.isEmpty()) return
            scope.launch(Dispatchers.IO) {
                for (event in events) {
                    try {
                        decisionLog.record(event.toEntity())
                        val key = event.conversationKey
                        if (key != null && event.packageName != Targets.SELF_PACKAGE) {
                            seenSenders.record(
                                packageName = event.packageName,
                                conversationKey = key,
                                displayName = event.displayName,
                                isGroup = event.isGroup,
                                seenAt = event.timeMillis,
                                senderKey = event.senderKey,
                                senderName = event.senderName,
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Storing a module event failed (${e.javaClass.simpleName})")
                    }
                }
            }
        }

        private fun saveStatus(received: ReceivedStatus) {
            _status.value = received
            val s = received.status
            prefs().edit {
                putLong(K_RECEIVED, received.receivedAtMillis)
                putLong(K_BOOT, s.bootTimeMillis)
                putString(K_HOOKS, s.hooks.joinToString(","))
                putString(K_MISSING, s.missingHooks.joinToString(","))
                putLong(K_GENERATION, s.tableGeneration)
                putInt(K_ROUTES, s.tableRoutes)
                putLong(K_DECISIONS, s.decisions)
                putLong(K_SLOW, s.slowDecisions)
                putLong(K_MAX_MICROS, s.maxHookMicros)
                putLong(K_DROPPED, s.dropped)
            }
        }

        private fun loadStatus(): ReceivedStatus? {
            val p = prefs()
            if (!p.contains(K_RECEIVED)) return null

            fun list(key: String) =
                p
                    .getString(key, "")
                    .orEmpty()
                    .split(',')
                    .filter { it.isNotEmpty() }
            return ReceivedStatus(
                receivedAtMillis = p.getLong(K_RECEIVED, 0),
                status =
                    ModuleStatus(
                        bootTimeMillis = p.getLong(K_BOOT, 0),
                        hooks = list(K_HOOKS),
                        missingHooks = list(K_MISSING),
                        tableGeneration = p.getLong(K_GENERATION, 0),
                        tableRoutes = p.getInt(K_ROUTES, 0),
                        decisions = p.getLong(K_DECISIONS, 0),
                        slowDecisions = p.getLong(K_SLOW, 0),
                        maxHookMicros = p.getLong(K_MAX_MICROS, 0),
                        dropped = p.getLong(K_DROPPED, 0),
                    ),
            )
        }

        private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        private companion object {
            const val TAG = "cucuto"
            const val PREFS = "module_status"
            const val K_RECEIVED = "received"
            const val K_BOOT = "boot"
            const val K_HOOKS = "hooks"
            const val K_MISSING = "missing"
            const val K_GENERATION = "generation"
            const val K_ROUTES = "routes"
            const val K_DECISIONS = "decisions"
            const val K_SLOW = "slow"
            const val K_MAX_MICROS = "maxMicros"
            const val K_DROPPED = "dropped"

            fun DecisionEvent.toEntity() =
                DecisionLogEntity(
                    timestamp = timeMillis,
                    packageName = packageName,
                    conversationKey = conversationKey,
                    senderKey = senderKey,
                    contactId = contactId,
                    outcome = DecisionOutcome.entries.firstOrNull { it.name == outcome } ?: DecisionOutcome.ERROR,
                    clipUri = clipUri,
                    detail = detail,
                    hookMicros = hookMicros,
                )
        }
    }
