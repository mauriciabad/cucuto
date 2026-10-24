package app.mauri.cucuto.ui.discover

import app.mauri.cucuto.core.repo.DEFAULT_REPOSITORY_URL
import app.mauri.cucuto.core.repo.LinkError
import app.mauri.cucuto.core.repo.LinkResult
import app.mauri.cucuto.core.repo.RepoError
import app.mauri.cucuto.core.repo.RepositoryCredentials
import app.mauri.cucuto.core.repo.RepositoryLink
import app.mauri.cucuto.core.repo.RepositoryLinks
import app.mauri.cucuto.core.repo.RepositorySnapshot
import app.mauri.cucuto.core.repo.ResolvedEntry
import app.mauri.cucuto.packs.ImportUi
import app.mauri.cucuto.packs.PacksMessage
import app.mauri.cucuto.packs.parseSeedColor
import app.mauri.cucuto.packspec.CharacterPreview
import app.mauri.cucuto.packspec.SemVer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Everything the Discover screen shows. */
data class DiscoverUiState(
    /** One section per enabled repository; null while the saved repositories load. */
    val sections: List<RepositorySection>? = null,
    /** Every saved repository, enabled or not, for the Repositories screen. */
    val repositories: List<SavedRepository> = emptyList(),
    val query: String = "",
    val tag: String? = null,
    /** Tags of every loaded entry, most used first. */
    val tags: List<String> = emptyList(),
    val import: ImportUi = ImportUi.Idle,
    val form: RepositoryForm? = null,
    val message: DiscoverMessage? = null,
    /** The one entry ID shown, when Home asked for the demo packs; cleared with the other filters. */
    val focus: String? = null,
    /** The demo packs Home asked for (#179), or null. */
    val demo: DemoPacksUi? = null,
) {
    /** True while any repository is being fetched (pull to refresh). */
    val refreshing: Boolean get() = sections.orEmpty().any { it.loading }

    /** True when a search or tag filter hides entries. */
    val filtering: Boolean get() = query.isNotBlank() || tag != null || focus != null
}

/** One repository: where it is, how its last fetch went, and its entries that match the filters. */
data class RepositorySection(
    val repository: SavedRepository,
    /** The host, always shown so users see where packs come from. */
    val host: String,
    /** The index's name, or the host until the first fetch. */
    val title: String,
    val loading: Boolean,
    /** Why the last fetch failed. With [stale] the entries are the last copy that worked. */
    val error: RepoError?,
    /** True when an index (fresh or cached) is shown. */
    val loaded: Boolean,
    val cards: List<PackCard>,
    /** Entries before filtering. */
    val totalCards: Int,
) {
    val stale: Boolean get() = loaded && error != null
    val needsSignIn: Boolean get() = error is RepoError.Unauthorized
}

/** Whether an entry is on the phone already. */
enum class InstallState {
    NEW,

    /** The same version, or a newer one, is installed. */
    INSTALLED,

    /** An older version is installed. */
    UPDATE,
}

/** One repository entry (a pack or a bundle) as a card. */
data class PackCard(
    /** Unique on the screen: repository ID and entry ID. */
    val key: String,
    val repositoryId: Long,
    val entry: ResolvedEntry,
    val cover: HttpUrl?,
    /** The pack's characters, or every character of a bundle's packs. */
    val characters: List<RemoteCharacter>,
    /** Names of a bundle's packs; empty for a pack. */
    val bundlePacks: List<String>,
    val state: InstallState,
) {
    val id: String get() = entry.entry.id
    val name: String get() = entry.entry.name
    val isBundle: Boolean get() = entry.entry.type == TYPE_BUNDLE

    companion object {
        const val TYPE_BUNDLE = "bundle"
    }
}

/** A character preview: picture and sample clip are fetched only when shown or tapped. */
data class RemoteCharacter(
    /** Unique on the screen, also the clip player's key. */
    val key: String,
    val name: String,
    val sprite: HttpUrl?,
    val sample: HttpUrl?,
    /** `seedColor` as ARGB, or null. */
    val seedColor: Int?,
)

