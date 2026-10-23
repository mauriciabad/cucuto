package app.mauri.cucuto.core.jxl

import okio.ByteString
import okio.ByteString.Companion.toByteString

/** The two ways a JPEG XL file starts (ISO/IEC 18181-2): a bare codestream or the ISOBMFF container. */
internal object JxlSignature {
    val CODESTREAM: ByteString = byteArrayOf(0xFF.toByte(), 0x0A).toByteString()

    val CONTAINER: ByteString =
        byteArrayOf(0, 0, 0, 0x0C, 'J'.code.toByte(), 'X'.code.toByte(), 'L'.code.toByte(), ' '.code.toByte())
            .plus(byteArrayOf(0x0D, 0x0A, 0x87.toByte(), 0x0A))
            .toByteString()

    val all = listOf(CODESTREAM, CONTAINER)

    fun matches(header: ByteArray): Boolean {
        val bytes = header.toByteString()
        return all.any { bytes.rangeEquals(0, it, 0, it.size) }
    }
}
