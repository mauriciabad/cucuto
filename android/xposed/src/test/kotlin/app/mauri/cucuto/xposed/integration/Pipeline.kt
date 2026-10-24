package app.mauri.cucuto.xposed.integration

import android.content.Context
import android.content.SharedPreferences
import android.os.VibrationEffect
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import app.mauri.cucuto.core.conditions.Place
import app.mauri.cucuto.core.data.db.CucutoDatabase
import app.mauri.cucuto.core.data.db.InstalledPackEntity
import app.mauri.cucuto.core.data.repository.AssignmentRepository
import app.mauri.cucuto.core.data.repository.ContactRepository
import app.mauri.cucuto.core.data.repository.InstalledPackRepository
import app.mauri.cucuto.core.data.routing.ClipMirror
import app.mauri.cucuto.core.data.routing.ClipMirrorStore
import app.mauri.cucuto.core.data.routing.LocalDay
import app.mauri.cucuto.core.data.routing.PackManifestCatalog
import app.mauri.cucuto.core.data.routing.ProviderClips
import app.mauri.cucuto.core.data.routing.PublishState
import app.mauri.cucuto.core.data.routing.PublishedTableFile
import app.mauri.cucuto.core.data.routing.RoutingPublisher
import app.mauri.cucuto.core.data.routing.RoutingSink
import app.mauri.cucuto.core.data.routing.RoutingTableBuilder
import app.mauri.cucuto.core.data.routing.RoutingTableSource
import app.mauri.cucuto.core.data.routing.SinkConnection
import app.mauri.cucuto.core.data.settings.DayNightSettings
import app.mauri.cucuto.core.data.settings.RoutingSettings
import app.mauri.cucuto.core.haptics.HapticCapabilities
import app.mauri.cucuto.core.haptics.HapticEffects
import app.mauri.cucuto.core.routing.HapticPreset
import app.mauri.cucuto.core.routing.RoutingContract
import app.mauri.cucuto.core.routing.RoutingFile
import app.mauri.cucuto.core.routing.RoutingTable
import app.mauri.cucuto.xposed.ModuleLog
import app.mauri.cucuto.xposed.RoutingTableHolder
import app.mauri.cucuto.xposed.events.DecisionEvent
import app.mauri.cucuto.xposed.hook.AlertHooks
import app.mauri.cucuto.xposed.hook.HapticVibrations
import app.mauri.cucuto.xposed.route.AlertRouter
import app.mauri.cucuto.xposed.route.ShuffleBags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeout
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Cucuto end to end, with fakes only at the platform edges (ADR 0047):
 *
 * - **App:** Room (in memory, platform SQLite through Robolectric), the real repositories and settings, the real
 *   `RoutingTableSource`, `RoutingTableBuilder` (with the real `PackManifestCatalog` over installed demo packs and the
 *   real `ClipMirrorStore`) and `RoutingPublisher`.
 * - **Edge:** [FakeXposedService] stands in for LSPosed: it keeps the Remote Preferences value and the remote file,
 *   and turns the app's nudge broadcast into the module's `refresh()`.
 * - **Module:** the real `RoutingTableHolder`, `AlertRouter`, `HapticVibrations` (with `HapticEffects`) and
 *   `AlertHooks`, installed on [FakeFramework] and fed real `Notification`s.
 *
 * Dates: conditions are compiled for [DAY] in [ZONE] at [PLACE]; [clock] is the module's wall clock.
 *
 * @param mirror Whether SystemUI can read the clip mirror (`file://` URIs); false keeps every clip on the provider.
 */
