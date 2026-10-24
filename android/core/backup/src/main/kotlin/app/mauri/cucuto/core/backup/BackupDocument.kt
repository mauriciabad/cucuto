package app.mauri.cucuto.core.backup

import app.mauri.cucuto.core.data.db.RingtoneRuleEntry
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * `backup.json`, the root manifest of a backup (`spec/backup.schema.json`, docs/pack-format.md#backups). Readers
 * validate the JSON against the schema before decoding it, so the shapes here can stay simple: optional fields are
 * nullable and omitted when null, and `x-` extension keys are ignored.
 */
@Serializable
data class BackupDocument(
    val formatVersion: Int = FORMAT_VERSION,
    /** RFC 3339 UTC timestamp of the export. */
    val createdAt: String,
    val app: BackupApp,
    /** App-owned settings; unknown keys are ignored on restore ([SettingsBackup]). */
    val settings: JsonObject = JsonObject(emptyMap()),
    val repositories: List<BackupRepository> = emptyList(),
    val packs: List<BackupPackReference> = emptyList(),
    val contacts: List<BackupContact> = emptyList(),
) {
    companion object {
        const val FORMAT_VERSION: Int = 1
        const val APP_ID: String = "app.mauri.cucuto"
    }
}

/** The app that wrote the backup. */
@Serializable
data class BackupApp(
    val id: String = BackupDocument.APP_ID,
    val versionName: String,
    val versionCode: Long,
)

@Serializable
data class BackupRepository(
    /** URL of `repository.json` or of the folder that holds it (the registered URL). */
    val url: String,
    val name: String? = null,
    val isDefault: Boolean = false,
    val enabled: Boolean = true,
    val auth: BackupAuth = BackupAuth(),
)

@Serializable
data class BackupAuth(
    val type: BackupAuthType = BackupAuthType.NONE,
    /** Only in encrypted backups, and only when the user opted in. */
    val credentials: BackupCredentials? = null,
)

@Serializable
enum class BackupAuthType {
    @SerialName("none")
    NONE,

    @SerialName("basic")
    BASIC,

    @SerialName("bearer")
    BEARER,
}

/** Either [username] and [password] (HTTP Basic) or [token] (bearer). [toString] never shows the secrets. */
@Serializable
data class BackupCredentials(
    val username: String? = null,
    val password: String? = null,
    val token: String? = null,
) {
    override fun toString(): String = "BackupCredentials(██)"
}

/** One installed pack, or one imported bundle with the packs the user installed from it. */
@Serializable
data class BackupPackReference(
    val id: String,
    val type: BackupPackType,
    val version: String,
    val name: String? = null,
    /** SHA-256 (lowercase hex) of the pack or bundle zip that was imported. */
    val sha256: String,
    /** Size of that zip in bytes. */
    val size: Long? = null,
    /** Bundles only: IDs of the installed packs. */
    val packs: List<String>? = null,
    val source: BackupPackSource,
    /** Path of the `.soundpack.zip` inside a full backup. */
    val included: String? = null,
) {
    /** The pack IDs this reference installs. */
    val packIds: List<String> get() = if (type == BackupPackType.PACK) listOf(id) else packs.orEmpty()
}

@Serializable
enum class BackupPackType {
    @SerialName("pack")
    PACK,

    @SerialName("bundle")
    BUNDLE,
}

/** Where a pack came from. The schema allows [repositoryUrl] only for repositories and [fileName] only for local. */
@Serializable
data class BackupPackSource(
    val type: BackupPackSourceType,
    val repositoryUrl: String? = null,
    val downloadUrl: String? = null,
    val fileName: String? = null,
)

@Serializable
enum class BackupPackSourceType {
    @SerialName("repository")
    REPOSITORY,

    @SerialName("url")
    URL,

    @SerialName("local")
    LOCAL,
}

@Serializable
data class BackupContact(
    /** Opaque ID, unique within the backup. */
    val id: String,
    val displayName: String? = null,
    val isGroup: Boolean = false,
    /** E.164 numbers, used to re-match contacts. */
    val phoneNumbers: List<String> = emptyList(),
    /** Every messenger key seen for this contact. */
    val keys: List<BackupContactKey> = emptyList(),
    val androidContact: BackupAndroidContact? = null,
    /** Groups only. */
    val groupPolicy: BackupGroupPolicy? = null,
    val message: BackupMessageAssignment? = null,
    val ringtone: BackupRingtoneAssignment? = null,
)

@Serializable
data class BackupContactKey(
    @SerialName("package") val packageName: String,
    val conversationKey: String,
)

@Serializable
data class BackupAndroidContact(
    val lookupKey: String,
    val displayName: String? = null,
)

@Serializable
enum class BackupGroupPolicy {
    @SerialName("group")
    GROUP,

    @SerialName("per-sender")
    PER_SENDER,
}

@Serializable
data class BackupMessageAssignment(
    val packId: String,
    val characterId: String,
    /** Clip files (paths from `pack.json`) the user switched off. */
    val disabledClips: List<String> = emptyList(),
    /** Per-contact haptic override in the pack format's `haptic` shape. */
    val haptic: JsonElement? = null,
)

/**
 * A contact's phone ringtone (ADR 0039, ADR 0057). [packId] and [characterId] name the character that rang when the
 * backup was written, even when it [followsVoice].
 */
@Serializable
data class BackupRingtoneAssignment(
    val packId: String,
    val characterId: String,
    /** True when the ringtone follows the message character; omitted (null) otherwise. */
    val followsVoice: Boolean? = null,
    val rule: BackupRingtoneRule,
    /** Device-local undo record: used only on the same phone ([BackupRingtoneUndo]). */
    val undo: BackupRingtoneUndo? = null,
)

/**
 * What turning a phone ringtone off gives back: the Android contact's own ringtone before Cucuto ([originalRingtone],
 * null for the default) and the ring file Cucuto wrote last ([writtenUri]). Both are URIs of this phone, so a restore
 * applies them only when the contact still rings with exactly [writtenUri] (the same phone, after a reinstall).
 */
@Serializable
data class BackupRingtoneUndo(
    val originalRingtone: String? = null,
    val writtenUri: String,
)

@Serializable
data class BackupRingtoneRule(
    val mode: BackupRingtoneMode,
    /** Ringtone file (path from `pack.json`) for [BackupRingtoneMode.FIXED]. */
    val variant: String? = null,
    /** The contact's own rules for [BackupRingtoneMode.CUSTOM] (ADR 0074), the first entry being the fallback. */
    val rules: List<RingtoneRuleEntry>? = null,
)

@Serializable
enum class BackupRingtoneMode {
    @SerialName("fixed")
    FIXED,

    @SerialName("random")
    RANDOM,

    @SerialName("time")
    TIME,

    @SerialName("season")
    SEASON,

    @SerialName("pack")
    PACK,

    @SerialName("custom")
    CUSTOM,
}

/** The JSON settings for backup documents: readable output, defaults written, nulls omitted. */
internal val BackupJson: Json =
    Json {
        ignoreUnknownKeys = true // `$schema` and `x-` extension keys
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
        prettyPrintIndent = "  "
    }

/** Compact JSON for values stored in the database (haptic overrides). */
internal val CompactJson: Json = Json { explicitNulls = false }
