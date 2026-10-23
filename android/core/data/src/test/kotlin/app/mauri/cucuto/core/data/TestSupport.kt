package app.mauri.cucuto.core.data

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
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
