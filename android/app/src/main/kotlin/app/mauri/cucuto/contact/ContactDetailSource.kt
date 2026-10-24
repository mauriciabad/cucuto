package app.mauri.cucuto.contact

import app.mauri.cucuto.core.data.db.DecisionLogEntity
import app.mauri.cucuto.core.data.db.GroupPolicy
import app.mauri.cucuto.core.data.repository.AssignmentRepository
import app.mauri.cucuto.core.data.repository.ContactRepository
import app.mauri.cucuto.core.data.repository.DecisionLogRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A contact's per-chat settings, as contact detail shows them.
 *
 * @property disabledClips Clip paths from `pack.json` switched off for this contact.
 * @property hapticOverride The per-contact haptic in the pack `haptic` JSON shape, or null for the character's.
 */
data class ChatSettings(
    val groupPolicy: GroupPolicy,
    val disabledClips: List<String>,
    val hapticOverride: String?,
)

/** What contact detail reads and writes besides the chat list and the characters. */
interface ContactDetailSource {
    /** [contactId]'s settings, or null when the contact does not exist (any more). */
    fun observeSettings(contactId: Long): Flow<ChatSettings?>

    /** The module's decision log, newest first. */
    fun observeDecisions(): Flow<List<DecisionLogEntity>>

    /** Switches one clip of the contact's character on or off. Republishes the routing table. */
    suspend fun setClipEnabled(
        contactId: Long,
        clipPath: String,
        enabled: Boolean,
    )
}

/** [ContactDetailSource] over the Room repositories. */
@Singleton
class RoomContactDetailSource
    @Inject
    constructor(
        private val contacts: ContactRepository,
        private val assignments: AssignmentRepository,
        private val decisionLog: DecisionLogRepository,
    ) : ContactDetailSource {
        override fun observeSettings(contactId: Long): Flow<ChatSettings?> =
            combine(contacts.observeContact(contactId), assignments.observe(contactId)) { contact, assignment ->
                contact?.let {
                    ChatSettings(
                        groupPolicy = it.contact.groupPolicy,
                        disabledClips = assignment?.disabledClips.orEmpty(),
                        hapticOverride = assignment?.hapticOverride,
                    )
                }
            }

        override fun observeDecisions(): Flow<List<DecisionLogEntity>> = decisionLog.observeRecent()

        override suspend fun setClipEnabled(
            contactId: Long,
            clipPath: String,
            enabled: Boolean,
        ) = assignments.setClipEnabled(contactId, clipPath, enabled)
    }

@Module
@InstallIn(SingletonComponent::class)
interface ContactDetailModule {
    @Binds fun contactDetailSource(source: RoomContactDetailSource): ContactDetailSource
}
