package app.mauri.cucuto.core.repo

import okhttp3.HttpUrl.Companion.toHttpUrl
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

    @Test
    fun googleDriveFileLinksBecomeDirectDownloads() {
        val id = "1AbCdEfGhIjKlMnOpQrStUv-wx_yz"
        val download = "https://drive.usercontent.google.com/download?id=$id&export=download&confirm=t"
        for (shared in listOf(
            "https://drive.google.com/file/d/$id/view?usp=sharing",
            "drive.google.com/file/d/$id/view",
            "https://drive.google.com/file/d/$id",
            "https://drive.google.com/open?id=$id",
            "https://drive.google.com/uc?export=download&id=$id",
            "https://docs.google.com/uc?id=$id",
            "https://drive.usercontent.google.com/download?id=$id&export=download",
        )) {
            val link = (RepositoryLinks.parse(shared) as LinkResult.Ok).link
            assertEquals(shared, RepositoryLink.DirectPack::class, link::class)
            assertEquals(shared, download, link.url.toString())
            assertEquals("Google Drive", (link as RepositoryLink.DirectPack).fileName)
        }
        val deep = RepositoryLinks.parseDeepLink(RepositoryLinks.deepLinkFor(download.toHttpUrl())) as LinkResult.Ok
        assertEquals(download, deep.link.url.toString())
    }

    @Test
    fun googleDriveFolderLinksAreExplained() {
        assertEquals(
            LinkError.DRIVE_FOLDER,
            error("https://drive.google.com/drive/folders/1AbCdEfGhIjKlMnOp?usp=sharing"),
        )
        assertEquals(LinkError.DRIVE_FOLDER, error("https://drive.google.com/drive/u/0/folders/1AbCdEfGhIjKlMnOp"))
        // Other Drive pages are not links to a file: the usual folder rule applies.
        assertEquals(
            "https://drive.google.com/drive/my-drive/repository.json",
            index("https://drive.google.com/drive/my-drive"),
        )
        assertEquals("https://drive.google.com/open/repository.json?id=x", index("https://drive.google.com/open?id=x"))
    }

    @Test
    fun directPacksAreNamedAfterTheirFile() {
        val link = (RepositoryLinks.parse("https://example.com/dl/cats.soundpack.zip") as LinkResult.Ok).link
        assertEquals("cats.soundpack.zip", (link as RepositoryLink.DirectPack).fileName)
    }
}
