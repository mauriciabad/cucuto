package app.mauri.cucuto.packs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Another app must not make Cucuto import its own data through "Open with" or the share sheet (ADR 0079). */
class IncomingImportsTest {
    private val owners =
        mapOf(
            "com.android.providers.downloads.documents" to "com.android.providers.downloads",
            "com.whatsapp.provider.media" to "com.whatsapp",
            "app.mauri.cucuto.creator" to OWN,
            "app.mauri.cucuto.debug.creator" to "$OWN.debug",
            "innocent.looking.alias" to OWN,
        )

    private fun foreign(
        authority: String?,
        own: String = OWN,
    ) = isForeignContentAuthority(authority, own) { owners[it] }

    @Test
    fun acceptsOtherAppsProviders() {
        assertTrue(foreign("com.android.providers.downloads.documents"))
        assertTrue(foreign("com.whatsapp.provider.media"))
        // A work profile's file arrives as <userId>@<authority>.
        assertTrue(foreign("10@com.android.providers.downloads.documents"))
        // Unknown to the package manager (the import then fails to open it): not Cucuto's either.
        assertTrue(foreign("com.example.gone"))
    }

    @Test
    fun refusesCucutosOwnProviders() {
        assertFalse(foreign("app.mauri.cucuto.creator"))
        assertFalse(foreign("app.mauri.cucuto.clips"))
        assertFalse(foreign("app.mauri.cucuto.events"))
        // The user prefix used to slip past the prefix check.
        assertFalse(foreign("0@app.mauri.cucuto.creator"))
        assertFalse(foreign("0@0@app.mauri.cucuto.events"))
        // Any authority the package manager says is Cucuto's, whatever it is called.
        assertFalse(foreign("innocent.looking.alias"))
        // A debug build with an application ID suffix.
        assertFalse(foreign("app.mauri.cucuto.debug.creator", own = "$OWN.debug"))
    }

    @Test
    fun refusesMissingAuthorities() {
        assertFalse(foreign(null))
        assertFalse(foreign(""))
        assertFalse(foreign("10@"))
    }

    private companion object {
        const val OWN = "app.mauri.cucuto"
    }
}
