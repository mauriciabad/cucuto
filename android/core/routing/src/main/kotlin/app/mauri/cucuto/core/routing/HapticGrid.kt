package app.mauri.cucuto.core.routing

/**
 * A coarse grid for clip envelopes, so the routing table can store them in three characters per control point
 * (`docs/routing-table.md`, "Clip haptics"; issue #98):
 *
 * - intensity and sharpness on [LEVELS] levels (steps of about 1.6 %; the auto haptics analyser already ignores
 *   wobbles under 3 %),
 * - control point durations in steps of [DURATION_STEP_MS] (the analyser's frame), up to [MAX_DURATION_MS].
 *
 * [snap] moves an envelope onto the grid. The codec writes on-grid envelopes in the compact form and everything
 * else in the full syntax, so encoding stays lossless either way.
 */
public object HapticGrid {
    /** Intensity and sharpness levels: one base-64 digit. */
    public const val LEVELS: Int = 64

    public const val DURATION_STEP_MS: Int = 20

    /** The longest control point the grid holds: 63 steps (longer than the 1 s every device guarantees). */
    public const val MAX_DURATION_MS: Int = (LEVELS - 1) * DURATION_STEP_MS

    private const val TOP = LEVELS - 1

    /**
     * [haptic] with every envelope on the grid: values rounded to the nearest level, durations rounded up (never
     * below the device's shortest segment) to the next step. Presets are kept as they are, and so is an envelope
     * with a control point longer than [MAX_DURATION_MS]. Idempotent.
     */
    public fun snap(haptic: Haptic.Custom): Haptic.Custom {
        val events =
            haptic.events.map { event ->
                if (event !is HapticEvent.Envelope || event.points.any { it.durationMs > MAX_DURATION_MS }) {
                    event
                } else {
                    HapticEvent.Envelope(
                        atMs = event.atMs,
                        points =
                            event.points.map { p ->
                                ControlPoint(
                                    milliIntensity = snapValue(p.milliIntensity),
                                    milliSharpness = snapValue(p.milliSharpness),
                                    durationMs = durationSteps(p.durationMs) * DURATION_STEP_MS,
                                )
                            },
                        milliInitialSharpness =
                            if (event.milliInitialSharpness == HapticEvent.Envelope.DEFAULT_SHARPNESS) {
                                HapticEvent.Envelope.DEFAULT_SHARPNESS
                            } else {
                                snapValue(event.milliInitialSharpness)
                            },
                    )
                }
            }
        return if (events == haptic.events) haptic else Haptic.Custom(events)
    }

    /** True when every event of [haptic] is an envelope the compact form holds exactly. */
    internal fun isOnGrid(haptic: Haptic.Custom): Boolean =
        haptic.events.all { event ->
            event is HapticEvent.Envelope &&
                event.atMs >= 0 &&
                (
                    event.milliInitialSharpness == HapticEvent.Envelope.DEFAULT_SHARPNESS ||
                        isLevelValue(event.milliInitialSharpness)
                ) &&
                event.points.all { p ->
                    isLevelValue(p.milliIntensity) &&
                        isLevelValue(p.milliSharpness) &&
                        p.durationMs % DURATION_STEP_MS == 0 &&
                        p.durationMs in 0..MAX_DURATION_MS
                }
        }

    /** The level (0..63) of an on-grid value in thousandths. */
    internal fun level(milli: Int): Int = (milli.coerceIn(0, 1000) * TOP + 500) / 1000

    /** The value in thousandths of [level] (0..63): 0, 16, 32, … 984, 1000. */
    internal fun value(level: Int): Int = (level * 1000 + TOP / 2) / TOP

    private fun snapValue(milli: Int): Int = value(level(milli))

    private fun isLevelValue(milli: Int): Boolean = milli in 0..1000 && value(level(milli)) == milli

    private fun durationSteps(ms: Int): Int =
        ((ms.coerceAtLeast(0) + DURATION_STEP_MS - 1) / DURATION_STEP_MS).coerceAtMost(TOP)
}
