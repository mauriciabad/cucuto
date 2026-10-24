package app.mauri.cucuto.core.backup

import app.mauri.cucuto.core.data.db.AssignmentEntity
import app.mauri.cucuto.core.data.db.ContactKeyEntity
import app.mauri.cucuto.core.data.db.ContactKeyKind
import app.mauri.cucuto.core.data.db.ContactRingtoneWriteEntity
import app.mauri.cucuto.core.data.db.ContactWithKeys
import app.mauri.cucuto.core.data.db.GroupPolicy
import app.mauri.cucuto.core.data.db.InstalledPackEntity
import app.mauri.cucuto.core.data.db.PackRepositoryEntity
import app.mauri.cucuto.core.data.db.PackSource
import app.mauri.cucuto.core.data.db.PhoneRingtoneEntity
import app.mauri.cucuto.core.data.db.RepositoryAuthType
import app.mauri.cucuto.core.data.db.RingtoneMode
import app.mauri.cucuto.packspec.SemVer
import kotlinx.serialization.json.JsonElement

/**
 * Pure mapping from database rows to backup entries. Values are clipped to the schema's limits where the database
 * allows more (long names, many keys), so one odd row cannot make the whole backup invalid.
 */
internal object BackupSnapshot {
    private const val MAX_NAME = 200
    private const val MAX_KEYS = 50
    private const val MAX_PHONES = 20
    private const val MAX_CONVERSATION_KEY = 512
    private const val MAX_LOOKUP_KEY = 1024
    private const val MAX_FILE_NAME = 255
    private const val MAX_PACK_NAME = 80
    private const val MAX_URI = 2048
    private const val MAX_PATH = 255
    private val packagePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val sha256Pattern = Regex("^[0-9a-f]{64}$")
    private val httpsPattern = Regex("^https://[^\\s/?#]+\\S*$")

    fun contact(
        row: ContactWithKeys,
        assignment: AssignmentEntity?,
        ringtone: PhoneRingtoneEntity? = null,
        ringtoneWrite: ContactRingtoneWriteEntity? = null,
    ): BackupContact {
        val c = row.contact
        val keys =
            row.keys
                .filter { it.packageName != ContactKeyEntity.NO_PACKAGE }
                .filter { packagePattern.matches(it.packageName) && it.value.length in 1..MAX_CONVERSATION_KEY }
                .sortedWith(compareByDescending<ContactKeyEntity> { it.lastSeenAt ?: Long.MIN_VALUE }.thenBy { it.id })
                .take(MAX_KEYS)
                .map { BackupContactKey(it.packageName, it.value) }
        val phones =
            row.keys
                .filter { it.packageName == ContactKeyEntity.NO_PACKAGE && it.kind == ContactKeyKind.PHONE_NUMBER }
                .map { it.value }
                .filter { ContactKeyKind.classify(it) == ContactKeyKind.PHONE_NUMBER }
                .distinct()
                .take(MAX_PHONES)
        return BackupContact(
            id = c.id.toString(),
            displayName = c.displayName?.take(MAX_NAME),
            isGroup = c.isGroup,
            phoneNumbers = phones,
            keys = keys,
            androidContact =
                c.androidLookupKey?.takeIf { it.isNotEmpty() }?.let {
                    BackupAndroidContact(it.take(MAX_LOOKUP_KEY), c.androidDisplayName?.take(MAX_NAME))
                },
            groupPolicy = if (c.isGroup) groupPolicy(c.groupPolicy) else null,
            message = assignment?.let(::message),
            ringtone = ringtone?.let { ringtone(it, assignment, ringtoneWrite) },
        )
    }

    /**
     * A phone ringtone (ADR 0057, ADR 0063). It names the character that rings: its own (picked by the user, or kept
     * while the voice has no ringtones), else the voice's. One that follows the voice says `followsVoice`; one with
     * neither its own character nor a voice has nothing to ring and is left out. The undo record goes along only while
     * Cucuto's file is on the contact ([ContactRingtoneWriteEntity.writtenUri], not changed by the user since).
     */
    fun ringtone(
        row: PhoneRingtoneEntity,
        assignment: AssignmentEntity?,
        write: ContactRingtoneWriteEntity?,
    ): BackupRingtoneAssignment? {
        val own = row.packId != null && row.characterId != null
        val follows = row.followsVoice || !own
        val packId = (if (own) row.packId else assignment?.packId) ?: return null
        val characterId = (if (own) row.characterId else assignment?.characterId) ?: return null
        val mode =
            when (row.mode) {
                RingtoneMode.FIXED -> BackupRingtoneMode.FIXED
                RingtoneMode.RANDOM -> BackupRingtoneMode.RANDOM
                RingtoneMode.TIME -> BackupRingtoneMode.TIME
                RingtoneMode.SEASON -> BackupRingtoneMode.SEASON
                RingtoneMode.PACK -> BackupRingtoneMode.PACK
            }
        // A fixed rule needs its variant (schema): without one the first variant rang, which "pack" also gives when
        // the pack has no conditions; keep it fixed only when the variant is known.
        val variant = row.variant?.takeIf { mode == BackupRingtoneMode.FIXED && it.length <= MAX_PATH }
        val undo =
            write
                ?.takeIf { !it.userChanged }
                ?.writtenUri
                ?.takeIf { it.length <= MAX_URI }
                ?.let { written ->
                    BackupRingtoneUndo(
                        originalRingtone = write.originalRingtone?.takeIf { it.isNotEmpty() && it.length <= MAX_URI },
                        writtenUri = written,
                    )
                }
        return BackupRingtoneAssignment(
            packId = packId,
            characterId = characterId,
            followsVoice = if (follows) true else null,
            rule =
                if (mode == BackupRingtoneMode.FIXED && variant == null) {
                    BackupRingtoneRule(BackupRingtoneMode.PACK)
                } else {
                    BackupRingtoneRule(mode, variant)
                },
            undo = undo,
        )
    }