/** One-off results for a snackbar. Cleared with [DiscoverViewModel.messageShown]. */
sealed interface DiscoverMessage {
    data class Installed(
        val installed: PacksMessage.Installed,
    ) : DiscoverMessage

    data class Added(
        val name: String,
    ) : DiscoverMessage

    data class SignedIn(
        val name: String,
    ) : DiscoverMessage

    data class Removed(
        val name: String,
    ) : DiscoverMessage

    /** Already in the list (and turned back on if it was off). */
    data class AlreadyAdded(
        val name: String,
    ) : DiscoverMessage

    /** A `cucuto://` link that is not a valid "Add to app" link. */
    data object BadLink : DiscoverMessage
}

/** How a repository signs in (brief, "Pack repositories": HTTP Basic or a bearer token). */
enum class AuthKind { NONE, BASIC, BEARER }

/**
 * The "add a repository" form, or the sign-in form of a saved repository ([repositoryId] set, URL fixed). The URL is
 * parsed as the user types so the form can show the host and the unknown-source warning before anything is fetched.
 * Secrets live only here, in memory, and [toString] never shows them.
 */
data class RepositoryForm(
    val repositoryId: Long? = null,
    val input: String = "",
    /** Opened from an "Add to app" link rather than typed. */
    val fromLink: Boolean = false,
    val auth: AuthKind = AuthKind.NONE,
    val username: String = "",
    val password: String = "",
    val token: String = "",
    val checking: Boolean = false,
    val error: FormError? = null,
) {
    val parsed: LinkResult? get() = input.takeIf { it.isNotBlank() }?.let(RepositoryLinks::parse)

    val link: RepositoryLink? get() = (parsed as? LinkResult.Ok)?.link

    /** The host the link points at, shown in the form. */
    val host: String? get() = link?.url?.host

    /** False for anything but the project website: the form shows the unknown-source warning. */
    val trusted: Boolean get() = host == DEFAULT_HOST

    /** The credentials as typed. Throws [IllegalArgumentException] when they are not valid header values. */
    fun credentials(): RepositoryCredentials? =
        when (auth) {
            AuthKind.NONE -> null
            AuthKind.BASIC -> RepositoryCredentials.Basic(username.trim(), password)
            AuthKind.BEARER -> RepositoryCredentials.Bearer(token.trim())
        }

    override fun toString(): String =
        "RepositoryForm(repositoryId=$repositoryId, host=$host, auth=$auth, checking=$checking, error=$error, " +
            "secrets=${RepositoryCredentials.REDACTED})"

    companion object {
        val DEFAULT_HOST: String = DEFAULT_REPOSITORY_URL.toHttpUrl().host
    }
}

/** Why the form cannot go on. */
sealed interface FormError {
    data class Link(
        val error: LinkError,
    ) : FormError

    data class Fetch(
        val error: RepoError,
    ) : FormError

    /** A username with ":" or control characters, an empty username or token, or a token with spaces. */
    data object InvalidCredentials : FormError
}

/** The cached state of one repository's index. */
internal data class RepoLoad(
    val loading: Boolean = false,
    val snapshot: RepositorySnapshot? = null,
    val error: RepoError? = null,
)

/** The host of a saved URL, for display. */
internal fun hostOf(url: String): String = url.toHttpUrlOrNull()?.host ?: url

/** Builds the sections from the saved repositories, their loads, the installed packs and the filters. */
internal fun sections(
    repositories: List<SavedRepository>,
    loads: Map<Long, RepoLoad>,
    installed: List<InstalledRef>,
    query: String,
    tag: String?,
    focus: String? = null,
): List<RepositorySection> =
    repositories.filter { it.enabled }.map { repo ->
        val load = loads[repo.id] ?: RepoLoad(loading = true)
        val snapshot = load.snapshot
        val cards = snapshot?.let { cards(repo.id, it, installed) }.orEmpty()
        RepositorySection(
            repository = repo,
            host = hostOf(repo.url),
            title = snapshot?.index?.name ?: repo.name ?: hostOf(repo.url),
            loading = load.loading,
            error = load.error,
            loaded = snapshot != null,
            cards = cards.filter { it.matches(query, tag) && (focus == null || it.id == focus) },
            totalCards = cards.size,
        )
    }

