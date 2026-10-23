package app.mauri.cucuto.ui.status

import android.content.Context
import android.content.pm.PackageManager
import app.mauri.cucuto.xposed.route.Targets
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finds the messengers Cucuto routes (WhatsApp, WhatsApp Business). They are declared in the manifest's `<queries>`,
 * the documented way to see specific packages on Android 11+
 * (https://developer.android.com/training/package-visibility/declaring), so no broad visibility is needed.
 */
@Singleton
class MessengerDetector
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        /** Package name to "enabled" for each installed messenger. */
        suspend fun installed(): Map<String, Boolean> =
            withContext(Dispatchers.IO) {
                val packageManager = context.packageManager
                Targets.MESSENGERS
                    .mapNotNull { packageName ->
                        try {
                            val info =
                                packageManager.getApplicationInfo(
                                    packageName,
                                    // Disabled apps are reported (as disabled) instead of looking uninstalled.
                                    PackageManager.ApplicationInfoFlags.of(
                                        PackageManager.MATCH_DISABLED_COMPONENTS.toLong(),
                                    ),
                                )
                            packageName to info.enabled
                        } catch (_: PackageManager.NameNotFoundException) {
                            null
                        }
                    }.toMap()
            }
    }
