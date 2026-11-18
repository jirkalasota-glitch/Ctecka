package cz.jirka.ctecka

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.PlaybackParams
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import kotlin.concurrent.thread

/** Kdo cte nahlas. Callbacky muzou prijit z libovolneho vlakna. */
interface Speaker {
    interface Callbacks {
        fun onSentence(i: Int)
        fun onDone()
        fun onError(msg: String)
        fun onStatus(msg: String?)
    }

    fun start(sentences: List<String>, from: Int, rate: Float)
    fun stop()
    fun setRate(rate: Float, sentences: List<String>, current: Int)
    fun release()
}

// ======================================================================
// Hlas telefonu (Google TTS apod.)
// ======================================================================
class SystemSpeaker(ctx: Context, private val cb: Speaker.Callbacks) : Speaker, TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(ctx, this)
    private var ready = false
    private var pending: Triple<List<String>, Int, Float>? = null
    private var sentences: List<String> = emptyList()
    private var index = 0
    private var queuedUpTo = -1
    @Volatile private var generation = 0
    private var running = false

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            cb.onError("Hlas v telefonu se nepodařilo spustit. Zkontroluj Nastavení → Převod textu na řeč.")
            return
        }
        ready = true
        tts.setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        )
        val r = tts.setLanguage(Locale("cs", "CZ"))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            cb.onError("Chybí český hlas. Otevři „Hlas“ → Nastavení hlasu telefonu a stáhni češtinu.")
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {
                val (g, i) = parse(id) ?: return
                if (g != generation) return
                synchronized(this@SystemSpeaker) { index = i; fill() }
                cb.onSentence(i)
            }

            override fun onDone(id: String) {
                val (g, i) = parse(id) ?: return
                if (g != generation) return
                if (i >= sentences.size - 1) { running = false; cb.onDone() }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String) = onDone(id)
            override fun onError(id: String, errorCode: Int) = onDone(id)
        })
        pending?.let { (s, f, r2) -> pending = null; start(s, f, r2) }
    }

    private fun parse(id: String): Pair<Int, Int>? {
        val p = id.split(':')
        if (p.size != 2) return null
        return Pair(p[0].toIntOrNull() ?: return null, p[1].toIntOrNull() ?: return null)
    }

    @Synchronized
    override fun start(sentences: List<String>, from: Int, rate: Float) {
        if (!ready) { pending = Triple(sentences, from, rate); return }
        this.sentences = sentences
        generation++
        running = true
        tts.stop()
        tts.setSpeechRate(rate)
        index = from
        queuedUpTo = from - 1
        fill()
    }

    private fun fill() {
        if (!running) return
        while (queuedUpTo < index + 3 && queuedUpTo < sentences.size - 1) {
            val q = queuedUpTo + 1
            val mode = if (q == index && queuedUpTo == index - 1) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts.speak(sentences[q], mode, null, "$generation:$q")
            queuedUpTo = q
        }
    }

    @Synchronized
    override fun stop() {
        pending = null
        running = false
        generation++
        tts.stop()
    }

    override fun setRate(rate: Float, sentences: List<String>, current: Int) {
        if (running) start(sentences, current, rate) else tts.setSpeechRate(rate)
    }

    override fun release() {
        stop()
        tts.shutdown()
    }
}

// ======================================================================
// Vlastni hlas (Pocket TTS v telefonu)
// ======================================================================
class PocketSpeaker(private val ctx: Context, private val cb: Speaker.Callbacks) : Speaker {

    private class Chunk(val index: Int, val pcm: FloatArray?)

    private var voice: PocketVoice? = null
    private val lock = Object()
    @Volatile private var generation = 0
    private var producer: Thread? = null
    private var player: Thread? = null
    private var rate = 1f
    private val track: AudioTrack

