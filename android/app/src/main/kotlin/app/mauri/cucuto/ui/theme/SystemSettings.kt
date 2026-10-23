package app.mauri.cucuto.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode

/**
 * True when the user turned on **Remove animations** (Settings › Accessibility, which sets
 * `Settings.Global.ANIMATOR_DURATION_SCALE` to 0). Compose animations already jump to their end values then; read
 * this to also drop purely decorative motion: particles, squash and stretch, sound arcs (brand/motion.md, "Reduced
 * motion"). Provided by [CucutoTheme].
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** Whether the theme is dark and the system contrast level, so nested themes ([CharacterTheme]) can follow them. */
data class ThemeConditions(
    val darkTheme: Boolean,
    val contrast: Float,
)

val LocalThemeConditions = staticCompositionLocalOf { ThemeConditions(darkTheme = false, contrast = 0f) }

/** Reads the animator duration scale and keeps following it while composed. */
@Composable
fun rememberReducedMotion(): Boolean {
    if (LocalInspectionMode.current) return false
    val context = LocalContext.current
    var reduced by remember(context) { mutableStateOf(isReducedMotion(context)) }
    DisposableEffect(context) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    reduced = isReducedMotion(context)
                }
            }
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        context.contentResolver.registerContentObserver(uri, false, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return reduced
}

/** True when animations are removed system-wide. Usable outside composition (the Feedback engine). */
fun isReducedMotion(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/**
 * The system contrast level (Settings › Accessibility › Color and motion › Contrast): 0 standard, 0.5 medium,
 * 1 high. Follows changes while composed.
 */
@Composable
fun rememberSystemContrast(): Float {
    if (LocalInspectionMode.current) return 0f
    val context = LocalContext.current
    val uiModeManager = remember(context) { context.getSystemService(UiModeManager::class.java) }
    var contrast by remember(uiModeManager) { mutableFloatStateOf(uiModeManager?.contrast ?: 0f) }
    DisposableEffect(uiModeManager) {
        val listener = UiModeManager.ContrastChangeListener { contrast = it }
        uiModeManager?.addContrastChangeListener(context.mainExecutor, listener)
        onDispose { uiModeManager?.removeContrastChangeListener(listener) }
    }
    return contrast
}
