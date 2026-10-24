package app.mauri.cucuto.smoke

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Runs the smoke tests on Hilt's test application: the app's real modules (Room database, importer, routing
 * publisher, backups), with test code injected. `CucutoApplication.onCreate` does not run, so each test starts the
 * background work it needs itself ([SmokeTest.startApp]).
 */
class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application = super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
