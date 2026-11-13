package app.graveyard

import app.graveyard.book.Books
import app.graveyard.ocr.OcrText
import app.graveyard.text.Sentences
import app.graveyard.tts.Wav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TextTest {
    @Test
    fun sentencesAreSplitAtTheirEnd() {
        val pieces = Sentences.split("Il pleuvait depuis le matin sur la ville endormie. « Vous venez ? » demanda-t-il sans se retourner vers elle. Elle ne répondit pas tout de suite à sa question.")
        assertEquals(3, pieces.size)
        assertEquals("Il pleuvait depuis le matin sur la ville endormie.", pieces[0])
    }

    @Test
    fun shortSentencesJoinTheNextOne() {
        assertEquals(listOf("Non. Elle ne viendrait pas ce soir, ni demain."), Sentences.split("Non. Elle ne viendrait pas ce soir, ni demain."))
    }

    @Test
    fun longSentencesAreCutAtAComma() {
        val long = (1..40).joinToString(", ") { "un mot numéro $it" } + "."
        val pieces = Sentences.split(long)
        assertTrue(pieces.size > 1)
        assertTrue(pieces.all { it.length <= Sentences.MAX })
        assertEquals(long.replace(" ", ""), pieces.joinToString("").replace(" ", ""))
    }

    @Test
    fun dialogueDashIsDropped() {
        assertEquals("Vous venez avec nous ?", Sentences.normalize("—\u00A0Vous venez avec nous\u00A0?"))
    }

    @Test
    fun dialogueDashIsDroppedInsideAText() {
        val pieces = Sentences.split("Il se leva lentement de sa chaise en bois. — Vous venez avec nous jusqu'à la gare ce soir ?")
        assertEquals("Vous venez avec nous jusqu'à la gare ce soir ?", pieces[1])
    }

    @Test
    fun ocrJoinsHyphenatedCapitals() {
        assertEquals("Tu ne comprends pas ! C'est impossible.", OcrText.clean(listOf("TU NE COM-", "PRENDS PAS !", "C'EST IMPOSSIBLE.")))
    }

    @Test
    fun ocrDropsNoise() {
        assertNull(OcrText.clean(listOf("POSEIDON-SCANS.NET")))
        assertNull(OcrText.clean(listOf("!?")))
        assertNull(OcrText.clean(listOf("12 345")))
    }

    @Test
    fun ocrKeepsMixedCase() {
        assertEquals("Bonjour Marie.", OcrText.clean(listOf("Bonjour", "Marie.")))
    }

    @Test
    fun paragraphsComeOutWithoutMarkup() {
        val html = "<html><body><h1>Chapitre 1</h1><div><p>Un <i>premier</i> paragraphe.<sup>1</sup></p><p> </p><p>Le second.</p></div></body></html>"
        assertEquals(listOf("Chapitre 1", "Un premier paragraphe.", "Le second."), Books.paragraphs(html))
    }

    @Test
    fun wavIsReadAsMono() {
        val frames = 4
        val data = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { data.putShort(16384); data.putShort(0) }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + data.capacity()).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(8000).putInt(8000 * 4).putShort(4).putShort(16)
        header.put("data".toByteArray()).putInt(data.capacity())
        val file = File.createTempFile("test", ".wav")
        try {
            file.writeBytes(header.array() + data.array())
            val pcm = Wav.read(file)
            assertEquals(8000, pcm.sampleRate)
            assertEquals(frames, pcm.samples.size)
            assertEquals(0.25f, pcm.samples[0], 0.001f)
        } finally {
            file.delete()
        }
    }
}