    init {
        val min = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build()
            )
            .setBufferSizeInBytes(maxOf(min, 24000 * 4 / 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /** nacte model a vzorek hlasu (pomale, vola se z vlakna) */
    private fun ensureVoice(): PocketVoice {
        synchronized(lock) {
            voice?.let { return it }
            cb.onStatus("Připravuji hlas…")
            val dir = PocketVoice.modelDir(ctx.filesDir)
            val v = PocketVoice(dir)
            val sample = voiceSampleFile(ctx)
            if (!sample.exists()) {
                v.close()
                throw IllegalStateException("Chybí vzorek hlasu – vyber ho v nabídce Hlas.")
            }
            v.setVoice(readFloats(sample))
            voice = v
            cb.onStatus(null)
            return v
        }
    }

    /** po zmene vzorku hlasu */
    fun reloadVoice() {
        synchronized(lock) { voice?.close(); voice = null }
    }

    override fun start(sentences: List<String>, from: Int, rate: Float) {
        stop()
        this.rate = rate
        val g = ++generation
        val queue = ArrayBlockingQueue<Chunk>(2)
        val end = Chunk(-1, null)

        producer = thread(name = "hlas-generovani") {
            try {
                val v = ensureVoice()
                for (i in from until sentences.size) {
                    if (g != generation) return@thread
                    val pcm = v.synth(sentences[i]) { g != generation } ?: return@thread
                    queue.put(Chunk(i, pcm))
                }
                queue.put(end)
            } catch (_: InterruptedException) {
            } catch (e: Throwable) {
                cb.onStatus(null)
                if (g == generation) cb.onError("Vlastní hlas selhal: ${e.message ?: e.javaClass.simpleName}")
            }
        }

        player = thread(name = "hlas-prehravani") {
            try {
                applyRate()
                track.play()
                var written = 0L
                val pause = FloatArray(24000 / 4)
                while (g == generation) {
                    val c = queue.take()
                    if (c === end) {
                        // dohrat, co je v bufferu
                        while (g == generation && (track.playbackHeadPosition.toLong() and 0xffffffffL) < written) Thread.sleep(50)
                        if (g == generation) cb.onDone()
                        break
                    }
                    cb.onSentence(c.index)
                    written += write(c.pcm!!, g)
                    written += write(pause, g)
                }
            } catch (_: InterruptedException) {
            } catch (e: Throwable) {
                if (g == generation) cb.onError("Přehrávání selhalo: ${e.message}")
            }
        }
    }

    private fun write(a: FloatArray, g: Int): Long {
        var off = 0
        while (off < a.size && g == generation) {
            val n = minOf(4800, a.size - off)
            val w = track.write(a, off, n, AudioTrack.WRITE_BLOCKING)
            if (w < 0) break
            off += w
        }
        return off.toLong()
    }

    private fun applyRate() {
        try {
            track.playbackParams = PlaybackParams().setSpeed(rate).setPitch(1f)
        } catch (_: Exception) {
        }
    }

    override fun stop() {
        generation++
        producer?.interrupt(); player?.interrupt()
        producer = null; player = null
        try { track.pause(); track.flush() } catch (_: Exception) {}
    }

    override fun setRate(rate: Float, sentences: List<String>, current: Int) {
        this.rate = rate
        applyRate()
    }

    override fun release() {
        stop()
        try { track.release() } catch (_: Exception) {}
        synchronized(lock) { voice?.close(); voice = null }
    }

    companion object {
        fun voiceSampleFile(ctx: Context) = File(ctx.filesDir, "vzorek_hlasu.f32")

        private fun readFloats(f: File): FloatArray {
            val b = f.readBytes()
            val fb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(fb.remaining()).also { fb.get(it) }
        }

        /** Dekoduje libovolny zvuk (mp3, m4a, wav ...) na mono 24 kHz, max 15 s, a ulozi jako vzorek hlasu. */
        fun importVoiceSample(ctx: Context, uri: Uri): Float {
            val ex = MediaExtractor()
            ex.setDataSource(ctx, uri, null)
            var track = -1
            for (i in 0 until ex.trackCount) {
                if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; break }
            }
            if (track < 0) throw IllegalStateException("V souboru není zvuk.")
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()
            var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmFloat = false
            val mono = ArrayList<Float>()
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            val limitSec = 16
            while (!outDone) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(10000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    pcmFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi)!!.order(ByteOrder.LITTLE_ENDIAN)
                    ob.position(info.offset); ob.limit(info.offset + info.size)
                    if (pcmFloat) {
                        val fb = ob.asFloatBuffer()
                        while (fb.remaining() >= ch) { var s = 0f; repeat(ch) { s += fb.get() }; mono += s / ch }
                    } else {
                        val sb = ob.asShortBuffer()
                        while (sb.remaining() >= ch) { var s = 0f; repeat(ch) { s += sb.get() / 32768f }; mono += s / ch }
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    if (mono.size > sr * limitSec) outDone = true
                }
            }
            codec.stop(); codec.release(); ex.release()
            if (mono.size < sr * 2) throw IllegalStateException("Vzorek je moc krátký – potřebuju aspoň 5 s, ideálně 10–15 s.")

            // prevzorkovani na 24 kHz (linearni)
            val outN = (mono.size.toLong() * 24000 / sr).toInt().coerceAtMost(24000 * 15)
            val out = FloatArray(outN)
            val ratio = sr.toDouble() / 24000
            for (i in 0 until outN) {
                val p = i * ratio
                val i0 = p.toInt().coerceAtMost(mono.size - 1)
                val i1 = (i0 + 1).coerceAtMost(mono.size - 1)
                val fr = (p - i0).toFloat()
                out[i] = mono[i0] * (1 - fr) + mono[i1] * fr
            }
            // hlasitost na rozumnou uroven
            val peak = out.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-4f)
            val g = (0.9f / peak).coerceAtMost(10f)
            for (i in out.indices) out[i] *= g

            val bb = ByteBuffer.allocate(out.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bb.asFloatBuffer().put(out)
            voiceSampleFile(ctx).writeBytes(bb.array())
            return out.size / 24000f
        }
    }
}

