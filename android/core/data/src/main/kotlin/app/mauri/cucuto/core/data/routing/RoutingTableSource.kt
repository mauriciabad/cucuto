package app.mauri.cucuto.core.data.routing

import app.mauri.cucuto.core.conditions.ConditionCompiler
import app.mauri.cucuto.core.conditions.Place
import app.mauri.cucuto.core.data.db.ContactDao
import app.mauri.cucuto.core.data.db.InstalledPackDao
import app.mauri.cucuto.core.data.db.InstalledPackEntity
import app.mauri.cucuto.core.data.db.RoutingRow
import app.mauri.cucuto.core.data.settings.DayNightSettings
import app.mauri.cucuto.core.data.settings.RoutingSettings
import app.mauri.cucuto.core.routing.RoutingTable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * The routing table as a flow: it emits a new table whenever contacts, keys, assignments, installed packs or the
 * global switch change, or the catalog re-reads a pack (new auto haptics), and only when the resulting table differs.
 *
 * Clip conditions (ADR 0037) are compiled from the day-and-night place and today's date, so a table with day or
 * night clips also changes when the place changes and once a day while the app runs, which moves their published
 * span forward. Tables without conditional clips do not depend on either.
 */
class RoutingTableSource
    @Inject
    constructor(
        private val contactDao: ContactDao,
        private val installedPackDao: InstalledPackDao,
        private val settings: RoutingSettings,
        private val builder: RoutingTableBuilder,
        private val dayNight: DayNightSettings,
        private val calendar: LocalCalendar,
    ) {
        private data class Inputs(
            val rows: List<RoutingRow>,
            val packs: List<InstalledPackEntity>,
            val enabled: Boolean,
            val catalogRevision: Long,
            val sky: Sky,
        )

        /** What clip conditions are compiled from. */
        private data class Sky(
            val place: Place?,
            val day: LocalDay,
        )

        val tables: Flow<RoutingTable> =
            combine(
                contactDao.observeRoutingRows(),
                installedPackDao.observeAll(),
                settings.enabled,
                builder.catalogRevisions,
                combine(dayNight.place, calendar.days(), ::Sky),
                ::Inputs,
            ).distinctUntilChanged()
                .map { inputs ->
                    val compiler = ConditionCompiler(inputs.sky.place, inputs.sky.day.zone, inputs.sky.day.date)
                    builder.build(inputs.rows, inputs.packs.associateBy { it.packId }, inputs.enabled, compiler)
                }.distinctUntilChanged()
    }
