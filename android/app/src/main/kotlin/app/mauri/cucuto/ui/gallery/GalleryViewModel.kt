package app.mauri.cucuto.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mauri.cucuto.core.data.settings.UiFeedbackSettings
import app.mauri.cucuto.core.data.settings.UiSoundLevel
import app.mauri.cucuto.ui.feedback.Feedback
import app.mauri.cucuto.ui.feedback.FeedbackEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The design gallery's view of the feedback settings (the real Settings screen will reuse [UiFeedbackSettings]). */
@HiltViewModel
class GalleryViewModel
    @Inject
    constructor(
        private val settings: UiFeedbackSettings,
        private val engine: FeedbackEngine,
    ) : ViewModel() {
        val soundLevel: StateFlow<UiSoundLevel> = engine.soundLevel
        val hapticsEnabled: StateFlow<Boolean> = engine.hapticsEnabled
        val richHaptics: Boolean = engine.supportsRichHaptic(Feedback.Assign())

        fun setSoundLevel(level: UiSoundLevel) {
            viewModelScope.launch { settings.setSoundLevel(level) }
        }

        fun setHapticsEnabled(enabled: Boolean) {
            viewModelScope.launch { settings.setHapticsEnabled(enabled) }
        }
    }
