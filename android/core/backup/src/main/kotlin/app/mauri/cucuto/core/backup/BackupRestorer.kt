package app.mauri.cucuto.core.backup

import androidx.room3.withWriteTransaction
import app.mauri.cucuto.core.data.db.AssignmentEntity
import app.mauri.cucuto.core.data.db.ContactEntity
import app.mauri.cucuto.core.data.db.ContactKeyEntity
import app.mauri.cucuto.core.data.db.ContactKeyKind
import app.mauri.cucuto.core.data.db.CucutoDatabase
import app.mauri.cucuto.core.data.db.GroupPolicy
import app.mauri.cucuto.core.data.db.PackRepositoryEntity
import app.mauri.cucuto.core.data.db.PhoneRingtoneEntity
import app.mauri.cucuto.core.data.db.RepositoryAuthType
import app.mauri.cucuto.core.data.db.RingtoneMode
import app.mauri.cucuto.core.data.di.WallClock
import app.mauri.cucuto.core.repo.RepositoryCredentials
import app.mauri.cucuto.core.repo.credentials.CredentialStore
import kotlinx.serialization.json.JsonElement
import kotlin.random.Random

/** Choices for [BackupRestorer.restore]. */
data class RestoreOptions(
    /**
     * Backup contact IDs whose voice should replace the one set on this phone: the user's answers to
     * [RestoreReport.conflicts]. Everything else that the user changed on this phone is kept.
     */
    val overwrite: Set<String> = emptySet(),
)

/** How a backup contact was matched to this phone. */
enum class ContactMatch {
    /** No contact on this phone had any of its keys: a new contact was created. */
    CREATED,

    /** Matched by a messenger key (WhatsApp conversation key). */
    MESSENGER_KEY,

    /** Matched by an E.164 phone number. */
    PHONE_NUMBER,
}

/** What happened to a backup contact's voice (message assignment and group policy). */
enum class VoiceOutcome {
    /** The backup sets nothing for this contact. */
    NONE,

    /** Applied: new contact, a contact with nothing set yet, or an overwrite the user chose. */
    APPLIED,

    /** This phone already has exactly the backup's voice. */
    UNCHANGED,

    /** This phone has a different voice set; kept, and listed in [RestoreReport.conflicts]. */
    CONFLICT,
}

/** What happened to a backup contact's phone ringtone. */
enum class RingtoneOutcome {
    /** Applied: the contact had no phone ringtone on this phone, or the user chose to overwrite it. */
    APPLIED,

    /** This phone already has exactly the backup's phone ringtone. */
    UNCHANGED,

    /** This phone has a different phone ringtone set; kept. */
    KEPT,
}

/**
 * A backup contact's phone ringtone after the restore (ADR 0057). The database now holds the choice; the app still
 * has to find the Android contact on this phone, seed the undo record and render the ring files, which needs the
 * contacts permission (`PhoneRingtones.afterRestore`).
 *
 * @property displayName The contact's name in the backup.
 * @property androidContact The Android contact on the phone that wrote the backup (its lookup key and name).
 * @property undo The device-local undo record from the backup, if any.
 */
data class RingtoneRestoreOutcome(
    val backupId: String,
    val contactId: Long,
    val outcome: RingtoneOutcome,
    val displayName: String?,
    val androidContact: BackupAndroidContact?,
    val undo: BackupRingtoneUndo?,
)

data class ContactRestoreOutcome(
    val backupId: String,
    val contactId: Long,
    val match: ContactMatch,
    val voice: VoiceOutcome,
    /** Keys of the backup contact that belong to another contact on this phone (left there; merge by hand). */
    val keysOwnedElsewhere: List<String>,
)

/** A contact whose voice on this phone differs from the backup's. Restore again with its [backupId] to replace it. */
data class AssignmentConflict(
    val backupId: String,
    val contactId: Long,
    val displayName: String?,
    val current: VoiceSnapshot,
    val backup: VoiceSnapshot,
)

/** A voice for comparison and display: the message character and the group policy. */
data class VoiceSnapshot(
    val packId: String?,
    val characterId: String?,
    val disabledClips: List<String>,
    val hapticOverride: JsonElement?,
    val groupPolicy: GroupPolicy,
)

