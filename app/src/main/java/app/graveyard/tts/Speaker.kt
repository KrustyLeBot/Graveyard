package app.graveyard.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the reading cost so far. [genMs] over [audioMs] is the real-time factor: under 1 the phone
 * generates faster than it reads. [stallMs] is the silence heard while waiting for the next piece.
 */
data class SpeakStats(
    val pieces: Int = 0,
    val genMs: Long = 0,
    val audioMs: Long = 0,
    val stallMs: Long = 0,
    val firstMs: Long = 0,
    val loadMs: Long = 0,
) {
    val rtf: Float get() = if (audioMs > 0) genMs.toFloat() / audioMs else 0f
}

data class SpeakState(
    val active: Boolean = false,
    val loading: Boolean = false,
    val paused: Boolean = false,
    /** The piece being heard, counted in the caller's own numbering. */
    val index: Int = -1,
    val voice: String = "",
    val stats: SpeakStats = SpeakStats(),
    val error: String? = null,
)

/**
 * Reads pieces of text aloud with the chosen voice: one coroutine generates up to two pieces
 * ahead, another plays them. The caller hands the text piece by piece, so the source can still be
 * growing (a webtoon being recognised) while the reading goes on.
 */
class Speaker(private val context: Context, private val hub: VoiceHub, private val scope: CoroutineScope) {
    var state by mutableStateOf(SpeakState())
        private set
    private var job: Job? = null
    private val paused = MutableStateFlow(false)
    /** Counts readings, so one that was replaced does not stop the service of its successor. */
    private var session = 0

    private class Piece(val index: Int, val samples: FloatArray, val genMs: Long)

    /** Reads from piece [from]; [source] returns null after the last one. [gapMs] is a silence after each piece. */
    fun play(from: Int, gapMs: Int = 0, source: suspend (Int) -> String?) {
        stop()
        paused.value = false
        state = SpeakState(active = true, loading = true, index = from)
        val mine = ++session
        job = scope.launch {
            var track: AudioTrack? = null
            try {
                PlaybackService.start(context)
                val started = System.nanoTime()
                val synth = hub.synth()
                val variant = hub.variant(synth.spec)
                val speed = hub.speed
                state = state.copy(voice = "${synth.spec.name} · ${variant.label}", stats = SpeakStats(loadMs = synth.loadMs))
                val rate = synth.sampleRate
                track = track(rate).also { it.play() }
                val pieces = Channel<Piece>(capacity = 2)
                val producer = launch(Dispatchers.Default) {
                    var i = from
                    try {
                        while (isActive) {
                            val text = source(i) ?: break
                            val t0 = System.nanoTime()
                            val samples = if (text.isBlank()) FloatArray(0) else synth.generate(text, variant, speed)
                            pieces.send(Piece(i, samples, (System.nanoTime() - t0) / 1_000_000))
                            i++
                        }
                        pieces.close()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        pieces.close(e)
                    }
                }
                var first = true
                while (true) {
                    val waitFrom = System.nanoTime()
                    val piece = pieces.receiveCatching().let { r -> r.exceptionOrNull()?.let { throw it }; r.getOrNull() } ?: break
                    val waited = (System.nanoTime() - waitFrom) / 1_000_000
                    val s = state.stats
                    state = state.copy(
                        loading = false, index = piece.index,
                        stats = s.copy(
                            pieces = s.pieces + 1,
                            genMs = s.genMs + piece.genMs,
                            audioMs = s.audioMs + piece.samples.size * 1000L / rate,
                            firstMs = if (first) (System.nanoTime() - started) / 1_000_000 else s.firstMs,
                            // The first wait is the start-up delay, counted apart.
                            stallMs = s.stallMs + if (first) 0 else waited,
                        ),
                    )
                    first = false
                    write(track, piece.samples)
                    if (gapMs > 0) write(track, FloatArray(rate * gapMs / 1000))
                }
                producer.join()
                // Let the last buffer (half a second) play out before the track goes away.
                track.stop()
                delay(700)
                state = state.copy(active = false, loading = false, paused = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                state = state.copy(active = false, loading = false, error = e.message ?: e.toString())
            } finally {
                runCatching { track?.pause(); track?.flush(); track?.release() }
                if (session == mine) PlaybackService.stop(context)
            }
        }
    }

    private suspend fun write(track: AudioTrack, samples: FloatArray) = withContext(Dispatchers.IO) {
        val slice = track.sampleRate / 5
        var at = 0
        while (at < samples.size) {
            ensureActive()
            if (paused.value) {
                track.pause()
                paused.first { !it }
                track.play()
            }
            val n = track.write(samples, at, minOf(slice, samples.size - at), AudioTrack.WRITE_BLOCKING)
            if (n <= 0) break
            at += n
        }
    }

    private fun track(rate: Int): AudioTrack {
        val format = AudioFormat.Builder()
            .setSampleRate(rate)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            // Half a second: short enough for the highlighted sentence to follow what is heard.
            .setBufferSizeInBytes(maxOf(min, rate * 4 / 2))
            .build()
    }

    fun pause() {
        if (!state.active) return
        paused.value = true
        state = state.copy(paused = true)
    }

    fun resume() {
        paused.value = false
        state = state.copy(paused = false)
    }

    fun stop() {
        job?.cancel()
        job = null
        session++
        PlaybackService.stop(context)
        paused.value = false
        if (state.active) state = state.copy(active = false, loading = false, paused = false)
    }
}
