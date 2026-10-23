package app.mauri.cucuto

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/** Hilt entry point for the app process. Never loaded inside `system_server` (that is `:xposed`). */
@HiltAndroidApp
class CucutoApplication : Application()
