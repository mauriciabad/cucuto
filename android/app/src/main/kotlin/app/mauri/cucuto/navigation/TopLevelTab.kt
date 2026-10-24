package app.mauri.cucuto.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import app.mauri.cucuto.R

/**
 * The four top-level destinations of the adaptive navigation (a bar on phones, a rail on large screens). Each tab
 * keeps its own back stack, starting at [root] (ADR 0027).
 */
enum class TopLevelTab(
    val root: CucutoDestination,
    @param:StringRes val label: Int,
) {
    Home(CucutoDestination.Home, R.string.tab_home),
    Packs(CucutoDestination.Packs, R.string.tab_packs),
    Discover(CucutoDestination.Discover, R.string.tab_discover),
    Settings(CucutoDestination.Settings, R.string.tab_settings),
    ;

    val icon: ImageVector
        get() =
            when (this) {
                Home -> Icons.Rounded.Home
                Packs -> TabIcons.Packs
                Discover -> TabIcons.Discover
                Settings -> Icons.Rounded.Settings
            }

    companion object {
        /** Where the app opens after onboarding, and where back from another tab's root lands. */
        val Start = Home

        /** The tab whose root is [key], or null when [key] is not a tab root. */
        fun ofRoot(key: Any): TopLevelTab? = entries.firstOrNull { it.root == key }
    }
}

/**
 * Tab icons missing from `material-icons-core`, drawn from the Material Icons path data (Apache-2.0) instead of
 * pulling in the multi-megabyte extended icon set.
 */
private object TabIcons {
    /** Material "library_music": a stack of albums, one pack of voices. */
    val Packs: ImageVector by lazy {
        icon(
            "Packs",
            "M20 2H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-2 5h-3v5.5c0 1.38-1.12 " +
                "2.5-2.5 2.5S10 13.88 10 12.5s1.12-2.5 2.5-2.5c.57 0 1.08.19 1.5.51V5h4v2zM4 6H2v14c0 1.1.9 2 2 2h14v-2H4V6z",
        )
    }

    /** Material "explore": a compass. */
    val Discover: ImageVector by lazy {
        icon(
            "Discover",
            "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 " +
                "8-8 8 3.59 8 8-3.59 8-8 8zm-5.5-2.5l7.51-3.49L17.5 6.5 9.99 9.99 6.5 17.5z" +
                "m5.5-6.6c.61 0 1.1.49 1.1 1.1s-.49 1.1-1.1 1.1-1.1-.49-1.1-1.1.49-1.1 1.1-1.1z",
        )
    }

    private fun icon(
        name: String,
        path: String,
    ): ImageVector =
        ImageVector
            .Builder(name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
            .build()
}