internal class Pipeline(
    private val root: File,
    mirror: Boolean = true,
    capabilities: HapticCapabilities? = CAPABILITIES,
) {
    val clock = Clock(at(12, 0))
    private val appClock = Clock(1_761_000_000_000)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ---------------------------------------------------------------- app

    val db: CucutoDatabase =
        Room
            .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CucutoDatabase::class.java)
            .setDriver(AndroidSQLiteDriver())
            .build()
    val contacts = ContactRepository(db, db.contactDao(), appClock)
    val assignments = AssignmentRepository(db, db.assignmentDao(), appClock)
    val packs = InstalledPackRepository(db.installedPackDao())
    private val dataStore =
        PreferenceDataStoreFactory.create(scope = scope) { File(root, "settings.preferences_pb") }
    val settings = RoutingSettings(dataStore)
    val dayNight = DayNightSettings(dataStore)
    val packRoot = File(root, "files/packs")
    val mirrorRoot = File(root, "media/clips")
    val chirp = File(root, "files/selftest/chirp.wav").apply { parentFile!!.mkdirs() }
    val clipMirror: ClipMirror =
        if (mirror) {
            StoreMirror(
                ClipMirrorStore(mirrorRoot).also {
                    it.prepare()
                },
                chirp,
            )
        } else {
            ProviderClips
        }
    val catalog = PackManifestCatalog()
    val service = FakeXposedService()

    /** The app's own copy of the table, which its event provider serves to the module (ADR 0048). */
    val store = PublishedTableFile(File(root, "files/routing/published.table"))
    val publisher =
        RoutingPublisher(
            RoutingTableSource(
                db.contactDao(),
                db.installedPackDao(),
                settings,
                RoutingTableBuilder(catalog, clipMirror),
                dayNight,
                { flowOf(LocalDay(DAY, ZONE)) },
            ),
            service,
            store,
            clipMirror,
            appClock,
            scope,
        )

    // ---------------------------------------------------------------- module

    val events: MutableList<DecisionEvent> = CopyOnWriteArrayList()
    val moduleLog: MutableList<String> = CopyOnWriteArrayList()
    private val held = MutableStateFlow(RoutingTable.EMPTY)

    val holder =
        RoutingTableHolder(
            preferences = { service.preferences },
            log = { message, _ -> moduleLog += message },
            remoteFile = { service.remoteFile },
            providerTable = { store.file.takeIf { it.isFile }?.readBytes() },
            onReload = { held.value = it },
        )
    val vibrations = HapticVibrations<VibrationEffect>(HapticEffects::create)
    val router = AlertRouter(ShuffleBags(randomFor = { Random(it.toLong()) }), now = clock::now)
    val framework = FakeFramework()
    val hooks =
        AlertHooks(
            module = framework.xposed,
            table = holder::peek,
            report = { events += it },
            log = ModuleLog(sink = { _, message, _ -> moduleLog += message }),
            router = router,
            vibrations = vibrations,
        )

    init {
        chirp.writeBytes(ByteArray(64) { it.toByte() })
        hooks.install(framework.classLoader)
        capabilities?.let(vibrations::useCapabilities)
        service.onNudge = { generation -> holder.refresh(generation) }
    }

    /** Boots the module (attaches to Remote Preferences) and starts the app's publisher. */
    fun start() {
        holder.ensureAttached()
        held.value = holder.peek()
        publisher.start()
    }

    /** The table the module holds, as it changes. */
    val moduleTables: StateFlow<RoutingTable> = held.asStateFlow()

    /** Waits until the module holds a table matching [predicate] (the app published and the module loaded it). */
    suspend fun awaitModule(predicate: (RoutingTable) -> Boolean): RoutingTable =
        withTimeout(TIMEOUT_MS) { held.first { it.generation != 0L && predicate(it) } }

    suspend fun awaitPublished(): PublishState.Published =
        withTimeout(TIMEOUT_MS) { publisher.state.first { it is PublishState.Published } as PublishState.Published }

    fun install(
        packId: String,
        autoHaptics: Map<String, app.mauri.cucuto.core.routing.Haptic.Custom?> = emptyMap(),
    ): InstalledPackEntity = DemoPacks.install(packRoot, packId, autoHaptics)

    suspend fun setPlace(place: Place = PLACE) = dayNight.setPlace(place)

    /** One alert through the hooks; returns what the framework played and the decision the module reported. */
    fun alert(
        record: FakeNotificationRecord,
        sound: android.net.Uri? = FakeFramework.WHATSAPP_TONE,
        vibration: VibrationEffect? = FakeFramework.WHATSAPP_VIBRATION,
    ): Pair<Alert, DecisionEvent?> {
        val before = events.size
        val alert = framework.alert(record, sound, vibration)
        check(events.size - before <= 1) { "One decision per record, got ${events.size - before}" }
        return alert to events.drop(before).firstOrNull()
    }

    fun close() {
        scope.cancel()
        db.close()
    }

    class Clock(
        @Volatile var time: Long,
    ) : app.mauri.cucuto.core.data.di.WallClock {
        override fun now(): Long = time
    }

    /** The app side of the clip mirror, as `MediaClipMirror` does it once SystemUI can read shared storage. */
    private class StoreMirror(
        private val store: ClipMirrorStore,
        private val chirp: File,
    ) : ClipMirror {
        override suspend fun clipUri(
            pack: InstalledPackEntity,
            characterId: String,
            file: String,
        ): String =
            store.mirrorClip(File(pack.path), pack.packId, pack.version, file)
                ?: ProviderClips.clipUri(pack, characterId, file)

        override suspend fun selfTestUri(): String? = store.mirror(chirp, ClipMirrorStore.SELF_TEST)

        override suspend fun retain(table: RoutingTable) = store.retain(ClipMirrorStore.fileUris(table))
    }

    companion object {
        const val TIMEOUT_MS = 20_000L
        val ZONE: ZoneId = ZoneId.of("Europe/Madrid")
        val DAY: LocalDate = LocalDate.of(2026, 10, 24)

        /** Barcelona: sunrise at 08:13, sunset at 18:57 local time on [DAY] (NOAA, as the app computes it). */
        val PLACE = Place(41.39, 2.17, "Barcelona")

        /** A phone with amplitude control and the click and tick primitives, without `VibrationEffect.Builder`. */
        val CAPABILITIES =
            HapticCapabilities(
                amplitudeControl = true,
                primitiveDurationsMs = mapOf(HapticPreset.CLICK to 12, HapticPreset.TICK to 5),
            )

        /** Epoch milliseconds of [hour]:[minute] on [DAY] in [ZONE]. */
        fun at(
            hour: Int,
            minute: Int,
            date: LocalDate = DAY,
        ): Long = ZonedDateTime.of(date.atTime(hour, minute), ZONE).toInstant().toEpochMilli()
    }
}

