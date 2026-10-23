package app.mauri.cucuto.jxl

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import com.awxkee.jxlcoder.JxlAnimatedImage
import com.awxkee.jxlcoder.JxlCoder
import com.awxkee.jxlcoder.PreferredColorConfig
import com.awxkee.jxlcoder.ScaleMode
import java.nio.ByteBuffer
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.runBlocking

/**
 * Runs the spike's measurements once on launch and logs them under the `JxlSpike` tag:
 *
 *   adb shell am start -W -n app.mauri.cucuto.jxlspike/app.mauri.cucuto.jxl.BenchActivity
 *   adb logcat -d -s JxlSpike   # or, complete:
 *   adb shell cat /sdcard/Android/data/app.mauri.cucuto.jxlspike/files/results.txt
 */
class BenchActivity : Activity() {
    private lateinit var text: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply { setPadding(32, 96, 32, 32); textSize = 11f }
        setContentView(ScrollView(this).apply { addView(text) })
        Thread({ runCatching { runAll() }.onFailure { log("FAILED: ${it.stackTraceToString()}") }; log("DONE") }, "bench").start()
    }

    private val results by lazy { java.io.File(getExternalFilesDir(null), "results.txt").apply { writeText("") } }

    private fun log(line: String) {
        Log.i(TAG, line)
        results.appendText(line + "\n")
        runOnUiThread { text.append(line + "\n") }
    }

    private fun asset(name: String) = assets.open(name).use { it.readBytes() }

    private fun bench(reps: Int, block: () -> Unit): String {
        repeat(5) { block() }
        val t = LongArray(reps) { val s = System.nanoTime(); block(); System.nanoTime() - s }.sorted()
        return "median %.3f ms, min %.3f ms".format(t[reps / 2] / 1e6, t[0] / 1e6)
    }

    private fun platformDecode(bytes: ByteArray): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { d, _, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    /** Max per-channel difference and number of differing pixels between two same-size bitmaps. */
    private fun diff(a: Bitmap, b: Bitmap): String {
        if (a.width != b.width || a.height != b.height) return "size mismatch"
        val pa = IntArray(a.width * a.height).also { a.getPixels(it, 0, a.width, 0, 0, a.width, a.height) }
        val pb = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        var maxDiff = 0
        var differing = 0
        for (i in pa.indices) {
            if (pa[i] != pb[i]) differing++
            for (shift in intArrayOf(0, 8, 16, 24)) {
                maxDiff = maxOf(maxDiff, abs((pa[i] ushr shift and 0xFF) - (pb[i] ushr shift and 0xFF)))
            }
        }
        return "differing px $differing/${pa.size}, max channel diff $maxDiff"
    }

    private fun runAll() {
        log("device ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")

        // 1. Does the platform decode JPEG XL by itself?
        val spriteJxl = asset("sprite.jxl")
        log("platform: ImageDecoder.isMimeTypeSupported(image/jxl) = ${ImageDecoder.isMimeTypeSupported("image/jxl")}")
        log("platform: ImageDecoder on sprite.jxl -> " + runCatching { platformDecode(spriteJxl).let { "${it.width}x${it.height}" } }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" })
        log("platform: BitmapFactory on sprite.jxl -> ${BitmapFactory.decodeByteArray(spriteJxl, 0, spriteJxl.size)}")

        // 2. Native library sizes as packaged in this APK.
        ZipFile(applicationInfo.sourceDir).use { zip ->
            zip.entries().asSequence().filter { it.name.startsWith("lib/arm64-v8a/") }.sortedBy { it.name }.forEach {
                log("apk: ${it.name} size ${it.size} stored ${it.compressedSize}")
            }
        }

        // 3. Static images.
        for (name in listOf("sprite", "photo")) {
            val jxl = asset("$name.jxl")
            val png = asset("$name.png")
            val webp = asset("$name.webp")
            log("--- $name: jxl ${jxl.size} B, png ${png.size} B, webp ${webp.size} B")
            log("jxl-rs 1 thread (open+alloc+decode): " + bench(100) { JxlImage.decodeBitmap(jxl, 1) })
            JxlImage(jxl).use { img ->
                val reuse = img.newBitmap()
                log("jxl-rs 1 thread, reused bitmap:     " + bench(100) { JxlImage(jxl).use { it.decodeNextFrame(reuse) } })
            }
            log("jxl-rs 4 threads:                   " + bench(100) { JxlImage.decodeBitmap(jxl, 4) })
            log("jxl-coder 2.6.1 (libjxl):           " + bench(100) { JxlCoder.decode(jxl, PreferredColorConfig.RGBA_8888, ScaleMode.FIT) })
            log("platform PNG (ImageDecoder, sw):    " + bench(100) { platformDecode(png) })
            log("platform WebP (ImageDecoder, sw):   " + bench(100) { platformDecode(webp) })
            val rs = JxlImage.decodeBitmap(jxl)
            log("check jxl-rs vs PNG source: ${diff(rs, platformDecode(png))}")
            log("check jxl-rs vs jxl-coder:  ${diff(rs, JxlCoder.decode(jxl, PreferredColorConfig.RGBA_8888, ScaleMode.FIT))}")
        }

        // 4. Animation.
        val anim = asset("anim.jxl")
        log("--- anim: jxl ${anim.size} B, webp ${asset("anim.webp").size} B")
        log("jxl-rs frame count (scan only) = ${JxlImage.frameCount(anim)}: " + bench(100) { JxlImage.frameCount(anim) })
        JxlImage(anim).use { img ->
            log("jxl-rs info: ${img.width}x${img.height}, animated ${img.isAnimated}, loops ${img.loopCount}")
            val bmp = img.newBitmap()
            val durations = mutableListOf<Int>()
            while (true) durations += img.decodeNextFrame(bmp) ?: break
            log("jxl-rs durations ms $durations")
        }
        log("jxl-rs all 12 frames, 1 bitmap:     " + bench(30) {
            JxlImage(anim).use { img -> val b = img.newBitmap(); while (img.decodeNextFrame(b) != null) Unit }
        })
        JxlImage(anim).use { img ->
            val b = img.newBitmap()
            while (img.decodeNextFrame(b) != null) Unit
            log("jxl-rs rewind + frame 0:            " + bench(30) { img.rewind(); img.decodeNextFrame(b) })
        }
        JxlAnimatedImage(anim, PreferredColorConfig.RGBA_8888).use { a ->
            log("jxl-coder frames=${a.numberOfFrames}, loops=${a.loopsCount}, duration0=${a.getFrameDuration(0)}")
            log("jxl-coder all frames via getFrame(i): " + bench(30) { for (i in 0 until a.numberOfFrames) a.getFrame(i) })
            log("jxl-coder getFrame(11) (random access): " + bench(30) { a.getFrame(11) })
        }
        log("jxl-coder open (JxlAnimatedImage ctor): " + bench(30) { JxlAnimatedImage(anim, PreferredColorConfig.RGBA_8888).close() })

        // 5. Coil 3 end to end (no memory/disk cache, software bitmaps).
        fun loader(factory: coil3.decode.Decoder.Factory?) = ImageLoader.Builder(this)
            .components { if (factory != null) add(factory) }
            .memoryCache(null).diskCache(null).build()
        fun load(l: ImageLoader, bytes: ByteArray) = runBlocking {
            val r = l.execute(ImageRequest.Builder(this@BenchActivity).data(bytes).allowHardware(false).build())
            check(r is SuccessResult) { "coil failed: $r" }
        }
        val rsLoader = loader(JxlRsCoilDecoder.Factory())
        val coderLoader = loader(com.awxkee.jxlcoder.coil.JxlDecoder.Factory())
        val plainLoader = loader(null)
        val spritePng = asset("sprite.png")
        log("--- coil 3.6.3, sprite")
        log("coil + jxl-rs decoder:     " + bench(50) { load(rsLoader, spriteJxl) })
        log("coil + jxl-coder-coil:     " + bench(50) { load(coderLoader, spriteJxl) })
        log("coil + platform PNG:       " + bench(50) { load(plainLoader, spritePng) })
        log("coil + jxl-rs, animated:   " + bench(20) { load(rsLoader, anim) })

        // 6. Robustness: truncated and corrupted files must fail cleanly, never crash.
        val rnd = Random(7)
        val cases = (anim.indices step 23).map { anim.copyOf(it) } + List(400) {
            anim.copyOf().also { m -> repeat(1 + rnd.nextInt(8)) { m[rnd.nextInt(m.size)] = rnd.nextInt(256).toByte() } }
        }
        var ok = 0
        var failed = 0
        val t0 = System.nanoTime()
        for (c in cases) {
            try {
                JxlImage(c).use { img -> val b = img.newBitmap(); while (img.decodeNextFrame(b) != null) Unit }
                ok++
            } catch (e: RuntimeException) {
                failed++
            }
        }
        log("robustness jxl-rs: ${cases.size} corrupt inputs -> decoded $ok, clean exception $failed, crashes 0 (%.1f s)".format((System.nanoTime() - t0) / 1e9))
        ok = 0; failed = 0
        cases.forEachIndexed { i, c ->
            if (i % 100 == 0) log("robustness jxl-coder: case $i...")
            try {
                JxlAnimatedImage(c, PreferredColorConfig.RGBA_8888).use { a -> for (f in 0 until a.numberOfFrames) a.getFrame(f) }
                ok++
            } catch (e: Exception) {
                failed++
            }
        }
        log("robustness jxl-coder: ${cases.size} corrupt inputs -> decoded $ok, clean exception $failed, crashes 0")
    }

    companion object {
        const val TAG = "JxlSpike"
    }
}
