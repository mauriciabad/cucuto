package app.mauri.cucuto.backup

import app.mauri.cucuto.core.backup.AssignmentConflict
import app.mauri.cucuto.core.backup.BackupAndroidContact
import app.mauri.cucuto.core.backup.BackupApp
import app.mauri.cucuto.core.backup.BackupContact
import app.mauri.cucuto.core.backup.BackupDocument
import app.mauri.cucuto.core.backup.BackupPackReference
import app.mauri.cucuto.core.backup.BackupPackSource
import app.mauri.cucuto.core.backup.BackupPackSourceType
import app.mauri.cucuto.core.backup.BackupPackType
import app.mauri.cucuto.core.backup.ContactMatch
import app.mauri.cucuto.core.backup.ContactRestoreOutcome
import app.mauri.cucuto.core.backup.PackRestoreItem
import app.mauri.cucuto.core.backup.RestoreReport
import app.mauri.cucuto.core.backup.RingtoneOutcome
import app.mauri.cucuto.core.backup.RingtoneRestoreOutcome
import app.mauri.cucuto.core.backup.SettingsRestoreResult
import app.mauri.cucuto.core.backup.VoiceOutcome
import app.mauri.cucuto.core.backup.VoiceSnapshot
import app.mauri.cucuto.core.data.db.GroupPolicy
import app.mauri.cucuto.core.ringtone.ContactLinkRestore
import app.mauri.cucuto.core.ringtone.UnmatchedContact
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.transformWhile

/** Made-up backups: no real names, keys or packs. */
object BackupTestData {
    const val CRITTERS = "app.mauri.demo.critters"
    const val KITCHEN = "app.mauri.demo.kitchen"
    const val PRIVATE = "app.mauri.demo.private"
    private val HASH = "a".repeat(64)

    fun pack(
        id: String,
        source: BackupPackSourceType,
        fileName: String? = null,
        included: Boolean = false,
    ) = BackupPackReference(
        id = id,
        type = BackupPackType.PACK,
        version = "1.0.0",
        name = id.substringAfterLast('.'),
        sha256 = HASH,
        source =
            BackupPackSource(
                type = source,
                repositoryUrl =
                    if (source ==
                        BackupPackSourceType.REPOSITORY
                    ) {
                        "https://packs.example/index.json"
                    } else {
                        null
                    },
                downloadUrl =
                    if (source ==
                        BackupPackSourceType.URL
                    ) {
                        "https://packs.example/$id.soundpack.zip"
                    } else {
                        null
                    },
                fileName = fileName,
            ),
        included = if (included) "packs/$id-1.0.0.soundpack.zip" else null,
    )

    fun document(
        packs: List<BackupPackReference> = emptyList(),
        contacts: Int = 2,
    ) = BackupDocument(
        createdAt = "2026-10-24T09:30:00Z",
        app = BackupApp(versionName = "0.1.0", versionCode = 1),
        packs = packs,
        contacts = (1..contacts).map { BackupContact(id = "$it", displayName = "Contact $it") },
    )

    fun voice(
        pack: String?,
        character: String?,
        policy: GroupPolicy = GroupPolicy.GROUP,
    ) = VoiceSnapshot(pack, character, emptyList(), null, policy)

    fun conflict(
        id: String,
        name: String?,
        current: VoiceSnapshot = voice(CRITTERS, "frog"),
        backup: VoiceSnapshot = voice(CRITTERS, "owl"),
    ) = AssignmentConflict(id, contactId = id.toLong(), displayName = name, current = current, backup = backup)

    fun report(
        applied: List<String> = emptyList(),
        conflicts: List<AssignmentConflict> = emptyList(),
        created: Int = 0,
        keysElsewhere: Int = 0,
        ringtones: List<RingtoneRestoreOutcome> = emptyList(),
        /** Backup contact IDs (among [applied]) whose Android link came from the backup. */
        linked: Set<String> = emptySet(),
    ): RestoreReport {
        val outcomes =
            applied.mapIndexed { i, id ->
                ContactRestoreOutcome(
                    backupId = id,
                    contactId = id.toLong(),
                    match = if (i < created) ContactMatch.CREATED else ContactMatch.MESSENGER_KEY,
                    voice = VoiceOutcome.APPLIED,
                    keysOwnedElsewhere = if (i < keysElsewhere) listOf("com.whatsapp:1@lid") else emptyList(),
                    displayName = "Contact $id",
                    androidContact = if (id in linked) BackupAndroidContact("0r$id-OLD", "Contact $id") else null,
                )
            } +
                conflicts.map {
                    ContactRestoreOutcome(
                        it.backupId,
                        it.contactId,
                        ContactMatch.MESSENGER_KEY,
                        VoiceOutcome.CONFLICT,
                        emptyList(),
                    )
                }
        return RestoreReport(
            contacts = outcomes,
            conflicts = conflicts,
            repositoriesAdded = listOf("https://packs.example/index.json"),
            credentialsRestored = emptyList(),
            credentialsSkipped = emptyList(),
            settings = SettingsRestoreResult(listOf("enabled"), emptyList()),
            ringtones = ringtones,
        )
    }

