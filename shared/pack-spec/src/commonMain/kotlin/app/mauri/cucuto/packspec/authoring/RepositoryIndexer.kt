package app.mauri.cucuto.packspec.authoring

import app.mauri.cucuto.packspec.BundledPack
import app.mauri.cucuto.packspec.CharacterPreview
import app.mauri.cucuto.packspec.Download
import app.mauri.cucuto.packspec.Limits
import app.mauri.cucuto.packspec.PackContent
import app.mauri.cucuto.packspec.PackEntry
import app.mauri.cucuto.packspec.PackPaths
import app.mauri.cucuto.packspec.RepositoryEntry
import app.mauri.cucuto.packspec.RepositoryIndex
import app.mauri.cucuto.packspec.SemVer
import app.mauri.cucuto.packspec.SpecJson

/**
 * How a pack repository folder is laid out (`packer index`, and the website's "Host your own repository" helper, which
 * runs this code in the browser):
 *
 * ```
 * repository.json
 * previews/<id>/cover.<ext>, <character>.<ext>, <character>-sample.<ext>   (bundles: previews/<bundle>/<pack>/…)
 * packs/<file>.soundpack.zip                                                (unless the downloads live elsewhere)
 * ```
 *
 * Preview and download URLs are relative, so the folder can be hosted anywhere (they resolve against the index URL,
 * docs/pack-format.md#pack-repositories). Sizes and SHA-256 hashes are computed by the caller (platform code).
 */
object RepositoryIndexer {
    /** A preview file to copy: [archivePath] inside the pack or bundle archive, [path] in the repository folder. */
    data class Preview(
        val archivePath: String,
        val path: String,
    )

    /** One index entry and the preview files it refers to. */
    class Indexed(
        val entry: RepositoryEntry,
        val previews: List<Preview>,
    )

    /** The download URL of [fileName]: under `packs/` in the folder, or under [baseUrl] when the files live elsewhere. */
    fun downloadUrl(
        fileName: String,
        baseUrl: String?,
    ): String = if (baseUrl.isNullOrBlank()) "$PACKS_FOLDER/$fileName" else baseUrl.trim().trimEnd('/') + "/" + fileName

    /**
     * The entry for a validated pack or bundle ([content]), with [sizeUnpacked] (the sum of the archive's entry sizes)
     * and its [download].
     */
    fun entry(
        content: PackContent,
        sizeUnpacked: Long,
        download: Download,
    ): Indexed {
        val previews = mutableListOf<Preview>()

        fun copy(
            archivePath: String,
            previewPath: String,
        ): String {
            if (previews.none { it.path == previewPath }) previews += Preview(archivePath, previewPath)
            return previewPath
        }

        fun ext(p: String) = p.substringAfterLast('.')

        fun previews(
            pack: PackEntry,
            prefix: String,
        ): Pair<String?, List<CharacterPreview>> {
            val m = pack.manifest
            val cover = m.cover?.let { copy(PackPaths.join(pack.root, it), "$prefix/cover.${ext(it)}") }
            val characters =
                m.characters.map { c ->
                    CharacterPreview(
                        id = c.id,
                        name = c.name,
                        sprite = copy(PackPaths.join(pack.root, c.sprite), "$prefix/${c.id}.${ext(c.sprite)}"),
                        sample =
                            c.clips.firstOrNull()?.let {
                                copy(PackPaths.join(pack.root, it.file), "$prefix/${c.id}-sample.${ext(it.file)}")
                            },
                        seedColor = c.seedColor,
                    )
                }
            return cover to characters
        }

        val entry =
            when (content) {
                is PackContent.Single -> {
                    val m = content.pack.manifest
                    val (cover, characters) = previews(content.pack, "$PREVIEWS_FOLDER/${m.id}")
                    RepositoryEntry(
                        id = m.id,
                        type = "pack",
                        packFormatVersion = m.formatVersion,
                        name = m.name,
                        version = m.version,
                        author = m.author,
                        license = m.license,
                        description = m.description,
                        tags = m.tags,
                        cover = cover ?: characters.firstOrNull()?.sprite,
                        characters = characters,
                        sizeUnpacked = sizeUnpacked,
                        download = download,
                    )
                }

                is PackContent.Bundle -> {
                    val b = content.manifest
                    val packs =
                        content.packs.map { p ->
                            val (cover, characters) = previews(p, "$PREVIEWS_FOLDER/${b.id}/${p.manifest.id}")
                            BundledPack(
                                p.manifest.id,
                                p.manifest.name,
                                p.manifest.version,
                                p.manifest.license,
                                cover,
                                characters,
                            )
                        }
                    val cover =
                        b.cover?.let { copy(it, "$PREVIEWS_FOLDER/${b.id}/cover.${ext(it)}") }
                            ?: packs.firstNotNullOfOrNull { it.cover }
                    RepositoryEntry(
                        id = b.id,
                        type = "bundle",
                        packFormatVersion = b.formatVersion,
                        name = b.name,
                        version = b.version,
                        author = b.author,
                        license = b.license,
                        description = b.description,
                        tags = b.tags,
                        cover = cover,
                        packs = packs,
                        sizeUnpacked = sizeUnpacked,
                        download = download,
                    )
                }
            }
        return Indexed(entry, previews)
    }

    /**
     * The entries an index lists: one per ID, the highest version when an ID appears more than once (an index lists
     * only the latest version of each pack), sorted by ID.
     */
    fun latest(entries: List<RepositoryEntry>): List<RepositoryEntry> {
        val byId = linkedMapOf<String, RepositoryEntry>()
        for (entry in entries) {
            val existing = byId[entry.id]
            if (existing == null || newer(entry.version, existing.version)) byId[entry.id] = entry
        }
        return byId.values.sortedBy { it.id }
    }

    /** The index of [entries] ([latest] of them). Blank texts are left out. */
    fun index(
        name: String,
        description: String?,
        homepage: String?,
        generatedAt: String?,
        entries: List<RepositoryEntry>,
    ): RepositoryIndex =
        RepositoryIndex(
            formatVersion = Limits.FORMAT_VERSION,
            name = name.trim(),
            description = description?.trim()?.takeIf { it.isNotEmpty() },
            homepage = homepage?.trim()?.takeIf { it.isNotEmpty() },
            generatedAt = generatedAt?.trim()?.takeIf { it.isNotEmpty() },
            packs = latest(entries),
        )

    /** `repository.json` as written by every tool: pretty-printed UTF-8, newline at the end. */
    fun json(index: RepositoryIndex): String = SpecJson.encodeToString(RepositoryIndex.serializer(), index) + "\n"

    private fun newer(
        candidate: String,
        current: String,
    ): Boolean {
        val a = SemVer.parse(candidate) ?: return false
        val b = SemVer.parse(current) ?: return true
        return a > b
    }

    const val INDEX_FILE = "repository.json"
    const val PACKS_FOLDER = "packs"
    const val PREVIEWS_FOLDER = "previews"
}
