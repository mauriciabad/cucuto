package app.mauri.cucuto.backup

import app.mauri.cucuto.core.backup.BackupAndroidContact
import app.mauri.cucuto.core.backup.BackupRingtoneUndo
import app.mauri.cucuto.core.backup.RingtoneOutcome
import app.mauri.cucuto.core.ringtone.restore.RestoredLink
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which restored contacts look for their Android contact on this phone (ADR 0013's amendment for #43). */
class RestoredLinksTest {
    @Test
    fun `every Android link from the backup and every phone ringtone written, one entry per contact`() {
        val report =
            BackupTestData.report(
                applied = listOf("1", "2", "3", "4"),
                linked = setOf("1", "2"),
                ringtones =
                    listOf(
                        BackupTestData
                            .ringtone("2")
                            .copy(
                                androidContact = BackupAndroidContact("0r2-OLD", "Bruno L."),
                                undo = BackupRingtoneUndo(originalRingtone = "content://o", writtenUri = "content://w"),
                            ),
                        BackupTestData.ringtone("3"),
                        // This phone's own ringtone: its link is this phone's too.
                        BackupTestData.ringtone("4", RingtoneOutcome.KEPT),
                    ),
            )
        assertEquals(
            listOf(
                RestoredLink(1, "Contact 1", androidName = "Contact 1", ringtone = false),
                RestoredLink(
                    2,
                    "Contact 2",
                    androidName = "Bruno L.",
                    ringtone = true,
                    originalRingtone = "content://o",
                    writtenUri = "content://w",
                ),
                RestoredLink(3, "Contact 3", androidName = null, ringtone = true),
            ),
            restoredLinks(listOf(report)),
        )
    }

    @Test
    fun `with the overwrite run, a ringtone kept in the first run and written in the second is matched`() {
        val first =
            BackupTestData.report(
                applied = listOf("1"),
                linked = setOf("1"),
                ringtones = listOf(BackupTestData.ringtone("1", RingtoneOutcome.KEPT)),
            )
        val second = BackupTestData.report(applied = listOf("1"), ringtones = listOf(BackupTestData.ringtone("1")))
        assertEquals(
            listOf(RestoredLink(1, "Contact 1", androidName = "Contact 1", ringtone = true)),
            restoredLinks(listOf(first, second)),
        )
    }
}
