package app.mauri.cucuto.ui.contacts

import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.chats.KnownContacts
import app.mauri.cucuto.core.data.chats.WhatsAppContact
import app.mauri.cucuto.testing.FakeContactChats
import app.mauri.cucuto.testing.FakeWhatsAppContactsSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** "Add a chat from contacts" (ADR 0065), with made-up entries only. */
@OptIn(ExperimentalCoroutinesApi::class)
class AddFromContactsViewModelTest {
    private val ana = entry("lk-ana", "Ana Test", "34600000001")
    private val bruno = entry("lk-bruno", "Bruno Test", "34600000002")
    private val brunoBusiness = entry("lk-bruno", "Bruno Test", "34600000002", ChatKey.WHATSAPP_BUSINESS)
    private val jose = entry("lk-jose", "José Test", "34600000003")
    private val source = FakeWhatsAppContactsSource(listOf(ana, bruno, brunoBusiness, jose))
    private val chats = FakeContactChats()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun entry(
        key: String,
        name: String,
        number: String,
        packageName: String = ChatKey.WHATSAPP,
    ) = WhatsAppContact(key, name, packageName, listOf("$number@s.whatsapp.net"))

    private fun viewModel() = AddFromContactsViewModel(source, chats)

    private val AddFromContactsViewModel.now get() = state.value

    @Test
    fun `the explainer comes first and nothing is read before the user continues`() {
        val vm = viewModel()

        assertEquals(AddStep.Explainer, vm.now.step)
        assertTrue(vm.needsPermission())
        assertEquals(0, source.loads)
    }

    @Test
    fun `a refused permission reads nothing`() {
        val vm = viewModel()
        vm.denied()
        assertEquals(AddStep.Denied, vm.now.step)

        // Continuing without the permission (revoked meanwhile) also ends there.
        vm.load()
        assertEquals(AddStep.Denied, vm.now.step)
        assertEquals(0, source.loads)
    }

    @Test
    fun `with the permission the list shows every entry, people already in Cucuto checked and locked`() {
        source.granted = true
        chats.known = KnownContacts(keys = setOf(ChatKey(ChatKey.WHATSAPP, "34600000001@s.whatsapp.net")))
        val vm = viewModel()
        assertFalse(vm.needsPermission())

        vm.load()

        assertEquals(AddStep.List, vm.now.step)
        assertEquals(4, vm.now.total)
        val anaRow = vm.now.rows.first()
        assertTrue(anaRow.known && anaRow.selected)
        vm.toggle(anaRow.id)
        assertTrue(
            "known people cannot be unpicked or added twice",
            vm.now.rows
                .first()
                .selected,
        )
        assertEquals(0, vm.now.selected)
    }

    @Test
    fun `search ignores case and accents`() {
        source.granted = true
        val vm = viewModel()
        vm.load()

        vm.setQuery("JOSE")
        assertEquals(listOf("lk-jose"), vm.now.rows.map { it.entry.lookupKey })
        vm.setQuery("bruno")
        assertEquals(2, vm.now.rows.size)
        vm.setQuery("nobody")
        assertTrue(vm.now.rows.isEmpty())
        assertEquals("the total is before search", 4, vm.now.total)
    }

    @Test
    fun `only the people picked are saved, WhatsApp and Business separately`() =
        runTest {
            source.granted = true
            val vm = viewModel()
            vm.load()
            val rows = vm.now.rows
            vm.toggle(rows.single { it.entry == brunoBusiness }.id)
            vm.toggle(rows.single { it.entry == jose }.id)
            vm.toggle(rows.single { it.entry == ana }.id)
            vm.toggle(rows.single { it.entry == ana }.id)
            assertEquals(2, vm.now.selected)

            vm.save()

            assertEquals(listOf(listOf(brunoBusiness, jose)), chats.added)
            assertEquals(listOf(brunoBusiness.chatKey, jose.chatKey), vm.now.done)
        }

    @Test
    fun `saving nobody does nothing, and a failure keeps the picks for another try`() =
        runTest {
            source.granted = true
            val vm = viewModel()
            vm.load()
            vm.save()
            assertTrue(chats.added.isEmpty())

            chats.failAdd = true
            vm.toggle(
                vm.now.rows
                    .first()
                    .id,
            )
            vm.save()
            assertTrue(vm.now.failed)
            assertNull(vm.now.done)
            assertEquals(1, vm.now.selected)
            vm.clearFailure()
            assertFalse(vm.now.failed)
        }

    @Test
    fun `no WhatsApp entries is an empty list, not an error`() {
        source.granted = true
        source.entries = emptyList()
        val vm = viewModel()
        vm.load()

        assertEquals(AddStep.List, vm.now.step)
        assertEquals(0, vm.now.total)
    }
}
