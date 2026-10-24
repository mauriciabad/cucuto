package app.mauri.cucuto.smoke

import android.content.Intent
import androidx.compose.ui.test.hasText
import app.mauri.cucuto.R
import app.mauri.cucuto.core.data.db.CucutoDatabase
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import javax.inject.Inject

/** A full backup (with the packs) brings a voice back after everything is wiped, as on a new phone. */
@HiltAndroidTest
class BackupSmokeTest : SmokeTest() {
    @Inject lateinit var database: CucutoDatabase

    @Test
    fun exportWipeAndRestore() {
        installDemoPack(CHIPTUNE)
        messageFrom(ANA)
        startApp()
        giveAnaAVoice(FROG)
        tap(R.string.ringtone_picker_skip)
        // The first voice is celebrated once; "Nice" closes the card.
        waitForText(R.string.home_journey_done_title, ANA)
        tap(R.string.home_journey_done_dismiss)
        waitForAnaOnHome()

        // Settings → Back up to a file, with the packs; the save dialog answers with a file the test owns.
        val backup = File(checkNotNull(context.getExternalFilesDir("smoke")), "cucuto-backup-smoke.zip")
        answerFilePicker(Intent.ACTION_CREATE_DOCUMENT, backup)
        tap(R.string.tab_settings)
        scrollListTo(hasText(str(R.string.settings_backup_now)))
        tap(R.string.settings_backup_now)
        waitForText(R.string.export_title)
        tap(R.string.export_packs)
        tap(R.string.export_choose_place)
        compose.waitUntil(TIMEOUT_MS) { backup.length() > 0 }
        waitForGone(hasText(str(R.string.settings_backup_busy)))

        // Wipe: no packs, no chats, no voices (the database a new phone starts with).
        runBlocking {
            library.installed.first().forEach { library.remove(it.id, clearAssignments = true) }
            database.clearAllTables()
        }
        waitForPublishedTable { it.routes.isEmpty() }
        tap(R.string.tab_home)
        waitForText(R.string.home_journey_import_title)

        // Restore from Home's first-run card, the place a new phone looks first.
        answerFilePicker(Intent.ACTION_OPEN_DOCUMENT, backup)
        tap(R.string.home_journey_restore)
        waitForText(R.string.restore_pack_installed)
        tap(R.string.restore_packs_continue)
        waitForText(R.string.restore_done_title)
        tap(R.string.restore_finish)

        // The chat has its voice again, and the module's table routes it.
        waitForAnaOnHome()
        waitForAnaRoutedTo("frog")
    }
}
