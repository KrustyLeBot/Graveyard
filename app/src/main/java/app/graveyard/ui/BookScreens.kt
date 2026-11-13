package app.graveyard.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import app.graveyard.Bench
import app.graveyard.GraveyardApp
import app.graveyard.Nav
import app.graveyard.Screen
import app.graveyard.book.BookChapter
import app.graveyard.book.BookFile
import app.graveyard.book.FolderBook
import app.graveyard.suite.AboutSettings
import app.graveyard.suite.SettingsHint
import app.graveyard.suite.SuiteTopBar
import app.graveyard.suite.UpdateSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The EPUBs of the folder the user downloads into, and the ones already opened. */
@Composable
fun BooksTab(app: GraveyardApp, nav: Nav) {
    var books by remember { mutableStateOf<List<BookFile>>(emptyList()) }
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var folder by remember { mutableStateOf(app.books.folder) }
    var inFolder by remember { mutableStateOf<List<FolderBook>>(emptyList()) }

    fun import(uri: Uri, open: Boolean = false) {
        busy = true
        error = null
        app.scope.launch {
            try {
                val book = app.books.import(uri)
                if (open) nav.open(Screen.Book(book))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Import impossible : ${e.message ?: e}"
            } finally {
                busy = false
                revision++
            }
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> if (uri != null) import(uri) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
        if (tree != null) {
            app.books.chooseFolder(tree)
            folder = tree
            revision++
        }
    }
    LaunchedEffect(revision) {
        books = withContext(Dispatchers.IO) { app.books.list() }
        try {
            inFolder = app.books.folderBooks()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            inFolder = emptyList()
            error = e.message ?: e.toString()
        }
    }
    LaunchedEffect(app.pendingBook) {
        app.pendingBook?.let {
            app.pendingBook = null
            import(it)
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { pickFolder.launch(folder) }, enabled = !busy) {
                Text(if (folder == null) "Choisir le dossier des EPUB" else "Changer de dossier")
            }
            OutlinedButton(onClick = { pick.launch(arrayOf("application/epub+zip", "application/octet-stream")) }, enabled = !busy) {
                Text("Un fichier")
            }
        }
        SettingsHint(
            if (busy) "Ouverture du livre…"
            else "Choisis le dossier où tu télécharges tes EPUB : ils s'affichent ici, du plus récent au plus ancien. La lecture utilise la voix cochée dans l'onglet Voix.",
        )
        error?.let { SettingsHint(it, MaterialTheme.colorScheme.error) }
        LazyColumn(Modifier.fillMaxSize()) {
            if (folder != null) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Dans le dossier (${inFolder.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { revision++ }) { Text("Actualiser") }
                    }
                }
                itemsIndexed(inFolder, key = { _, b -> b.uri.toString() }) { _, entry ->
                    ListItem(
                        headlineContent = { Text(entry.name) },
                        modifier = Modifier.clickable(enabled = !busy) { import(entry.uri, open = true) },
                    )
                    HorizontalDivider()
                }
            }
            if (books.isNotEmpty()) item { Text("Déjà ouverts", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp)) }
            itemsIndexed(books, key = { _, b -> b.file.path }) { _, book ->
                ListItem(
                    headlineContent = { Text(book.title) },
                    supportingContent = { if (book.authors.isNotEmpty()) Text(book.authors) },
                    trailingContent = {
                        IconButton(onClick = { app.books.delete(book); revision++ }) { Icon(Icons.Default.Delete, "Supprimer") }
                    },
                    modifier = Modifier.clickable { nav.open(Screen.Book(book)) },
                )
                HorizontalDivider()
            }
        }
    }
}

/** The chapters of a book, with a rough listening time. */
@Composable
fun BookScreen(app: GraveyardApp, nav: Nav, book: BookFile) {
    var chapters by remember { mutableStateOf<List<BookChapter>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(book) {
        try {
            chapters = app.books.chapters(book)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "Livre illisible : ${e.message ?: e}"
        }
    }
    Scaffold(topBar = { SuiteTopBar(book.title, onBack = nav::back) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            error?.let { SettingsHint(it, MaterialTheme.colorScheme.error) }
            val list = chapters
            if (list == null && error == null) CircularProgressIndicator(Modifier.padding(24.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(list.orEmpty(), key = { _, c -> c.index }) { _, chapter ->
                    // About 15 characters a second at a natural pace.
                    val minutes = (chapter.characters / 15 / 60).coerceAtLeast(1)
                    ListItem(
                        headlineContent = { Text(chapter.title) },
                        supportingContent = { Text("${chapter.pieces.size} phrases · environ $minutes min") },
                        modifier = Modifier.clickable { nav.open(Screen.Listen(book, chapter)) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/** A chapter read aloud: the sentence being heard is highlighted and kept in view; a tap reads from there. */
@Composable
fun ListenScreen(app: GraveyardApp, nav: Nav, book: BookFile, chapter: BookChapter) {
    val speaker = app.speaker
    val state = speaker.state
    val list = rememberLazyListState()
    var position by remember { mutableIntStateOf(0) }
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            speaker.stop()
        }
    }
    fun play(from: Int) = speaker.play(from) { chapter.pieces.getOrNull(it) }

    LaunchedEffect(state.index, state.active) {
        if (state.active && state.index in chapter.pieces.indices) {
            position = state.index
            list.animateScrollToItem((state.index - 2).coerceAtLeast(0))
        }
    }

    Scaffold(
        topBar = { SuiteTopBar(chapter.title, onBack = nav::back) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilledIconButton(onClick = {
                            when {
                                !state.active -> play(position)
                                state.paused -> speaker.resume()
                                else -> speaker.pause()
                            }
                        }) {
                            Icon(if (state.active && !state.paused) Icons.Default.Pause else Icons.Default.PlayArrow, "Lire ou mettre en pause")
                        }
                        IconButton(onClick = { speaker.stop() }, enabled = state.active) { Icon(Icons.Default.Stop, "Arrêter") }
                        Text(
                            when {
                                state.loading -> "Chargement de la voix…"
                                state.active -> "${state.voice} · phrase ${state.index + 1} sur ${chapter.pieces.size}"
                                else -> "Phrase ${position + 1} sur ${chapter.pieces.size}"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    state.error?.let { SettingsHint(it, MaterialTheme.colorScheme.error) }
                    if (state.stats.pieces > 0) SettingsHint(Bench.describe(state.stats))
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), state = list) {
            itemsIndexed(chapter.pieces) { i, piece ->
                val current = i == (if (state.active) state.index else position)
                Text(
                    piece,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                        .clickable { play(i) }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** Updates and what this app is. */
@Composable
fun SettingsTab(app: GraveyardApp) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            UpdateSettings(app.updates)
            AboutSettings(
                "Graveyard",
                "Application de test : voix de synthèse sur le téléphone, lecture d'un EPUB à voix haute, " +
                    "et webtoons avec reconnaissance du texte, lecture des bulles, défilement automatique et musique. " +
                    "Rien ici n'est fait pour durer.",
            )
        }
    }
}
