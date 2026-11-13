package app.graveyard

import android.app.Application
import app.graveyard.suite.SuiteUpdater
import app.graveyard.suite.SuiteUpdates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class GraveyardApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val updates by lazy { SuiteUpdates(this, SuiteUpdater(this, "KrustyLeBot/graveyard", "$packageName.fileprovider"), scope) }
}
