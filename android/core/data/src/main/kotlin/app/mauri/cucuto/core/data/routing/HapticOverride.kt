package app.mauri.cucuto.core.data.routing

import app.mauri.cucuto.core.routing.Haptic
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * A contact's own vibration, chosen on the contact detail screen (#33). It wins over the character's haptic in the
 * routing table ([RoutingTableBuilder]), so the module plays it. Stored in
 * [app.mauri.cucuto.core.data.db.AssignmentEntity.hapticOverride] as the pack format's `haptic` JSON ([toJson]).
 */
sealed interface HapticOverride {
    /** No override: the character's haptic from its pack applies (stored as null). */
    data object PackDefault : HapticOverride

    /** "Haptics follow the voice" with each clip's envelope, whatever the pack says (`{"mode":"auto"}`). */
    data object Auto : HapticOverride

    /** No vibration at all for this contact (`{"mode":"none"}`, [Haptic.Off] in the routing table). */
    data object Off : HapticOverride

    /** A pattern made in the haptic editor (`{"mode":"custom","events":[…]}`). */
    data class Custom(
        val haptic: Haptic.Custom,
    ) : HapticOverride

    /** The JSON to store, or null for [PackDefault]. */
    fun toJson(): String? {
        val json: JsonObject =
            when (this) {
                PackDefault -> return null
                Auto -> HapticJson.modeJson("auto")
                Off -> HapticJson.modeJson("none")
                is Custom -> HapticJson.toJson(haptic)
            }
        return Json.encodeToString(JsonObject.serializer(), json)
    }

    companion object {
        /** Reads a stored override. Null, unreadable or empty input means [PackDefault], as in the routing table. */
        fun parse(json: String?): HapticOverride =
            when (val haptic = HapticJson.fromOverride(json)) {
                null, Haptic.Keep -> PackDefault
                Haptic.Auto -> Auto
                Haptic.Off -> Off
                is Haptic.Custom -> Custom(haptic)
            }
    }
}
