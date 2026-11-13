package app.graveyard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.graveyard.suite.AboutSettings
import app.graveyard.suite.SuiteTopBar
import app.graveyard.suite.UpdateBanner
import app.graveyard.suite.UpdateSettings
import app.graveyard.ui.GraveyardTheme

/** An empty shell: the only thing it does is update itself. Experiments get added here. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as GraveyardApp
        setContent {
            GraveyardTheme {
                LaunchedEffect(Unit) { app.updates.checkOnOpen() }
                Scaffold(topBar = { SuiteTopBar("Graveyard") }) { padding ->
                    Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
                        if (app.updates.showBanner) UpdateBanner(app.updates)
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            UpdateSettings(app.updates)
                            AboutSettings("Graveyard", "Application de test, vide pour l'instant. Elle sait seulement se mettre à jour.")
                        }
                    }
                }
            }
        }
    }
}
