package app.mauri.cucuto.core.data.db

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * The character that speaks for a contact's messages. Characters are referenced by `(packId, characterId)`, which
 * are stable across pack versions, so updating a pack keeps every assignment (pack format "Versioning").
 *
 * Call ringtones (M9) get their own table: they have variant rules and are written to Android contacts.
 *
 * @property disabledClips Clip files (paths from `pack.json`) the user switched off for this contact.
 * @property hapticOverride Per-contact haptic in the pack format's `haptic` JSON shape
 *   (`pack.schema.json#/$defs/haptic`), or null to use the character's. The haptic editor (M4) writes it.
 */
@Entity(
    tableName = "assignments",
    indices = [Index("packId", "characterId")],
    foreignKeys = [
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["id"],
            childColumns = ["contactId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class AssignmentEntity(
    @PrimaryKey val contactId: Long,
    val packId: String,
    val characterId: String,
    val disabledClips: List<String> = emptyList(),
    val hapticOverride: String? = null,
    val updatedAt: Long,
)
