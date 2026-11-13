package app.graveyard.book

import android.content.Context
import android.net.Uri
import app.graveyard.text.Sentences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.File
import java.io.IOException

data class BookFile(val file: File, val title: String, val authors: String)

/** A chapter cut into the pieces the voice reads; [index] is its place in the book's reading order. */
data class BookChapter(val index: Int, val title: String, val pieces: List<String>) {
    val characters: Int get() = pieces.sumOf { it.length }
}

/** The EPUBs imported into the app (`files/books/`), kept private: this is a test bench, not a library. */
class Books(private val context: Context) {
    private val dir = File(context.filesDir, "books").apply { mkdirs() }

    fun list(): List<BookFile> = dir.listFiles { f -> f.extension.equals("epub", true) }.orEmpty()
        .sortedByDescending { it.lastModified() }
        .mapNotNull { f -> runCatching { Epub(f).use { BookFile(f, it.title, it.authors.joinToString(", ")) } }.getOrNull() }

    /** Copies the EPUB behind [uri] into the app and checks it opens. */
    suspend fun import(uri: Uri): BookFile = withContext(Dispatchers.IO) {
        val temp = File(dir, "import-${System.currentTimeMillis()}.part")
        try {
            (context.contentResolver.openInputStream(uri) ?: throw IOException("Fichier illisible")).use { input ->
                temp.outputStream().use { input.copyTo(it) }
            }
            val (title, authors) = Epub(temp).use { it.title to it.authors.joinToString(", ") }
            val name = title.replace(Regex("""[^\p{L}\p{N} _-]"""), "").trim().take(60).ifEmpty { "livre" }
            val target = File(dir, "$name.epub")
            target.delete()
            if (!temp.renameTo(target)) throw IOException("Impossible d'enregistrer le livre")
            BookFile(target, title, authors)
        } finally {
            temp.delete()
        }
    }

    fun delete(book: BookFile) {
        book.file.delete()
    }

    /** The chapters that hold text, each cut into pieces. */
    suspend fun chapters(book: BookFile): List<BookChapter> = withContext(Dispatchers.Default) {
        Epub(book.file).use { epub ->
            epub.spine.mapIndexedNotNull { i, path ->
                val html = epub.open(path)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: return@mapIndexedNotNull null
                val pieces = paragraphs(html).flatMap { Sentences.split(it) }
                if (pieces.isEmpty()) return@mapIndexedNotNull null
                val title = epub.chapterTitle(i) ?: "Section ${i + 1}"
                BookChapter(i, title, pieces)
            }
        }
    }

    companion object {
        private const val BLOCKS = "p, h1, h2, h3, h4, h5, h6, li, blockquote, dt, dd"

        /** The text of a chapter, one entry per paragraph or heading, without markup. */
        fun paragraphs(html: String): List<String> {
            val body = Jsoup.parse(html).body()
            body.select("script, style, sup, rt").remove()
            val blocks = body.select(BLOCKS).filter { e -> e.select(BLOCKS).size == 1 }
            val texts = if (blocks.isEmpty()) listOf(body.text()) else blocks.map { it.text() }
            return texts.map { it.trim() }.filter { it.isNotEmpty() }
        }
    }
}
