package app.mauri.cucuto.ui.status

import app.mauri.cucuto.core.data.repository.InstalledPackRepository
import app.mauri.cucuto.core.data.routing.FrameworkReport
import app.mauri.cucuto.core.data.routing.PublishState
import app.mauri.cucuto.core.data.routing.PublishedTableFile
import app.mauri.cucuto.core.data.routing.RoutingPublisher
import app.mauri.cucuto.core.data.routing.RoutingSink
import app.mauri.cucuto.core.data.routing.SinkConnection
import app.mauri.cucuto.core.data.routing.XposedFramework
import app.mauri.cucuto.diagnostics.TestVoice
import app.mauri.cucuto.provider.ModuleEventSink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.withIndex
import javax.inject.Inject

/**
 * The live status report (#24): gathers the inputs of the four checks, the framework link and what LSPosed says about
 * the module (scope, running processes), the module's own reports from `system_server`, the installed messengers and
 * packs, and evaluates them with [StatusChecks]. Framework and package answers are re-read on each value of
 * `refreshes` (scope and installed apps change outside Cucuto); everything else is observed.
 *
 * Shared by the status card ([StatusViewModel]) and the Settings tab's badge ([SetupAttentionViewModel]), so both
 * read setup the same way.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatusReports
    @Inject
    constructor(
        private val sink: RoutingSink,
        private val events: ModuleEventSink,
        private val installedPacks: InstalledPackRepository,
        private val framework: XposedFramework,
        private val messengers: MessengerDetector,
        private val appUpdate: AppUpdateDetector,
        private val publisher: RoutingPublisher,
        private val publishedTable: PublishedTableFile,
    ) {
        private data class Probe(
            val connection: SinkConnection,
            val framework: FrameworkReport?,
            val messengers: Map<String, Boolean>,
            val appUpdatedSinceBoot: Boolean,
        )

        /** The report, re-probed on every value of [refreshes]. Emits nothing until every source has answered once. */
        fun reports(refreshes: Flow<*>): Flow<StatusReport> =
            combine(
                probes(refreshes),
                events.status,
                packCount(),
                providerDelivered(),
            ) { probe, status, packs, delivered ->
                StatusChecks.evaluate(
                    StatusInputs(
                        connection = probe.connection,
                        framework = probe.framework,
                        moduleStatus = status,
                        bootTimeMillis = AppUpdateDetector.bootTimeMillis(),
                        messengers = probe.messengers,
                        packCount = packs,
                        appUpdatedSinceBoot = probe.appUpdatedSinceBoot,
                        providerDelivered = delivered,
                    ),
                )
            }

        /**
         * The framework link, except that "no service" right after the process started is held back until LSPosed
         * had time to hand the service over: otherwise the card would flash a failure (and celebrate when it turns
         * green a moment later) on every launch. The card shows "Checking" meanwhile.
         */
        private fun connection(): Flow<SinkConnection> =
            sink.connection.transformLatest { connection ->
                if (connection == SinkConnection.Disconnected) {
                    val wait = AppUpdateDetector.serviceGraceLeftMillis()
                    if (wait > 0) delay(wait)
                }
                emit(connection)
            }

        private fun probes(refreshes: Flow<*>): Flow<Probe> =
            combine(connection(), refreshes) { connection, _ -> connection }
                .mapLatest { connection ->
                    Probe(
                        connection = connection,
                        framework = if (connection is SinkConnection.Connected) framework.report() else null,
                        messengers = messengers.installed(),
                        appUpdatedSinceBoot = appUpdate.updatedSinceBoot(),
                    )
                }

        private fun packCount(): Flow<Int> =
            installedPacks.observeAll().map { packs -> packs.count { it.packId != TestVoice.PACK_ID } }

        /**
         * Whether the module pulled the app's latest table without the service (ADR 0048). A new publication is
         * "not delivered" for the fraction of a second until the module fetches it, so a change to false (after the
         * first value) is held back for [PULL_GRACE_MILLIS]: the card does not flash "reboot" on every voice change.
         */
        private fun providerDelivered(): Flow<Boolean> =
            combine(publisher.state, publishedTable.served, events.status) { publish, served, status ->
                StatusChecks.providerDelivered(
                    latestGeneration = (publish as? PublishState.Published)?.generation,
                    servedGeneration = served,
                    moduleStatus = status,
                    bootTimeMillis = AppUpdateDetector.bootTimeMillis(),
                )
            }.distinctUntilChanged()
                .withIndex()
                .transformLatest { (index, delivered) ->
                    if (!delivered && index > 0) delay(PULL_GRACE_MILLIS)
                    emit(delivered)
                }

        private companion object {
            /** The module pulls a new table within a fraction of a second of the nudge; this leaves room for it. */
            const val PULL_GRACE_MILLIS = 5_000L
        }
    }
