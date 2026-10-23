package app.mauri.cucuto.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class BackupFileNamesTest {
    @Test
    fun `names use the local date and end in zip`() {
        assertEquals("cucuto-backup-2026-10-24.zip", BackupFileNames.forDate(LocalDate.of(2026, 10, 24)))
        assertEquals(LocalDate.of(2026, 1, 2), BackupFileNames.dateOf("cucuto-backup-2026-01-02.zip"))
        assertEquals(LocalDate.of(2026, 1, 2), BackupFileNames.dateOf("cucuto-backup-2026-01-02 (3).zip"))
        assertNull(BackupFileNames.dateOf("cucuto-backup-2026-13-02.zip"))
        assertNull(BackupFileNames.dateOf("cucuto-backup-2026-01-02.cucuto-backup"))
        assertNull(BackupFileNames.dateOf("songs.soundpack.zip"))
    }

    @Test
    fun `pruning keeps the newest backups and ignores other files`() {
        val days = (1..12).map { "cucuto-backup-2026-10-${"%02d".format(it)}.zip" }
        val names = days.shuffled(kotlin.random.Random(7)) + listOf("notes.txt", "cucuto-backup-2026-10-12 (1).zip")
        val pruned = BackupFileNames.toPrune(names, keep = 10)
        assertEquals(listOf(days[2], days[1], days[0]), pruned)
        assertEquals(emptyList<String>(), BackupFileNames.toPrune(days.take(3)))
    }
}
