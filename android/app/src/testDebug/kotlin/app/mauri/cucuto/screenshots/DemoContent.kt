package app.mauri.cucuto.screenshots

import app.mauri.cucuto.core.data.chats.Chat
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.chats.ChatVoice
import app.mauri.cucuto.core.data.db.PackSource
import app.mauri.cucuto.core.data.voices.VoiceCharacter
import app.mauri.cucuto.core.data.voices.VoicePack
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.packs.AssignedContact
import app.mauri.cucuto.packs.LibraryCharacter
import app.mauri.cucuto.packs.LibraryPack
import app.mauri.cucuto.ui.ringtone.RingSound
import java.io.File
import java.util.Locale

/**
 * What the screenshots show: the two CC0 demo packs in `packs/` (real sprites and covers, read straight from the
 * pack sources) and made-up chats. Every name here is invented; never put a real contact, group or phone number in
 * a screenshot (ADR 0032).
 *
 * The pack names, character names and seed colors mirror `packs/<pack>/pack.yaml`; [DemoPacksTest] fails when a
 * sprite or cover moves.
 */
object DemoContent {
    /** The pack sources, relative to the module directory (the working directory of Gradle's unit tests). */
    val packsDir = File("../../packs")

    val critters =
        DemoPack(
            folder = "chiptune-critters",
            id = "app.mauri.demo.chiptune-critters",
            name = "Chiptune critters",
            description =
                "Four tiny 8-bit friends that blip, chirp, mew and buzz. Bleep the frog also sings a pond song as a " +
                    "ringtone, bright by day and sleepy at night.",
            characters =
                listOf(
                    DemoCharacter("frog", "Bleep the frog", 0xFF4CAF50, ringtones = 2),
                    DemoCharacter("bird", "Tweet the bird", 0xFF29B6F6),
                    DemoCharacter("cat", "Mew the cat", 0xFFFF9800),
                    DemoCharacter("bee", "Bzz the bee", 0xFFFFC107),
                ),
            sizeBytes = 412_000,
        )

    val kitchen =
        DemoPack(
            folder = "kitchen-orchestra",
            id = "app.mauri.demo.kitchen-orchestra",
            name = "Kitchen orchestra",
            description =
                "The kitchen is warming up. A pot that bongs, a glass that tings, a whisk that shakes and a kettle " +
                    "that whistles.",
            characters =
                listOf(
                    DemoCharacter("pot", "Bong the pot", 0xFF78909C),
                    DemoCharacter("glass", "Ting the glass", 0xFF26C6DA),
                    DemoCharacter("whisk", "Swish the whisk", 0xFFFFB74D),
                    DemoCharacter("kettle", "Tootle the kettle", 0xFFE57373),
                ),
            sizeBytes = 298_000,
        )

    val packs = listOf(critters, kitchen)

    /**
     * Made-up chats, newest first. The ones without a voice that wrote this week fill the recent senders strip.
     * Group and family names follow the default locale's language (English or Spanish).
     */
    fun chats(
        now: Long = System.currentTimeMillis(),
        spanish: Boolean = Locale.getDefault().language == "es",
    ): List<Chat> {
        fun name(
            english: String,
            spanishName: String,
        ) = if (spanish) spanishName else english
        return listOf(
            chat(1, "Noa", now - 1 * HOUR),
            chat(2, name("Climbers", "Escalada"), now - 2 * HOUR, group = true),
            chat(3, "Iris", now - 3 * HOUR, voice = critters.voice("frog")),
            chat(4, "Leo", now - 4 * HOUR),
            chat(5, name("Flatmates", "Piso"), now - 5 * HOUR, group = true, voice = kitchen.voice("kettle")),
            chat(6, name("Grandpa", "Abuelo"), now - 6 * HOUR),
            chat(7, "Sami", now - 9 * HOUR, voice = critters.voice("bird")),
            chat(
                8,
                name("Book club", "Club de lectura"),
                now - 26 * HOUR,
                group = true,
                voice = kitchen.voice("glass"),
            ),
            chat(9, "Dani", now - 50 * HOUR, voice = critters.voice("cat")),
            chat(10, "Marta", now - 4 * DAY, voice = kitchen.voice("pot")),
            chat(11, name("Five-a-side", "Fútbol sala"), now - 6 * DAY, group = true, voice = critters.voice("bee")),
        )
    }

    /** The contacts that use each pack, matching [chats]. */
    fun contactsOf(pack: DemoPack): List<AssignedContact> =
        chats()
            .filter { it.voice?.packId == pack.id }
            .map { AssignedContact(it.contactId ?: 0, it.displayName, it.voice!!.characterId) }

    private fun chat(
        n: Long,
        name: String,
        seenAt: Long,
        group: Boolean = false,
        voice: ChatVoice? = null,
    ): Chat {
        val key =
            if (group) {
                ChatKey(WHATSAPP, "1203000000000%05d@g.us".format(n))
            } else {
                ChatKey(WHATSAPP, "1000000000%05d@lid".format(n))
            }
        return Chat(key, n, name, group, voice, seenAt, messageCount = (n * 7 % 23 + 1).toInt())
    }

    private const val WHATSAPP = "com.whatsapp"
    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR
}

data class DemoCharacter(
    val id: String,
    val name: String,
    val seedColor: Long,
    val ringtones: Int = 0,
)

/** A demo pack as every screen's model wants it. */
data class DemoPack(
    val folder: String,
    val id: String,
    val name: String,
    val description: String,
    val characters: List<DemoCharacter>,
    val sizeBytes: Long,
) {
    val root: File get() = File(DemoContent.packsDir, folder)
    val cover: File get() = File(root, "cover.svg")

    fun sprite(character: String): File = File(root, "$character/sprite.svg")

    fun clips(character: String): List<File> = File(root, "$character/clips").listFiles().orEmpty().sortedBy { it.name }

    fun voice(character: String): ChatVoice = ChatVoice(id, character)

    fun voicePack(): VoicePack =
        VoicePack(
            packId = id,
            name = name,
            characters =
                characters.map { c ->
                    VoiceCharacter(
                        packId = id,
                        id = c.id,
                        name = c.name,
                        sprite = sprite(c.id),
                        spriteAnimated = null,
                        seedColor = c.seedColor.toInt(),
                        clips = clips(c.id),
                        haptic = Haptic.Keep,
                        clipPaths = clips(c.id).map { "${c.id}/clips/${it.name}" },
                        ringtoneCount = c.ringtones,
                    )
                },
        )

    fun libraryCharacters(): List<LibraryCharacter> =
        characters.map { c ->
            LibraryCharacter(
                c.id,
                c.name,
                sprite(c.id),
                c.seedColor.toInt(),
                clips(c.id),
                c.ringtones,
                ringtones = List(c.ringtones) { RingSound(File("/demo/${c.id}/ringtones/$it.ogg")) },
            )
        }

    fun libraryPack(contacts: List<AssignedContact> = DemoContent.contactsOf(this)): LibraryPack =
        LibraryPack(
            id = id,
            name = name,
            version = "1.0.0",
            author = "Cucuto contributors",
            license = "CC0-1.0",
            description = description,
            cover = cover,
            characters = libraryCharacters(),
            sizeBytes = sizeBytes,
            source = PackSource.LOCAL,
            bundleId = null,
            contacts = contacts,
        )
}
