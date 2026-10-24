package app.mauri.cucuto.spike.waring.app

import android.app.Application
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Binds the libxposed service once per process, so [ConfigReceiver] can write Remote Preferences. The framework binds
 * asynchronously; [awaitService] waits a few seconds for it.
 */
class SpikeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(
            object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    Log.i(TAG, "bound to ${service.frameworkName} ${service.frameworkVersion}, API ${service.apiVersion}")
                    bound = service
                    ready.countDown()
                }

                override fun onServiceDied(service: XposedService) {
                    Log.w(TAG, "framework service died")
                    if (bound === service) bound = null
                }
            },
        )
    }

    companion object {
        const val TAG = "cucuto-waring"

        @Volatile private var bound: XposedService? = null
        private val ready = CountDownLatch(1)

        /** The bound service, waiting up to [seconds] for the first bind. Null when the module is not enabled. */
        fun awaitService(seconds: Long): XposedService? {
            bound?.let { return it }
            ready.await(seconds, TimeUnit.SECONDS)
            return bound
        }
    }
}
