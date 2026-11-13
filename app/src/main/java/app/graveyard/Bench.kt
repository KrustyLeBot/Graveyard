package app.graveyard

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import app.graveyard.tts.SpeakStats
import org.json.JSONObject

/** The last measured reading of each voice, kept between launches so voices can be compared side by side. */
class Bench(context: Context) {
    private val prefs = context.getSharedPreferences("bench", Context.MODE_PRIVATE)
    val results = mutableStateMapOf<String, SpeakStats>()

    init {
        prefs.all.forEach { (id, raw) ->
            runCatching {
                val o = JSONObject(raw as String)
                results[id] = SpeakStats(o.getInt("pieces"), o.getLong("gen"), o.getLong("audio"), o.getLong("stall"), o.getLong("first"), o.getLong("load"))
            }
        }
    }

    fun record(id: String, stats: SpeakStats) {
        if (stats.pieces == 0 || stats.audioMs == 0L) return
        results[id] = stats
        val o = JSONObject().put("pieces", stats.pieces).put("gen", stats.genMs).put("audio", stats.audioMs)
            .put("stall", stats.stallMs).put("first", stats.firstMs).put("load", stats.loadMs)
        prefs.edit().putString(id, o.toString()).apply()
    }

    companion object {
        const val SAMPLE = "Le 14 juillet 1789, à l'aube, Paris s'éveilla dans un grondement sourd. " +
            "Louis XVI, lui, dormait encore à Versailles, à vingt kilomètres de là. " +
            "— Vous venez avec nous ? demanda Camille en ajustant son chapeau. " +
            "Elle hésita, regarda la foule qui grossissait sous les fenêtres, puis répondit d'une voix blanche : " +
            "« Non, pas ce soir… Demain, peut-être. » " +
            "Il haussa les épaules. Qu'importait, au fond ! Dans trois heures, tout serait joué."

        /** "0,42" style: generation time over audio time. */
        fun describe(s: SpeakStats): String {
            fun sec(ms: Long) = "%.1f s".format(ms / 1000f).replace('.', ',')
            val ratio = "%.2f".format(s.rtf).replace('.', ',')
            val verdict = when {
                s.rtf < 0.5f -> "lecture en direct possible"
                s.rtf < 1f -> "en direct, de justesse"
                s.rtf < 3f -> "à préparer à l'avance"
                else -> "trop lent pour un livre"
            }
            return "Génération ÷ audio : $ratio ($verdict)\n" +
                "Premier son : ${sec(s.firstMs)} · chargement : ${sec(s.loadMs)} · silences subis : ${sec(s.stallMs)}\n" +
                "${sec(s.audioMs)} d'audio en ${sec(s.genMs)}, ${s.pieces} morceaux"
        }
    }
}
