package app.mauri.cucuto.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

/** Providers disagree on write modes (ADR 0040): the overwrite falls back until one accepts. */
class DocumentOverwriteTest {
    @Test
    fun `the documented truncating mode comes first`() {
        val tried = mutableListOf<String>()
        val (mode, opened) =
            DocumentOverwrite.openFirst {
                tried += it
                "fd:$it"
            }
        assertEquals("wt", mode)
        assertEquals("fd:wt", opened)
        assertEquals(listOf("wt"), tried)
    }

    @Test
    fun `a provider that refuses wt, like Google Drive, gets rwt`() {
        val (mode, _) =
            DocumentOverwrite.openFirst {
                if (it == "wt") throw FileNotFoundException("Unsupported mode: wt") else "fd"
            }
        assertEquals("rwt", mode)
    }

    @Test
    fun `plain w is the last resort`() {
        val (mode, _) =
            DocumentOverwrite.openFirst {
                when (it) {
                    "wt" -> throw IllegalArgumentException("Bad mode")
                    "rwt" -> throw UnsupportedOperationException("No reading")
                    else -> "fd"
                }
            }
        assertEquals("w", mode)
    }

    @Test
    fun `no accepted mode is a plain write error`() {
        try {
            DocumentOverwrite.openFirst<String> { null }
            fail("Expected an IOException")
        } catch (e: IOException) {
            assertEquals("The backup file can't be written", e.message)
        }
    }

    @Test
    fun `a missing permission is not retried`() {
        val tried = mutableListOf<String>()
        try {
            DocumentOverwrite.openFirst<String> {
                tried += it
                throw SecurityException("Permission revoked")
            }
            fail("Expected a SecurityException")
        } catch (e: SecurityException) {
            assertEquals(listOf("wt"), tried)
        }
    }
}
