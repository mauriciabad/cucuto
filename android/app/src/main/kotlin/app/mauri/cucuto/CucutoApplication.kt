package app.mauri.cucuto

import android.app.Application
import app.mauri.cucuto.core.data.routing.RoutingPublisher
import app.mauri.cucuto.core.jxl.JxlCoilDecoder
import app.mauri.cucuto.packs.AutoHapticsRunner
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.svg.SvgDecoder
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/** Hilt entry point for the app process. Never loaded inside `system_server` (that is `:xposed`). */
@HiltAndroidApp
class CucutoApplication :
    Application(),
    SingletonImageLoader.Factory {
    @Inject lateinit var routingPublisher: RoutingPublisher

    @Inject lateinit var autoHaptics: AutoHapticsRunner

    override fun onCreate() {
        super.onCreate()
        // libxposed hands the framework service to the app at process start; from then on every database change
        // that affects routing is republished to the module.
        routingPublisher.start()
        // Background: analyse clips of installed packs whose auto haptics are missing or stale (ADR 0020).
        autoHaptics.start()
    }

    /**
     * The ImageLoader behind every `AsyncImage`: Coil's defaults plus JPEG XL (jxl-rs, ADR 0006) and SVG (pack sprites
     * and covers; the importer rejects unsafe SVGs).
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader
            .Builder(context)
            .components {
                add(JxlCoilDecoder.Factory())
                add(SvgDecoder.Factory())
            }.build()
}
