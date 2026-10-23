package app.mauri.cucuto.packs

import app.mauri.cucuto.R
import app.mauri.cucuto.core.pack.FailureReason
import app.mauri.cucuto.packspec.ErrorCode
import app.mauri.cucuto.packspec.Issue
import app.mauri.cucuto.packspec.ValidationReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportProblemsTest {
    @Test
    fun `every error code has its own plain-words sentence`() {
        val messages = ErrorCode.entries.map(::plainWords)
        assertEquals(ErrorCode.entries.size, messages.toSet().size)
    }

    @Test
    fun `every failure reason has its own plain-words sentence`() {
        val messages = FailureReason.entries.map(::plainWords)
        assertEquals(FailureReason.entries.size, messages.toSet().size)
    }

    @Test
    fun `a backup gets its own explanation instead of layout errors`() {
        val report = ValidationReport(listOf(Issue(ErrorCode.MANIFEST_MISSING, "No manifest")))
        val problem = problemFor(report, DetectedFile.BACKUP)
        assertEquals(R.string.import_problem_backup_title, problem.title)
        assertTrue(problem.items.isEmpty())
        assertTrue(problem.details.isEmpty())
    }

    @Test
    fun `a zip without a manifest is not a pack`() {
        val report = ValidationReport(listOf(Issue(ErrorCode.MANIFEST_MISSING, "No manifest")))
        val problem = problemFor(report)
        assertEquals(R.string.import_problem_not_pack_title, problem.title)
        assertEquals(R.string.import_problem_not_pack_summary, problem.summary)
        assertEquals(listOf("error MANIFEST_MISSING: No manifest"), problem.details)
    }

    @Test
    fun `a newer format asks to update the app`() {
        val report =
            ValidationReport(
                listOf(Issue(ErrorCode.FORMAT_VERSION_UNSUPPORTED, "formatVersion 3", "pack.json", "/formatVersion")),
            )
        assertEquals(R.string.import_problem_newer_title, problemFor(report).title)
    }

    @Test
    fun `errors are grouped by code with the first file and how many more`() {
        val report =
            ValidationReport(
                listOf(
                    Issue(ErrorCode.CLIP_DURATION_OUT_OF_RANGE, "6 s", "packs/a/cat/meow.ogg"),
                    Issue(ErrorCode.LICENSE_NOT_SPDX, "free text", "packs/a/pack.json"),
                    Issue(ErrorCode.IMAGE_TOO_LARGE, "1024 px", "packs/a/cat/sprite.png"),
                    Issue(ErrorCode.CLIP_DURATION_OUT_OF_RANGE, "5 s", "packs/b/dog/woof.ogg"),
                    Issue(ErrorCode.CLIP_DURATION_OUT_OF_RANGE, "5 s again", "packs/b/dog/woof.ogg"),
                ),
            )
        val problem = problemFor(report)
        assertEquals(R.string.import_problem_invalid_title, problem.title)
        assertEquals(
            listOf(
                ProblemItem(
                    ErrorCode.CLIP_DURATION_OUT_OF_RANGE,
                    R.string.error_clip_duration_out_of_range,
                    "packs/a/cat/meow.ogg",
                    moreFiles = 1,
                ),
                ProblemItem(ErrorCode.IMAGE_TOO_LARGE, R.string.error_image_too_large, "packs/a/cat/sprite.png", 0),
            ),
            problem.items,
        )
        assertEquals(0, problem.hiddenItems)
        // Warnings are not problems, but authors see every finding in the details.
        assertEquals(5, problem.details.size)
        assertTrue(problem.details.any { it.startsWith("warning LICENSE_NOT_SPDX") })
    }

    @Test
    fun `long lists are capped`() {
        val codes =
            listOf(
                ErrorCode.UNSAFE_PATH,
                ErrorCode.EXECUTABLE_FILE,
                ErrorCode.UNKNOWN_FILE_TYPE,
                ErrorCode.FILE_TOO_LARGE,
                ErrorCode.FILE_MISSING,
                ErrorCode.HAPTIC_INVALID,
                ErrorCode.SVG_UNSAFE,
            )
        val problem = problemFor(ValidationReport(codes.map { Issue(it, it.name) }))
        assertEquals(ImportProblem.MAX_ITEMS, problem.items.size)
        assertEquals(codes.size - ImportProblem.MAX_ITEMS, problem.hiddenItems)
        assertNull(problem.items.first().file)
    }

    @Test
    fun `failures explain the reason and keep the message for details`() {
        val problem = problemFor(FailureReason.NOT_ENOUGH_STORAGE, "Needs 10 bytes")
        assertEquals(R.string.import_problem_failed_title, problem.title)
        assertEquals(R.string.import_failure_not_enough_storage, problem.summary)
        assertEquals(listOf("NOT_ENOUGH_STORAGE: Needs 10 bytes"), problem.details)
    }

    @Test
    fun `quiet warnings are not shown in the review`() {
        val warnings =
            listOf(
                Issue(ErrorCode.IGNORED_ENTRY, ".DS_Store", ".DS_Store"),
                Issue(ErrorCode.UNREFERENCED_FILE, "unused", "extra.png"),
                Issue(ErrorCode.LARGE_PACK, "300 MiB"),
            )
        assertEquals(listOf(ErrorCode.LARGE_PACK), warningItems(warnings).map { it.code })
    }

    @Test
    fun `seed colors parse only the schema's form`() {
        assertEquals(0xFF4CAF50.toInt(), parseSeedColor("#4CAF50"))
        assertNull(parseSeedColor("4CAF50"))
        assertNull(parseSeedColor("#4CAF5"))
        assertNull(parseSeedColor("#GGGGGG"))
        assertNull(parseSeedColor(null))
    }
}
