package app.mauri.cucuto.core.data.routing

/**
 * What the connected Xposed framework says about the module, for the Status screen (#24). Production:
 * [XposedRoutingSink], which holds the framework's service; tests use a fake.
 */
interface XposedFramework {
    /** Asks the framework now (Binder calls, off the main thread). Null when no framework service is connected. */
    suspend fun report(): FrameworkReport?
}

/** One answer from the framework. Each part is null when the framework could not give it. */
data class FrameworkReport(
    /** The module's LSPosed scope as package names; System Framework is `system` (ADR 0002). */
    val scope: List<String>?,
    /** Processes running the module right now (libxposed API 102, `getRunningTargets`). */
    val targets: List<RunningTarget>?,
)

/** A process the module is loaded in. The framework documents uid and name as diagnostic values. */
data class RunningTarget(
    val uid: Int,
    val processName: String,
    val state: TargetState,
)

/** Mirrors libxposed's `HookedTarget.State`. */
enum class TargetState {
    /** Running the currently installed module code. */
    UP_TO_DATE,

    /** Still running old module code: the app was updated since the process started. */
    STALE,

    /** Being hot-reloaded. */
    RELOADING,

    /** The last hot reload failed. */
    FAILED,
}
