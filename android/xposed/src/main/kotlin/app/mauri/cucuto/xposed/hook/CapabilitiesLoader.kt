package app.mauri.cucuto.xposed.hook

import app.mauri.cucuto.core.haptics.HapticCapabilities

/**
 * Reads the vibrator's capabilities once per boot on the module's background thread (#190, ADR 0044), so the
 * notification path never pays for it: `playVibration` only does a volatile read of
 * [HapticVibrations.capabilities] and keeps WhatsApp's vibration while they are unknown.
 *
 * The first [read] runs [firstDelayMillis] after [start]; while it returns null (the vibrator service is not ready
 * yet) it is retried every [retryMillis], up to [maxAttempts] reads in all. The first capabilities read go to [use]
 * with `read = true`; if every attempt fails, [fallback] goes to [use] with `read = false`, so custom haptics still
 * play with generic capabilities. [use] is called exactly once. [post] schedules a task on the background thread.
 *
 * Pure scheduling, unit-tested with fakes; never throws.
 */
internal class CapabilitiesLoader(
    private val read: () -> HapticCapabilities?,
    private val use: (capabilities: HapticCapabilities, read: Boolean) -> Unit,
    private val post: (delayMillis: Long, task: () -> Unit) -> Unit,
    private val fallback: () -> HapticCapabilities = VibratorReader::fallback,
    private val firstDelayMillis: Long = FIRST_DELAY_MILLIS,
    private val retryMillis: Long = RETRY_MILLIS,
    private val maxAttempts: Int = MAX_ATTEMPTS,
) {
    /** Schedules the first read. Call once, after boot. */
    fun start() = schedule(attempt = 1, delayMillis = firstDelayMillis)

    private fun schedule(
        attempt: Int,
        delayMillis: Long,
    ) {
        try {
            post(delayMillis) { attempt(attempt) }
        } catch (_: Throwable) {
            // Nothing scheduled: custom haptics keep WhatsApp's vibration until the next boot.
        }
    }

    private fun attempt(attempt: Int) {
        val capabilities =
            try {
                read()
            } catch (_: Throwable) {
                null
            }
        when {
            capabilities != null -> deliver(capabilities, read = true)
            attempt < maxAttempts -> schedule(attempt + 1, retryMillis)
            else -> deliver(fallbackOrNull() ?: return, read = false)
        }
    }

    private fun fallbackOrNull(): HapticCapabilities? =
        try {
            fallback()
        } catch (_: Throwable) {
            null
        }

    private fun deliver(
        capabilities: HapticCapabilities,
        read: Boolean,
    ) {
        try {
            use(capabilities, read)
        } catch (_: Throwable) {
            // The consumer guards itself; a failure here must not escape on the background thread either.
        }
    }

    companion object {
        /** The vibrator service usually registers within a few seconds of `onSystemServerStarting`. */
        const val FIRST_DELAY_MILLIS: Long = 2_000

        const val RETRY_MILLIS: Long = 2_000

        /** 150 reads two seconds apart: five minutes, well past the first alert any messenger can post. */
        const val MAX_ATTEMPTS: Int = 150
    }
}
