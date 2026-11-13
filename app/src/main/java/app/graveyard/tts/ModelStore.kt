package app.graveyard.tts

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * The voices on the phone: `files/voices/<id>/` per voice, `files/voices/espeak-ng-data/` shared.
 * Models are fetched from Hugging Face when asked for, never bundled: the APK stays small and a
 * model can be added to the list without the others being downloaded again.
 */
class ModelStore(context: Context, private val client: OkHttpClient) {
    private val root = File(context.filesDir, "voices")
    val espeakDir = File(root, "espeak-ng-data")

    fun dir(spec: VoiceSpec) = File(root, spec.id)

    fun installed(spec: VoiceSpec) = File(dir(spec), DONE).exists() && (!spec.needsEspeak || File(espeakDir, DONE).exists())

    fun delete(spec: VoiceSpec) {
        dir(spec).deleteRecursively()
    }

    /** [onProgress] gets 0..1, from the sizes the catalog announces. */
    suspend fun download(spec: VoiceSpec, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val espeak = spec.needsEspeak && !File(espeakDir, DONE).exists()
        val total = (spec.sizeMb + if (espeak) Voices.ESPEAK_MB else 0) * 1_000_000L
        val done = AtomicLong()
        val report = { n: Long -> onProgress((done.addAndGet(n).toFloat() / total).coerceAtMost(1f)) }
        if (espeak) {
            fetch(Fetch(Voices.ESPEAK_REPO, "espeak-ng-data/", ""), espeakDir, report)
            File(espeakDir, DONE).writeText("ok")
        }
        val target = dir(spec)
        spec.fetch.forEach { fetch(it, target, report) }
        File(target, DONE).writeText("ok")
        onProgress(1f)
    }

    private suspend fun fetch(item: Fetch, target: File, report: (Long) -> Unit) = coroutineScope {
        if (!item.path.endsWith("/")) {
            file(item.repo, item.path, File(target, item.to), report)
            return@coroutineScope
        }
        val folder = item.path.trimEnd('/')
        val listing = get("https://huggingface.co/api/models/${item.repo}/tree/main/$folder?recursive=true&limit=1000")
        val entries = JSONArray(listing)
        val gate = Semaphore(6)
        (0 until entries.length()).map { entries.getJSONObject(it) }
            .filter { it.optString("type") == "file" }
            .map { entry ->
                val path = entry.getString("path")
                async { gate.withPermit { file(item.repo, path, File(target, item.to + path.removePrefix("$folder/")), report) } }
            }.awaitAll()
    }

    private suspend fun file(repo: String, path: String, target: File, report: (Long) -> Unit) = withContext(Dispatchers.IO) {
        if (target.exists() && target.length() > 0) {
            report(target.length())
            return@withContext
        }
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        val request = Request.Builder().url("https://huggingface.co/$repo/resolve/main/$path").build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Téléchargement de $path impossible (HTTP ${response.code})")
                val body = response.body ?: throw IOException("Réponse vide pour $path")
                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            report(n.toLong())
                            ensureActive()
                        }
                    }
                }
            }
            if (!part.renameTo(target)) throw IOException("Impossible d'enregistrer $path")
        } finally {
            part.delete()
        }
    }

    private fun get(url: String): String = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Hugging Face a répondu ${response.code}")
        response.body?.string().orEmpty()
    }

    private companion object {
        const val DONE = ".complete"
    }
}
