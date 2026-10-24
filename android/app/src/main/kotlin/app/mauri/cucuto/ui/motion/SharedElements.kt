package app.mauri.cucuto.ui.motion

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.navigation3.ui.LocalNavAnimatedContentScope

/** The app-wide shared transition scope; null outside [SharedElementsHost] (previews, tests). */
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/** The animated scope of the current navigation entry; null outside [SharedElementEntry]. */
val LocalEntryAnimatedScope = compositionLocalOf<AnimatedContentScope?> { null }

/** Wraps the navigation host so entries can share elements (brief: "Shared-element transitions from grid to detail"). */
@Composable
fun SharedElementsHost(content: @Composable () -> Unit) {
    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransitionScope provides this, content = content)
    }
}

/** Wraps one Navigation 3 entry's content so [sharedElement] can find the entry's transition. */
@Composable
fun SharedElementEntry(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalEntryAnimatedScope provides LocalNavAnimatedContentScope.current, content = content)
}

/**
 * Shows [content] as an element shared under [key] across navigation entries (an avatar flying from the home list
 * into the picker header). A plain box outside [SharedElementsHost] and [SharedElementEntry], or when [key] is null.
 * Keys must be unique among the elements visible in one entry.
 */
@Composable
fun SharedElement(
    key: Any?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shared = LocalSharedTransitionScope.current
    val animated = LocalEntryAnimatedScope.current
    val sharing =
        if (key != null && shared != null && animated != null) {
            with(shared) { Modifier.sharedElement(rememberSharedContentState(key), animatedVisibilityScope = animated) }
        } else {
            Modifier
        }
    Box(modifier.then(sharing), propagateMinConstraints = true) { content() }
}

/**
 * Like [SharedElement], but for two different pieces of content that morph into each other (Home's setup banner into
 * the Setup screen's card): the bounds animate and the contents cross-fade.
 */
@Composable
fun SharedBounds(
    key: Any?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shared = LocalSharedTransitionScope.current
    val animated = LocalEntryAnimatedScope.current
    val sharing =
        if (key != null && shared != null && animated != null) {
            with(shared) { Modifier.sharedBounds(rememberSharedContentState(key), animatedVisibilityScope = animated) }
        } else {
            Modifier
        }
    Box(modifier.then(sharing), propagateMinConstraints = true) { content() }
}

/**
 * A small element that rides on a [SharedElement] as one group (a ringtone badge on a chat's avatar, #339): its own
 * bounds fly under [key] on the same spring as the host's, and it is drawn above the host in the overlay
 * ([COMPANION_Z_INDEX]), so it stays on top at the start, middle and end of the transition instead of popping. The
 * size and offset of each side interpolate, so a badge that is bigger in the header grows on the way.
 *
 * Both sides render opaque and identical on top of each other (no cross-fade), so a matched pair reads as one
 * picture. A side without the companion shows an empty [SharedCompanion] of size zero at the host's corner: the other
 * side's badge then shrinks into, or grows out of, the avatar instead of vanishing under it. A plain box outside a
 * transition, like [SharedElement].
 *
 * See developer.android.com, "Shared elements in Compose" > "Customize shared element transition" (overlay rendering
 * and `zIndexInOverlay`; sharedBounds' default `RemeasureToBounds`).
 */
@Composable
fun SharedCompanion(
    key: Any?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    val shared = LocalSharedTransitionScope.current
    val animated = LocalEntryAnimatedScope.current
    val sharing =
        if (key != null && shared != null && animated != null) {
            with(shared) {
                Modifier.sharedBounds(
                    rememberSharedContentState(key),
                    animatedVisibilityScope = animated,
                    enter = EnterTransition.None,
                    exit = ExitTransition.None,
                    zIndexInOverlay = COMPANION_Z_INDEX,
                )
            }
        } else {
            Modifier
        }
    Box(modifier.then(sharing), propagateMinConstraints = true) { content() }
}

/** Above shared elements, which sit at the overlay's default z-index 0. */
private const val COMPANION_Z_INDEX = 1f
