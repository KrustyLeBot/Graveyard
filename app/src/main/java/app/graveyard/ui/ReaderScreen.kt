package app.graveyard.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import app.graveyard.GraveyardApp
import app.graveyard.Nav
import app.graveyard.source.ChapterInfo
import app.graveyard.webtoon.Tile
import app.graveyard.webtoon.WebtoonChapter

/**
 * A webtoon chapter with the four things under test: text recognition (frames drawn over the
 * page, text listed on demand), the bubbles read aloud with the page following the voice,
 * automatic scrolling at a set speed, and background music. A tap on the page shows or hides the
 * controls.
 */
@Composable
fun ReaderScreen(app: GraveyardApp, nav: Nav, slug: String, title: String, info: ChapterInfo) {
    val chapter = remember(slug, info.id) {
        WebtoonChapter(app.poseidon, app.http, app.cacheDir, slug, info, app.scope).also { it.load() }
    }
    val speaker = app.speaker
    val speaking = speaker.state
    val music = app.music
    val list = rememberLazyListState()
    val density = LocalDensity.current.density
    val view = LocalView.current
    val context = LocalContext.current

    var controls by remember { mutableStateOf(true) }
    var auto by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(90f) }
    var frames by remember { mutableStateOf(true) }
    var gap by remember { mutableIntStateOf(400) }
    var showText by remember { mutableStateOf(false) }

    DisposableEffect(chapter) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            speaker.stop()
            music.stop()
            chapter.close()
        }
    }

    val pickMusic = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val label = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "Musique"
            music.choose(uri, label)
            music.play()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val widthPx = constraints.maxWidth
        val heightPx = constraints.maxHeight

        // Automatic scrolling, at [speed] dp per second, unless the voice is leading.
        LaunchedEffect(auto, speaking.active, speed) {
            if (!auto || speaking.active) return@LaunchedEffect
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                list.scrollBy(speed * density * (now - last) / 1_000_000_000f)
                last = now
            }
        }

        // The page follows the voice: the bubble being read sits a third of the way down.
        LaunchedEffect(speaking.index, speaking.active, speaking.loading) {
            if (!speaking.active || speaking.loading) return@LaunchedEffect
            val bubble = chapter.bubbles.getOrNull(speaking.index) ?: return@LaunchedEffect
            val tile = chapter.tiles.getOrNull(bubble.tile) ?: return@LaunchedEffect
            val scale = widthPx.toFloat() / tile.pageWidth
            list.animateScrollToItem(bubble.tile, ((bubble.block.top - tile.top) * scale).toInt() - heightPx / 3)
        }

        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { controls = !controls } },
        ) {
            itemsIndexed(chapter.tiles) { index, tile ->
                TileView(chapter, tile, index, widthPx, frames, if (speaking.active) speaking.index else -1)
            }
            item {
                Text(
                    when {
                        chapter.error != null -> "Erreur : ${chapter.error}"
                        chapter.complete -> "Fin du chapitre"
                        chapter.pageCount == 0 -> "Chargement du chapitre…"
                        else -> "Page ${chapter.pagesReady + 1} sur ${chapter.pageCount} en préparation…"
                    },
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                )
            }
        }

        if (controls) {
            Surface(Modifier.align(Alignment.TopCenter).fillMaxWidth(), color = Color.Black.copy(alpha = 0.75f), contentColor = Color.White) {
                Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
                    Column {
                        Text(title, maxLines = 1)
                        Text(info.name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                }
            }
            Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = speaking.active,
                            onClick = {
                                if (speaking.active) speaker.stop() else {
                                    val first = list.firstVisibleItemIndex
                                    val from = chapter.bubbles.indexOfFirst { it.tile >= first }.takeIf { it >= 0 } ?: chapter.bubbles.size
                                    speaker.play(from, gap) { chapter.bubbleText(it) }
                                }
                            },
                            label = { Text(if (speaking.active) "Arrêter la voix" else "Lire les bulles") },
                        )
                        FilterChip(selected = auto, onClick = { auto = !auto }, label = { Text("Défilement") })
                        FilterChip(
                            selected = music.playing,
                            onClick = {
                                when {
                                    music.playing -> music.stop()
                                    music.uri == null -> pickMusic.launch(arrayOf("audio/*"))
                                    else -> music.play()
                                }
                            },
                            label = { Text("Musique") },
                        )
                        FilterChip(selected = frames, onClick = { frames = !frames }, label = { Text("Cadres") })
                        FilterChip(selected = false, onClick = { showText = true }, label = { Text("Texte reconnu") })
                    }
                    if (auto) {
                        Text("Vitesse de défilement : ${speed.toInt()}", style = MaterialTheme.typography.labelMedium)
                        Slider(value = speed, onValueChange = { speed = it }, valueRange = 20f..400f)
                    }
                    Text("Pause entre deux bulles : ${gap / 100 / 10f} s".replace('.', ','), style = MaterialTheme.typography.labelMedium)
                    Slider(value = gap.toFloat(), onValueChange = { gap = (it / 100).toInt() * 100 }, valueRange = 0f..2000f)
                    if (music.uri != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Musique : ${music.name ?: ""}", style = MaterialTheme.typography.labelMedium, maxLines = 1, modifier = Modifier.weight(1f))
                            TextButton(onClick = { pickMusic.launch(arrayOf("audio/*")) }) { Text("Changer") }
                        }
                        Slider(value = music.volume, onValueChange = { music.changeVolume(it) }, valueRange = 0f..1f)
                    }
                    Text(status(chapter, speaking.active, speaking.loading, speaking.voice, speaking.stats.rtf), style = MaterialTheme.typography.bodySmall)
                    (speaking.error ?: music.error)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }

    if (showText) {
        AlertDialog(
            onDismissRequest = { showText = false },
            confirmButton = { TextButton(onClick = { showText = false }) { Text("Fermer") } },
            title = { Text("Texte reconnu (${chapter.bubbles.size})") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(chapter.bubbles) { i, bubble ->
                        Text(
                            "${i + 1}. ${bubble.block.text}",
                            modifier = Modifier.fillMaxWidth().clickable {
                                showText = false
                                speaker.play(i, gap) { chapter.bubbleText(it) }
                            }.padding(vertical = 4.dp),
                        )
                    }
                }
            },
        )
    }
}