    fun message(a: AssignmentEntity): BackupMessageAssignment =
        BackupMessageAssignment(
            packId = a.packId,
            characterId = a.characterId,
            disabledClips = a.disabledClips.distinct().sorted(),
            haptic = a.hapticOverride?.let(::parseHaptic),
        )

    fun parseHaptic(json: String): JsonElement? =
        try {
            CompactJson.parseToJsonElement(json)
        } catch (e: Exception) {
            null
        }

    fun groupPolicy(policy: GroupPolicy): BackupGroupPolicy =
        when (policy) {
            GroupPolicy.GROUP -> BackupGroupPolicy.GROUP
            GroupPolicy.PER_SENDER -> BackupGroupPolicy.PER_SENDER
        }

    fun repository(
        row: PackRepositoryEntity,
        credentials: BackupCredentials?,
    ): BackupRepository =
        BackupRepository(
            url = row.url,
            name = row.name?.takeIf { it.isNotBlank() }?.take(MAX_PACK_NAME),
            isDefault = row.isDefault,
            enabled = row.enabled,
            auth =
                BackupAuth(
                    type =
                        when (row.authType) {
                            RepositoryAuthType.NONE -> BackupAuthType.NONE
                            RepositoryAuthType.BASIC -> BackupAuthType.BASIC
                            RepositoryAuthType.BEARER -> BackupAuthType.BEARER
                        },
                    credentials = credentials,
                ),
        )

    /**
     * One reference per installed single pack and one per imported bundle (its installed packs grouped by bundle ID).
     * References whose file hash is unknown get [UNKNOWN_SHA256]: a full backup fills it from the included file, a
     * plain backup leaves them out.
     */
    fun packReferences(rows: List<InstalledPackEntity>): List<BackupPackReference> {
        val singles =
            rows.filter { it.bundleId == null }.map { row ->
                BackupPackReference(
                    id = row.packId,
                    type = BackupPackType.PACK,
                    version = row.version,
                    name = row.name.takeIf { it.isNotBlank() }?.take(MAX_PACK_NAME),
                    sha256 = row.sha256.takeIf(sha256Pattern::matches) ?: UNKNOWN_SHA256,
                    size = row.sizeBytes.takeIf { it >= MIN_ZIP_BYTES },
                    source = source(row),
                )
            }
        val bundles =
            rows.filter { it.bundleId != null }.groupBy { it.bundleId!! }.map { (bundleId, members) ->
                val first = members.maxBy { it.installedAt }
                BackupPackReference(
                    id = bundleId,
                    type = BackupPackType.BUNDLE,
                    version =
                        first.bundleVersion?.takeIf { SemVer.parse(it) != null }
                            ?: members.map { it.version }.maxBy { SemVer.parse(it) ?: SemVer.parse("0.0.0")!! },
                    name = first.bundleName?.takeIf { it.isNotBlank() }?.take(MAX_PACK_NAME),
                    sha256 = members.map { it.sha256 }.firstOrNull(sha256Pattern::matches) ?: UNKNOWN_SHA256,
                    size = first.sizeBytes.takeIf { it >= MIN_ZIP_BYTES },
                    packs = members.map { it.packId }.sorted(),
                    source = source(first),
                )
            }
        return (singles + bundles).sortedBy { it.id }
    }

    fun source(row: InstalledPackEntity): BackupPackSource {
        val repository = row.repositoryUrl?.takeIf(httpsPattern::matches)
        val download = row.downloadUrl?.takeIf(httpsPattern::matches)
        return when {
            row.source == PackSource.REPOSITORY && repository != null -> {
                BackupPackSource(BackupPackSourceType.REPOSITORY, repositoryUrl = repository, downloadUrl = download)
            }

            row.source != PackSource.LOCAL && download != null -> {
                BackupPackSource(BackupPackSourceType.URL, downloadUrl = download)
            }

            else -> {
                BackupPackSource(BackupPackSourceType.LOCAL, fileName = row.fileName?.take(MAX_FILE_NAME))
            }
        }
    }

    /** Path of a reference's file inside a full backup. IDs and versions only use characters valid in paths. */
    fun includedPath(reference: BackupPackReference): String =
        "packs/${reference.id}-${reference.version}.soundpack.zip"

    /** Placeholder for an unknown hash (a pack installed by debug tools, or recorded before hashes were kept). */
    const val UNKNOWN_SHA256 = "0000000000000000000000000000000000000000000000000000000000000000"

    /** The smallest possible zip (an empty archive). */
    private const val MIN_ZIP_BYTES = 22L
}
