package app.mauri.cucuto.ui.settings.restore

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mauri.cucuto.backup.AutoBackupControl
import app.mauri.cucuto.backup.BackupGateway
import app.mauri.cucuto.backup.OpenOutcome
import app.mauri.cucuto.backup.PackFailure
import app.mauri.cucuto.backup.PackProgress
import app.mauri.cucuto.backup.RestoreSession
import app.mauri.cucuto.core.backup.AssignmentConflict
import app.mauri.cucuto.core.backup.PackRestoreAction
import app.mauri.cucuto.core.backup.PackRestoreItem
import app.mauri.cucuto.core.backup.PackRestorePlanner
import app.mauri.cucuto.core.backup.RestoreReport
import app.mauri.cucuto.core.backup.VoiceSnapshot
import app.mauri.cucuto.core.backup.ZipContentType
import app.mauri.cucuto.core.data.db.GroupPolicy
import app.mauri.cucuto.core.data.voices.CharacterDirectory
import app.mauri.cucuto.core.ringtone.PhoneRingtoneRestore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/**
 * The restore flow (docs/project-brief.md, "Restore on a new phone"; ADR 0028): pick the file, type the passphrase if
 * it is encrypted, bring back the packs, restore contacts and voices, answer the conflicts, then the LSPosed checklist.
 */
sealed interface RestoreStep {
    /** Nothing opened yet (the screen opens its file right away), or the flow was closed. */
    data object Closed : RestoreStep

    /** Reading, decrypting or restoring: a progress indicator. */
    data class Working(
        val phase: Phase,
    ) : RestoreStep

    enum class Phase { OPENING, RESTORING }

    data class Passphrase(
        /** The last passphrase did not open the backup. */
        val wrong: Boolean = false,
    ) : RestoreStep

    data class Problem(
        val kind: ProblemKind,
        val details: List<String> = emptyList(),
    ) : RestoreStep

    /** The packs the backup needs, with what is being done for each. */
    data class Packs(
        val rows: List<PackRow>,
        val createdAt: String,
        val contacts: Int,
    ) : RestoreStep {
        val busy: Boolean get() = rows.any { it.status is PackStatus.Working }
        val missing: Int get() = rows.count { it.status !is PackStatus.Done }
    }

    /** Contacts whose voice on this phone differs from the backup's: keep this phone's, or take the backup's. */
    data class Conflicts(
        val rows: List<ConflictRow>,
        /** Backup contact IDs that will get the backup's voice. Empty by default: nothing is overwritten unasked. */
        val selected: Set<String> = emptySet(),
    ) : RestoreStep {
        val allSelected: Boolean get() = rows.isNotEmpty() && selected.size == rows.size
    }

    /** Restored: what happened, and what a backup can't do (the LSPosed checklist). */
    data class Done(
        val summary: RestoreSummary,
        /**
         * The restored document, offered as the automatic backup file (a new phone carries on where the old one
         * left off, ADR 0040). Null for encrypted backups: an automatic backup is plain and must not replace them.
         */
        val reusableSource: String? = null,
        /** The user chose to keep automatic backups in [reusableSource]'s file. */
        val keptForBackups: Boolean = false,
    ) : RestoreStep
}

enum class ProblemKind {
    /** A sound pack, not a backup: import it from Packs. */
    PACK_NOT_BACKUP,

    /** The file breaks the backup format. */
    INVALID,

    /** Reading the file or restoring failed. */
    FAILED,
}

data class PackRow(
    val item: PackRestoreItem,
    val status: PackStatus,
) {
    val id: String get() = item.reference.id
    val name: String get() = item.reference.name ?: item.reference.id
}

sealed interface PackStatus {
    /** Waiting for its turn, or for the user to pick its file ([PackRestoreAction.PICK_FILE]). */
    data object Pending : PackStatus

    data class Working(
        val fraction: Float? = null,
    ) : PackStatus

    data object Done : PackStatus

    data class Failed(
        val reason: PackFailure,
    ) : PackStatus
}

/** One conflict as the screen shows it, with character and pack names where they are installed. */
data class ConflictRow(
    val backupId: String,
    val name: String?,
    val current: VoiceLabel,
    val backup: VoiceLabel,
)

/** A voice for display: [character] and [pack] are names when installed, IDs otherwise; both null for no voice. */
data class VoiceLabel(
    val character: String?,
    val pack: String?,
    /** Shown only when the two sides differ in it (groups). */
    val groupPolicy: GroupPolicy?,
)

