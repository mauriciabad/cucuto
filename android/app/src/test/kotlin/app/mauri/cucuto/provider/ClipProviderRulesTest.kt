package app.mauri.cucuto.provider

import app.mauri.cucuto.diagnostics.SelfTestSound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class ClipAccessTest {
    @Test
    fun systemUidIsAllowedInEveryUser() {
        assertTrue(ClipAccess.isAllowedCaller(1000, null))
        assertTrue(ClipAccess.isAllowedCaller(10_001_000, null))
    }

    @Test
    fun systemUiIsAllowedByPackageWhateverItsUid() {
        assertTrue(ClipAccess.isAllowedCaller(10253, arrayOf("com.android.systemui")))
        assertTrue(ClipAccess.isAllowedCaller(10087, arrayOf("com.android.systemui", "com.android.shared")))
    }

    @Test
    fun everyoneElseIsRefused() {
        assertFalse(ClipAccess.isAllowedCaller(10300, arrayOf("com.whatsapp")))
        assertFalse(ClipAccess.isAllowedCaller(10301, arrayOf("app.mauri.cucuto")))
        assertFalse(ClipAccess.isAllowedCaller(10302, arrayOf("com.android.systemui.evil")))
        assertFalse(ClipAccess.isAllowedCaller(2000, arrayOf("com.android.shell")))
        assertFalse(ClipAccess.isAllowedCaller(0, null))
        assertFalse(ClipAccess.isAllowedCaller(10303, null))
        assertFalse(ClipAccess.isAllowedCaller(10304, emptyArray()))
        assertFalse(ClipAccess.isAllowedCaller(-1, arrayOf("com.android.systemui")))
    }

    @Test
    fun eventProviderAcceptsOnlyTheSystem() {
        assertTrue(ModuleEventProvider.isSystemCaller(1000))
        assertTrue(ModuleEventProvider.isSystemCaller(10_001_000))
        assertFalse(ModuleEventProvider.isSystemCaller(0))
        assertFalse(ModuleEventProvider.isSystemCaller(2000))
        assertFalse(ModuleEventProvider.isSystemCaller(10253))
        assertFalse(ModuleEventProvider.isSystemCaller(-1))
    }
}

class ClipPathsTest {
    @get:Rule val temp = TemporaryFolder()

    private val pack = "app.mauri.demo.critters"

    private fun parse(vararg segments: String) = ClipPaths.parse(segments.toList())

    @Test
    fun parsesPackClips() {
        assertEquals(
            ClipTarget.PackClip(pack, "frog", listOf("characters", "frog", "clips", "ribbit.ogg")),
            parse(pack, "frog", "characters", "frog", "clips", "ribbit.ogg"),
        )
        assertEquals(ClipTarget.SelfTest, parse("selftest", "chirp.wav"))
    }

    @Test
    fun refusesTraversalAndMalformedSegments() {
        assertNull(parse(pack, "frog", "..", "pack.ogg"))
        assertNull(parse(pack, "frog", ".", "a.ogg"))
        assertNull(parse(pack, "frog", "a/../../b.ogg")) // an encoded %2F decodes into one segment
        assertNull(parse(pack, "frog", "..\\b.ogg"))
        assertNull(parse(pack, "frog", ".hidden.ogg"))
        assertNull(parse(pack, "frog", "-dash.ogg"))
        assertNull(parse(pack, "frog", "a\u0000.ogg"))
        assertNull(parse(pack, "frog", ""))
        assertNull(parse("..", "frog", "a.ogg"))
        assertNull(parse("selftest", "frog", "a.ogg")) // not a pack ID (needs a dot)
        assertNull(parse("App.Mauri.X", "frog", "a.ogg"))
        assertNull(parse(pack, "Frog", "a.ogg"))
        assertNull(parse(pack, "frog"))
        assertNull(parse("selftest", "other.wav"))
        assertNull(parse(pack, "frog", "a".repeat(300) + ".ogg"))
    }

    @Test
    fun servesOnlyAudio() {
        assertNull(parse(pack, "frog", "pack.json"))
        assertNull(parse(pack, "frog", "sprite.png"))
        assertNull(parse(pack, "frog", "noextension"))
        for (ext in listOf("ogg", "opus", "oga", "mp3", "m4a", "aac", "flac", "wav", "OGG")) {
            assertNotNull(ext, parse(pack, "frog", "clip.$ext"))
        }
    }

    private fun install(
        version: String = "1.0.0",
        pointer: String? = version,
    ): File {
        val root = temp.newFolder("packs")
        val dir = File(root, "$pack/$version/clips").apply { mkdirs() }
        File(dir, "ribbit.ogg").writeText("ogg")
        if (pointer != null) File(root, "$pack/active").writeText("$pointer\n")
        return root
    }

    private fun clip(vararg path: String) = ClipTarget.PackClip(pack, "frog", path.toList())

    @Test
    fun resolvesAgainstTheActiveVersion() {
        val root = install()
        val file = ClipPaths.resolve(root, clip("clips", "ribbit.ogg"))
        assertEquals(File(root, "$pack/1.0.0/clips/ribbit.ogg").canonicalFile, file)
        assertNull(ClipPaths.resolve(root, clip("clips", "missing.ogg")))
        assertNull(ClipPaths.resolve(root, clip("clips"))) // a directory, not a file
    }

    @Test
    fun withoutAPointerOnlyASingleVersionIsServed() {
        val root = install(pointer = null)
        assertNotNull(ClipPaths.resolve(root, clip("clips", "ribbit.ogg")))
        File(root, "$pack/2.0.0").mkdirs()
        assertNull(ClipPaths.resolve(root, clip("clips", "ribbit.ogg")))
    }

    @Test
    fun aTamperedPointerCannotEscape() {
        val root = install(pointer = "../../etc")
        assertNull(ClipPaths.resolve(root, clip("clips", "ribbit.ogg")))
    }

    @Test
    fun symlinksOutOfThePackAreRefused() {
        val root = install()
        val secret = temp.newFile("secret.ogg").apply { writeText("private") }
        val link = File(root, "$pack/1.0.0/clips/link.ogg").toPath()
        Files.createSymbolicLink(link, secret.toPath())
        assertNull(ClipPaths.resolve(root, clip("clips", "link.ogg")))
    }

    @Test
    fun mimeTypesFollowTheExtension() {
        assertEquals("audio/ogg", ClipPaths.mimeType("a.ogg"))
        assertEquals("audio/mpeg", ClipPaths.mimeType("a.MP3"))
        assertEquals("audio/wav", ClipPaths.mimeType("chirp.wav"))
    }

    @Test
    fun selfTestSoundIsAValidWav() {
        val bytes = SelfTestSound.wav()
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals("WAVE", String(bytes, 8, 4))
        assertEquals(44 + 44_100 * 7 / 10 * 2, bytes.size)
        val file = SelfTestSound.ensureFile(temp.root)
        assertNotNull(file)
        assertEquals(bytes.size.toLong(), file!!.length())
    }
}
