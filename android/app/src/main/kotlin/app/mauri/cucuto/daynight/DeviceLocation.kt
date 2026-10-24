package app.mauri.cucuto.daynight

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.os.CancellationSignal
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.coroutines.resume

/** Result of [DeviceLocation.approximateFix]. */
sealed interface LocationFix {
    data class Found(
        val latitude: Double,
        val longitude: Double,
    ) : LocationFix

    /** The user has not granted approximate location. */
    data object NoPermission : LocationFix

    /** Location is switched off in the system settings. */
    data object Off : LocationFix

    /** No provider answered in time. */
    data object Unavailable : LocationFix
}

/**
 * One approximate location fix, for the day and night place (ADR 0037). Only when the user taps "Use my location
 * once": a single low-power request to the fused (or network) provider, with `ACCESS_COARSE_LOCATION` only, so
 * Android blurs the result to about 2 km. No GPS, no updates, nothing in the background.
 */
class DeviceLocation
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        fun hasPermission(): Boolean =
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        suspend fun approximateFix(): LocationFix {
            if (!hasPermission()) return LocationFix.NoPermission
            val manager = context.getSystemService(LocationManager::class.java) ?: return LocationFix.Unavailable
            if (!manager.isLocationEnabled) return LocationFix.Off
            val provider =
                when {
                    manager.hasProvider(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
                    manager.hasProvider(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                    else -> return LocationFix.Unavailable
                }
            val location = withTimeoutOrNull(TIMEOUT_MS) { currentLocation(manager, provider) }
            return location?.let { LocationFix.Found(it.latitude, it.longitude) } ?: LocationFix.Unavailable
        }

        private suspend fun currentLocation(
            manager: LocationManager,
            provider: String,
        ): Location? =
            suspendCancellableCoroutine { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                val request =
                    LocationRequest
                        .Builder(0)
                        .setQuality(LocationRequest.QUALITY_LOW_POWER)
                        .setDurationMillis(TIMEOUT_MS)
                        .build()
                try {
                    if (!hasPermission()) {
                        continuation.resume(null)
                        return@suspendCancellableCoroutine
                    }
                    manager.getCurrentLocation(provider, request, signal, context.mainExecutor) { location ->
                        if (continuation.isActive) continuation.resume(location)
                    }
                } catch (_: SecurityException) {
                    continuation.resume(null)
                } catch (_: IllegalArgumentException) {
                    continuation.resume(null)
                }
            }

        private companion object {
            const val TIMEOUT_MS = 30_000L
        }
    }
