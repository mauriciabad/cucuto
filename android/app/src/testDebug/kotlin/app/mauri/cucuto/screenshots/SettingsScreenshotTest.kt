package app.mauri.cucuto.screenshots

import app.mauri.cucuto.core.data.settings.AutoBackupState
import app.mauri.cucuto.navigation.TopLevelTab
import app.mauri.cucuto.ui.settings.SettingsActions
import app.mauri.cucuto.ui.settings.SettingsContent
import app.mauri.cucuto.ui.settings.SettingsState
import org.junit.Test

/** Settings: voices on, the default feedback, and automatic backups to a folder. */
class SettingsScreenshotTest(
    variant: Variant,
) : ScreenshotTest(variant) {
    @Test
    fun settings() =
        screenshot("settings") {
            InShell(TopLevelTab.Settings) {
                SettingsContent(
                    state =
                        SettingsState(
                            autoBackup =
                                AutoBackupState(
                                    folderUri = BACKUP_FOLDER,
                                    folderName = "Backups",
                                    lastSuccessAt = LAST_BACKUP_AT,
                                ),
                        ),
                    systemRemovesAnimations = false,
                    actions = SettingsActions(),
                )
            }
        }

    private companion object {
        const val BACKUP_FOLDER = "content://com.android.externalstorage.documents/tree/primary%3ABackups"

        /** 24 October 2026, 9:30 in the screenshots' time zone: a fixed date, same on every run. */
        const val LAST_BACKUP_AT = 1_792_827_000_000L
    }
}
