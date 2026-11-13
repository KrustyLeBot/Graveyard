package app.graveyard.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.graveyard.GraveyardApp
import app.graveyard.Nav
import app.graveyard.Screen
import app.graveyard.source.Http
import app.graveyard.source.SeriesDetails
import app.graveyard.source.SeriesSummary
import app.graveyard.suite.SettingsHint
import app.graveyard.suite.SuiteTopBar
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import kotlinx.coroutines.CancellationException

/** Poseidon Scans: the latest series, or a search. */
@Composable
fun WebtoonTab(app: GraveyardApp, nav: Nav) {
    var query by rememberSaveable { mutableStateOf("") }
    var asked by rememberSaveable { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<SeriesSummary>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(asked, attempt) {
        items = null
        error = null
        try {
            items = (if (asked.isBlank()) app.poseidon.latest(1) else app.poseidon.search(asked, 1)).items
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            label = { Text("Chercher sur Poseidon Scans") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { asked = query.trim() }),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let {
            SettingsHint(it, MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { attempt++ }) { Text("Réessayer") }
                OutlinedButton(onClick = { nav.open(Screen.Web(app.poseidon.config.baseUrl, "Poseidon Scans")) }) { Text("Ouvrir le site") }
            }
        }
        if (items == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.fillMaxSize()) {
            items(items.orEmpty(), key = { it.slug }) { series ->
                ListItem(
                    leadingContent = { Cover(app, series.cover) },
                    headlineContent = { Text(series.title) },
                    modifier = Modifier.clickable { nav.open(Screen.Series(series.slug, series.title)) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun Cover(app: GraveyardApp, url: String?) {
    val context = LocalContext.current
    val request = remember(url) {
        ImageRequest.Builder(context).data(url)
            .httpHeaders(NetworkHeaders.Builder().apply { app.poseidon.imageHeaders().forEach { (k, v) -> set(k, v) } }.build())
            .build()
    }
    AsyncImage(request, null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp, 68.dp).clip(RoundedCornerShape(6.dp)))
}

/** The chapters of a series, newest first. */
@Composable
fun SeriesScreen(app: GraveyardApp, nav: Nav, slug: String, title: String) {
    var details by remember { mutableStateOf<SeriesDetails?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(slug) {
        try {
            details = app.poseidon.details(slug)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }
    Scaffold(topBar = { SuiteTopBar(title, onBack = nav::back) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            error?.let { SettingsHint(it, MaterialTheme.colorScheme.error) }
            if (details == null && error == null) CircularProgressIndicator(Modifier.padding(24.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(details?.chapters.orEmpty(), key = { it.id }) { chapter ->
                    ListItem(
                        headlineContent = { Text(chapter.name) },
                        modifier = Modifier.clickable(enabled = !chapter.locked) { nav.open(Screen.Reader(slug, title, chapter)) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * The site inside the app, sharing its cookies with the source: passing an anti-bot page here
 * unblocks the Webtoon tab.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(app: GraveyardApp, nav: Nav, url: String, name: String) {
    var view by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    DisposableEffect(Unit) { onDispose { CookieManager.getInstance().flush() } }
    BackHandler { if (view?.canGoBack() == true) view?.goBack() else nav.back() }
    Scaffold(topBar = { SuiteTopBar(name, onBack = nav::back) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (progress in 0.01f..0.99f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = Http.USER_AGENT
                        webViewClient = WebViewClient()
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress / 100f
                            }
                        }
                        loadUrl(url)
                        view = this
                    }
                },
            )
        }
    }
}
