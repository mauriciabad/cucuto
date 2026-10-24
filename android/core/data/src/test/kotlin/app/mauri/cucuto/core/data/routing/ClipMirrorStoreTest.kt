package app.mauri.cucuto.core.data.routing

import app.mauri.cucuto.core.routing.Clip
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.RoutingTable
import app.mauri.cucuto.core.routing.Voice
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ClipMirrorStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var packDir: File
    private lateinit var root: File
    private lateinit var store: ClipMirrorStore

    @Before
    fun setUp() {
        packDir = folder.newFolder("packs", CRITTERS, "1.0.0")
        File(packDir, "characters/frog/clips").mkdirs()
        File(packDir, "characters/frog/clips/ribbit.ogg").writeBytes(byteArrayOf(1, 2, 3))
        File(packDir, "characters/frog/clips/croak.ogg").writeBytes(byteArrayOf(4, 5))
        root = File(folder.root, "media/clips")
        store = ClipMirrorStore(root)
        assertTrue(store.prepare())
    }

    private fun mirror(path: String) = store.mirrorClip(packDir, CRITTERS, "1.0.0", path)

    private fun mirrored(path: String) = File(root, "$CRITTERS/1.0.0/$path")

    @Test
    fun prepareCreatesTheFolderAndKeepsItOutOfTheMediaLibrary() {
        assertTrue(root.isDirectory)
        assertTrue(File(root, ".nomedia").isFile)
        assertTrue(store.prepare()) // idempotent
    }

    @Test
    fun copiesAClipAndReturnsItsFileUri() {
        val uri = mirror("characters/frog/clips/ribbit.ogg")
        val copy = mirrored("characters/frog/clips/ribbit.ogg")
        assertEquals("file://${copy.absolutePath}", uri)
        assertArrayEquals(byteArrayOf(1, 2, 3), copy.readBytes())
        // No temporary file is left next to it.
        assertEquals(listOf("ribbit.ogg"), copy.parentFile!!.list()!!.toList())
    }

    @Test
    fun recopiesWhenTheSourceChangesAndRepairsADeletedCopy() {
        mirror("characters/frog/clips/ribbit.ogg")
        val source = File(packDir, "characters/frog/clips/ribbit.ogg")
        source.writeBytes(byteArrayOf(9, 9, 9, 9))
        source.setLastModified(System.currentTimeMillis() + 60_000)
        mirror("characters/frog/clips/ribbit.ogg")
        assertArrayEquals(byteArrayOf(9, 9, 9, 9), mirrored("characters/frog/clips/ribbit.ogg").readBytes())

        // Deleted behind the app's back (a file manager): the next publication's retain forgets it was checked.
        mirrored("characters/frog/clips/ribbit.ogg").delete()
        store.retain(emptySet())
        assertTrue(mirror("characters/frog/clips/ribbit.ogg") != null)
        assertTrue(mirrored("characters/frog/clips/ribbit.ogg").isFile)
    }

    @Test
    fun replacesACopyWithTheSameSizeAndANewerDateButOtherBytes() {
        // Another app with all-files access plants (or edits) the copy before this process checks it.
        val copy = mirrored("characters/frog/clips/ribbit.ogg")
        copy.parentFile!!.mkdirs()
        copy.writeBytes(byteArrayOf(6, 6, 6))
        copy.setLastModified(System.currentTimeMillis() + 60_000)
        mirror("characters/frog/clips/ribbit.ogg")
        assertArrayEquals(byteArrayOf(1, 2, 3), copy.readBytes())
    }

    @Test
    fun keepsACopyThatAlreadyHasTheSourceBytes() {
        val copy = mirrored("characters/frog/clips/ribbit.ogg")
        copy.parentFile!!.mkdirs()
        copy.writeBytes(byteArrayOf(1, 2, 3))
        val stamp = System.currentTimeMillis() - 3_600_000
        copy.setLastModified(stamp)
        mirror("characters/frog/clips/ribbit.ogg")
        assertEquals(stamp / 1000, copy.lastModified() / 1000)
    }

    @Test
    fun refusesWhatItCannotMirrorSafely() {
        assertNull(mirror("characters/frog/clips/missing.ogg"))
        assertNull(mirror("../escape.ogg"))
        assertNull(mirror("characters//frog.ogg"))
        assertNull(store.mirrorClip(packDir, "../x", "1.0.0", "characters/frog/clips/ribbit.ogg"))
        assertNull(store.mirrorClip(packDir, CRITTERS, ".", "characters/frog/clips/ribbit.ogg"))
        assertNull(store.mirror(File(packDir, "characters/frog/clips/ribbit.ogg"), emptyList()))
        // A root whose path would need escaping in a URI is never used.
        val spaced = ClipMirrorStore(File(folder.root, "with space/clips"))
        assertTrue(spaced.prepare())
        assertNull(spaced.mirrorClip(packDir, CRITTERS, "1.0.0", "characters/frog/clips/ribbit.ogg"))
    }

    @Test
    fun mirrorsTheSelfTestChirpBesideThePacks() {
        val chirp = folder.newFile("chirp.wav").apply { writeBytes(byteArrayOf(7)) }
        val uri = store.mirror(chirp, ClipMirrorStore.SELF_TEST)
        assertEquals("file://${File(root, "selftest/chirp.wav").absolutePath}", uri)
    }

    @Test
    fun retainDeletesWhatIsNoLongerPlayableButKeepsFreshCopies() {
        val ribbit = mirror("characters/frog/clips/ribbit.ogg")!!
        val croak = mirror("characters/frog/clips/croak.ogg")!!
        File(root, "$CRITTERS/0.9.0/old.ogg").apply { parentFile!!.mkdirs() }.writeBytes(byteArrayOf(0))

        // Both were handed out since the last retain (a table still being built), so both stay.
        store.retain(setOf(ribbit))
        assertTrue(mirrored("characters/frog/clips/croak.ogg").isFile)
        assertFalse(File(root, "$CRITTERS/0.9.0").exists())

        // Next publication: croak is gone from the table and was not handed out again.
        store.retain(setOf(ribbit))
        assertFalse(mirrored("characters/frog/clips/croak.ogg").exists())
        assertTrue(mirrored("characters/frog/clips/ribbit.ogg").isFile)
        assertTrue(File(root, ".nomedia").isFile)
        assertTrue(croak.startsWith("file://"))

        // Nothing left to play: only the folder and its marker remain.
        store.retain(emptySet())
        assertEquals(listOf(".nomedia"), root.list()!!.toList())
    }

    @Test
    fun fileUrisCollectsMirroredClipsAndTheSelfTestOnly() {
        val table =
            RoutingTable(
                generation = 1,
                enabled = true,
                voices =
                    listOf(
                        Voice(
                            CRITTERS,
                            "frog",
                            listOf(Clip("file:///m/a.ogg"), Clip("content://app.mauri.cucuto.clips/p.q/c/b.ogg")),
                            Haptic.Keep,
                        ),
                    ),
                routes = emptyList(),
                selfTestClip = "file:///m/selftest/chirp.wav",
            )
        assertEquals(setOf("file:///m/a.ogg", "file:///m/selftest/chirp.wav"), ClipMirrorStore.fileUris(table))
    }
}
