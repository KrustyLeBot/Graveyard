package app.graveyard.tts

import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.io.IOException

/**
 * One voice loaded in memory. Not thread-safe: one generation at a time. The settings of each
 * family are the ones that produced French on a PC with the same engine (see the README).
 */
class Synth(val spec: VoiceSpec, store: ModelStore, val threads: Int) {
    private val dir = store.dir(spec)
    private val tts: OfflineTts
    private var reference: Pair<String, Pcm>? = null

    /** How long the model took to load, in milliseconds. */
    val loadMs: Long
    val sampleRate: Int

    init {
        val started = System.nanoTime()
        fun path(name: String) = File(dir, name).path
        val model = OfflineTtsModelConfig()
        model.numThreads = threads
        when (spec.family) {
            Family.PIPER -> model.vits = OfflineTtsVitsModelConfig().apply {
                this.model = path("model.onnx")
                tokens = path("tokens.txt")
                dataDir = store.espeakDir.path
            }
            Family.KOKORO -> model.kokoro = OfflineTtsKokoroModelConfig().apply {
                this.model = path("model.int8.onnx")
                voices = path("voices.bin")
                tokens = path("tokens.txt")
                dataDir = store.espeakDir.path
                lang = "fr"
            }
            Family.SUPERTONIC -> model.supertonic = OfflineTtsSupertonicModelConfig().apply {
                durationPredictor = path("duration_predictor.int8.onnx")
                textEncoder = path("text_encoder.int8.onnx")
                vectorEstimator = path("vector_estimator.int8.onnx")
                vocoder = path("vocoder.int8.onnx")
                ttsJson = path("tts.json")
                unicodeIndexer = path("unicode_indexer.bin")
                voiceStyle = path("voice.bin")
            }
            Family.POCKET -> model.pocket = OfflineTtsPocketModelConfig().apply {
                lmFlow = path("lm_flow.int8.onnx")
                lmMain = path("lm_main.int8.onnx")
                encoder = path("encoder.onnx")
                decoder = path("decoder.int8.onnx")
                textConditioner = path("text_conditioner.onnx")
                vocabJson = path("vocab.json")
                tokenScoresJson = path("token_scores.json")
            }
        }
        val config = OfflineTtsConfig()
        config.model = model
        tts = OfflineTts(null, config)
        sampleRate = tts.sampleRate()
        if (sampleRate <= 0) {
            tts.release()
            throw IOException("Le modèle ${spec.name} ne s'est pas chargé")
        }
        loadMs = (System.nanoTime() - started) / 1_000_000
    }

    /** Speech for [text]; empty when the model produced nothing. [speed] 1 is the voice's own pace. */
    fun generate(text: String, variant: Variant, speed: Float): FloatArray {
        val config = GenerationConfig()
        config.sid = variant.sid
        config.speed = speed
        when (spec.family) {
            Family.SUPERTONIC -> {
                config.numSteps = 5
                config.extra = mapOf("lang" to "fr")
            }
            Family.POCKET -> {
                val name = variant.reference ?: throw IOException("Pocket TTS a besoin d'un enregistrement de référence")
                val pcm = reference?.takeIf { it.first == name }?.second ?: run {
                    val file = File(dir, name)
                    if (!file.exists()) throw IOException("Choisis d'abord un enregistrement WAV pour cette voix")
                    Wav.read(file).also { reference = name to it }
                }
                config.referenceAudio = pcm.samples
                config.referenceSampleRate = pcm.sampleRate
                config.numSteps = 5
            }
            else -> Unit
        }
        val samples = tts.generateWithConfig(text, config).samples
        if (spec.gain != 1f) for (i in samples.indices) samples[i] = (samples[i] * spec.gain).coerceIn(-1f, 1f)
        return samples
    }

    fun release() = tts.release()
}
