package app.mauri.cucuto.core.repo

import org.junit.Assert.assertEquals
import org.junit.Test

class RepositoryLinksTest {
    private fun index(input: String) =
        (RepositoryLinks.parse(input) as LinkResult.Ok)
            .link
            .also { assert(it is RepositoryLink.Index) }
            .url
            .toString()

    private fun error(input: String) = (RepositoryLinks.parse(input) as LinkResult.Error).error

    @Test
    fun folderUrlsGetRepositoryJson() {
        assertEquals("https://example.com/packs/repository.json", index("https://example.com/packs/"))
        assertEquals("https://example.com/packs/repository.json", index("https://example.com/packs"))
        assertEquals("https://example.com/repository.json", index("https://example.com"))
        assertEquals("https://example.com/repository.json", index("https://example.com/"))
        assertEquals("https://example.com/p/repository.json?key=1", index("https://example.com/p/?key=1"))
    }

    @Test
    fun jsonUrlsAreIndexes() {
        assertEquals(DEFAULT_REPOSITORY_URL, index(DEFAULT_REPOSITORY_URL))
        assertEquals("https://example.com/r/Repository.JSON", index("https://Example.com/r/Repository.JSON#top"))
        assertEquals(DEFAULT_REPOSITORY_URL, RepositoryLinks.default.url.toString())
    }

    @Test
    fun zipUrlsAreDirectPacks() {
        val link = (RepositoryLinks.parse("  https://example.com/dl/cats-1.0.0.soundpack.zip ") as LinkResult.Ok).link
        assertEquals(RepositoryLink.DirectPack::class, link::class)
        assertEquals("https://example.com/dl/cats-1.0.0.soundpack.zip", link.url.toString())
    }

    @Test
    fun missingSchemeMeansHttps() {
        assertEquals("https://cucuto.mauri.app/repo/repository.json", index("cucuto.mauri.app/repo/"))
    }

    @Test
    fun rejectsInsecureForeignAndCredentialUrls() {
        assertEquals(LinkError.NOT_HTTPS, error("http://example.com/repository.json"))
        assertEquals(LinkError.NOT_HTTPS, error("HTTP://example.com/"))
        assertEquals(LinkError.NOT_A_URL, error("ftp://example.com/repository.json"))
        assertEquals(LinkError.NOT_A_URL, error("file:///sdcard/repository.json"))
        assertEquals(LinkError.NOT_A_URL, error(""))
        assertEquals(LinkError.NOT_A_URL, error("https://exa mple.com/"))
        assertEquals(LinkError.CREDENTIALS_IN_URL, error("https://ana:secret@example.com/"))
    }

    @Test
    fun parsesDeepLinks() {
        val url = "https://example.com/packs/index.json?x=1&y=2"
        val deepLink =
            RepositoryLinks.deepLinkFor(
                okhttp3.HttpUrl
                    .Builder()
                    .scheme(
                        "https",
                    ).host("example.com")
                    .addPathSegments("packs/index.json")
                    .query("x=1&y=2")
                    .build(),
            )
        assertEquals("cucuto://repo/add?url=https%3A%2F%2Fexample.com%2Fpacks%2Findex.json%3Fx%3D1%26y%3D2", deepLink)
        val parsed = RepositoryLinks.parseDeepLink(deepLink) as LinkResult.Ok
        assertEquals(url, parsed.link.url.toString())

        val folder =
            RepositoryLinks.parseDeepLink(
                "cucuto://repo/add?utm=site&url=https%3A%2F%2Fexample.com%2Fp%2F",
            ) as LinkResult.Ok
        assertEquals("https://example.com/p/repository.json", folder.link.url.toString())

        val pack =
            RepositoryLinks.parseDeepLink(
                "CUCUTO://repo/add/?url=https://example.com/a+b.soundpack.zip",
            ) as LinkResult.Ok
        assertEquals("https://example.com/a+b.soundpack.zip", pack.link.url.toString())
    }

    @Test
    fun rejectsBadDeepLinks() {
        fun deepError(link: String) = (RepositoryLinks.parseDeepLink(link) as LinkResult.Error).error
        assertEquals(LinkError.NOT_A_DEEP_LINK, deepError("https://cucuto.mauri.app/repo/add?url=x"))
        assertEquals(LinkError.NOT_A_DEEP_LINK, deepError("cucuto://pack/add?url=https%3A%2F%2Fexample.com"))
        assertEquals(LinkError.NOT_A_DEEP_LINK, deepError("cucuto://repo/remove?url=https%3A%2F%2Fexample.com"))
        assertEquals(LinkError.NOT_A_DEEP_LINK, deepError("not a link"))
        assertEquals(LinkError.MISSING_URL, deepError("cucuto://repo/add"))
        assertEquals(LinkError.MISSING_URL, deepError("cucuto://repo/add?url="))
        assertEquals(
            LinkError.MISSING_URL,
            deepError("cucuto://repo/add?url=https%3A%2F%2Fa.com&url=https%3A%2F%2Fb.com"),
        )
        assertEquals(LinkError.NOT_HTTPS, deepError("cucuto://repo/add?url=http%3A%2F%2Fexample.com%2F"))
        assertEquals(
            LinkError.CREDENTIALS_IN_URL,
            deepError("cucuto://repo/add?url=https%3A%2F%2Fa%3Ab%40example.com%2F"),
        )
        assertEquals(LinkError.NOT_A_URL, deepError("cucuto://repo/add?url=javascript%3Aalert(1)"))
    }
}
