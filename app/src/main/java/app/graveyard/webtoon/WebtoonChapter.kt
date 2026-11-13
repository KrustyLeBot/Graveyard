package app.graveyard.webtoon

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import app.graveyard.ocr.Ocr
import app.graveyard.ocr.OcrBlock
import app.graveyard.source.ChapterContent
import app.graveyard.source.ChapterInfo
import app.graveyard.source.Http
import app.graveyard.source.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** A horizontal band of a page: what one row of the reader shows. */
data class Tile(val page: Int, val top: Int, val height: Int, val pageWidth: Int)

/** A text block placed in the chapter: [tile] is the row it starts in, [block] is in page pixels. */
data class Bubble(val tile: Int, val page: Int, val block: OcrBlock)

/**
 * A chapter being read: its pages are downloaded one after the other into the cache, cut into
 * bands for display (a page can be far taller than a bitmap may be), and read by the text
 * recogniser. [tiles] and [bubbles] grow as pages arrive, in reading order.
 */
class WebtoonChapter(
    private val source: Source,
    private val http: Http,
    private val cacheDir: File,
    val slug: String,
    val chapter: ChapterInfo,
    private val scope: CoroutineScope,
) {
    val tiles = mutableStateListOf<Tile>()
    val bubbles = mutableStateListOf<Bubble>()
    var pageCount by mutableStateOf(0)
        private set
    var pagesReady by mutableStateOf(0)
        private set
    /** Every page is downloaded and recognised: no more bubbles will come. */
    var complete by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** Time spent recognising text, to judge the cost of OCR on this phone. */
    var ocrMs by mutableStateOf(0L)
        private set

    private val decoders = mutableMapOf<Int, BitmapRegionDecoder>()
    private val ocr = Ocr()
    private var job: Job? = null

    fun load() {
        job?.cancel()
        error = null
        job = scope.launch {
            try {
                val content = source.content(slug, chapter)
                val urls = (content as? ChapterContent.Images)?.urls ?: throw IOException("Ce chapitre n'est pas en images")
                pageCount = urls.size
                urls.forEachIndexed { page, url ->
                    val file = download(url)
                    val decoder = withContext(Dispatchers.IO) { newDecoder(file) }
                    synchronized(decoders) { decoders[page] = decoder }
                    val firstTile = tiles.size
                    val newTiles = buildList {
                        var top = 0
                        while (top < decoder.height) {
                            val h = minOf(TILE, decoder.height - top)
                            add(Tile(page, top, h, decoder.width))
                            top += h
                        }
                    }
                    tiles.addAll(newTiles)
                    val started = System.nanoTime()
                    val blocks = ocr.page(decoder, decoder.width, decoder.height)
                    ocrMs += (System.nanoTime() - started) / 1_000_000
                    bubbles.addAll(blocks.map { b -> Bubble(firstTile + (b.top / TILE).coerceAtMost(newTiles.lastIndex), page, b) })
                    pagesReady = page + 1
                }
                complete = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.message ?: e.toString()
            }
        }
    }

    /** The text of bubble [index], waiting for it while pages are still coming; null past the last one. */
    suspend fun bubbleText(index: Int): String? {
        snapshotFlow { bubbles.size > index || complete || error != null }.first { it }
        return bubbles.getOrNull(index)?.block?.text
    }

    /** The band [tile] as a bitmap no wider than [maxWidth]. */
    suspend fun bitmap(tile: Tile, maxWidth: Int): Bitmap? = withContext(Dispatchers.IO) {
        val decoder = synchronized(decoders) { decoders[tile.page] } ?: return@withContext null
        var sample = 1
        while (tile.pageWidth / (sample * 2) >= maxWidth) sample *= 2
        runCatching {
            decoder.decodeRegion(Rect(0, tile.top, tile.pageWidth, tile.top + tile.height), BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }

    fun close() {
        job?.cancel()
        ocr.close()
        synchronized(decoders) {
            decoders.values.forEach { runCatching { it.recycle() } }
            decoders.clear()
        }
    }

    private suspend fun download(url: String): File = withContext(Dispatchers.IO) {
        val dir = File(cacheDir, "pages").apply { mkdirs() }
        val name = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(dir, name)
        if (file.exists() && file.length() > 0) return@withContext file
        val part = File(dir, "$name.part")
        val request = Request.Builder().url(url).apply { source.imageHeaders().forEach { (k, v) -> header(k, v) } }.build()
        try {
            http.images.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Page non chargée (HTTP ${response.code})")
                part.outputStream().use { out -> response.body?.byteStream()?.copyTo(out) ?: throw IOException("Page vide") }
            }
            if (!part.renameTo(file)) throw IOException("Impossible d'enregistrer la page")
        } finally {
            part.delete()
        }
        file
    }

    /**
     * Region decoding only exists for JPEG, PNG and WebP. Any other format the phone can decode
     * (AVIF…) is decoded whole once and kept as a JPEG next to the original.
     */
    @Suppress("DEPRECATION")
    private fun newDecoder(file: File): BitmapRegionDecoder {
        runCatching { BitmapRegionDecoder.newInstance(file.path, false) }.getOrNull()?.let { return it }
        val converted = File(file.path + ".jpg")
        if (!converted.exists()) {
            val bitmap = BitmapFactory.decodeFile(file.path) ?: throw IOException("Image illisible")
            try {
                converted.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            } finally {
                bitmap.recycle()
            }
        }
        return BitmapRegionDecoder.newInstance(converted.path, false) ?: throw IOException("Image illisible")
    }

    companion object {
        /** Height of a display band, in page pixels. */
        const val TILE = 1024
    }
}
