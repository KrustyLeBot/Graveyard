package app.graveyard.tts

enum class Family(val label: String) {
    PIPER("Piper"), KOKORO("Kokoro"), SUPERTONIC("Supertonic 3"), POCKET("Pocket TTS")
}

/** A file of a Hugging Face repository, or a whole folder when [path] ends with '/', saved as [to]. */
data class Fetch(val repo: String, val path: String, val to: String = path)

/** One way to use a model: a speaker of a multi-speaker model, or a reference recording to imitate. */
data class Variant(val label: String, val sid: Int = 0, val reference: String? = null)

/**
 * A voice the bench can download and run. Every entry was run once on a PC with the same engine
 * version and produced French speech; how it sounds and how fast it is on a phone is what the app
 * is for.
 */
data class VoiceSpec(
    val id: String,
    val name: String,
    val family: Family,
    val sizeMb: Int,
    val note: String,
    val fetch: List<Fetch>,
    val variants: List<Variant> = listOf(Variant("Voix unique")),
    /** Piper and Kokoro read text through espeak-ng's data, shared by all of them. */
    val needsEspeak: Boolean = false,
    /** Some models come out quiet: a fixed gain keeps the comparison fair. */
    val gain: Float = 1f,
)

object Voices {
    const val ESPEAK_REPO = "csukuangfj/vits-piper-fr_FR-siwis-medium"
    const val ESPEAK_MB = 18

    private fun piper(id: String, name: String, voice: String, sizeMb: Int, note: String, variants: List<Variant> = listOf(Variant("Voix unique")), gain: Float = 1f) =
        VoiceSpec(
            id, name, Family.PIPER, sizeMb, note,
            fetch = listOf(
                Fetch("csukuangfj/vits-piper-fr_FR-$voice", "fr_FR-$voice.onnx", "model.onnx"),
                Fetch("csukuangfj/vits-piper-fr_FR-$voice", "tokens.txt"),
            ),
            variants = variants, needsEspeak = true, gain = gain,
        )

    private const val KYUTAI_VOICES = "kyutai/tts-voices"
    private const val POCKET = "haex-space/pocket-tts-sherpa-onnx"

    val all: List<VoiceSpec> = listOf(
        piper("piper-siwis", "Piper · Siwis", "siwis-medium", 63, "Voix féminine. Le plus léger et le plus rapide : sert de référence de vitesse."),
        piper(
            "piper-upmc", "Piper · UPMC", "upmc-medium", 77, "Deux locuteurs dans le même modèle.",
            variants = listOf(Variant("Jessica", 0), Variant("Pierre", 1)),
        ),
        piper("piper-tom", "Piper · Tom", "tom-medium", 64, "Voix masculine, en 44 kHz."),
        piper("piper-miro", "Piper · Miro (haute qualité)", "miro-high", 63, "Modèle Piper de qualité « high ».", gain = 3f),
        VoiceSpec(
            "kokoro", "Kokoro · Siwis", Family.KOKORO, 143,
            "Narration plus naturelle que Piper. Une seule voix française. Vitesse à mesurer sur le téléphone.",
            fetch = listOf(
                Fetch("csukuangfj/kokoro-int8-multi-lang-v1_0", "model.int8.onnx"),
                Fetch("csukuangfj/kokoro-int8-multi-lang-v1_0", "voices.bin"),
                Fetch("csukuangfj/kokoro-int8-multi-lang-v1_0", "tokens.txt"),
            ),
            variants = listOf(Variant("ff_siwis", 30)),
            needsEspeak = true,
        ),
        VoiceSpec(
            "supertonic", "Supertonic 3", Family.SUPERTONIC, 146,
            "Modèle récent, 31 langues, dix voix. Très rapide sur PC.",
            fetch = listOf("duration_predictor.int8.onnx", "text_encoder.int8.onnx", "vector_estimator.int8.onnx", "vocoder.int8.onnx", "tts.json", "unicode_indexer.bin", "voice.bin")
                .map { Fetch("csukuangfj2/sherpa-onnx-supertonic-3-tts-int8-2026-05-11", it) },
            variants = (0 until 10).map { Variant("Voix ${it + 1}", it) },
        ),
        VoiceSpec(
            "pocket", "Pocket TTS (Kyutai)", Family.POCKET, 380,
            "Imite une voix à partir d'un court enregistrement : la plus expressive de la liste. " +
                "Plus lente que la lecture sur PC, donc sûrement trop lente en direct sur téléphone.",
            fetch = listOf("lm_flow.int8.onnx", "lm_main.int8.onnx", "encoder.onnx", "decoder.int8.onnx", "text_conditioner.onnx", "vocab.json", "token_scores.json")
                .map { Fetch(POCKET, "fr/$it", it) } + listOf(
                Fetch(KYUTAI_VOICES, "cml-tts/fr/10087_11650_000028-0002_enhanced.wav", "ref/lecteur-1.wav"),
                Fetch(KYUTAI_VOICES, "cml-tts/fr/10177_10625_000134-0003_enhanced.wav", "ref/lecteur-2.wav"),
                Fetch(KYUTAI_VOICES, "unmute-prod-website/developpeuse-3.wav", "ref/developpeuse.wav"),
                Fetch(KYUTAI_VOICES, "unmute-prod-website/fabieng-enhanced-v2.wav", "ref/fabien.wav"),
            ),
            variants = listOf(
                Variant("Lecteur de livre audio 1", reference = "ref/lecteur-1.wav"),
                Variant("Lecteur de livre audio 2", reference = "ref/lecteur-2.wav"),
                Variant("Développeuse", reference = "ref/developpeuse.wav"),
                Variant("Fabien", reference = "ref/fabien.wav"),
                Variant("Mon enregistrement (WAV)", reference = CUSTOM_REFERENCE),
            ),
        ),
    )

    /** The variant whose reference is a WAV the user picked, copied to this name in the voice's folder. */
    const val CUSTOM_REFERENCE = "ref/custom.wav"

    fun byId(id: String?) = all.firstOrNull { it.id == id }
}
