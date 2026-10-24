package app.mauri.cucuto.video

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * A character of the CC0 demo packs (`packs/`): its name, seed color and files. The video only ever shows these two
 * packs and the brand (docs/explainer-video.md: demo packs only).
 */
@Immutable
internal data class Character(
    val pack: String,
    val id: String,
    val name: String,
    val seed: Color,
) {
    /** The first word of the name, for small labels ("Bleep the frog" → "Bleep"). */
    val shortName: String get() = name.substringBefore(' ')

    /** The sprite, relative to the repository. */
    val sprite: String get() = "packs/$pack/$id/sprite.svg"

    /** One of the character's clips, relative to the repository. */
    fun clip(name: String): String = "packs/$pack/$id/clips/$name.ogg"
}

/** A made-up person (docs/explainer-video.md: made-up people only). Never a real name, number or photo. */
@Immutable
internal data class Person(
    val name: String,
    val message: String,
    /** The cookie avatar's color while the person has no voice: a neutral tone, never a photo. */
    val tint: Color,
)

internal object Cast {
    private const val CRITTERS = "chiptune-critters"
    private const val KITCHEN = "kitchen-orchestra"

    val frog = Character(CRITTERS, "frog", "Bleep the frog", Color(0xFF4CAF50))
    val bird = Character(CRITTERS, "bird", "Tweet the bird", Color(0xFF29B6F6))
    val cat = Character(CRITTERS, "cat", "Mew the cat", Color(0xFFFF9800))
    val bee = Character(CRITTERS, "bee", "Bzz the bee", Color(0xFFFFC107))
    val pot = Character(KITCHEN, "pot", "Bong the pot", Color(0xFF78909C))
    val glass = Character(KITCHEN, "glass", "Ting the glass", Color(0xFF26C6DA))
    val whisk = Character(KITCHEN, "whisk", "Swish the whisk", Color(0xFFFFB74D))
    val kettle = Character(KITCHEN, "kettle", "Tootle the kettle", Color(0xFFE57373))

    val critters = listOf(frog, bird, cat, bee)
    val kitchen = listOf(pot, glass, whisk, kettle)
    val all = critters + kitchen

    val ada = Person("Ada", "Dinner at 8?", Color(0xFFFFB59A))
    val bea = Person("Bea", "Running late", Color(0xFFFFC5A6))
    val leo = Person("Leo", "Look at this", Color(0xFFF2CDB8))

    const val CRITTERS_NAME = "Chiptune critters"
    const val KITCHEN_NAME = "Kitchen orchestra"
    const val CRITTERS_COVER = "packs/$CRITTERS/cover.svg"
    const val KITCHEN_COVER = "packs/$KITCHEN/cover.svg"
    const val BUNDLE_COVER = "packs/demo-bundle-cover.svg"
}

/** The brand's UI sounds (`brand/sounds`, CC0). */
internal object BrandSounds {
    const val ASSIGN = "brand/sounds/assign.ogg"
    const val SUCCESS = "brand/sounds/success.ogg"
    const val TAP = "brand/sounds/tap.ogg"
}