data class RestoreSummary(
    val contacts: Int,
    val created: Int,
    val voicesApplied: Int,
    /** Conflicts where this phone's voice was kept. */
    val conflictsKept: Int,
    /** Keys that already belong to another contact here and were left alone. */
    val keysLeftAlone: Int,
    val repositoriesAdded: Int,
    val credentialsRestored: Int,
    /** Packs still missing when the user went on: their contacts hear WhatsApp's tone until imported. */
    val packsMissing: Int,
    /** Phone ringtones set differently on this phone, kept. */
    val ringtonesKept: Int = 0,
    /** Where the restored phone ringtones stand on this phone (ADR 0057); null when the backup has none. */
    val phoneRingtones: PhoneRingtoneRestore? = null,
) {
    /** Phone ringtones are back but nothing is written to Android contacts until the user turns them on. */
    val phoneRingtonesWaiting: Boolean
        get() = phoneRingtones != null && (phoneRingtones.waitingForPermission || !phoneRingtones.enabled)
}

@HiltViewModel
class RestoreViewModel
    @Inject
    constructor(
        private val gateway: BackupGateway,
        private val characters: CharacterDirectory,
        private val autoBackup: AutoBackupControl,
    ) : ViewModel() {
        private val _step = MutableStateFlow<RestoreStep>(RestoreStep.Closed)
        val step: StateFlow<RestoreStep> = _step.asStateFlow()

        private var source: String? = null
        private var session: RestoreSession? = null
        private var firstReport: RestoreReport? = null
        private var packsMissing = 0
        private val packMutex = Mutex()
        private var job: Job? = null
        private val packJobs = mutableListOf<Job>()

        /**
         * Opens [uri] unless this restore already started: the Restore screen calls it each time it appears, and a
         * screen restored after process death (a new view model) opens the file again.
         */
        fun start(uri: String) {
            if (source == null && _step.value == RestoreStep.Closed) open(uri)
        }

        /** Starts a restore from the document the user picked. */
        fun open(uri: String) {
            reset()
            source = uri
            load(passphrase = null)
        }

        /** Tries the passphrase; the array is wiped once used. */
        fun submitPassphrase(passphrase: CharArray) {
            if (_step.value !is RestoreStep.Passphrase || passphrase.isEmpty()) return
            load(passphrase)
        }

        private fun load(passphrase: CharArray?) {
            val uri = source ?: return
            job =
                viewModelScope.launch {
                    _step.value = RestoreStep.Working(RestoreStep.Phase.OPENING)
                    val outcome =
                        try {
                            gateway.open(uri, passphrase)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            OpenOutcome.Failed(e.message ?: e.javaClass.simpleName)
                        } finally {
                            passphrase?.fill('\u0000')
                        }
                    if (outcome is OpenOutcome.Opened) {
                        session = outcome.session
                        if (!showPacks(outcome.session)) restoreContacts()
                        return@launch
                    }
                    _step.value =
                        when (outcome) {
                            is OpenOutcome.Opened -> {
                                return@launch
                            }

                            OpenOutcome.PassphraseRequired -> {
                                RestoreStep.Passphrase()
                            }

                            OpenOutcome.WrongPassphrase -> {
                                RestoreStep.Passphrase(wrong = true)
                            }

                            is OpenOutcome.NotABackup -> {
                                val pack = outcome.type == ZipContentType.PACK || outcome.type == ZipContentType.BUNDLE
                                RestoreStep.Problem(if (pack) ProblemKind.PACK_NOT_BACKUP else ProblemKind.INVALID)
                            }

                            is OpenOutcome.Invalid -> {
                                RestoreStep.Problem(ProblemKind.INVALID, outcome.details)
                            }

                            is OpenOutcome.Failed -> {
                                RestoreStep.Problem(ProblemKind.FAILED, listOf(outcome.message))
                            }
                        }
                }
        }

        /** Shows the pack checklist and starts the packs that need no file. False when the backup has no packs. */
        private suspend fun showPacks(session: RestoreSession): Boolean {
            val document = session.document
            if (document.packs.isEmpty()) return false
            val plan = PackRestorePlanner.plan(document.packs, gateway.installedPacks(), session.includedPacks)
            val rows =
                plan.map { item ->
                    PackRow(
                        item,
                        if (item.action ==
                            PackRestoreAction.INSTALLED
                        ) {
                            PackStatus.Done
                        } else {
                            PackStatus.Pending
                        },
                    )
                }
            _step.value = RestoreStep.Packs(rows, document.createdAt, document.contacts.size)
            rows.filter { it.item.action in AUTOMATIC }.forEach { restorePack(it.id) }
            return true
        }

        /** Restores (or retries) one pack from the backup, its repository or its link. */
        fun restorePack(id: String) {
            val current = session ?: return
            val row = packRow(id)?.takeIf { it.status !is PackStatus.Working } ?: return
            if (row.item.action !in AUTOMATIC) return
            runPack(id) { gateway.restorePack(current, row.item) }
        }

        /** Imports the file the user picked for a local-only pack. */
        fun importPackFile(
            id: String,
            uri: String,
        ) {
            val row = packRow(id)?.takeIf { it.status !is PackStatus.Working } ?: return
            runPack(id) { gateway.importPackFile(uri, row.item) }
        }

        private fun runPack(
            id: String,
            work: () -> Flow<PackProgress>,
        ) {
            setPackStatus(id, PackStatus.Working())
            packJobs +=
                viewModelScope.launch {
                    packMutex.withLock {
                        try {
                            work().collect { progress ->
                                setPackStatus(
                                    id,
                                    when (progress) {
                                        is PackProgress.Working -> PackStatus.Working(progress.fraction)
                                        PackProgress.Done -> PackStatus.Done
                                        is PackProgress.Failed -> PackStatus.Failed(progress.reason)
                                    },
                                )
                            }
                            if (packRow(
                                    id,
                                )?.status is PackStatus.Working
                            ) {
                                setPackStatus(id, PackStatus.Failed(PackFailure.OTHER))
                            }
                            replan()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            setPackStatus(id, PackStatus.Failed(PackFailure.OTHER))
                        }
                    }
                }
        }

        /** A bundle may bring packs other rows were waiting for: mark every row the planner now sees as installed. */
        private suspend fun replan() {
            val current = session ?: return
            val installed = gateway.installedPacks()
            val plan = PackRestorePlanner.plan(current.document.packs, installed, current.includedPacks)
            val done = plan.filter { it.action == PackRestoreAction.INSTALLED }.map { it.reference.id }.toSet()
            _step.update { step ->
                if (step !is RestoreStep.Packs) return@update step
                step.copy(
                    rows =
                        step.rows.map { row ->
                            if (row.id in done &&
                                row.status !is PackStatus.Working
                            ) {
                                row.copy(status = PackStatus.Done)
                            } else {
                                row
                            }
                        },
                )
            }
        }

        private fun packRow(id: String): PackRow? =
            (_step.value as? RestoreStep.Packs)?.rows?.firstOrNull { it.id == id }

        private fun setPackStatus(
            id: String,
            status: PackStatus,
        ) {
            _step.update { step ->
                if (step !is RestoreStep.Packs) return@update step
                step.copy(rows = step.rows.map { if (it.id == id) it.copy(status = status) else it })
            }
        }

        /** Goes on to contacts and voices; packs still missing can be imported later from Packs. */
        fun continueToContacts() {
            val step = _step.value as? RestoreStep.Packs ?: return
            if (step.busy) return
            packsMissing = step.missing
            job = viewModelScope.launch { restoreContacts() }
        }

        private suspend fun restoreContacts() {
            val current = session ?: return
            _step.value = RestoreStep.Working(RestoreStep.Phase.RESTORING)
            val report = runRestore(current, emptySet()) ?: return
            firstReport = report
            _step.value =
                if (report.conflicts.isEmpty()) {
                    done(summary(report, second = null, phoneRingtones(listOf(report))))
                } else {
                    RestoreStep.Conflicts(conflictRows(report.conflicts))
                }
        }

        fun toggleConflict(backupId: String) {
            _step.update { step ->
                if (step !is RestoreStep.Conflicts || step.rows.none { it.backupId == backupId }) return@update step
                step.copy(
                    selected =
                        if (backupId in
                            step.selected
                        ) {
                            step.selected - backupId
                        } else {
                            step.selected + backupId
                        },
                )
            }
        }

        fun selectAllConflicts(selected: Boolean) {
            _step.update { step ->
                if (step !is RestoreStep.Conflicts) return@update step
                step.copy(selected = if (selected) step.rows.map { it.backupId }.toSet() else emptySet())
            }
        }

        /** Gives the selected contacts the backup's voice and keeps this phone's voice for the rest. */
        fun applyConflicts() {
            val step = _step.value as? RestoreStep.Conflicts ?: return
            val current = session ?: return
            val first = firstReport ?: return
            job =
                viewModelScope.launch {
                    _step.value = RestoreStep.Working(RestoreStep.Phase.RESTORING)
                    if (step.selected.isEmpty()) {
                        _step.value = done(summary(first, second = null, phoneRingtones(listOf(first))))
                        return@launch
                    }
                    val second = runRestore(current, step.selected) ?: return@launch
                    _step.value = done(summary(first, second, phoneRingtones(listOf(first, second))))
                }
        }

        private suspend fun runRestore(
            current: RestoreSession,
            overwrite: Set<String>,
        ): RestoreReport? =
            try {
                gateway.restore(current, overwrite)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _step.value = RestoreStep.Problem(ProblemKind.FAILED, listOf(e.message ?: e.javaClass.simpleName))
                null
            }

        private suspend fun conflictRows(conflicts: List<AssignmentConflict>): List<ConflictRow> {
            val packs =
                runCatching { characters.observePacks().first() }
                    .getOrDefault(
                        emptyList(),
                    ).associateBy { it.packId }

            fun label(
                voice: VoiceSnapshot,
                showPolicy: Boolean,
            ): VoiceLabel {
                val pack = voice.packId?.let(packs::get)
                val character = pack?.characters?.firstOrNull { it.id == voice.characterId }
                return VoiceLabel(
                    character = character?.name ?: voice.characterId,
                    pack = pack?.name ?: voice.packId,
                    groupPolicy = voice.groupPolicy.takeIf { showPolicy },
                )
            }
            return conflicts.map { conflict ->
                val showPolicy = conflict.current.groupPolicy != conflict.backup.groupPolicy
                ConflictRow(
                    backupId = conflict.backupId,
                    name = conflict.displayName,
                    current = label(conflict.current, showPolicy),
                    backup = label(conflict.backup, showPolicy),
                )
            }
        }

        /**
         * Finds the restored phone ringtones' Android contacts and writes them (ADR 0057). A failure here never fails
         * the restore: the choices are in the database, and contact detail links them by hand.
         */
        private suspend fun phoneRingtones(reports: List<RestoreReport>): PhoneRingtoneRestore? {
            if (reports.all { it.ringtones.isEmpty() }) return null
            return try {
                gateway.restorePhoneRingtones(reports)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Restoring phone ringtones failed", e)
                null
            }
        }

        /**
         * The summary's "Turn on phone ringtones", once the contacts permission is granted: turns them on and finishes
         * the restored ones (contacts found, ring files rendered, contacts written).
         */
        fun enablePhoneRingtones() {
            if ((_step.value as? RestoreStep.Done)?.summary?.phoneRingtones == null) return
            viewModelScope.launch {
                val result =
                    try {
                        gateway.enablePhoneRingtones()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Turning on phone ringtones failed", e)
                        null
                    } ?: return@launch
                _step.update { step ->
                    val done = step as? RestoreStep.Done ?: return@update step
                    val before = done.summary.phoneRingtones ?: return@update step
                    // Nothing waited for the permission: the restore already matched the contacts, now they ring.
                    val now =
                        if (result.restored > 0) result else before.copy(waitingForPermission = false, enabled = true)
                    done.copy(summary = done.summary.copy(phoneRingtones = now))
                }
            }
        }

        private fun summary(
            first: RestoreReport,
            second: RestoreReport?,
            phoneRingtones: PhoneRingtoneRestore?,
        ): RestoreSummary =
            RestoreSummary(
                contacts = first.contacts.size,
                created = first.created,
                voicesApplied = first.applied + (second?.applied ?: 0),
                conflictsKept = (second ?: first).conflicts.size,
                keysLeftAlone = first.contacts.sumOf { it.keysOwnedElsewhere.size },
                repositoriesAdded = first.repositoriesAdded.size,
                credentialsRestored = first.credentialsRestored.size,
                packsMissing = packsMissing,
                ringtonesKept = (second ?: first).ringtonesKept,
                phoneRingtones = phoneRingtones,
            )

        private fun done(summary: RestoreSummary): RestoreStep.Done =
            RestoreStep.Done(summary, reusableSource = source.takeIf { session?.encrypted == false })

        /**
         * The user keeps automatic backups in the restored file (ADR 0040), from Settings or Home alike. A failure
         * leaves automatic backups off; Settings → Backup and restore shows their state.
         */
        fun keepForBackups() {
            val done = _step.value as? RestoreStep.Done ?: return
            val document = done.reusableSource ?: return
            viewModelScope.launch {
                try {
                    autoBackup.chooseFile(document)
                    _step.update { step -> if (step is RestoreStep.Done) step.copy(keptForBackups = true) else step }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Could not keep automatic backups in the restored file", e)
                }
            }
        }

        /** Leaves the flow (back, close, or after the checklist) and deletes the scratch copies. */
        fun close() {
            reset()
            _step.value = RestoreStep.Closed
        }

        private fun reset() {
            job?.cancel()
            job = null
            packJobs.forEach { it.cancel() }
            packJobs.clear()
            runCatching { session?.close() }
            session = null
            source = null
            firstReport = null
            packsMissing = 0
        }

        override fun onCleared() {
            runCatching { session?.close() }
        }

        private companion object {
            const val TAG = "Restore"

            /** Actions that run without asking: nothing to pick. */
            val AUTOMATIC =
                setOf(
                    PackRestoreAction.FROM_BACKUP,
                    PackRestoreAction.DOWNLOAD_FROM_REPOSITORY,
                    PackRestoreAction.DOWNLOAD_FROM_URL,
                )
        }
    }
