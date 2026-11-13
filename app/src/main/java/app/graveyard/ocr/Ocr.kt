package app.graveyard.ocr

import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A piece of text found on a page, in the page's own pixels. [text] is cleaned for reading aloud. */
data class OcrBlock(val text: String, val raw: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * Text recognition on a webtoon page with ML Kit's bundled Latin model: nothing leaves the phone.
 * A page can be tens of thousands of pixels tall, far more than the recogniser accepts, so it is
 * read in overlapping bands; a block belongs to the band its top edge falls in.
 */
class Ocr {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun page(decoder: BitmapRegionDecoder, width: Int, height: Int): List<OcrBlock> {
        val band = minOf(height, (width * 2).coerceIn(1200, 2400))
        val overlap = minOf(band / 3, 600)
        val step = (band - overlap).coerceAtLeast(1)
        val blocks = mutableListOf<OcrBlock>()
        var top = 0
        while (top < height) {
            val bottom = minOf(height, top + band)
            val last = bottom == height
            val bitmap = withContext(Dispatchers.IO) {
                decoder.decodeRegion(Rect(0, top, width, bottom), BitmapFactory.Options())
            } ?: break
            val found = try {
                recognize(InputImage.fromBitmap(bitmap, 0))
            } finally {
                bitmap.recycle()
            }
            for (block in found.textBlocks) {
                val box = block.boundingBox ?: continue
                // The same text shows in two bands where they overlap: only the band it starts in keeps it.
                if (!last && box.top >= step) continue
                val lines = block.lines.map { it.text }
                val text = OcrText.clean(lines) ?: continue
                blocks += OcrBlock(text, lines.joinToString("\n"), box.left, top + box.top, box.right, top + box.bottom)
            }
            if (last) break
            top += step
        }
        // Reading order of a vertical strip: top to bottom, left to right on a same line.
        return blocks.distinctBy { it.top / 8 to it.raw }.sortedWith(compareBy({ it.top / 40 }, { it.left }))
    }

    private suspend fun recognize(image: InputImage): Text = suspendCancellableCoroutine { cont ->
        recognizer.process(image)
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    fun close() = recognizer.close()
}
