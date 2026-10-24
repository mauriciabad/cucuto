package app.mauri.cucuto.core.data.routing

import app.mauri.cucuto.core.routing.RoutingCodec
import app.mauri.cucuto.core.routing.RoutingFile
import app.mauri.cucuto.core.routing.RoutingTable
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PublishedTableFileTest {
    @get:Rule val folder = TemporaryFolder()

    private fun encoded(generation: Long) = RoutingCodec.encode(RoutingTable.EMPTY.copy(generation = generation))

    @Test
    fun storesTheTableFramedLikeTheRemoteFile() =
        runBlocking {
            val store = PublishedTableFile(folder.root.resolve("routing/routing.table"))
            assertNull(store.read())

            store.write(encoded(5), 5)
            store.write(encoded(6), 6)
            assertEquals(encoded(6), store.read())
            // The module unframes what the provider serves exactly like the libxposed remote file.
            assertArrayEquals(RoutingFile.frame(encoded(6)), store.file.readBytes())
            assertFalse(folder.root.resolve("routing/routing.table.tmp").exists())
        }

    @Test
    fun aCorruptFileReadsAsNothing() =
        runBlocking {
            val store = PublishedTableFile(folder.root.resolve("routing.table"))
            store.file.writeText("cucuto-routing-file 1 999 0\ntruncated")
            assertNull(store.read())
        }

    @Test
    fun remembersWhatTheModuleFetched() =
        runBlocking {
            val path = folder.root.resolve("routing.table")
            PublishedTableFile(path).write(encoded(7), 7)

            // A new process: the generation is read from the file the first time the system opens it.
            val store = PublishedTableFile(path)
            assertNull(store.served.value)
            store.noteServed()
            assertEquals(7L, store.served.value)

            store.write(encoded(8), 8)
            store.noteServed()
            assertEquals(8L, store.served.value)
        }

    @Test
    fun readsTheGenerationFromTheHeader() {
        assertEquals(42L, PublishedTableFile.generationOf(encoded(42)))
        assertNull(PublishedTableFile.generationOf("garbage"))
    }
}
