package app.mauri.cucuto.core.data.routing

import app.mauri.cucuto.core.routing.ControlPoint
import app.mauri.cucuto.core.routing.Haptic
import app.mauri.cucuto.core.routing.HapticEvent
import app.mauri.cucuto.core.routing.HapticPreset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.roundToInt

/**
 * Converts the pack format's `haptic` JSON (`pack.schema.json#/$defs/haptic`) into the routing table's [Haptic].
 * Input was validated at import, so anything unexpected degrades to a safe default instead of throwing.
 */
object HapticJson {
    /** A character's haptic from `pack.json`. Absent means `auto`; `none` leaves WhatsApp's vibration alone. */
    fun fromPack(element: JsonElement?): Haptic {
        val obj = element as? JsonObject ?: return Haptic.Auto
        return when (obj.string("mode")) {
            "none" -> Haptic.Keep
            "custom" -> custom(obj) ?: Haptic.Auto
            else -> Haptic.Auto
        }
    }

    /**
     * A per-contact override ([app.mauri.cucuto.core.data.db.AssignmentEntity.hapticOverride]). Here `none` means
     * "no vibration for this contact". Returns null (use the character's haptic) when [json] is null or unreadable.
     */
    fun fromOverride(json: String?): Haptic? {
        if (json.isNullOrBlank()) return null
        val obj = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return null
        return when (obj.string("mode")) {
            "none" -> Haptic.Off
            "auto" -> Haptic.Auto
            "custom" -> custom(obj)
            else -> null
        }
    }

    /**
     * The pack format's `haptic` JSON for [haptic] (`{"mode":"custom","events":[…]}`), the inverse of
     * [fromOverride]: thousandths become fractions (`800` → `0.8`), a scale of 1 and the default initial sharpness
     * are left out. Used to store per-contact overrides and in backups.
     */
    fun toJson(haptic: Haptic.Custom): JsonObject =
        JsonObject(
            mapOf(
                "mode" to JsonPrimitive("custom"),
                "events" to JsonArray(haptic.events.map(::eventJson)),
            ),
        )

    /** The pack format's `{"mode": …}` object for the modes without a timeline. */
    fun modeJson(mode: String): JsonObject = JsonObject(mapOf("mode" to JsonPrimitive(mode)))

    private fun eventJson(event: HapticEvent): JsonObject =
        when (event) {
            is HapticEvent.Preset -> {
                buildMap {
                    put("at", JsonPrimitive(event.atMs))
                    put("preset", JsonPrimitive(event.preset.name))
                    if (event.milliScale != MILLI_ONE) put("scale", fraction(event.milliScale))
                }
            }

            is HapticEvent.Envelope -> {
                buildMap {
                    put("at", JsonPrimitive(event.atMs))
                    if (event.milliInitialSharpness != HapticEvent.Envelope.DEFAULT_SHARPNESS) {
                        put("initialSharpness", fraction(event.milliInitialSharpness))
                    }
                    put(
                        "envelope",
                        JsonArray(
                            event.points.map { point ->
                                JsonObject(
                                    mapOf(
                                        "intensity" to fraction(point.milliIntensity),
                                        "sharpness" to fraction(point.milliSharpness),
                                        "ms" to JsonPrimitive(point.durationMs),
                                    ),
                                )
                            },
                        ),
                    )
                }
            }
        }.let(::JsonObject)

    /** Thousandths as a JSON fraction: whole numbers stay integers (`0`, `1`), the rest keep three decimals. */
    private fun fraction(milli: Int): JsonPrimitive {
        val clamped = milli.coerceIn(0, MILLI_ONE)
        return if (clamped % MILLI_ONE == 0) {
            JsonPrimitive(clamped / MILLI_ONE)
        } else {
            JsonPrimitive(clamped / MILLI_ONE.toDouble())
        }
    }

    private const val MILLI_ONE = 1000

    private fun custom(obj: JsonObject): Haptic.Custom? {
        val events = (obj["events"] as? JsonArray)?.mapNotNull { event(it as? JsonObject ?: return@mapNotNull null) }
        return if (events.isNullOrEmpty()) null else Haptic.Custom(events)
    }

    private fun event(obj: JsonObject): HapticEvent? {
        val at = obj.int("at") ?: return null
        val preset = obj.string("preset")
        if (preset != null) {
            val type =
                when (preset) {
                    "CLICK" -> HapticPreset.CLICK
                    "TICK" -> HapticPreset.TICK
                    "LOW_TICK" -> HapticPreset.LOW_TICK
                    else -> return null
                }
            return HapticEvent.Preset(at, type, milli(obj.double("scale") ?: 1.0))
        }
        val points =
            (obj["envelope"] as? JsonArray)?.mapNotNull { point ->
                val p = point as? JsonObject ?: return@mapNotNull null
                ControlPoint(
                    milliIntensity = milli(p.double("intensity") ?: return@mapNotNull null),
                    milliSharpness = milli(p.double("sharpness") ?: return@mapNotNull null),
                    durationMs = p.int("ms") ?: return@mapNotNull null,
                )
            }
        if (points.isNullOrEmpty()) return null
        val sharpness = obj.double("initialSharpness")?.let(::milli) ?: HapticEvent.Envelope.DEFAULT_SHARPNESS
        return HapticEvent.Envelope(at, points, sharpness)
    }

    /** A fraction in 0..1 as thousandths. */
    internal fun milli(value: Double): Int = (value.coerceIn(0.0, 1.0) * 1000).roundToInt()

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.double(name: String): Double? = (this[name] as? JsonPrimitive)?.doubleOrNull
}
