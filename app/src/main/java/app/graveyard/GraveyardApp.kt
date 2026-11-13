package app.graveyard

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.graveyard.book.Books
import app.graveyard.source.Http
import app.graveyard.source.Source
import app.graveyard.source.SourceConfig
import app.graveyard.source.poseidon.PoseidonScans
import app.graveyard.suite.SuiteUpdater
import app.graveyard.suite.SuiteUpdates
import app.graveyard.tts.ModelStore
import app.graveyard.tts.Speaker
import app.graveyard.tts.VoiceHub
import app.graveyard.webtoon.Music
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class GraveyardApp : Application(), SingletonImageLoader.Factory {
    /** Work that outlives a screen: downloads, reading aloud. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val http by lazy { Http() }
    val store by lazy { ModelStore(this, http.images) }
    val hub by lazy { VoiceHub(this, store) }
    val speaker by lazy { Speaker(this, hub, scope) }
    val books by lazy { Books(this) }
    val music by lazy { Music(this) }
    val bench by lazy { Bench(this) }
    val updates by lazy { SuiteUpdates(this, SuiteUpdater(this, "KrustyLeBot/graveyard", "$packageName.fileprovider"), scope) }
    val poseidon: Source by lazy {
        PoseidonScans.TYPE.create(SourceConfig("poseidon", PoseidonScans.TYPE.id, PoseidonScans.TYPE.name, PoseidonScans.TYPE.defaultBaseUrl), http)
    }

    /** An EPUB handed to the app by "Ouvrir avec", waiting for the Livre tab to import it. */
    var pendingBook by mutableStateOf<Uri?>(null)

    /** Covers: Coil with the sources' client (cookies, user agent). */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { http.images })) }
        .crossfade(true)
        .build()
}
