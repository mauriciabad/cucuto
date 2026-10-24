package app.mauri.cucuto.contact

import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.home.ChatRow
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What Home already knows about the chat it opens (#360): Home [offer]s the tapped row, contact detail [take]s it as its
 * first state, so the header (title, shared avatar, ringtone badge, character colours) is drawn from the first frame of
 * the shared transition instead of after the database answers. In memory only and one-shot: one row at a time, gone
 * once taken; after process death, or from another screen, contact detail loads as before.
 */
@Singleton
class ContactDetailHandoff
    @Inject
    constructor() {
        private val pending = AtomicReference<ChatRow?>(null)

        fun offer(row: ChatRow) = pending.set(row)

        /** The row offered for [chat], once; null when none waits or it is for another chat. */
        fun take(chat: ChatKey): ChatRow? = pending.getAndSet(null)?.takeIf { chat in it.chat.keys }
    }
