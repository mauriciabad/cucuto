package app.mauri.cucuto.backup

import app.mauri.cucuto.core.backup.BackupContact
import app.mauri.cucuto.core.backup.BackupContactKey
import app.mauri.cucuto.core.backup.BackupMessageAssignment
import app.mauri.cucuto.core.backup.BackupPackSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The owner asked not to keep identical automatic backups (#108): the fingerprint decides what "identical" is. */
class AutoBackupFingerprintTest {
    private val document =
        BackupTestData
            .document(packs = listOf(BackupTestData.pack(BackupTestData.CRITTERS, BackupPackSourceType.REPOSITORY)))
            .copy(
                contacts =
                    listOf(
                        BackupContact(
                            id = "1",
                            displayName = "Contact 1",
                            keys =
                                listOf(
                                    BackupContactKey("com.whatsapp", "1@lid"),
                                    BackupContactKey("com.whatsapp", "2@lid"),
                                ),
                            message = BackupMessageAssignment(BackupTestData.CRITTERS, "frog"),
                        ),
                    ),
            )

    @Test
    fun `the time, the app version and the key order are not changes`() {
        val later =
            document.copy(
                createdAt = "2026-10-25T10:00:00Z",
                app = document.app.copy(versionName = "0.2.0", versionCode = 2),
                contacts = document.contacts.map { it.copy(keys = it.keys.reversed()) },
            )
        assertEquals(
            AutoBackup.fingerprint(document, includePacks = true),
            AutoBackup.fingerprint(later, includePacks = true),
        )
    }

    @Test
    fun `a new voice, a new pack version or including packs are changes`() {
        val base = AutoBackup.fingerprint(document, includePacks = true)
        val newVoice =
            document.copy(
                contacts =
                    document.contacts.map {
                        it.copy(message = BackupMessageAssignment(BackupTestData.CRITTERS, "owl"))
                    },
            )
        val newPack = document.copy(packs = document.packs.map { it.copy(version = "1.1.0") })
        assertNotEquals(base, AutoBackup.fingerprint(newVoice, includePacks = true))
        assertNotEquals(base, AutoBackup.fingerprint(newPack, includePacks = true))
        assertNotEquals(base, AutoBackup.fingerprint(document, includePacks = false))
    }
}
