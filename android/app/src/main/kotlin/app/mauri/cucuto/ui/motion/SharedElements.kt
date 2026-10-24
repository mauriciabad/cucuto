package app.mauri.cucuto.ui.motion

import androidx.compose.animation.AnimatedContentScope
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
