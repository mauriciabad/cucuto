package app.mauri.cucuto.ui.discover

import app.mauri.cucuto.core.data.db.RepositoryAuthType
import app.mauri.cucuto.core.repo.DEFAULT_REPOSITORY_URL
import app.mauri.cucuto.core.repo.ExpectedFile
import app.mauri.cucuto.core.repo.LinkError
import app.mauri.cucuto.core.repo.LinkResult
import app.mauri.cucuto.core.repo.RepositoryCredentials
import app.mauri.cucuto.core.repo.RepositoryLink
import app.mauri.cucuto.core.repo.RepositorySnapshot
import app.mauri.cucuto.core.repo.ResolvedEntry
import app.mauri.cucuto.packspec.Download
import app.mauri.cucuto.packspec.FileFormat
import app.mauri.cucuto.packspec.RepositoryEntry
import app.mauri.cucuto.packspec.RepositoryIndex
import app.mauri.cucuto.packspec.SpecJson
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
        state = InstallState.NEW,
    )

    private fun installed(
        id: String,
        version: String,
    ) = InstalledRef(id, version, null, null)

    @Test
    fun `install state compares SemVer`() {
        val id = "app.example.cats"
        assertEquals(InstallState.NEW, installState(id, "1.2.0", emptyList()))
        assertEquals(InstallState.UPDATE, installState(id, "1.2.0", listOf(installed(id, "1.1.9"))))
        assertEquals(InstallState.INSTALLED, installState(id, "1.2.0", listOf(installed(id, "1.2.0"))))
        assertEquals(InstallState.INSTALLED, installState(id, "1.2.0", listOf(installed(id, "2.0.0"))))
        assertEquals(InstallState.UPDATE, installState(id, "1.2.0", listOf(installed(id, "1.2.0-beta.1"))))
        assertEquals(InstallState.NEW, installState(id, "1.2.0", listOf(installed("app.example.dogs", "1.2.0"))))
    }

    @Test
    fun `a bundle is installed when all its packs are, new while one is missing, else an update`() {
        val (new, update, done) = Triple(InstallState.NEW, InstallState.UPDATE, InstallState.INSTALLED)
        assertEquals(done, bundleState(listOf(done, done)))
        assertEquals(new, bundleState(listOf(done, new)))
        assertEquals(new, bundleState(listOf(update, new)))
        assertEquals(update, bundleState(listOf(done, update)))
    }

    private fun snapshot(vararg entries: String) =
        RepositorySnapshot(
            indexUrl = "https://a.example/repository.json".toHttpUrl(),
            baseUrl = "https://a.example/repository.json".toHttpUrl(),
            index = SpecJson.decodeFromString(RepositoryIndex.serializer(), indexJson("A", *entries)),
            warnings = emptyList(),
            etag = null,
        )

    /** The default repository's shape: the bundle first, then its two packs on their own, then a pack of its own. */
    private val demoIndex =
        snapshot(
            demoBundleEntry(),
            packEntry("app.mauri.demo.cats", "Cats", version = "1.1.0", tags = listOf("animals"), character = "Tabby"),
            packEntry("app.mauri.demo.bells", "Bells", tags = listOf("objects"), character = "Ding"),
            packEntry("app.example.dogs", "Dogs", tags = listOf("animals"), character = "Rex"),
        )

    @Test
    fun `a bundle is one card with its packs inside, and those packs are not cards of their own`() {
        val cards = cards(1, demoIndex, emptyList())

        assertEquals(listOf(DEMO_BUNDLE_ID, "app.example.dogs"), cards.map { it.id })
        val bundle = cards.first()
        assertTrue(bundle.isBundle)
        assertTrue(bundle.characters.isEmpty())
        assertEquals(listOf("Cats", "Bells"), bundle.members.map { it.name })
        // The rows keep the bundle's versions and previews, and link to the packs listed alone.
        assertEquals(listOf("1.0.0", "1.0.0"), bundle.members.map { it.version })
        assertEquals(listOf("Tabby"), bundle.members[0].characters.map { it.name })
        assertEquals(listOf("app.mauri.demo.cats", "app.mauri.demo.bells"), bundle.members.map { it.card?.id })
        assertEquals(
            "1.1.0",
            bundle.members[0]
                .card
                ?.entry
                ?.entry
                ?.version,
        )
    }

    @Test
    fun `install state is per pack inside a bundle`() {
        val cats = "app.mauri.demo.cats"
        val bells = "app.mauri.demo.bells"

        val none = cards(1, demoIndex, emptyList()).first()
        assertEquals(listOf(InstallState.NEW, InstallState.NEW), none.members.map { it.state })
        assertEquals(InstallState.NEW, none.state)
        assertEquals(0, none.installedMembers)

        // Cats was installed on its own: the bundle knows, though it was not installed from the bundle.
        val some = cards(1, demoIndex, listOf(installed(cats, "1.0.0"))).first()
        // Cats 1.0.0 is what the bundle has, but the pack listed alone is 1.1.0: the row offers that update.
        assertEquals(listOf(InstallState.UPDATE, InstallState.NEW), some.members.map { it.state })
        assertEquals(InstallState.NEW, some.state)
        assertEquals(1, some.installedMembers)

        val all = cards(1, demoIndex, listOf(installed(cats, "1.0.0"), installed(bells, "1.0.0"))).first()
        // Installing the bundle again would not update Cats, so the bundle itself is installed.
        assertEquals(InstallState.INSTALLED, all.state)
        assertEquals(2, all.installedMembers)

        val older = cards(1, demoIndex, listOf(installed(cats, "0.9.0"), installed(bells, "1.0.0"))).first()
        assertEquals(InstallState.UPDATE, older.state)
    }

    @Test
    fun `a bundle without its packs listed alone has rows to look at but not to install`() {
        val cards = cards(1, snapshot(demoBundleEntry()), listOf(installed("app.mauri.demo.bells", "1.0.0")))
        val bundle = cards.single()
        assertEquals(listOf(null, null), bundle.members.map { it.card })
        assertEquals(listOf(InstallState.NEW, InstallState.INSTALLED), bundle.members.map { it.state })
    }

    @Test
    fun `search and tags find a bundle once through any of its packs`() {
        val sections =
            { query: String, tag: String? ->
                val repo =
                    SavedRepository(1, "https://a.example/repository.json", null, false, true, RepositoryAuthType.NONE)
                sections(listOf(repo), mapOf(1L to RepoLoad(snapshot = demoIndex)), emptyList(), query, tag).single()
            }
        // A character of a pack inside the bundle: one card, not the bundle and the pack.
        assertEquals(listOf(DEMO_BUNDLE_ID), sections("tabby", null).cards.map { it.id })
        assertEquals(listOf(DEMO_BUNDLE_ID), sections("bells", null).cards.map { it.id })
        assertEquals(listOf(DEMO_BUNDLE_ID), sections("cucuto", null).cards.map { it.id })
        // Tags come from the packs listed alone.
        assertEquals(listOf(DEMO_BUNDLE_ID), sections("", "objects").cards.map { it.id })
        assertEquals(listOf(DEMO_BUNDLE_ID, "app.example.dogs"), sections("", "animals").cards.map { it.id })
        assertEquals(listOf("app.example.dogs"), sections("rex", null).cards.map { it.id })
        // The count is of cards: the bundle and Dogs.
        assertEquals(2, sections("", null).totalCards)
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
