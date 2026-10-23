package app.mauri.cucuto.core.backup

import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Backup file names (docs/decisions/0007-zip-file-names.md): `cucuto-backup-YYYY-MM-DD.zip`, the local date of the
 * export, for plain and encrypted backups alike. Names carry no authority: readers sniff the contents.
 */
object BackupFileNames {
    /** MIME type for `ACTION_CREATE_DOCUMENT` and shares: backups are plain zips. */
    const val MIME_TYPE = "application/zip"

    /** Number of automatic backups kept in the chosen folder (docs/project-brief.md, "Backup and restore"). */
    const val AUTOMATIC_KEEP = 10

    private val pattern = Regex("""^cucuto-backup-(\d{4}-\d{2}-\d{2})(?: \((\d{1,4})\))?\.zip$""")

    /** The name for a backup made on [date]. */
    fun forDate(date: LocalDate): String = "cucuto-backup-$date.zip"

    /**
     * The date in a backup name, or null for other files. Accepts the ` (n)` suffix that document providers add when
     * a name is taken.
     */
    fun dateOf(name: String): LocalDate? {
        val match = pattern.matchEntire(name) ?: return null
        return try {
            LocalDate.parse(match.groupValues[1])
        } catch (e: DateTimeParseException) {
            null
        }
    }

    /**
     * Which of the files in an automatic backup folder to delete so that only the newest [keep] backups stay. Files
     * that are not backups are never touched. Newest means the latest date, then the highest ` (n)` copy number.
     */
    fun toPrune(
        names: Collection<String>,
        keep: Int = AUTOMATIC_KEEP,
    ): List<String> {
        require(keep >= 1) { "Keep at least one backup" }
        return names
            .mapNotNull { name ->
                val match = pattern.matchEntire(name) ?: return@mapNotNull null
                val date = dateOf(name) ?: return@mapNotNull null
                Triple(name, date, match.groupValues[2].toIntOrNull() ?: 0)
            }.sortedWith(
                compareByDescending<Triple<String, LocalDate, Int>> { it.second }.thenByDescending { it.third },
            ).drop(keep)
            .map { it.first }
    }
}
