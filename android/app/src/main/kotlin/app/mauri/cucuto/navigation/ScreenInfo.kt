package app.mauri.cucuto.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import app.mauri.cucuto.R
import app.mauri.cucuto.core.data.chats.ChatKey

/** What a placeholder screen shows until the real screen lands in [milestone]. */
data class ScreenInfo(
    @param:StringRes val title: Int,
    @param:StringRes val body: Int,
    val milestone: String,
    val icon: ImageVector,
)

/**
 * Link metadata for the Status screen and what placeholder screens show; null for the screens Status does not list
 * ([CucutoDestination.Status], [CucutoDestination.Onboarding], [CucutoDestination.Diagnostics],
 * [CucutoDestination.Gallery]). Home and the picker are real screens (#25) but keep their entry for the Status links.
 */
fun CucutoDestination.screenInfo(): ScreenInfo? =
    when (this) {
        CucutoDestination.Status,
        CucutoDestination.Onboarding,
        CucutoDestination.Diagnostics,
        CucutoDestination.Gallery,
        CucutoDestination.DayNight,
        -> {
            null
        }

        CucutoDestination.Home -> {
            ScreenInfo(R.string.screen_home, R.string.screen_home_body, "M2", Icons.Rounded.Home)
        }

        is CucutoDestination.Picker -> {
            ScreenInfo(R.string.screen_picker, R.string.screen_picker_body, "M3", Icons.Rounded.Face)
        }

        is CucutoDestination.ContactDetail -> {
            ScreenInfo(R.string.screen_contact, R.string.screen_contact_body, "M3", Icons.Rounded.Person)
        }

        CucutoDestination.Packs -> {
            ScreenInfo(R.string.screen_packs, R.string.screen_packs_body, "M3", Icons.AutoMirrored.Rounded.List)
        }

        CucutoDestination.Discover -> {
            ScreenInfo(R.string.screen_discover, R.string.screen_discover_body, "M5", Icons.Rounded.Search)
        }

        is CucutoDestination.HapticEditor -> {
            ScreenInfo(R.string.screen_haptics, R.string.screen_haptics_body, "M4", Icons.Rounded.Build)
        }

        CucutoDestination.Settings -> {
            ScreenInfo(R.string.screen_settings, R.string.screen_settings_body, "M3", Icons.Rounded.Settings)
        }
    }

/** Destinations reachable from the Status screen while the app is a scaffold. */
val PreviewDestinations: List<CucutoDestination> =
    listOf(
        CucutoDestination.Home,
        CucutoDestination.Picker(),
        CucutoDestination.ContactDetail(chat = ChatKey("com.whatsapp", "demo")),
        CucutoDestination.Packs,
        CucutoDestination.Discover,
        CucutoDestination.HapticEditor(),
        CucutoDestination.Settings,
    )
