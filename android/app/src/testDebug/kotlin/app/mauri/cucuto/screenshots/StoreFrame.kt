package app.mauri.cucuto.screenshots

import androidx.compose.ui.graphics.toArgb
import app.mauri.cucuto.ui.theme.CucutoPalette
import java.awt.Color
import java.awt.RenderingHints
import java.awt.TexturePaint
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Puts a store screenshot on a 1080 × 1920 px (9:16) brand-colored canvas, in place (ADR 0071).
 *
 * The screens render at 822 × 1782 px, taller than 2:1, and Google Play refuses screenshots whose long side is more
 * than twice the short one, as well as any alpha channel; it recommends 9:16 at 1080 × 1920 px or more for its
 * promotions. The screen keeps its pixels (no scaling), centered with rounded corners on the brand's primary-90 tone.
 * No device frame and no captions, as Play's guidelines ask. F-Droid, IzzyOnDroid and Obtainium show the same file.
 */
internal object StoreFrame {
    const val WIDTH = 1080
    const val HEIGHT = 1920
    private const val CORNER_RADIUS = 48.0

    fun frame(file: File) {
        System.setProperty("java.awt.headless", "true")
        val screen = ImageIO.read(file)
        require(screen.width <= WIDTH && screen.height <= HEIGHT) {
            "${file.name} is ${screen.width} × ${screen.height}, larger than the $WIDTH × $HEIGHT store canvas"
        }
        val canvas = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB)
        val g = canvas.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color(CucutoPalette.Primary90.toArgb())
            g.fillRect(0, 0, WIDTH, HEIGHT)
            val x = (WIDTH - screen.width) / 2.0
            val y = (HEIGHT - screen.height) / 2.0
            // A texture fill keeps the rounded edges antialiased, which a clip would not.
            g.paint = TexturePaint(screen, Rectangle2D.Double(x, y, screen.width.toDouble(), screen.height.toDouble()))
            val corner = CORNER_RADIUS * 2
            g.fill(RoundRectangle2D.Double(x, y, screen.width.toDouble(), screen.height.toDouble(), corner, corner))
        } finally {
            g.dispose()
        }
        check(ImageIO.write(canvas, "png", file)) { "No PNG writer for ${file.name}" }
    }
}
