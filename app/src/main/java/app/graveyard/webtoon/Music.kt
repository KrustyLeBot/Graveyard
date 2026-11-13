package app.graveyard.webtoon

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Background music while reading: one audio file the user picked, played in a loop under the voice. */
class Music(private val context: Context) {
    private val prefs = context.getSharedPreferences("music", Context.MODE_PRIVATE)
    private var player: MediaPlayer? = null

    var uri by mutableStateOf(prefs.getString("uri", null)?.let(Uri::parse))
        private set
    var name by mutableStateOf(prefs.getString("name", null))
        private set
    var volume by mutableFloatStateOf(prefs.getFloat("volume", 0.25f))
        private set
    var playing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun choose(picked: Uri, label: String) {
        // The permission must outlive this launch for the same file to play next time.
        runCatching { context.contentResolver.takePersistableUriPermission(picked, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        stop()
        uri = picked
        name = label
        prefs.edit().putString("uri", picked.toString()).putString("name", label).apply()
    }

    fun changeVolume(v: Float) {
        volume = v
        prefs.edit().putFloat("volume", v).apply()
        player?.setVolume(v, v)
    }

    fun play() {
        val source = uri ?: return
        stop()
        error = null
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setDataSource(context, source)
                isLooping = true
                setVolume(volume, volume)
                prepare()
                start()
            }
            playing = true
        } catch (e: Exception) {
            player?.release()
            player = null
            error = "Musique illisible : ${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun stop() {
        player?.runCatching { stop(); release() }
        player = null
        playing = false
    }
}
