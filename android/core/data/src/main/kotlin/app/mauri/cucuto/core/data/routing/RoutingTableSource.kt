package app.mauri.cucuto.core.data.routing

import app.mauri.cucuto.core.data.db.ContactDao
import app.mauri.cucuto.core.data.db.InstalledPackDao
import app.mauri.cucuto.core.data.db.InstalledPackEntity
import app.mauri.cucuto.core.data.db.RoutingRow
import app.mauri.cucuto.core.data.settings.RoutingSettings
import app.mauri.cucuto.core.routing.RoutingTable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * The routing table as a flow: it emits a new table whenever contacts, keys, assignments, installed packs or the
 * global switch change, and only when the resulting table differs.
 */
class RoutingTableSource
    @Inject
    constructor(
        private val contactDao: ContactDao,
        private val installedPackDao: InstalledPackDao,
        private val settings: RoutingSettings,
        private val builder: RoutingTableBuilder,
    ) {
        private data class Inputs(
            val rows: List<RoutingRow>,
            val packs: List<InstalledPackEntity>,
            val enabled: Boolean,
        )

        val tables: Flow<RoutingTable> =
            combine(contactDao.observeRoutingRows(), installedPackDao.observeAll(), settings.enabled, ::Inputs)
                .distinctUntilChanged()
                .map { inputs -> builder.build(inputs.rows, inputs.packs.associateBy { it.packId }, inputs.enabled) }
                .distinctUntilChanged()
    }
