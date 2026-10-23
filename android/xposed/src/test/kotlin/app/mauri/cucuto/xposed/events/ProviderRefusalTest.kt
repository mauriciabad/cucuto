package app.mauri.cucuto.xposed.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderRefusalTest {
    private val cert = byteArrayOf(1, 2, 3)
    private val other = byteArrayOf(9, 9)

    private fun refusal(
        providerPackage: String? = "app.mauri.cucuto",
        modulePackage: String? = "app.mauri.cucuto",
        signers: List<ByteArray>? = listOf(cert),
        held: List<ByteArray> = listOf(cert),
    ) = providerRefusal(providerPackage, modulePackage, signers) { c -> held.any { it.contentEquals(c) } }

    @Test
    fun `own package signed by the module's key may receive events`() {
        assertNull(refusal())
    }

    @Test
    fun `another package claiming the authority is refused`() {
        assertEquals("the event provider belongs to another package", refusal(providerPackage = "com.example.squat"))
    }

    @Test
    fun `same package name signed by another key is refused`() {
        assertEquals("the event provider's app is signed by another key", refusal(held = listOf(other)))
    }

    @Test
    fun `every module signer must be held`() {
        assertEquals(
            "the event provider's app is signed by another key",
            refusal(signers = listOf(cert, other), held = listOf(cert)),
        )
        assertNull(refusal(signers = listOf(cert, other), held = listOf(cert, other)))
    }

    @Test
    fun `unknown module identity fails closed`() {
        assertEquals("the module's package name is unknown", refusal(modulePackage = null))
        assertEquals("the module APK's signing certificate could not be read", refusal(signers = null))
        assertEquals("the module APK's signing certificate could not be read", refusal(signers = emptyList()))
    }
}
