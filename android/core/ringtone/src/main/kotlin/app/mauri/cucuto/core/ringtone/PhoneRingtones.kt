package app.mauri.cucuto.core.ringtone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import app.mauri.cucuto.core.data.db.ContactKeyKind
import app.mauri.cucuto.core.data.db.ContactRingtoneWriteEntity
import app.mauri.cucuto.core.data.db.PhoneRingtoneDao
import app.mauri.cucuto.core.data.db.PhoneRingtoneEntity
import app.mauri.cucuto.core.data.db.RingtoneMode
import app.mauri.cucuto.core.data.di.ApplicationScope
import app.mauri.cucuto.core.data.di.WallClock
import app.mauri.cucuto.core.data.repository.AssignmentRepository
import app.mauri.cucuto.core.data.repository.ContactRepository
import app.mauri.cucuto.core.data.repository.InstalledPackRepository
import app.mauri.cucuto.core.data.settings.DayNightSettings
import app.mauri.cucuto.core.data.settings.PhoneRingtoneSettings
import app.mauri.cucuto.core.ringtone.contacts.AndroidContact
import app.mauri.cucuto.core.ringtone.contacts.AndroidContacts
import app.mauri.cucuto.core.ringtone.render.RingFileRenderer
import app.mauri.cucuto.core.ringtone.restore.RelinkOutcome
import app.mauri.cucuto.core.ringtone.restore.RestoredRingtone
import app.mauri.cucuto.core.ringtone.restore.RingtoneLinks
import app.mauri.cucuto.core.ringtone.restore.RingtoneRelinker
import app.mauri.cucuto.core.ringtone.store.CharacterRingtones
import app.mauri.cucuto.core.ringtone.store.PhoneRingtoneStore
import app.mauri.cucuto.core.ringtone.store.RingtoneCatalog
import app.mauri.cucuto.core.ringtone.store.RingtoneExports
import app.mauri.cucuto.core.ringtone.sync.RingtoneProblem
import app.mauri.cucuto.core.ringtone.sync.RingtoneSync
import app.mauri.cucuto.core.ringtone.sync.SyncReason
import app.mauri.cucuto.core.ringtone.system.BoundaryScheduler
import app.mauri.cucuto.core.ringtone.system.RingtoneReceivers
import app.mauri.cucuto.core.ringtone.variants.RuleContext
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** The sync's live state, for the UI. */
data class PhoneRingtoneSyncState(
    val running: Boolean = false,
    val problems: Map<Long, RingtoneProblem> = emptyMap(),
)

/**
 * One contact's phone ringtone, as the contact detail screen shows it.
 *
 * @property ringtone The stored choice, or null when the contact has no phone ringtone.
 * @property character The character that rings ([ringtone]'s own, or the contact's voice), null when unknown.
 * @property write What Cucuto last wrote on the linked Android contact.
 */
data class ContactPhoneRingtone(
    val enabled: Boolean,
    val contactId: Long,
    val linkedLookupKey: String?,
    val linkedName: String?,
    val ringtone: PhoneRingtoneEntity?,
    val character: CharacterRingtones?,
    val write: ContactRingtoneWriteEntity?,
    val problem: RingtoneProblem?,
    val syncing: Boolean,
)

/**
 * Where restored phone ringtones stand on this phone (ADR 0057), for the restore summary.
 *
 * @property restored Phone ringtones the restore brought back (linked or not).
 * @property linked Those whose Android contact was found on this phone.
 * @property missing Names of the Cucuto contacts with no matching Android contact here (null: no name); the user links
 *   them from the chat.
 * @property waitingForPermission The contacts permission is missing: Android contacts are matched once it is granted.
 * @property enabled Whether phone ringtones are on (only then are contacts written).
 */
data class PhoneRingtoneRestore(
    val restored: Int,
    val linked: Int,
    val missing: List<String?>,
    val waitingForPermission: Boolean,
    val enabled: Boolean,
)

/**
 * Phone-call ringtones (#52, ADR 0039): the app-side entry point. Public APIs only, no hook: Telecom plays a contact's
 * `CUSTOM_RINGTONE` itself (M9 report §3), so this keeps the linked Android contacts' ringtones in line with what the
 * user chose, through [RingtoneSync].
 *
 * Off until the user turns it on and grants the contacts permission. When on, it syncs on every relevant database
 * change while the app runs, on app start (repairs missing ring files), after each call for "random per call" (a
 * manifest receiver, enabled only while such a contact exists), and at the next rule boundary (an inexact alarm).
 * Turning it off restores every changed contact and deletes the ring files.
 */
