package app.mauri.cucuto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.mauri.cucuto.navigation.CucutoNavDisplay
import app.mauri.cucuto.ui.theme.CucutoTheme
import dagger.hilt.android.AndroidEntryPoint

/** The single activity. Screens are Navigation 3 entries inside [CucutoNavDisplay]. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            CucutoTheme {
                CucutoNavDisplay()
            }
        }
    }
}
