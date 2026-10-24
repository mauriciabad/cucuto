package app.mauri.cucuto.core.data

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import app.mauri.cucuto.core.data.chats.WhatsAppContact
import app.mauri.cucuto.core.data.chats.WhatsAppContactsSource
import app.mauri.cucuto.core.data.db.CucutoDatabase
import app.mauri.cucuto.core.data.di.WallClock

/** An in-memory database on the platform SQLite (Robolectric's native SQLite in unit tests). */
fun inMemoryDatabase(): CucutoDatabase =
    Room
        .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CucutoDatabase::class.java)
        .setDriver(AndroidSQLiteDriver())
        .build()

/** A clock that only moves when told to. */
class FakeClock(
    var time: Long = 1_761_000_000_000,
) : WallClock {
    override fun now(): Long = time

    fun advance(ms: Long = 1000) {
        time += ms
    }
}

/** A phone where Cucuto may not read contacts. */
object NoContacts : WhatsAppContactsSource {
    override fun canRead(): Boolean = false

    override suspend fun load(): List<WhatsAppContact>? = null
}

/** A phone whose contacts hold these made-up WhatsApp entries; counts how often they were read. */
class FakeWhatsAppContacts(
    var entries: List<WhatsAppContact> = emptyList(),
    var granted: Boolean = true,
) : WhatsAppContactsSource {
    var loads = 0

    override fun canRead(): Boolean = granted

    override suspend fun load(): List<WhatsAppContact>? {
        if (!granted) return null
        loads++
        return entries
    }
}
