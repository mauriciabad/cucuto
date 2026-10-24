package app.mauri.cucuto.xposed.integration

import app.mauri.cucuto.core.data.db.InstalledPackEntity
import app.mauri.cucuto.core.data.db.PackSource
import app.mauri.cucuto.core.haptics.auto.AutoHapticFile
import app.mauri.cucuto.core.haptics.auto.AutoHapticSet
import app.mauri.cucuto.core.routing.Haptic
import java.io.File

/**
 * The CC0 demo packs of `packs/`, installed the way `PackStore` lays them out (`<root>/<pack id>/<version>/`, with
 * `pack.json` and every file it references), so the app's real `PackManifestCatalog` reads them.
 *
 * The manifests are the packer's output (`packer build packs/<pack>`), checked in under
 * `src/test/resources/integration/demo-packs/`. CI's `packs` job builds the packs and fails when a checked-in manifest
 * differs from the packer's, so they cannot drift from `pack.yaml`. The media are never copied into the repository
 * again: each file is read from `packs/` (the packer moves `<character>/…` to `characters/<character>/…`).
 */
internal object DemoPacks {
    const val CRITTERS = "app.mauri.demo.chiptune-critters"
    const val KITCHEN = "app.mauri.demo.kitchen-orchestra"

    /** Pack ID → source folder in `packs/`. */
    val SOURCES: Map<String, String> = mapOf(CRITTERS to "chiptune-critters", KITCHEN to "kitchen-orchestra")

    val packsDir: File by lazy {
        File(checkNotNull(System.getProperty("cucuto.packsDir")) { "Run through Gradle: cucuto.packsDir is unset" })
    }

    fun manifest(packId: String): String =
        checkNotNull(DemoPacks::class.java.getResource("/integration/demo-packs/$packId.json")) {
            "No checked-in manifest for $packId"
        }.readText()

    fun version(manifest: String): String =
        checkNotNull(Regex("\"version\": \"([^\"]+)\"").find(manifest)) { "No version" }.groupValues[1]

    /** Every file the manifest references (clips, ringtones, sprites, cover). */
    fun files(manifest: String): List<String> =
        Regex(
            "\"(?:file|sprite|spriteAnimated|cover)\": \"([^\"]+)\"",
        ).findAll(manifest).map { it.groupValues[1] }.toList()

    /** Where a packed file comes from in `packs/<source>/`. */
    fun sourceFile(
        packId: String,
        path: String,
    ): File = File(packsDir, "${SOURCES.getValue(packId)}/${path.removePrefix("characters/")}")

    /**
     * Installs [packId] under [root] and returns its database row. [autoHaptics] stands in for the clip analysis
     * (`AutoHapticAnalyzer` decodes with MediaCodec, which only exists on a device): clip path → envelope.
     */
    fun install(
        root: File,
        packId: String,
        autoHaptics: Map<String, Haptic.Custom?> = emptyMap(),
        manifest: String = manifest(packId),
        files: List<String> = files(manifest),
    ): InstalledPackEntity {
        val version = version(manifest)
        val dir = File(root, "$packId/$version")
        dir.mkdirs()
        File(dir, "pack.json").writeText(manifest)
        for (path in files) {
            val target = File(dir, path)
            target.parentFile.mkdirs()
            sourceFile(packId, path).copyTo(target, overwrite = true)
        }
        if (autoHaptics.isNotEmpty()) {
            AutoHapticFile.write(dir, AutoHapticSet(analyzerVersion = 1, 16, 20, autoHaptics))
        }
        return InstalledPackEntity(
            packId = packId,
            version = version,
            name = packId,
            path = dir.absolutePath,
            sha256 = "0".repeat(64),
            sizeBytes = dir.walk().filter { it.isFile }.sumOf { it.length() },
            source = PackSource.LOCAL,
            installedAt = 1,
        )
    }
}
