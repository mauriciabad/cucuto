package app.mauri.cucuto.core.creator

import app.mauri.cucuto.packspec.FileInput
import app.mauri.cucuto.packspec.JvmPackIo
import app.mauri.cucuto.packspec.PackManifest
import app.mauri.cucuto.packspec.PackValidator
import app.mauri.cucuto.packspec.ValidationReport
import app.mauri.cucuto.packspec.authoring.AssembledPack
import app.mauri.cucuto.packspec.authoring.Authoring
import app.mauri.cucuto.packspec.authoring.DraftProblem
import app.mauri.cucuto.packspec.authoring.PackAssembler
import app.mauri.cucuto.packspec.authoring.PackDraft
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The outcome of [PackWriter.write]. */
sealed interface WriteResult {
    /** The draft still needs something before it can become a pack. */
    data class Incomplete(
        val problems: List<DraftProblem>,
    ) : WriteResult

    /** The pack was written to [file]; [report] is the validator's verdict on that exact file. */
    data class Written(
        val file: File,
        val manifest: PackManifest,
        val report: ValidationReport,
    ) : WriteResult {
        val isValid: Boolean get() = report.isValid
    }
}

/**
 * Turns a [PackDraft] into a `*.soundpack.zip`: pack-spec's shared assembler lays it out (the same layout as the
 * website's creator and `packer build`), this writes the zip, and pack-spec's [PackValidator] checks the result with
 * every rule, media included, exactly as packer, the website and the importer do. Plain JVM: tested without a device.
 */
class PackWriter(
    private val validator: PackValidator = PackValidator(),
) {
    /** Assembles [draft], writes it to [out] (replaced) and validates the file. Blocking IO. */
    fun write(
        draft: PackDraft,
        out: File,
    ): WriteResult {
        val problems = PackAssembler.problems(draft)
        if (problems.isNotEmpty()) return WriteResult.Incomplete(problems)
        val pack = PackAssembler.assemble(draft)
        zip(pack, out)
        return WriteResult.Written(out, pack.manifest, validate(out))
    }

    /** Writes [pack] as a zip: `pack.json` first, then every file, deflated, with fixed timestamps. */
    fun zip(
        pack: AssembledPack,
        out: File,
    ) {
        out.parentFile?.mkdirs()
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.putNextEntry(entry(AssembledPack.MANIFEST))
            zip.write(pack.manifestJson.encodeToByteArray())
            zip.closeEntry()
            for (file in pack.files) {
                zip.putNextEntry(entry(file.path))
                File(file.key).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** Validates a pack file with every rule (pack-spec, the same code as packer and the website). */
    fun validate(file: File): ValidationReport =
        FileInput(file.toPath()).use { input -> validator.validateArchive(input, JvmPackIo.inflater).report }

    private fun entry(path: String) = ZipEntry(path).apply { time = FIXED_TIME }

    companion object {
        /** 1980-01-02, inside the zip format's date range: no clock time leaks into the file. */
        private const val FIXED_TIME = 315_619_200_000L

        /** The file name to suggest when exporting: `<pack name slug>.soundpack.zip`. */
        fun fileName(manifest: PackManifest): String =
            Authoring.slug(manifest.name).ifEmpty { manifest.id.substringAfterLast('.') } + ".soundpack.zip"
    }
}
