package app.graveyard.book

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import app.graveyard.text.Sentences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.File
import java.io.IOException

data class BookFile(val file: File, val title: String, val authors: String)

/** An EPUB found in the folder the user chose, not imported yet. */
data class FolderBook(val name: String, val uri: Uri)

/** A chapter cut into the pieces the voice reads; [index] is its place in the book's reading order. */
data class BookChapter(val index: Int, val title: String, val pieces: List<String>) {
    val characters: Int get() = pieces.sumOf { it.length }
}

/** The EPUBs imported into the app (`files/books/`), kept private: this is a test bench, not a library. */
class Books(private val context: Context) {
    private val dir = File(context.filesDir, "books").apply { mkdirs() }

    private val prefs = context.getSharedPreferences("books", Context.MODE_PRIVATE)

    /** The folder the user downloads EPUBs into, picked once with the system folder chooser. */
    var folder: Uri? = prefs.getString("folder", null)?.let(Uri::parse)
        private set

    fun chooseFolder(tree: Uri) {
        // The permission must outlive this launch for the folder to be listed next time.
        runCatching { context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        folder = tree
        prefs.edit().putString("folder", tree.toString()).apply()
    }

    /** The EPUBs of the chosen folder and of its sub-folders (two levels down), newest first. */
    suspend fun folderBooks(): List<FolderBook> = withContext(Dispatchers.IO) {
        val tree = folder ?: return@withContext emptyList()
        val found = mutableListOf<Pair<Long, FolderBook>>()
        fun scan(documentId: String, depth: Int) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
            val columns = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
            val folders = mutableListOf<String>()
            context.contentResolver.query(children, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1).orEmpty()
                    when {
                        c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR -> folders += id
                        name.endsWith(".epub", ignoreCase = true) ->
                            found += c.getLong(3) to FolderBook(name, DocumentsContract.buildDocumentUriUsingTree(tree, id))
                    }
                }
            } ?: throw IOException("Dossier illisible : choisis-le à nouveau")
            if (depth < 2) folders.forEach { scan(it, depth + 1) }
        }
        scan(DocumentsContract.getTreeDocumentId(tree), 0)
        found.sortedByDescending { it.first }.map { it.second }
    }

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
