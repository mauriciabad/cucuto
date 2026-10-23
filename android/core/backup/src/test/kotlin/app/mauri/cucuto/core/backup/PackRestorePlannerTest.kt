package app.mauri.cucuto.core.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class PackRestorePlannerTest {
    private val sha = "a".repeat(64)

    private fun ref(
        id: String,
        source: BackupPackSource,
        type: BackupPackType = BackupPackType.PACK,
        packs: List<String>? = null,
    ) = BackupPackReference(id, type, "1.0.0", sha256 = sha, packs = packs, source = source)

    private val repo =
        BackupPackSource(BackupPackSourceType.REPOSITORY, repositoryUrl = "https://r.example/repository.json")
    private val url = BackupPackSource(BackupPackSourceType.URL, downloadUrl = "https://r.example/p.soundpack.zip")
    private val local = BackupPackSource(BackupPackSourceType.LOCAL, fileName = "p.soundpack.zip")

    @Test
    fun `each source maps to its action and installed packs need nothing`() {
        val refs =
            listOf(
                ref("com.example.installed", local),
                ref("com.example.repo", repo),
                ref("com.example.url", url),
                ref("com.example.local", local),
                ref("com.example.full", local),
            )
        val plan =
            PackRestorePlanner.plan(
                refs,
                installed = mapOf("com.example.installed" to null),
                included = listOf(refs[4]),
            )
        assertEquals(
            listOf(
                PackRestoreAction.INSTALLED,
                PackRestoreAction.DOWNLOAD_FROM_REPOSITORY,
                PackRestoreAction.DOWNLOAD_FROM_URL,
                PackRestoreAction.PICK_FILE,
                PackRestoreAction.FROM_BACKUP,
            ),
            plan.map { it.action },
        )
        assertEquals(listOf("com.example.repo"), plan[1].missingPackIds)
    }

    @Test
    fun `a bundle is restored until every selected pack is installed`() {
        val bundle =
            ref(
                "com.example.songs",
                local,
                BackupPackType.BUNDLE,
                listOf("com.example.songs.day", "com.example.songs.night"),
            )
        val partly =
            PackRestorePlanner
                .plan(
                    listOf(bundle),
                    mapOf("com.example.songs.day" to "com.example.songs"),
                ).single()
        assertEquals(PackRestoreAction.PICK_FILE, partly.action)
        assertEquals(listOf("com.example.songs.night"), partly.missingPackIds)

        val done =
            PackRestorePlanner.plan(
                listOf(bundle),
                mapOf("com.example.songs.day" to "com.example.songs", "com.example.songs.night" to "com.example.songs"),
            )
        assertEquals(PackRestoreAction.INSTALLED, done.single().action)

        val noList = bundle.copy(packs = null)
        assertEquals(PackRestoreAction.PICK_FILE, PackRestorePlanner.plan(listOf(noList), emptyMap()).single().action)
        assertEquals(
            PackRestoreAction.INSTALLED,
            PackRestorePlanner
                .plan(
                    listOf(noList),
                    mapOf("com.example.songs.day" to "com.example.songs"),
                ).single()
                .action,
        )
    }
}
