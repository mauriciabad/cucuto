package app.mauri.cucuto

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.mauri.cucuto.navigation.CucutoNavDisplay
import app.mauri.cucuto.packs.IncomingImports
import app.mauri.cucuto.packs.importSourceOf
import app.mauri.cucuto.ui.feedback.FeedbackEngine
import app.mauri.cucuto.ui.feedback.ProvideFeedback
import app.mauri.cucuto.ui.theme.CucutoTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** The single activity. Screens are Navigation 3 entries inside [CucutoNavDisplay]. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var feedbackEngine: FeedbackEngine

    @Inject lateinit var incomingImports: IncomingImports

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A recreated activity already handed its file over; only a fresh launch carries a new one.
        if (savedInstanceState == null) offerImport(intent)
        setContent {
            CucutoTheme {
                ProvideFeedback(feedbackEngine) {
                    CucutoNavDisplay(pendingImport = incomingImports.pending)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        offerImport(intent)
    }

    /** "Open with" and the share sheet: queue the zip for the Packs screen (#27). */
    private fun offerImport(intent: Intent?) {
        importSourceOf(this, intent)?.let(incomingImports::offer)
    }
}
