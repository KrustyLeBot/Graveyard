package app.graveyard.ocr

import java.util.Locale

/** Turns the raw lines of a recognised block into a sentence a voice can read, or null for noise. */
object OcrText {
    private val NOISE = Regex("""poseidon|scans?\b|\.com\b|\.net\b|\.fr\b|discord|https?:|www\.""", RegexOption.IGNORE_CASE)

    fun clean(lines: List<String>): String? {
        val joined = StringBuilder()
        for (line in lines.map { it.trim() }.filter { it.isNotEmpty() }) {
            when {
                joined.isEmpty() -> joined.append(line)
                // Lettering breaks words across lines with a hyphen.
                joined.endsWith("-") && joined.length > 1 && joined[joined.length - 2].isLetter() && line.first().isLetter() -> {
                    joined.setLength(joined.length - 1)
                    joined.append(line)
                }
                else -> joined.append(' ').append(line)
            }
        }
        var text = joined.toString().replace(Regex("""\s+"""), " ").trim()
        val letters = text.count { it.isLetter() }
        if (letters < 2 || letters * 2 < text.length) return null
        if (NOISE.containsMatchIn(text)) return null
        // Comics are lettered in capitals, which some voices spell out letter by letter.
        if (text.count { it.isUpperCase() } * 10 >= letters * 7) text = sentenceCase(text)
        return text
    }

    private fun sentenceCase(text: String): String {
        val lower = text.lowercase(Locale.FRENCH)
        val out = StringBuilder(lower.length)
        var start = true
        for (c in lower) {
            if (start && c.isLetter()) {
                out.append(c.titlecase(Locale.FRENCH))
                start = false
            } else {
                out.append(c)
                if (c == '.' || c == '!' || c == '?' || c == '…') start = true
            }
        }
        return out.toString()
    }
}
