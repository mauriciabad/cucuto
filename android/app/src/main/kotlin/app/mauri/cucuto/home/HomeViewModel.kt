package app.mauri.cucuto.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mauri.cucuto.core.data.chats.ChatDirectory
import app.mauri.cucuto.core.data.chats.ChatKey
import app.mauri.cucuto.core.data.di.WallClock
import app.mauri.cucuto.core.data.voices.CharacterDirectory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The home screen: chats with their characters, the recent senders strip, search and the pack filter. */
@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        private val chats: ChatDirectory,
        characters: CharacterDirectory,
        private val clock: WallClock,
    ) : ViewModel() {
        private val query = MutableStateFlow("")
        private val packFilter = MutableStateFlow<String?>(null)

        val state: StateFlow<HomeState> =
            combine(chats.observeChats(), characters.observePacks(), query, packFilter) { chats, packs, q, filter ->
                buildHomeState(chats, packs, q, filter, clock.now())
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeState())

        fun setQuery(value: String) {
            query.value = value
        }

        /** Selects [packId] as the filter, or clears it when it is already selected. */
        fun togglePackFilter(packId: String) {
            packFilter.update { if (it == packId) null else packId }
        }

        /** Hides [chat] from the recent senders strip until it messages again. */
        fun dismissRecent(chat: ChatKey) {
            viewModelScope.launch { chats.dismissRecent(chat) }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
