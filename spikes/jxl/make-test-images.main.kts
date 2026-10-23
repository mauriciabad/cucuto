#!/usr/bin/env kotlin

/**
 * Generates the spike's test images from code (no third-party media) into `build/test-images/`.
 *
 *   kotlin make-test-images.main.kts
 *
 * Needs `cjxl` (libjxl), `cwebp`, `img2webp` and `ffmpeg` on PATH (Homebrew: jpeg-xl, webp, ffmpeg).
 *
 * Outputs (all 512 x 512):
 *   sprite.png / sprite.jxl (lossless, -d 0) / sprite.webp (lossless)  pixel art with transparency
 *   photo.png  / photo.jxl  (lossy, -d 1)    / photo.webp (q 85)       smooth "photo-like" noise
 *   anim.png (APNG) / anim.jxl (lossless) / anim.webp (lossless)       12-frame pixel-art loop
 */

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

val out = File(__FILE__.parentFile, "build/test-images").apply { mkdirs() }
val size = 512

fun run(vararg cmd: String) {
    val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
    val log = p.inputStream.bufferedReader().readText()
    check(p.waitFor() == 0) { "${cmd.joinToString(" ")} failed:\n$log" }
}

// --- Pixel-art sprite: a symmetric 32x32 creature, 6-colour palette, scaled x16 nearest. ---
val palette = intArrayOf(0x00000000, 0xFF2B1B3A.toInt(), 0xFFF4A7C0.toInt(), 0xFFFFD166.toInt(), 0xFF06D6A0.toInt(), 0xFF118AB2.toInt())

fun spriteCells(seed: Int, frame: Int): Array<IntArray> {
    val rnd = Random(seed)
    val grid = Array(32) { IntArray(32) }
    for (y in 4 until 28) for (x in 4 until 16) {
        val dx = 16 - x
        val dy = abs(y - 16)
        val inside = dx * dx + dy * dy < 150 + rnd.nextInt(40)
        if (inside) {
            val c = if (rnd.nextInt(10) == 0) 3 else if (dy > 8) 5 else if (dx < 4) 4 else 2
            grid[y][x] = c
            grid[y][31 - x] = c
        }
    }
    // outline
    val copy = grid.map { it.clone() }
    for (y in 1 until 31) for (x in 1 until 31) {
        if (copy[y][x] == 0 && (copy[y - 1][x] > 1 || copy[y + 1][x] > 1 || copy[y][x - 1] > 1 || copy[y][x + 1] > 1)) grid[y][x] = 1
    }
    // eyes blink on frames 9..10
    val eye = if (frame in 9..10) 1 else 3
    for ((ex, ey) in listOf(12 to 13, 19 to 13)) { grid[ey][ex] = eye; grid[ey][ex + 1] = eye; grid[ey + 1][ex] = 1; grid[ey + 1][ex + 1] = 1 }
    return grid
}

fun sprite(frame: Int = 0): BufferedImage {
    val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val cells = spriteCells(7, frame)
    val bounce = (abs(sin(frame / 12.0 * Math.PI * 2)) * 3).toInt() // cells
    val scale = size / 32
    for (y in 0 until size) for (x in 0 until size) {
        val cy = y / scale + bounce - 1
        val c = if (cy in 0..31) cells[cy][x / scale] else 0
        img.setRGB(x, y, palette[c])
    }
    return img
}

// --- Photo-like image: fractal value noise with a sky gradient. ---
fun photo(): BufferedImage {
    val rnd = Random(42)
    val lattice = Array(65) { DoubleArray(65) { rnd.nextDouble() } }
    fun smooth(t: Double) = t * t * (3 - 2 * t)
    fun noise(x: Double, y: Double): Double {
        val xi = floor(x).toInt().mod(64); val yi = floor(y).toInt().mod(64)
        val tx = smooth(x - floor(x)); val ty = smooth(y - floor(y))
        val a = lattice[yi][xi] * (1 - tx) + lattice[yi][xi + 1] * tx
        val b = lattice[yi + 1][xi] * (1 - tx) + lattice[yi + 1][xi + 1] * tx
        return a * (1 - ty) + b * ty
    }
    fun fbm(x: Double, y: Double) = (0 until 6).sumOf { o -> noise(x * (1 shl o), y * (1 shl o)) / (1 shl o) } / 1.97
    val img = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until size) for (x in 0 until size) {
        val h = fbm(x / 96.0, y / 96.0)
        val horizon = 0.45 + 0.15 * fbm(x / 160.0, 3.0)
        val v = y / size.toDouble()
        val (r, g, b) = if (v < horizon) {
            Triple(90 + 120 * v, 150 + 80 * v, 230 - 20 * h)
        } else {
            Triple(60 + 120 * h, 90 + 110 * h, 40 + 60 * h)
        }
        val grain = rnd.nextDouble() * 8 - 4
        fun ch(c: Double) = (c + grain).toInt().coerceIn(0, 255)
        img.setRGB(x, y, (ch(r) shl 16) or (ch(g) shl 8) or ch(b))
    }
    return img
}

ImageIO.write(sprite(), "png", File(out, "sprite.png"))
ImageIO.write(photo(), "png", File(out, "photo.png"))
val frames = File(out.parentFile, "frames").apply { mkdirs() }
repeat(12) { ImageIO.write(sprite(it), "png", File(frames, "f%02d.png".format(it))) }

fun p(name: String) = File(out, name).path
run("cjxl", p("sprite.png"), p("sprite.jxl"), "-d", "0", "--quiet")
run("cjxl", p("photo.png"), p("photo.jxl"), "-d", "1", "--quiet")
run("ffmpeg", "-y", "-loglevel", "error", "-framerate", "10", "-i", "${frames.path}/f%02d.png", "-plays", "0", "-f", "apng", p("anim.png"))
run("cjxl", p("anim.png"), p("anim.jxl"), "-d", "0", "--quiet")
run("cwebp", "-quiet", "-lossless", p("sprite.png"), "-o", p("sprite.webp"))
run("cwebp", "-quiet", "-q", "85", p("photo.png"), "-o", p("photo.webp"))
run("img2webp", "-loop", "0", "-lossless", "-d", "100", *frames.listFiles()!!.sorted().map { it.path }.toTypedArray(), "-o", p("anim.webp"))

out.listFiles()!!.filter { it.isFile }.sortedBy { it.name }.forEach { println("%-12s %8d bytes".format(it.name, it.length())) }