    fun ringtone(
        id: String,
        outcome: RingtoneOutcome = RingtoneOutcome.APPLIED,
    ) = RingtoneRestoreOutcome(id, id.toLong(), outcome, "Contact $id", androidContact = null, undo = null)

    /** Where restored contacts stand, as the gateway reports it. */
    fun links(
        restored: Int = 0,
        linked: Int = 0,
        unmatched: List<UnmatchedContact> = emptyList(),
        ringtones: Int = 0,
        ringtonesLinked: Int = 0,
        waitingForPermission: Boolean = false,
        ringtonesEnabled: Boolean = true,
    ) = ContactLinkRestore(
        restored,
        linked,
        unmatched,
        ringtones,
        ringtonesLinked,
        waitingForPermission,
        ringtonesEnabled,
    )
}

class FakeSession(
    override val document: BackupDocument,
    override val encrypted: Boolean = false,
    override val includedPacks: List<BackupPackReference> = document.packs.filter { it.included != null },
) : RestoreSession {
    var closed = false

    override fun close() {
        closed = true
    }
}

/**
 * A scripted [BackupGateway]. Pack work is driven by the test through [packProgress] (one shared flow, completed per
 * pack by a [PackProgress.Done] or [PackProgress.Failed]), or answered at once with [instantPacks].
 */
class FakeBackupGateway : BackupGateway {
    val exports = mutableListOf<Pair<String, ExportRequest>>()

    /** Copies of the passphrases as they arrived (the caller wipes its arrays afterwards). */
    val exportedPassphrases = mutableListOf<String?>()
    var exportOutcome: ExportOutcome =
        ExportOutcome.Written(
            encrypted = false,
            contacts = 2,
            includedPacks = 0,
            skippedPacks = emptyList(),
        )

    /** Answers to [open], keyed by the passphrase (null: none given). */
    val openOutcomes = mutableMapOf<String?, OpenOutcome>()
    val openedWith = mutableListOf<String?>()

    var installed: MutableMap<String, String?> = mutableMapOf()

    /** Reports returned by [restore], in order. */
    val reports = ArrayDeque<RestoreReport>()
    val restoreCalls = mutableListOf<Set<String>>()

    var instantPacks: PackProgress? = PackProgress.Done
    val packProgress = MutableSharedFlow<Pair<String, PackProgress>>(extraBufferCapacity = 16)
    val restoredPacks = mutableListOf<String>()
    val importedFiles = mutableListOf<Pair<String, String>>()

    override fun suggestedFileName() = "cucuto-backup-2026-10-24.zip"

    override suspend fun export(
        destination: String,
        request: ExportRequest,
    ): ExportOutcome {
        exports += destination to request
        exportedPassphrases += request.passphrase?.concatToString()
        return exportOutcome
    }

    override suspend fun open(
        source: String,
        passphrase: CharArray?,
    ): OpenOutcome {
        val key = passphrase?.concatToString()
        openedWith += key
        return openOutcomes[key] ?: OpenOutcome.WrongPassphrase
    }

    override suspend fun installedPacks(): Map<String, String?> = installed.toMap()

    override suspend fun restore(
        session: RestoreSession,
        overwrite: Set<String>,
    ): RestoreReport {
        restoreCalls += overwrite
        return reports.removeFirst()
    }

    /** What [restoreContactLinks] answers, and the reports it was given. */
    var contactLinks = BackupTestData.links()
    val contactLinkReports = mutableListOf<RestoreReport>()

    /** What [enablePhoneRingtones] answers (null: the permission is still missing). */
    var enableResult: ContactLinkRestore? = null
    var enableCalls = 0

    /** What [findRestoredContacts] answers (null: the permission is still missing). */
    var findResult: ContactLinkRestore? = null
    var findCalls = 0

    /** Contact picker results that link ([linkPickedContact] answers false for any other URI). */
    val pickable = mutableSetOf<String>()
    val picked = mutableListOf<Pair<Long, String>>()

    override suspend fun restoreContactLinks(reports: List<RestoreReport>): ContactLinkRestore {
        contactLinkReports.addAll(reports)
        return contactLinks
    }

    override suspend fun findRestoredContacts(): ContactLinkRestore? {
        findCalls++
        return findResult
    }

    override suspend fun enablePhoneRingtones(): ContactLinkRestore? {
        enableCalls++
        return enableResult
    }

    override suspend fun linkPickedContact(
        contactId: Long,
        contactUri: String,
    ): Boolean {
        picked += contactId to contactUri
        return contactUri in pickable
    }

    override fun restorePack(
        session: RestoreSession,
        item: PackRestoreItem,
    ): Flow<PackProgress> {
        restoredPacks += item.reference.id
        return progressFor(item)
    }

    override fun importPackFile(
        source: String,
        item: PackRestoreItem,
    ): Flow<PackProgress> {
        importedFiles += item.reference.id to source
        return progressFor(item)
    }

    private fun progressFor(item: PackRestoreItem): Flow<PackProgress> {
        val id = item.reference.id
        instantPacks?.let { result ->
            if (result == PackProgress.Done) installed[id] = null
            return flowOf(result)
        }
        return packProgress.transformWhile { (target, progress) ->
            if (target != id) return@transformWhile true
            if (progress == PackProgress.Done) installed[id] = null
            emit(progress)
            progress is PackProgress.Working
        }
    }
}