/** Stahne hlasovy model z GitHub Releases (cca 165 MB). */
object ModelDownloader {
    fun download(ctx: Context, progress: (String) -> Unit) {
        val dir = PocketVoice.modelDir(ctx.filesDir).apply { mkdirs() }
        File(dir, ".hotovo").delete()
        val manifest = fetch(PocketVoice.BASE_URL + "manifest.json")
        File(dir, "manifest.json").writeBytes(manifest)
        val files = org.json.JSONObject(String(manifest)).getJSONArray("files")
        val names = (0 until files.length()).map { files.getString(it) }
        names.forEachIndexed { k, name ->
            val dst = File(dir, name)
            val tmp = File(dir, "$name.part")
            val conn = java.net.URL(PocketVoice.BASE_URL + name).openConnection() as java.net.HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 20000; conn.readTimeout = 60000
            conn.connect()
            if (conn.responseCode != 200) throw IllegalStateException("Stahování selhalo (${conn.responseCode}) u $name")
            val total = conn.contentLengthLong
            var done = 0L
            var lastPct = -1
            conn.inputStream.use { inp ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        if (pct != lastPct) {
                            lastPct = pct
                            progress("Stahuji hlas: soubor ${k + 1}/${names.size} · $pct %")
                        }
                    }
                }
            }
            if (total > 0 && done != total) throw IllegalStateException("Neúplné stažení $name")
            dst.delete(); tmp.renameTo(dst)
        }
        File(dir, ".hotovo").writeText("ok")
    }

    private fun fetch(url: String): ByteArray {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.instanceFollowRedirects = true
        c.connectTimeout = 20000; c.readTimeout = 60000
        if (c.responseCode != 200) throw IllegalStateException("Model zatím není připravený (${c.responseCode}).")
        return c.inputStream.use { it.readBytes() }
    }
}
