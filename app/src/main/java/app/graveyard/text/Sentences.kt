package app.graveyard.text

/**
 * Cuts a text into pieces a voice reads one at a time: whole sentences, split again at a comma or
 * a space when longer than [MAX], and glued to the next one when very short. Short pieces keep the
 * first sound quick to come and make a sentence the unit of highlighting and of position.
 */
object Sentences {
    const val MAX = 280
    private const val MIN = 40
    private val END = Regex("""(?<=[.!?…])["»”)\]]*\s+(?=[\p{Lu}\p{N}«"“—–-])""")

    private val DASH = Regex("""^\s*[—–-]\s+""")

    fun split(text: String): List<String> {
        val clean = normalize(text)
        if (clean.isEmpty()) return emptyList()
        val sentences = clean.split(END).map { it.replace(DASH, "").trim() }.filter { it.isNotEmpty() }.flatMap { shorten(it) }
        val out = mutableListOf<String>()
        for (s in sentences) {
            val last = out.lastOrNull()
            if (last != null && last.length < MIN && last.length + s.length + 1 <= MAX) out[out.lastIndex] = "$last $s" else out += s
        }
        return out
    }

    /** What a voice should not stumble on: odd spaces, typographic dashes opening a line of dialogue. */
    fun normalize(text: String): String = text
        .replace('\u00A0', ' ').replace('\u202F', ' ').replace('\u2009', ' ')
        .replace(DASH, "")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun shorten(sentence: String): List<String> {
        if (sentence.length <= MAX) return listOf(sentence)
        val out = mutableListOf<String>()
        var rest = sentence
        while (rest.length > MAX) {
            val window = rest.substring(0, MAX)
            val cut = listOf(window.lastIndexOf("; "), window.lastIndexOf(": "), window.lastIndexOf(", "))
                .filter { it > MAX / 3 }.maxOrNull()?.plus(1)
                ?: window.lastIndexOf(' ').takeIf { it > 0 }
                ?: MAX
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }
}
