package app.graveyard

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import app.graveyard.book.BookChapter
import app.graveyard.book.BookFile
import app.graveyard.source.ChapterInfo
import app.graveyard.suite.SuiteTopBar
import app.graveyard.suite.UpdateBanner
import app.graveyard.ui.BookScreen
import app.graveyard.ui.BooksTab
import app.graveyard.ui.GraveyardTheme
import app.graveyard.ui.ListenScreen
import app.graveyard.ui.ReaderScreen
import app.graveyard.ui.SeriesScreen
import app.graveyard.ui.SettingsTab
import app.graveyard.ui.VoicesTab
import app.graveyard.ui.WebScreen
import app.graveyard.ui.WebtoonTab

sealed interface Screen {
    data object Home : Screen
    data class Book(val book: BookFile) : Screen
    data class Listen(val book: BookFile, val chapter: BookChapter) : Screen
    data class Series(val slug: String, val title: String) : Screen
    data class Reader(val slug: String, val title: String, val chapter: ChapterInfo) : Screen
    data class Web(val url: String, val name: String) : Screen
}

/** The screens opened on top of the home tabs; the last one is shown. */
class Nav {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    var tab by mutableIntStateOf(0)

    fun open(screen: Screen) {
        stack += screen
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
}

class MainActivity : ComponentActivity() {
    private val nav = Nav()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as GraveyardApp
        take(intent)
        setContent {
            GraveyardTheme {
                LaunchedEffect(Unit) { app.updates.checkOnOpen() }
                BackHandler(nav.stack.size > 1) { nav.back() }
                when (val screen = nav.stack.last()) {
                    Screen.Home -> Home(app, nav)
                    is Screen.Book -> BookScreen(app, nav, screen.book)
                    is Screen.Listen -> ListenScreen(app, nav, screen.book, screen.chapter)
                    is Screen.Series -> SeriesScreen(app, nav, screen.slug, screen.title)
                    is Screen.Reader -> ReaderScreen(app, nav, screen.slug, screen.title, screen.chapter)
                    is Screen.Web -> WebScreen(app, nav, screen.url, screen.name)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    /** An EPUB opened with the app goes to the Livre tab. */
    private fun take(intent: Intent?) {
        val uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return
        (application as GraveyardApp).pendingBook = uri
        while (nav.stack.size > 1) nav.back()
        nav.tab = 1
    }
}

private class Tab(val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("Voix", Icons.Default.RecordVoiceOver),
    Tab("Livre", Icons.AutoMirrored.Filled.MenuBook),
    Tab("Webtoon", Icons.Default.Collections),
    Tab("Réglages", Icons.Default.Settings),
)

@Composable
private fun Home(app: GraveyardApp, nav: Nav) {
    Scaffold(
        topBar = { SuiteTopBar("Graveyard · ${TABS[nav.tab].label}") },
        bottomBar = {
            NavigationBar {
                TABS.forEachIndexed { i, tab ->
                    NavigationBarItem(
                        selected = nav.tab == i,
                        onClick = { nav.tab = i },
                        icon = { Icon(tab.icon, null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (app.updates.showBanner) UpdateBanner(app.updates)
            when (nav.tab) {
                0 -> VoicesTab(app)
                1 -> BooksTab(app, nav)
                2 -> WebtoonTab(app, nav)
                else -> SettingsTab(app)
            }
        }
    }
}
