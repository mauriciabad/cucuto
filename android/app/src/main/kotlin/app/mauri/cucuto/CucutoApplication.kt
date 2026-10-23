package app.mauri.cucuto

import android.app.Application
import app.mauri.cucuto.core.data.routing.RoutingPublisher
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/** Hilt entry point for the app process. Never loaded inside `system_server` (that is `:xposed`). */
@HiltAndroidApp
class CucutoApplication : Application() {
    @Inject lateinit var routingPublisher: RoutingPublisher

    override fun onCreate() {
        super.onCreate()
        // libxposed hands the framework service to the app at process start; from then on every database change
        // that affects routing is republished to the module.
        routingPublisher.start()
    }
}
