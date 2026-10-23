package app.mauri.cucuto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.mauri.cucuto.navigation.CucutoNavDisplay
import app.mauri.cucuto.ui.feedback.FeedbackEngine
import app.mauri.cucuto.ui.feedback.ProvideFeedback
import app.mauri.cucuto.ui.theme.CucutoTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** The single activity. Screens are Navigation 3 entries inside [CucutoNavDisplay]. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var feedbackEngine: FeedbackEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            CucutoTheme {
                ProvideFeedback(feedbackEngine) {
                    CucutoNavDisplay()
                }
            }
        }
    }
}
