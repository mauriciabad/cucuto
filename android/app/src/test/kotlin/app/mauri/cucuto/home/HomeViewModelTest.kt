package app.mauri.cucuto.home

import app.cash.turbine.test
import app.mauri.cucuto.core.data.chats.ChatVoice
import app.mauri.cucuto.core.data.di.WallClock
import app.mauri.cucuto.testing.FakeCharacterDirectory
import app.mauri.cucuto.testing.FakeChatDirectory
import app.mauri.cucuto.testing.TestData.ADA
import app.mauri.cucuto.testing.TestData.BEA
import app.mauri.cucuto.testing.TestData.CRITTERS
import app.mauri.cucuto.testing.TestData.NOW
import app.mauri.cucuto.testing.TestData.chat
import app.mauri.cucuto.ui.discover.DemoPacksRequests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val directory = FakeChatDirectory(listOf(chat(ADA, "Ada"), chat(BEA, "Bea", ChatVoice(CRITTERS, "frog"))))
    private val characters = FakeCharacterDirectory()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = HomeViewModel(directory, characters, WallClock { NOW }, DemoPacksRequests())

    @Test
    fun startsLoadingThenShowsChatsAndTheStrip() =
        runTest {
            viewModel().state.test {
                val first = awaitItem()
                val state = if (first.loading) awaitItem() else first
                assertEquals(listOf(ADA, BEA), state.chats.map { it.key })
                assertEquals(listOf(ADA), state.recent.map { it.key })
            }
        }

    @Test
    fun anAssignmentMovesTheSenderOutOfTheStrip() =
        runTest {
            val vm = viewModel()
            vm.state.test {
                skipUntilLoaded()
                directory.assign(ADA, CRITTERS, "owl")
                val state = awaitItem()
                assertTrue(state.recent.isEmpty())
                assertEquals(
                    "Hoot the owl",
                    state.chats
                        .first { it.key == ADA }
                        .character
                        ?.name,
                )
            }
        }

    @Test
    fun queryAndPackFilterNarrowTheList() =
        runTest {
            val vm = viewModel()
            vm.state.test {
                skipUntilLoaded()
                vm.setQuery("ada")
                assertEquals(listOf(ADA), awaitItem().chats.map { it.key })
                vm.setQuery("")
                awaitItem()
                vm.togglePackFilter(CRITTERS)
                val filtered = awaitItem()
                assertEquals(CRITTERS, filtered.packFilter)
                assertEquals(listOf(BEA), filtered.chats.map { it.key })
                vm.togglePackFilter(CRITTERS)
                assertNull(awaitItem().packFilter)
            }
        }

    @Test
    fun dismissingGoesToTheDirectory() =
        runTest {
            viewModel().dismissRecent(ADA)
            assertEquals(listOf(ADA), directory.dismissed)
        }

    private suspend fun app.cash.turbine.ReceiveTurbine<HomeState>.skipUntilLoaded() {
        while (awaitItem().loading) Unit
    }
}
