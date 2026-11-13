package app.graveyard.tts

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Mono samples in -1..1. */
class Pcm(val samples: FloatArray, val sampleRate: Int)

/** Reads an uncompressed WAV (8, 16, 24, 32-bit integer or 32-bit float), mixing channels down to one. */
object Wav {
    fun read(file: File, maxSeconds: Int = 20): Pcm {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 12 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" || String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") {
            throw IOException("Ce fichier n'est pas un WAV")
        }
        var format = 0
        var channels = 0
        var rate = 0
        var bits = 0
        var position = 12
        while (position + 8 <= bytes.size) {
            val id = String(bytes, position, 4, Charsets.US_ASCII)
            val size = buffer.getInt(position + 4)
            val start = position + 8
            if (id == "fmt " && size >= 16) {
                format = buffer.getShort(start).toInt() and 0xFFFF
                channels = buffer.getShort(start + 2).toInt()
                rate = buffer.getInt(start + 4)
                bits = buffer.getShort(start + 14).toInt()
                // WAVE_FORMAT_EXTENSIBLE: the real format is the first two bytes of the sub-format.
                if (format == 0xFFFE && size >= 26) format = buffer.getShort(start + 24).toInt() and 0xFFFF
            } else if (id == "data") {
                if (channels < 1 || rate < 1) throw IOException("WAV sans description du format")
                val end = minOf(bytes.size, start + size.coerceAtLeast(0).let { if (it == 0) bytes.size - start else it })
                val frameBytes = channels * bits / 8
                if (frameBytes == 0) throw IOException("WAV illisible")
                val frames = minOf((end - start) / frameBytes, maxSeconds * rate)
                val out = FloatArray(frames)
                for (f in 0 until frames) {
                    var sum = 0f
                    for (c in 0 until channels) sum += sample(buffer, start + f * frameBytes + c * bits / 8, format, bits)
                    out[f] = sum / channels
                }
                return Pcm(out, rate)
            }
            position = start + size + (size and 1)
            if (size < 0) break
        }
        throw IOException("WAV sans données audio")
    }

    private fun sample(b: ByteBuffer, at: Int, format: Int, bits: Int): Float = when {
        format == 3 && bits == 32 -> b.getFloat(at)
        format == 1 && bits == 16 -> b.getShort(at) / 32768f
        format == 1 && bits == 8 -> ((b.get(at).toInt() and 0xFF) - 128) / 128f
        format == 1 && bits == 24 ->
            (((b.get(at + 2).toInt() shl 16) or ((b.get(at + 1).toInt() and 0xFF) shl 8) or (b.get(at).toInt() and 0xFF))) / 8388608f
        format == 1 && bits == 32 -> b.getInt(at) / 2147483648f
        else -> throw IOException("Format WAV non pris en charge (code $format, $bits bits) : il faut du PCM non compressé")
    }
}
