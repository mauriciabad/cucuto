package app.mauri.cucuto.xposed.hook

import app.mauri.cucuto.core.haptics.HapticCapabilities
import app.mauri.cucuto.core.routing.Haptic

/** What `playVibration` does with WhatsApp's vibration (argument 1). */
internal sealed interface VibrationSwap<out E : Any> {
    /** Leave argument 1 alone. */
    data object Keep : VibrationSwap<Nothing>

    /** Play no vibration (a per-contact [Haptic.Off]). */
    data object Suppress : VibrationSwap<Nothing>

    /** Replace argument 1 with [effect]. */
    data class Replace<E : Any>(
        val effect: E,
    ) : VibrationSwap<E>
}

/**
 * Turns a decision's haptic into a [VibrationSwap] (issue #96, ADR 0012):
 *
 * - null or [Haptic.Keep]: keep WhatsApp's vibration.
 * - [Haptic.Off]: suppress it.
 * - [Haptic.Custom]: replace it with [build]'s effect (`HapticEffects.create` in production), or keep it when that
 *   returns null or fails.
 * - [Haptic.Auto] never reaches here (the router resolves it against the chosen clip with `forClip`); if it did, it
 *   keeps the original.
 *
 * The device's [HapticCapabilities] are set once, with [useCapabilities], from the background thread after boot
 * ([CapabilitiesLoader], #190); until then a custom haptic keeps the original vibration, and the notification path
 * only does a volatile read of [capabilities]. Effects are immutable, so each haptic is built once and cached
 * (bounded LRU, failures included): a repeated alert costs one map lookup and no allocation. The key is the haptic's
 * value, so a republished table whose haptics did not change reuses the effects already built.
 *
 * Generic over the effect type so it runs in JVM tests; never throws.
 */
internal class HapticVibrations<E : Any>(
    private val build: (Haptic.Custom, HapticCapabilities) -> E?,
    private val capacity: Int = CACHE_SIZE,
) {
    private val cache =
        object : LinkedHashMap<Haptic.Custom, VibrationSwap<E>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Haptic.Custom, VibrationSwap<E>>?) =
                size > capacity
        }

    /** The capabilities effects are built for, or null before [useCapabilities]. */
    @Volatile
    var capabilities: HapticCapabilities? = null
        private set

    /** Sets the device's capabilities. Only the first call counts; returns whether this one did. */
    fun useCapabilities(capabilities: HapticCapabilities): Boolean =
        synchronized(cache) {
            if (this.capabilities != null) return false
            this.capabilities = capabilities
            cache.clear()
            true
        }

    fun swapFor(haptic: Haptic?): VibrationSwap<E> =
        when (haptic) {
            Haptic.Off -> VibrationSwap.Suppress
            is Haptic.Custom -> effectFor(haptic)
            null, Haptic.Keep, Haptic.Auto -> VibrationSwap.Keep
        }

    private fun effectFor(haptic: Haptic.Custom): VibrationSwap<E> {
        val capabilities = capabilities ?: return VibrationSwap.Keep
        synchronized(cache) { cache[haptic]?.let { return it } }
        val swap = buildOrNull(haptic, capabilities)?.let { VibrationSwap.Replace(it) } ?: VibrationSwap.Keep
        synchronized(cache) { cache[haptic] = swap }
        return swap
    }

    /** Builds [haptic] for [capabilities] without caching it: loads and initialises the build path off the hot path. */
    fun warmUp(
        haptic: Haptic.Custom,
        capabilities: HapticCapabilities,
    ): E? = buildOrNull(haptic, capabilities)

    private fun buildOrNull(
        haptic: Haptic.Custom,
        capabilities: HapticCapabilities,
    ): E? =
        try {
            build(haptic, capabilities)
        } catch (_: Throwable) {
            null
        }

    companion object {
        const val CACHE_SIZE: Int = 256
    }
}
