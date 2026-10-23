#!/usr/bin/env kotlin

/**
 * Generates the JPEG XL test images of `:core:jxl` from code (no third-party media) into
 * `src/androidTest/assets/jxl/`. The outputs are committed: they are tiny, and CI does not need cjxl.
 *
 *   kotlin android/core/jxl/generate-test-images.main.kts
 *
 * Needs `cjxl`/`djxl` (Homebrew jpeg-xl) and `ffmpeg` (APNG for the animation) on PATH.
 *
 *   sprite.png, sprite.jxl         64x64 pixel art with transparency; lossless (`-d 0 -e 1`, the packer default)
 *   sprite-e7.jxl                  the same at cjxl's default effort (a different lossless coding path)
 *   photo.png, photo.jxl           128x128 photo-like noise; lossy (`-d 1`)
 *   photo-djxl.png                 photo.jxl decoded by libjxl (`djxl`), the reference for the lossy check
 *   anim.jxl, anim-<n>.png         8-frame 32x32 lossless animation, 100 ms frames, loops forever; its frames
 *   p3.jxl                         32x32 lossless sprite tagged Display P3 (bitmap color space check)
 *   oversized.jxl                  600x600, over the 512 px pack limit
 *   huge.jxl                       4096x4096, over the decoder's 2048 px hard limit
 */

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.floor
import kotlin.random.Random

val out = File(__FILE__.parentFile, "src/androidTest/assets/jxl").apply { mkdirs() }
val tmp: File = java.nio.file.Files.createTempDirectory("jxl-test-images").toFile()

fun run(vararg cmd: String) {
    val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
    val log = p.inputStream.bufferedReader().readText()
    check(p.waitFor() == 0) { "${cmd.joinToString(" ")} failed:\n$log" }
}

fun png(img: BufferedImage, file: File) = check(ImageIO.write(img, "png", file))

// Pixel art: a symmetric 16x16 creature, transparent background and opaque colours only, so premultiplied and
// straight alpha agree exactly and the lossless output can be compared pixel for pixel with the PNG.
val palette = intArrayOf(0x00000000, 0xFF2B1B3A.toInt(), 0xFFF4A7C0.toInt(), 0xFFFFD166.toInt(), 0xFF06D6A0.toInt())

fun creature(cells: Int, scale: Int, frame: Int = 0): BufferedImage {
    val rnd = Random(7)
    val grid = Array(cells) { IntArray(cells) }
    val half = cells / 2
    for (y in 1 until cells - 1) for (x in 1 until half) {
        val dx = half - x
        val dy = y - half
        if (dx * dx + dy * dy < half * half - rnd.nextInt(half)) {
            val c = if (dy > half / 2) 4 else if (rnd.nextInt(6) == 0) 3 else 2
            grid[y][x] = c
            grid[y][cells - 1 - x] = c
        }
    }
    val eyeY = half - 1 + frame % 2
    grid[eyeY][half - 2] = 1
    grid[eyeY][half + 1] = 1
    val shift = frame % 4 - 1
    val img = BufferedImage(cells * scale, cells * scale, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until img.height) for (x in 0 until img.width) {
        val cy = y / scale + shift
        img.setRGB(x, y, palette[if (cy in 0 until cells) grid[cy][x / scale] else 0])
    }
    return img
}

// Photo-like: smooth value noise with grain, so the lossy encoder has real work.
fun photo(size: Int): BufferedImage {
    val rnd = Random(42)
    val lattice = Array(17) { DoubleArray(17) { rnd.nextDouble() } }
    fun smooth(t: Double) = t * t * (3 - 2 * t)
    fun noise(x: Double, y: Double): Double {
        val xi = floor(x).toInt().mod(16)
        val yi = floor(y).toInt().mod(16)
        val tx = smooth(x - floor(x))
        val ty = smooth(y - floor(y))
        val a = lattice[yi][xi] * (1 - tx) + lattice[yi][xi + 1] * tx
        val b = lattice[yi + 1][xi] * (1 - tx) + lattice[yi + 1][xi + 1] * tx
        return a * (1 - ty) + b * ty
    }
    val img = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until size) for (x in 0 until size) {
        val h = (0 until 4).sumOf { o -> noise(x / 32.0 * (1 shl o), y / 32.0 * (1 shl o)) / (1 shl o) } / 1.875
        val grain = rnd.nextDouble() * 8 - 4
        fun ch(c: Double) = (c + grain).toInt().coerceIn(0, 255)
        img.setRGB(x, y, (ch(60 + 150 * h) shl 16) or (ch(90 + 120 * h) shl 8) or ch(200 - 120 * h))
    }
    return img
}

fun solid(size: Int) = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)

fun f(name: String) = File(out, name).path

png(creature(16, 4), File(out, "sprite.png"))
run("cjxl", f("sprite.png"), f("sprite.jxl"), "-d", "0", "-e", "1", "--quiet")
run("cjxl", f("sprite.png"), f("sprite-e7.jxl"), "-d", "0", "--quiet")

png(photo(128), File(out, "photo.png"))
run("cjxl", f("photo.png"), f("photo.jxl"), "-d", "1", "--quiet")
run("djxl", f("photo.jxl"), f("photo-djxl.png"), "--quiet")

repeat(8) { png(creature(8, 4, it), File(out, "anim-$it.png")) }
run(
    "ffmpeg", "-y", "-loglevel", "error", "-framerate", "10", "-i", f("anim-%d.png"),
    "-plays", "0", "-f", "apng", File(tmp, "anim.apng").path,
)
run("cjxl", File(tmp, "anim.apng").path, f("anim.jxl"), "-d", "0", "-e", "1", "--quiet")

png(creature(8, 4), File(tmp, "p3.png"))
run("cjxl", File(tmp, "p3.png").path, f("p3.jxl"), "-d", "0", "-e", "1", "-x", "color_space=RGB_D65_DCI_Rel_SRG", "--quiet")

png(solid(600), File(tmp, "oversized.png"))
run("cjxl", File(tmp, "oversized.png").path, f("oversized.jxl"), "-d", "0", "-e", "1", "--quiet")
png(solid(4096), File(tmp, "huge.png"))
run("cjxl", File(tmp, "huge.png").path, f("huge.jxl"), "-d", "0", "-e", "1", "--quiet")

tmp.deleteRecursively()
out.listFiles()!!.sortedBy { it.name }.forEach { println("%-16s %7d bytes".format(it.name, it.length())) }