/** The cards of [snapshot], in the index's order (the repository author's choice). */
internal fun cards(
    repositoryId: Long,
    snapshot: RepositorySnapshot,
    installed: List<InstalledRef>,
): List<PackCard> =
    snapshot.entries.map { resolved ->
        val entry = resolved.entry
        val key = "$repositoryId/${entry.id}"
        val bundled = entry.packs.orEmpty()
        val characters =
            if (bundled.isEmpty()) {
                entry.characters.orEmpty().map { remoteCharacter("$key/${it.id}", it, snapshot) }
            } else {
                bundled.flatMap { pack ->
                    pack.characters.orEmpty().map { remoteCharacter("$key/${pack.id}/${it.id}", it, snapshot) }
                }
            }
        PackCard(
            key = key,
            repositoryId = repositoryId,
            entry = resolved,
            cover = resolved.coverUrl,
            characters = characters,
            bundlePacks = bundled.map { it.name },
            state = installState(resolved, installed),
        )
    }

private fun remoteCharacter(
    key: String,
    preview: CharacterPreview,
    snapshot: RepositorySnapshot,
) = RemoteCharacter(
    key = key,
    name = preview.name,
    sprite = preview.sprite?.let(snapshot::resolve),
    sample = preview.sample?.let(snapshot::resolve),
    seedColor = parseSeedColor(preview.seedColor),
)

/**
 * A pack is installed when a pack with its ID is; a bundle when packs installed from it (same bundle ID) are. Older
 * installed versions mean an update; versions that do not parse count as installed (no badge promises an update).
 */
internal fun installState(
    resolved: ResolvedEntry,
    installed: List<InstalledRef>,
): InstallState {
    val entry = resolved.entry
    val version =
        if (entry.type == PackCard.TYPE_BUNDLE) {
            installed.filter { it.bundleId == entry.id }.mapNotNull { it.bundleVersion }
        } else {
            installed.filter { it.packId == entry.id }.map { it.version }
        }
    if (version.isEmpty()) return InstallState.NEW
    val available = SemVer.parse(entry.version) ?: return InstallState.INSTALLED
    val newest = version.mapNotNull(SemVer::parse).maxOrNull() ?: return InstallState.INSTALLED
    return if (newest < available) InstallState.UPDATE else InstallState.INSTALLED
}

/** Search matches the name, author, description, tags and character names; [tag] must be one of the entry's tags. */
internal fun PackCard.matches(
    query: String,
    tag: String?,
): Boolean {
    val entry = entry.entry
    if (tag != null && entry.tags.none { it.equals(tag, ignoreCase = true) }) return false
    val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val haystack =
        buildList {
            add(entry.name)
            entry.author?.let(::add)
            entry.description?.let(::add)
            addAll(entry.tags)
            addAll(bundlePacks)
            characters.forEach { add(it.name) }
        }
    return words.all { word -> haystack.any { it.contains(word, ignoreCase = true) } }
}

/** Every tag of the loaded entries, most used first, then alphabetically. Case-insensitive. */
internal fun tagsOf(
    repositories: List<SavedRepository>,
    loads: Map<Long, RepoLoad>,
): List<String> =
    repositories
        .filter { it.enabled }
        .flatMap {
            loads[it.id]
                ?.snapshot
                ?.index
                ?.packs
                .orEmpty()
        }.flatMap { it.tags }
        .groupBy { it.lowercase() }
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, List<String>>> { it.value.size }.thenBy { it.key })
        .map { it.value.first() }