@Singleton
class PhoneRingtones
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settings: PhoneRingtoneSettings,
        private val dayNight: DayNightSettings,
        private val dao: PhoneRingtoneDao,
        private val contactRepository: ContactRepository,
        private val assignments: AssignmentRepository,
        private val installedPacks: InstalledPackRepository,
        private val androidContacts: AndroidContacts,
        private val catalog: RingtoneCatalog,
        private val store: PhoneRingtoneStore,
        exports: RingtoneExports,
        renderer: RingFileRenderer,
        private val scheduler: BoundaryScheduler,
        private val receivers: RingtoneReceivers,
        private val clock: WallClock,
        @param:ApplicationScope private val scope: CoroutineScope,
    ) {
        private val sync =
            RingtoneSync(
                store = store,
                contacts = androidContacts,
                exports = exports,
                catalog = catalog,
                renderer = renderer,
                workDir = File(context.cacheDir, "ring-render"),
                wallClock = clock::now,
                ruleContext = { RuleContext.at(LocalDateTime.now(), dayNight.place.first(), ZoneId.systemDefault()) },
            )
        private val links = ContactRingtoneLinks(contactRepository, androidContacts)
        private val relinker = RingtoneRelinker(androidContacts, links, store, clock::now)
        private val mutex = Mutex()
        private val restoreMutex = Mutex()
        private val started = AtomicBoolean(false)
        private val state = MutableStateFlow(PhoneRingtoneSyncState())

        val syncState: StateFlow<PhoneRingtoneSyncState> = state.asStateFlow()

        val enabled: Flow<Boolean> = settings.enabled

        /** Starts following database changes (app start). Cheap when phone ringtones are off: one setting read. */
        @OptIn(FlowPreview::class)
        fun start() {
            if (!started.compareAndSet(false, true)) return
            scope.launch {
                combine(
                    settings.enabled,
                    dao.observeSyncRows(),
                    installedPacks.observeAll(),
                    dayNight.place,
                ) { on, rows, packs, place -> listOf(on, rows, packs.map { it.packId to it.version }, place) }
                    .distinctUntilChanged()
                    .debounce(DEBOUNCE_MS)
                    .collect { values -> if (values[0] == true) syncNow(SyncReason.Changed) }
            }
            // A restore that waited for the contacts permission, granted since (Android's app settings, another
            // screen): match its Android contacts now.
            scope.launch {
                try {
                    if (androidContacts.canReadAndWrite() && pending().isNotEmpty()) processPendingRestore()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Pending ringtone restore failed", e)
                }
            }
        }

        fun canReadAndWriteContacts(): Boolean = androidContacts.canReadAndWrite()

        fun canReadPhoneState(): Boolean =
            context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

        /**
         * Turns phone ringtones on. Returns false (nothing changes) without the contacts permission. Phone ringtones
         * a restore brought back find their Android contacts first.
         */
        suspend fun enable(): Boolean = enableAndRestore() != null

        /**
         * [enable], returning where the restored phone ringtones that waited for the permission stand (nothing
         * restored when none waited). Null without the contacts permission.
         */
        suspend fun enableAndRestore(): PhoneRingtoneRestore? {
            if (!androidContacts.canReadAndWrite()) return null
            settings.setEnabled(true)
            return processPendingRestore()
        }

        /**
         * After a backup restore (ADR 0057): finds the Android contact of each restored phone ringtone on this phone
         * (phone numbers, then the lookup key, then the exact name), adopts the backup's undo record on the phone that
         * wrote it, and renders the ring files and writes the contacts when phone ringtones are on. Without the
         * contacts permission the list waits, and [enable] (or the next app start with the permission) finishes it.
         */
        suspend fun afterRestore(items: List<RestoredRingtone>): PhoneRingtoneRestore {
            if (items.isEmpty()) return processPendingRestore()
            restoreMutex.withLock {
                val merged = (pending().filter { old -> items.none { it.contactId == old.contactId } } + items)
                settings.setPendingRestore(RestoredRingtone.encode(merged))
            }
            return processPendingRestore()
        }

        /** Matches the pending restored ringtones' Android contacts, if the permission allows it. Never throws. */
        suspend fun processPendingRestore(): PhoneRingtoneRestore {
            val on = settings.isEnabled()
            val result =
                restoreMutex.withLock {
                    val items = pending()
                    if (items.isEmpty()) return@withLock PhoneRingtoneRestore(0, 0, emptyList(), false, on)
                    if (!androidContacts.canReadAndWrite()) {
                        return@withLock PhoneRingtoneRestore(items.size, 0, emptyList(), true, on)
                    }
                    val results =
                        withContext(Dispatchers.IO) {
                            items.map { item ->
                                try {
                                    item to relinker.relink(item).outcome
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Log.w(TAG, "Relinking a restored ringtone failed", e)
                                    item to RelinkOutcome.MISSING
                                }
                            }
                        }
                    settings.setPendingRestore(null)
                    PhoneRingtoneRestore(
                        restored = items.size,
                        linked = results.count { it.second != RelinkOutcome.MISSING },
                        missing = results.filter { it.second == RelinkOutcome.MISSING }.map { it.first.displayName },
                        waitingForPermission = false,
                        enabled = on,
                    )
                }
            if (on && result.restored > 0 && !result.waitingForPermission) syncNow(SyncReason.Changed)
            return result
        }

        private suspend fun pending(): List<RestoredRingtone> = RestoredRingtone.decode(settings.pendingRestore())

        /** Turns phone ringtones off: every changed contact gets its own ringtone back and the ring files go. */
        suspend fun disable() {
            mutex.withLock {
                settings.setEnabled(false)
                withContext(Dispatchers.IO) { runCatching { sync.restoreAll() } }
                scheduler.schedule(null)
                receivers.apply(sync = false, calls = false)
                state.value = PhoneRingtoneSyncState()
            }
        }

        /** Runs one sync now (serialized). Never throws. */
        suspend fun syncNow(reason: SyncReason) {
            mutex.withLock {
                if (!settings.isEnabled()) return
                state.update { it.copy(running = true) }
                try {
                    val result = withContext(Dispatchers.IO) { sync.sync(reason) }
                    state.value = PhoneRingtoneSyncState(running = false, problems = result.problems)
                    scheduler.schedule(
                        result.nextBoundary
                            ?.atZone(ZoneId.systemDefault())
                            ?.toInstant()
                            ?.toEpochMilli(),
                    )
                    val rows = dao.syncRows()
                    receivers.apply(
                        sync = true,
                        calls =
                            canReadPhoneState() && rows.any { it.mode == RingtoneMode.RANDOM && it.lookupKey != null },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Phone ringtone sync failed", e)
                    state.update { it.copy(running = false) }
                }
            }
        }

        /** [contactId]'s phone ringtone for the contact detail screen. */
        fun observeContact(contactId: Long): Flow<ContactPhoneRingtone> =
            combine(
                settings.enabled,
                contactRepository.observeContact(contactId),
                dao.observeAll().map { all -> all.firstOrNull { it.contactId == contactId } },
                assignments.observe(contactId),
                combine(dao.observeWrites(), state, ::Pair),
            ) { on, contact, ringtone, assignment, (writes, sync) ->
                val lookupKey = contact?.contact?.androidLookupKey
                val packId = ringtone?.packId ?: assignment?.packId
                val characterId = ringtone?.characterId ?: assignment?.characterId
                ContactPhoneRingtone(
                    enabled = on,
                    contactId = contactId,
                    linkedLookupKey = lookupKey,
                    linkedName = contact?.contact?.androidDisplayName,
                    ringtone = ringtone,
                    character =
                        if (packId != null &&
                            characterId != null
                        ) {
                            catalog.character(packId, characterId)
                        } else {
                            null
                        },
                    write = writes.firstOrNull { it.lookupKey == lookupKey },
                    problem = sync.problems[contactId],
                    syncing = sync.running,
                )
            }.distinctUntilChanged()

        /** Android contacts that look like [contactId]: same phone number (WhatsApp phone JIDs), else same name. */
        suspend fun suggestions(contactId: Long): List<AndroidContact> {
            val contact = contactRepository.get(contactId) ?: return emptyList()
            val numbers = links.phoneNumbers(contactId)
            val byNumber = numbers.flatMap { androidContacts.byPhoneNumber(it) }.distinctBy { it.lookupKey }
            if (byNumber.isNotEmpty()) return byNumber
            val name = contact.contact.displayName ?: contact.keys.firstNotNullOfOrNull { it.displayName }
            return name?.let { androidContacts.byName(it) }.orEmpty()
        }

        /** The Android contact behind a contact picker result. */
        suspend fun resolvePicked(contactUri: String): AndroidContact? = androidContacts.resolve(contactUri)

        /**
         * Links [contactId] to [android] and gives it a phone ringtone that follows its voice and the pack's rules,
         * unless it already has one. Its phone numbers are kept so a restore can re-link it (brief, Backup).
         */
        suspend fun link(
            contactId: Long,
            android: AndroidContact,
        ) {
            contactRepository.linkAndroidContact(contactId, android.lookupKey, android.displayName)
            for (number in androidContacts.phoneNumbers(android.lookupKey)) {
                runCatching { contactRepository.addPhoneNumber(contactId, number) }
            }
            if (dao.get(contactId) ==
                null
            ) {
                dao.upsert(PhoneRingtoneEntity(contactId = contactId, updatedAt = clock.now()))
            }
        }

        /** Unlinks [contactId]; the sync gives the Android contact its own ringtone back. */
        suspend fun unlink(contactId: Long) {
            contactRepository.linkAndroidContact(contactId, null, null)
        }

        /** Sets how [contactId]'s variant is chosen ([variant] is the file for [RingtoneMode.FIXED]). */
        suspend fun setRule(
            contactId: Long,
            mode: RingtoneMode,
            variant: String? = null,
        ) {
            val current = dao.get(contactId) ?: PhoneRingtoneEntity(contactId = contactId, updatedAt = clock.now())
            dao.upsert(
                current.copy(
                    mode = mode,
                    variant =
                        if (mode ==
                            RingtoneMode.FIXED
                        ) {
                            variant
                        } else {
                            null
                        },
                    updatedAt = clock.now(),
                ),
            )
        }

        /**
         * The ringtone picker's choice (#53, ADR 0063): the character that rings for [contactId] ([packId] and
         * [characterId] null for its voice's), whether it keeps [followsVoice] (the user picked the voice's own
         * character, or left the kept one as it was) or is the user's own pick, how its variant is picked, and the
         * variant for [RingtoneMode.FIXED].
         */
        suspend fun choose(
            contactId: Long,
            packId: String?,
            characterId: String?,
            mode: RingtoneMode,
            variant: String?,
            followsVoice: Boolean = packId == null || characterId == null,
        ) {
            val own = packId != null && characterId != null
            dao.upsert(
                PhoneRingtoneEntity(
                    contactId = contactId,
                    packId = if (own) packId else null,
                    characterId = if (own) characterId else null,
                    mode = mode,
                    variant = if (mode == RingtoneMode.FIXED) variant else null,
                    updatedAt = clock.now(),
                    followsVoice = followsVoice || !own,
                ),
            )
        }

        /** Removes [contactId]'s phone ringtone; the sync restores the Android contact's own one. */
        suspend fun remove(contactId: Long) = dao.delete(contactId)

        /** Manages [contactId]'s Android contact again after its ringtone was changed in Contacts. */
        suspend fun takeOver(contactId: Long) {
            val key = contactRepository.get(contactId)?.contact?.androidLookupKey ?: return
            mutex.withLock { sync.takeOver(key) }
            syncNow(SyncReason.Changed)
        }

        private companion object {
            const val TAG = "cucuto"
            const val DEBOUNCE_MS = 400L
        }
    }

/** [RingtoneLinks] over the contacts table: the Android link and the phone keys it stores (ADR 0039). */
internal class ContactRingtoneLinks(
    private val contacts: ContactRepository,
    private val androidContacts: AndroidContacts,
) : RingtoneLinks {
    override suspend fun linkedKey(contactId: Long): String? = contacts.get(contactId)?.contact?.androidLookupKey

    override suspend fun phoneNumbers(contactId: Long): List<String> =
        contacts
            .get(contactId)
            ?.keys
            ?.mapNotNull { key ->
                when (key.kind) {
                    ContactKeyKind.PHONE_NUMBER -> key.value
                    ContactKeyKind.PHONE_JID -> "+" + key.value.substringBefore('@')
                    else -> null
                }
            }?.distinct()
            .orEmpty()

    override suspend fun link(
        contactId: Long,
        contact: AndroidContact?,
    ) {
        contacts.linkAndroidContact(contactId, contact?.lookupKey, contact?.displayName)
        if (contact == null) return
        for (number in androidContacts.phoneNumbers(contact.lookupKey)) {
            runCatching { contacts.addPhoneNumber(contactId, number) }
        }
    }
}
