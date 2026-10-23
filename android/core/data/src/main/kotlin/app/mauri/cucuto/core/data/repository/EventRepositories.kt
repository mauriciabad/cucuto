package app.mauri.cucuto.core.data.repository

import app.mauri.cucuto.core.data.db.DecisionLogDao
import app.mauri.cucuto.core.data.db.DecisionLogEntity
import app.mauri.cucuto.core.data.db.SeenSenderDao
import app.mauri.cucuto.core.data.db.SeenSenderEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** The module's decisions, newest first, bounded to [DecisionLogDao.CAPACITY] entries. */
@Singleton
class DecisionLogRepository
    @Inject
    constructor(
        private val dao: DecisionLogDao,
    ) {
        fun observeRecent(limit: Int = DecisionLogDao.CAPACITY): Flow<List<DecisionLogEntity>> =
            dao.observeRecent(limit)

        suspend fun record(entry: DecisionLogEntity): Long = dao.append(entry)

        suspend fun clear() = dao.clear()
    }

/** Conversations the module has seen, most recent first: the "recent senders" feed. */
@Singleton
class SeenSenderRepository
    @Inject
    constructor(
        private val dao: SeenSenderDao,
    ) {
        fun observeRecent(limit: Int = 50): Flow<List<SeenSenderEntity>> = dao.observeRecent(limit)

        suspend fun get(
            packageName: String,
            conversationKey: String,
        ): SeenSenderEntity? = dao.get(packageName, conversationKey)

        /**
         * Records one alerting notification.
         *
         * @param seenAt When the module saw it (milliseconds since the epoch).
         */
        suspend fun record(
            packageName: String,
            conversationKey: String,
            displayName: String?,
            isGroup: Boolean,
            seenAt: Long,
            senderKey: String? = null,
            senderName: String? = null,
        ) = dao.record(
            SeenSenderEntity(
                packageName = packageName,
                conversationKey = conversationKey,
                displayName = displayName,
                isGroup = isGroup,
                senderKey = senderKey,
                senderName = senderName,
                firstSeenAt = seenAt,
                lastSeenAt = seenAt,
            ),
        )

        suspend fun dismiss(
            packageName: String,
            conversationKey: String,
        ) = dao.delete(packageName, conversationKey)
    }
