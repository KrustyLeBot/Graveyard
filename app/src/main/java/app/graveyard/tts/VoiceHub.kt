package app.graveyard.tts

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The voice every screen reads with: which model, which variant, how many threads and how fast,
 * remembered between launches. Only one model stays loaded at a time.
 */
class VoiceHub(context: Context, val store: ModelStore) {
    private val prefs = context.getSharedPreferences("voices", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private var loaded: Synth? = null

    var activeId by mutableStateOf(prefs.getString("active", null))
        private set
    var threads by mutableIntStateOf(prefs.getInt("threads", 4))
        private set
    var speed by mutableFloatStateOf(prefs.getFloat("speed", 1f))
        private set
    private val variants = mutableStateMapOf<String, Int>().apply {
        Voices.all.forEach { put(it.id, prefs.getInt("variant-${it.id}", 0).coerceIn(0, it.variants.lastIndex)) }
    }

    val active: VoiceSpec? get() = Voices.byId(activeId)?.takeIf { store.installed(it) }

    fun variant(spec: VoiceSpec): Variant = spec.variants[variants[spec.id] ?: 0]

    fun variantIndex(spec: VoiceSpec): Int = variants[spec.id] ?: 0

    fun select(spec: VoiceSpec) {
        activeId = spec.id
        prefs.edit().putString("active", spec.id).apply()
    }

    fun setVariant(spec: VoiceSpec, index: Int) {
        variants[spec.id] = index
        prefs.edit().putInt("variant-${spec.id}", index).apply()
    }

    fun changeThreads(n: Int) {
        threads = n
        prefs.edit().putInt("threads", n).apply()
    }

    fun changeSpeed(v: Float) {
        speed = v
        prefs.edit().putFloat("speed", v).apply()
    }

    /** The model of [spec], loading it (and freeing the previous one) when needed. */
    suspend fun synth(spec: VoiceSpec): Synth = lock.withLock {
        loaded?.takeIf { it.spec.id == spec.id && it.threads == threads }?.let { return it }
        withContext(Dispatchers.Default) {
            loaded?.release()
            loaded = null
            if (!store.installed(spec)) throw IOException("La voix ${spec.name} n'est pas téléchargée")
            Synth(spec, store, threads).also { loaded = it }
        }
    }

    /** The model of the voice chosen in the Voix tab. */
    suspend fun synth(): Synth = synth(active ?: throw IOException("Choisis d'abord une voix dans l'onglet Voix"))

    suspend fun unload(spec: VoiceSpec) = lock.withLock {
        if (loaded?.spec?.id == spec.id) {
            loaded?.release()
            loaded = null
        }
    }
}