data class RestoreReport(
    val contacts: List<ContactRestoreOutcome>,
    val conflicts: List<AssignmentConflict>,
    val repositoriesAdded: List<String>,
    val credentialsRestored: List<String>,
    /** Credentials that were in the backup but could not be used (invalid, or a plain backup). */
    val credentialsSkipped: List<String>,
    val settings: SettingsRestoreResult,
    /** Every phone ringtone in the backup, in backup order. */
    val ringtones: List<RingtoneRestoreOutcome> = emptyList(),
) {
    /** Phone ringtones written to this phone's database. */
    val ringtonesApplied: Int get() = ringtones.count { it.outcome == RingtoneOutcome.APPLIED }

    /** Phone ringtones set differently on this phone, kept. */
    val ringtonesKept: Int get() = ringtones.count { it.outcome == RingtoneOutcome.KEPT }

    val created: Int get() = contacts.count { it.match == ContactMatch.CREATED }
    val matched: Int get() = contacts.count { it.match != ContactMatch.CREATED }
    val applied: Int get() = contacts.count { it.voice == VoiceOutcome.APPLIED }
}

/**
 * Brings a backup's contacts, assignments, repositories, credentials and settings into this phone's database
 * (docs/project-brief.md, "Restore on a new phone"). Packs are restored separately ([PackRestorePlanner]); the
 * routing table follows the database by itself.
 *
 * The restore is idempotent and never overwrites what the user set on this phone without asking:
 * - Contacts match on any messenger key, then on any phone number. Matched contacts gain the backup's missing keys
 *   (never keys that belong to another contact: two contacts are only merged by the user) and missing names and
 *   Android links; unmatched ones are created.
 * - A voice is applied when the contact is new or has nothing set; equal voices are left alone; different ones are
 *   reported as [RestoreReport.conflicts] and replaced only for the IDs in [RestoreOptions.overwrite].
 * - Repositories are added when their URL is unknown; existing ones keep this phone's settings. Credentials come only
 *   from encrypted backups and never replace stored ones.
 * - Settings in the backup are applied; unknown keys are ignored.
 * - Phone ringtones follow the voice's rule ([restoreRingtone]); finding their Android contacts on this phone and
 *   writing them is the app's next step ([RestoreReport.ringtones]).
 */