private fun status(chapter: WebtoonChapter, speaking: Boolean, loading: Boolean, voice: String, rtf: Float): String {
    val pages = if (chapter.complete) "${chapter.pageCount} pages" else "pages ${chapter.pagesReady}/${chapter.pageCount}"
    val ocr = "${chapter.bubbles.size} textes · OCR ${"%.1f".format(chapter.ocrMs / 1000f).replace('.', ',')} s"
    val speech = when {
        !speaking -> ""
        loading -> " · chargement de la voix…"
        else -> " · $voice, génération ÷ audio ${"%.2f".format(rtf).replace('.', ',')}"
    }
    return "$pages · $ocr$speech"
}

/** One band of a page, with the recognised blocks framed and the one being read filled in. */
@Composable
private fun TileView(chapter: WebtoonChapter, tile: Tile, index: Int, widthPx: Int, frames: Boolean, reading: Int) {
    val bitmap by produceState<ImageBitmap?>(null, tile, widthPx) { value = chapter.bitmap(tile, widthPx)?.asImageBitmap() }
    Box(Modifier.fillMaxWidth().aspectRatio(tile.pageWidth.toFloat() / tile.height)) {
        bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
        val current = chapter.bubbles.getOrNull(reading)
        if (frames || current != null) {
            Canvas(Modifier.fillMaxSize()) {
                val scale = size.width / tile.pageWidth
                chapter.bubbles.forEachIndexed { i, bubble ->
                    val b = bubble.block
                    if (bubble.page != tile.page || b.bottom <= tile.top || b.top >= tile.top + tile.height) return@forEachIndexed
                    val origin = Offset(b.left * scale, (b.top - tile.top) * scale)
                    val extent = Size((b.right - b.left) * scale, (b.bottom - b.top) * scale)
                    if (i == reading) drawRect(Color(0x55FFC107), origin, extent)
                    if (frames) drawRect(if (i == reading) Color(0xFFFFC107) else Color(0xFF00BCD4), origin, extent, style = Stroke(2.dp.toPx()))
                }
            }
        }
    }
}