/**
 * LSPosed, as the app and the module see it: Remote Preferences (the app's writes reach the module's change
 * listener unless [deliverPreferenceChanges] is off, as after an app update, ADR 0024), the routing remote file, and
 * the nudge broadcast, which reaches the module as [onNudge] with the published generation.
 */
internal class FakeXposedService : RoutingSink {
    override val connection = MutableStateFlow<SinkConnection>(SinkConnection.Connected("LSPosed", 102))
    val preferences = RemotePreferences()
    val writes: MutableList<String> = CopyOnWriteArrayList()

    @Volatile var remoteFile: ByteArray? = null

    @Volatile var deliverPreferenceChanges = true

    @Volatile var onNudge: (generation: Long) -> Unit = {}

    override fun start() = Unit

    override suspend fun read(): String? = preferences.value

    override suspend fun write(
        encoded: String,
        generation: Long,
    ) {
        writes += encoded
        preferences.put(encoded, notify = deliverPreferenceChanges)
        remoteFile = RoutingFile.frame(encoded)
    }

    override fun nudge(generation: Long) = onNudge(generation)

    /** Remote Preferences as the module reads them: one string value, change listeners. */
    class RemotePreferences : SharedPreferences {
        private val listeners = CopyOnWriteArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()

        @Volatile var value: String? = null
            private set

        fun put(
            encoded: String,
            notify: Boolean,
        ) {
            value = encoded
            if (notify) listeners.forEach { it.onSharedPreferenceChanged(this, RoutingContract.KEY_TABLE) }
        }

        override fun getString(
            key: String?,
            defValue: String?,
        ): String? = if (key == RoutingContract.KEY_TABLE) value ?: defValue else defValue

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) {
            listeners += listener
        }

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener,
        ) {
            listeners -= listener
        }

        override fun getAll(): Map<String, *> = mapOf(RoutingContract.KEY_TABLE to value)

        override fun getStringSet(
            key: String?,
            defValues: Set<String>?,
        ): Set<String>? = defValues

        override fun getInt(
            key: String?,
            defValue: Int,
        ): Int = defValue

        override fun getLong(
            key: String?,
            defValue: Long,
        ): Long = defValue

        override fun getFloat(
            key: String?,
            defValue: Float,
        ): Float = defValue

        override fun getBoolean(
            key: String?,
            defValue: Boolean,
        ): Boolean = defValue

        override fun contains(key: String?): Boolean = key == RoutingContract.KEY_TABLE && value != null

        override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException("The module never writes")
    }
}