class BackupRestorer(
    private val db: CucutoDatabase,
    private val settings: SettingsBackup,
    private val credentials: CredentialStore?,
    private val clock: WallClock,
    private val random: Random = Random.Default,
) {
    suspend fun restore(
        backup: BackupDocument,
        encrypted: Boolean,
        options: RestoreOptions = RestoreOptions(),
    ): RestoreReport {
        val outcomes = mutableListOf<ContactRestoreOutcome>()
        val conflicts = mutableListOf<AssignmentConflict>()
        val ringtones = mutableListOf<RingtoneRestoreOutcome>()
        val repositoriesAdded = mutableListOf<String>()
        db.withWriteTransaction {
            for (contact in backup.contacts) {
                val (outcome, conflict) = restoreContact(contact, options)
                outcomes += outcome
                conflict?.let(conflicts::add)
                restoreRingtone(contact, outcome, options)?.let(ringtones::add)
            }
            for (repository in backup.repositories) {
                if (restoreRepository(repository)) repositoriesAdded += repository.url
            }
        }
        val restored = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        for (repository in backup.repositories) {
            val secret = repository.auth.credentials ?: continue
            val store = credentials
            val value = toCredentials(repository.auth.type, secret)
            when {
                !encrypted || store == null || value == null -> skipped += repository.url

                store.get(repository.url) != null -> Unit

                // Keep the credentials entered on this phone.
                else -> store.put(repository.url, value).also { restored += repository.url }
            }
        }
        return RestoreReport(
            contacts = outcomes,
            conflicts = conflicts,
            repositoriesAdded = repositoriesAdded,
            credentialsRestored = restored,
            credentialsSkipped = skipped,
            settings = settings.restore(backup.settings),
            ringtones = ringtones,
        )
    }

    /**
     * The phone ringtone, with the voice's rule: applied when this phone has none for the contact (or the user chose
     * to overwrite the contact), left alone when equal, kept otherwise. One that follows the voice keeps following
     * whatever voice the contact ends up with.
     */
    private suspend fun restoreRingtone(
        backup: BackupContact,
        contact: ContactRestoreOutcome,
        options: RestoreOptions,
    ): RingtoneRestoreOutcome? {
        val ringtone = backup.ringtone ?: return null
        val dao = db.phoneRingtoneDao()
        val current = dao.get(contact.contactId)
        val follows = ringtone.followsVoice == true
        val mode = ringtone.rule.mode.toEntity()
        val wanted =
            PhoneRingtoneEntity(
                contactId = contact.contactId,
                packId = if (follows) null else ringtone.packId,
                characterId = if (follows) null else ringtone.characterId,
                mode = mode,
                variant = if (mode == RingtoneMode.FIXED) ringtone.rule.variant else null,
                updatedAt = clock.now(),
            )
        val outcome =
            when {
                current != null && current.copy(updatedAt = 0) == wanted.copy(updatedAt = 0) -> {
                    RingtoneOutcome.UNCHANGED
                }

                current != null && contact.match != ContactMatch.CREATED && backup.id !in options.overwrite -> {
                    RingtoneOutcome.KEPT
                }

                else -> {
                    dao.upsert(wanted)
                    RingtoneOutcome.APPLIED
                }
            }
        return RingtoneRestoreOutcome(
            backupId = backup.id,
            contactId = contact.contactId,
            outcome = outcome,
            displayName = backup.displayName,
            androidContact = backup.androidContact,
            undo = ringtone.undo,
        )
    }

    private suspend fun restoreContact(
        backup: BackupContact,
        options: RestoreOptions,
    ): Pair<ContactRestoreOutcome, AssignmentConflict?> {
        val contacts = db.contactDao()
        val identities =
            backup.keys.map { it.packageName to it.conversationKey } +
                backup.phoneNumbers.map { ContactKeyEntity.NO_PACKAGE to it }
        val now = clock.now()

        var match = ContactMatch.CREATED
        var contactId: Long? = null
        for ((packageName, value) in identities) {
            val key = contacts.findKey(packageName, value) ?: continue
            contactId = key.contactId
            match =
                if (packageName ==
                    ContactKeyEntity.NO_PACKAGE
                ) {
                    ContactMatch.PHONE_NUMBER
                } else {
                    ContactMatch.MESSENGER_KEY
                }
            break
        }
        val policy = if (backup.isGroup) backup.groupPolicy.toEntity() else GroupPolicy.GROUP
        val id =
            contactId ?: contacts.insert(
                ContactEntity(
                    displayName = backup.displayName,
                    isGroup = backup.isGroup,
                    groupPolicy = policy,
                    androidLookupKey = backup.androidContact?.lookupKey,
                    androidDisplayName = backup.androidContact?.displayName,
                    shuffleSeed = random.nextInt(),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        var local = contacts.get(id)!!.contact
        if (match != ContactMatch.CREATED) {
            val android = backup.androidContact
            val filled =
                local.copy(
                    displayName = local.displayName ?: backup.displayName,
                    androidLookupKey = local.androidLookupKey ?: android?.lookupKey,
                    androidDisplayName =
                        if (local.androidLookupKey == null) android?.displayName else local.androidDisplayName,
                )
            if (filled != local) {
                local = filled.copy(updatedAt = now)
                contacts.update(local)
            }
        }

        val elsewhere = mutableListOf<String>()
        for ((packageName, value) in identities) {
            val existing = contacts.findKey(packageName, value)
            when {
                existing == null -> {
                    contacts.insertKey(
                        ContactKeyEntity(
                            contactId = id,
                            packageName = packageName,
                            value = value,
                            kind = ContactKeyKind.classify(value),
                            displayName = if (packageName.isEmpty()) null else backup.displayName,
                        ),
                    )
                }

                existing.contactId != id -> {
                    elsewhere += value
                }
            }
        }

        val (voice, conflict) = restoreVoice(backup, local, match == ContactMatch.CREATED, options)
        return ContactRestoreOutcome(backup.id, id, match, voice, elsewhere) to conflict
    }

    private suspend fun restoreVoice(
        backup: BackupContact,
        local: ContactEntity,
        created: Boolean,
        options: RestoreOptions,
    ): Pair<VoiceOutcome, AssignmentConflict?> {
        val assignments = db.assignmentDao()
        val current = assignments.get(local.id)
        val currentVoice = snapshot(current, local.groupPolicy)
        val message = backup.message
        val wantedPolicy =
            if (local.isGroup &&
                backup.groupPolicy != null
            ) {
                backup.groupPolicy.toEntity()
            } else {
                local.groupPolicy
            }
        if (message == null && wantedPolicy == local.groupPolicy) return VoiceOutcome.NONE to null

        val wantedVoice =
            if (message == null) {
                currentVoice.copy(groupPolicy = wantedPolicy)
            } else {
                VoiceSnapshot(
                    packId = message.packId,
                    characterId = message.characterId,
                    disabledClips = message.disabledClips.distinct().sorted(),
                    hapticOverride = message.haptic,
                    groupPolicy = wantedPolicy,
                )
            }
        if (wantedVoice == currentVoice) return VoiceOutcome.UNCHANGED to null
        val untouched = current == null && local.groupPolicy == GroupPolicy.GROUP
        if (!created && !untouched && backup.id !in options.overwrite) {
            return VoiceOutcome.CONFLICT to
                AssignmentConflict(backup.id, local.id, local.displayName, currentVoice, wantedVoice)
        }
        val now = clock.now()
        if (message != null) {
            assignments.upsert(
                AssignmentEntity(
                    contactId = local.id,
                    packId = message.packId,
                    characterId = message.characterId,
                    disabledClips = wantedVoice.disabledClips,
                    hapticOverride = message.haptic?.let { CompactJson.encodeToString(JsonElement.serializer(), it) },
                    updatedAt = now,
                ),
            )
        }
        if (wantedPolicy !=
            local.groupPolicy
        ) {
            db.contactDao().update(local.copy(groupPolicy = wantedPolicy, updatedAt = now))
        }
        return VoiceOutcome.APPLIED to null
    }

    /** Adds an unknown repository; returns true when it was added. */
    private suspend fun restoreRepository(repository: BackupRepository): Boolean {
        val dao = db.packRepositoryDao()
        if (dao.getByUrl(repository.url) != null) return false
        val id =
            dao.insert(
                PackRepositoryEntity(
                    url = repository.url,
                    name = repository.name,
                    isDefault = repository.isDefault,
                    enabled = repository.enabled,
                    authType =
                        when (repository.auth.type) {
                            BackupAuthType.NONE -> RepositoryAuthType.NONE
                            BackupAuthType.BASIC -> RepositoryAuthType.BASIC
                            BackupAuthType.BEARER -> RepositoryAuthType.BEARER
                        },
                    addedAt = clock.now(),
                ),
            )
        return id != -1L
    }

    private fun snapshot(
        assignment: AssignmentEntity?,
        policy: GroupPolicy,
    ) = VoiceSnapshot(
        packId = assignment?.packId,
        characterId = assignment?.characterId,
        disabledClips =
            assignment
                ?.disabledClips
                .orEmpty()
                .distinct()
                .sorted(),
        hapticOverride = assignment?.hapticOverride?.let(BackupSnapshot::parseHaptic),
        groupPolicy = policy,
    )

    private fun toCredentials(
        type: BackupAuthType,
        secret: BackupCredentials,
    ): RepositoryCredentials? =
        try {
            when {
                type == BackupAuthType.BASIC && secret.username != null && secret.password != null -> {
                    RepositoryCredentials.Basic(secret.username, secret.password)
                }

                type == BackupAuthType.BEARER && secret.token != null -> {
                    RepositoryCredentials.Bearer(secret.token)
                }

                else -> {
                    null
                }
            }
        } catch (e: IllegalArgumentException) {
            null // A username with ':' or a token with spaces: the user enters them again.
        }

    private fun BackupRingtoneMode.toEntity(): RingtoneMode =
        when (this) {
            BackupRingtoneMode.FIXED -> RingtoneMode.FIXED
            BackupRingtoneMode.RANDOM -> RingtoneMode.RANDOM
            BackupRingtoneMode.TIME -> RingtoneMode.TIME
            BackupRingtoneMode.SEASON -> RingtoneMode.SEASON
            BackupRingtoneMode.PACK -> RingtoneMode.PACK
        }

    private fun BackupGroupPolicy?.toEntity(): GroupPolicy =
        when (this) {
            BackupGroupPolicy.PER_SENDER -> GroupPolicy.PER_SENDER
            BackupGroupPolicy.GROUP, null -> GroupPolicy.GROUP
        }
}
