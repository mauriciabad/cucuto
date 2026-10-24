package app.mauri.cucuto.ui.discover

import app.mauri.cucuto.core.data.db.RepositoryAuthType
import app.mauri.cucuto.core.repo.DEFAULT_REPOSITORY_URL
import app.mauri.cucuto.core.repo.ExpectedFile
import app.mauri.cucuto.core.repo.LinkError
import app.mauri.cucuto.core.repo.LinkResult
import app.mauri.cucuto.core.repo.RepositoryCredentials
import app.mauri.cucuto.core.repo.RepositoryLink
import app.mauri.cucuto.core.repo.ResolvedEntry
import app.mauri.cucuto.packspec.Download
import app.mauri.cucuto.packspec.FileFormat
import app.mauri.cucuto.packspec.RepositoryEntry
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverModelTest {
    private fun entry(
        id: String = "app.example.cats",
        type: String = "pack",
        version: String = "1.2.0",
        tags: List<String> = emptyList(),
    ) = ResolvedEntry(
        entry =
            RepositoryEntry(
                id = id,
                type = type,
                name = "Cats",
                version = version,
                author = "Ada",
                description = "Purrs and meows",
                tags = tags,
                download = Download("cats.soundpack.zip", 10, "0".repeat(64)),
            ),
        downloadUrl = "https://example.com/cats.soundpack.zip".toHttpUrl(),
        expected = ExpectedFile(10, "0".repeat(64)),
        coverUrl = null,
        supported = true,
    )

    private fun card(
        entry: ResolvedEntry = entry(),
        characters: List<String> = listOf("Tabby"),
    ) = PackCard(
        key = "1/${entry.entry.id}",
        repositoryId = 1,
        entry = entry,
        cover = null,
        characters = characters.map { RemoteCharacter("1/x/$it", it, null, null, null) },
        bundlePacks = emptyList(),
        state = InstallState.NEW,
    )

    @Test
    fun `install state compares SemVer, and bundles by bundle ID`() {
        val pack = entry()
        assertEquals(InstallState.NEW, installState(pack, emptyList()))
        assertEquals(InstallState.UPDATE, installState(pack, listOf(InstalledRef(pack.entry.id, "1.1.9", null, null))))
        assertEquals(
            InstallState.INSTALLED,
            installState(pack, listOf(InstalledRef(pack.entry.id, "1.2.0", null, null))),
        )
        assertEquals(
            InstallState.INSTALLED,
            installState(pack, listOf(InstalledRef(pack.entry.id, "2.0.0", null, null))),
        )
        assertEquals(
            InstallState.UPDATE,
            installState(pack, listOf(InstalledRef(pack.entry.id, "1.2.0-beta.1", null, null))),
        )

        val bundle = entry(id = "app.example.bundle", type = "bundle", version = "2.0.0")
        val fromBundle = InstalledRef("app.example.bundle.first", "1.0.0", "app.example.bundle", "1.0.0")
        assertEquals(InstallState.UPDATE, installState(bundle, listOf(fromBundle)))
        assertEquals(InstallState.INSTALLED, installState(bundle, listOf(fromBundle.copy(bundleVersion = "2.0.0"))))
        // A pack with the bundle's ID is not the bundle.
        assertEquals(
            InstallState.NEW,
            installState(bundle, listOf(InstalledRef("app.example.bundle", "2.0.0", null, null))),
        )
    }

    @Test
    fun `search matches every word in names, author, description, tags or characters`() {
        val card = card(entry(tags = listOf("Animals")))
        assertTrue(card.matches("", null))
        assertTrue(card.matches("  cats  ", null))
        assertTrue(card.matches("ada purr", null))
        assertTrue(card.matches("tabby", null))
        assertTrue(card.matches("anim", null))
        assertFalse(card.matches("cats dogs", null))
        assertTrue(card.matches("", "animals"))
        assertFalse(card.matches("", "objects"))
    }

    @Test
    fun `tags are counted across enabled repositories, case-insensitively`() {
        val on = SavedRepository(1, "https://a.example/repository.json", null, false, true, RepositoryAuthType.NONE)
        val off = on.copy(id = 2, enabled = false)

        fun load(vararg tags: List<String>) =
            RepoLoad(
                snapshot =
                    app.mauri.cucuto.core.repo.RepositorySnapshot(
                        indexUrl = on.url.toHttpUrl(),
                        baseUrl = on.url.toHttpUrl(),
                        index =
                            app.mauri.cucuto.packspec.RepositoryIndex(
                                formatVersion = 1,
                                name = "A",
                                packs = tags.mapIndexed { i, t -> entry(id = "app.example.p$i", tags = t).entry },
                            ),
                        warnings = emptyList(),
                        etag = null,
                    ),
            )
        val loads =
            mapOf(
                1L to load(listOf("pets"), listOf("Animals", "pets"), listOf("animals")),
                2L to load(listOf("hidden")),
            )
        assertEquals(listOf("Animals", "pets"), tagsOf(listOf(on, off), loads))
    }

    @Test
    fun `the form shows the host, warns for unknown sources and builds credentials`() {
        val default = RepositoryForm(input = DEFAULT_REPOSITORY_URL)
        assertEquals("cucuto.mauri.app", default.host)
        assertTrue(default.trusted)

        val other = RepositoryForm(input = "packs.example.com/friends/")
        assertEquals("packs.example.com", other.host)
        assertFalse(other.trusted)
        assertEquals(
            RepositoryLink.Index("https://packs.example.com/friends/repository.json".toHttpUrl()),
            other.link,
        )
        assertEquals(LinkResult.Error(LinkError.NOT_HTTPS), RepositoryForm(input = "http://x.example/").parsed)
        assertNull(RepositoryForm().parsed)

        assertNull(other.credentials())
        assertEquals(
            RepositoryCredentials.Basic("ana", "pw"),
            other.copy(auth = AuthKind.BASIC, username = " ana ", password = "pw").credentials(),
        )
        assertEquals(RepositoryCredentials.Bearer("t"), other.copy(auth = AuthKind.BEARER, token = " t ").credentials())
        assertThrows(IllegalArgumentException::class.java) {
            other.copy(auth = AuthKind.BEARER, token = "has space").credentials()
        }
        // Secrets never show up in logs.
        val secret = other.copy(auth = AuthKind.BASIC, username = "ana", password = "s3cret").toString()
        assertFalse(secret.contains("s3cret"))
        assertFalse(secret.contains("ana"))
    }

    @Test
    fun `previews must follow the pack media rules`() {
        assertEquals(FileFormat.PNG, PreviewStore.previewFormat("https://e.example/a/cover.png".toHttpUrl()))
        assertNull(PreviewStore.previewFormat("https://e.example/a/cover.html".toHttpUrl()))
        assertNull(PreviewStore.previewFormat("https://e.example/a/pack.json".toHttpUrl()))

        assertTrue(PreviewStore.isAcceptable(FileFormat.PNG, pngBytes))
        assertFalse(PreviewStore.isAcceptable(FileFormat.PNG, "<html/>".encodeToByteArray()))
        val huge = pngBytes.copyOf().also { it[18] = 0x10 } // 4160 px wide
        assertFalse(PreviewStore.isAcceptable(FileFormat.PNG, huge))
        val safeSvg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1 1"><rect width="1" height="1"/></svg>"""
        assertTrue(PreviewStore.isAcceptable(FileFormat.SVG, safeSvg.encodeToByteArray()))
        val scriptSvg = """<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>"""
        assertFalse(PreviewStore.isAcceptable(FileFormat.SVG, scriptSvg.encodeToByteArray()))
    }
}
