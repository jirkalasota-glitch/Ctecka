package cz.jirka.ctecka

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer
import java.util.Random
import kotlin.math.sqrt

/**
 * Cesky Pocket TTS (Kyutai) bezici primo v telefonu pres ONNX Runtime.
 * Klonuje hlas z kratkeho vzorku (10-15 s).
 * Postup je 1:1 podle model/smoke_test.py, ktery je overeny proti puvodnimu modelu.
 */
class PocketVoice(private val dir: File) : AutoCloseable {

    private class StateSpec(val name: String, val dtype: String, val fill: String, val shape: LongArray)

    private val env = OrtEnvironment.getEnvironment()
    private val enc: OrtSession
    private val txt: OrtSession
    private val main: OrtSession
    private val flow: OrtSession
    private val dec: OrtSession
    private val lmStates: List<StateSpec>
    private val mimiStates: List<StateSpec>
    private val vocab = HashMap<String, Int>()
    private val score = HashMap<String, Float>()
    private val rnd = Random()

    // prazdne vstupy a pocatecni stav (ORT vstupy nemeni, takze se daji pouzivat opakovane)
    private val emptySeq: OnnxTensor
    private val emptyTxt: OnnxTensor
    private val lmInit: Map<String, OnnxTensor>
    private var voice: OnnxTensor? = null

    var temperature = 0.5f
    var flowSteps = 4

    init {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        fun s(f: String) = env.createSession(File(dir, f).absolutePath, opts)
        enc = s("encoder.onnx")
        txt = s("text_conditioner.onnx")
        main = s("lm_main.int8.onnx")
        flow = s("lm_flow.int8.onnx")
        dec = s("decoder.int8.onnx")

        val man = JSONObject(File(dir, "manifest.json").readText())
        fun specs(key: String): List<StateSpec> {
            val a = man.getJSONArray(key)
            return (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val sh = o.getJSONArray("shape")
                StateSpec(
                    o.getString("name"), o.getString("dtype"), o.getString("fill"),
                    LongArray(sh.length()) { sh.getLong(it) }
                )
            }
        }
        lmStates = specs("lm_states")
        mimiStates = specs("mimi_states")

        val v = JSONObject(File(dir, "vocab.json").readText(Charsets.UTF_8))
        v.keys().forEach { k -> vocab[k] = v.getInt(k) }
        val sc = JSONObject(File(dir, "token_scores.json").readText(Charsets.UTF_8))
        sc.keys().forEach { k -> score[k] = sc.getDouble(k).toFloat() }

        emptySeq = floatTensor(FloatArray(0), longArrayOf(1, 0, 32))
        emptyTxt = floatTensor(FloatArray(0), longArrayOf(1, 0, 1024))
        lmInit = lmStates.associate { it.name to makeState(it) }
    }

    // ---------- tensory ----------
    private fun floatTensor(a: FloatArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(a), shape)

    private fun makeState(s: StateSpec): OnnxTensor {
        val n = s.shape.fold(1L) { a, b -> a * b }.toInt()
        return when (s.dtype) {
            "float32" -> {
                val f = if (s.fill == "nan") Float.NaN else if (s.fill == "ones") 1f else 0f
                val buf = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                if (f != 0f) for (i in 0 until n) buf.put(i, f)
                OnnxTensor.createTensor(env, buf, s.shape)
            }
            "int64" -> {
                val v = if (s.fill == "ones") 1L else 0L
                val buf = ByteBuffer.allocateDirect(n * 8).order(ByteOrder.nativeOrder()).asLongBuffer()
                if (v != 0L) for (i in 0 until n) buf.put(i, v)
                OnnxTensor.createTensor(env, buf, s.shape)
            }
            "bool" -> {
                val buf = ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder())
                if (s.fill == "ones") for (i in 0 until n) buf.put(i, 1)
                OnnxTensor.createTensor(env, buf, s.shape, OnnxJavaType.BOOL)
            }
            else -> throw IllegalStateException("neznamy typ ${s.dtype}")
        }
    }

    // ---------- hlas ----------
    /** audio = mono 24 kHz, idealne 10-15 s ciste reci */
    fun setVoice(audio24k: FloatArray) {
        val a = if (audio24k.size > 24000 * 15) audio24k.copyOf(24000 * 15) else audio24k
        val t = floatTensor(a, longArrayOf(1, 1, a.size.toLong()))
        val r = enc.run(mapOf("audio" to t))
        val out = r.get(0) as OnnxTensor
        val shape = out.info.shape
        val data = FloatArray(out.floatBuffer.remaining()).also { out.floatBuffer.get(it) }
        r.close(); t.close()
        voice?.close()
        voice = floatTensor(data, shape)
    }

    val hasVoice get() = voice != null

    // ---------- text ----------
    fun tokenize(text: String): LongArray {
        val t = "▁" + Normalizer.normalize(text, Normalizer.Form.NFKC).trim().split(Regex("\\s+")).joinToString("▁")
        val sym = ArrayList<String>(t.length)
        var i = 0
        while (i < t.length) {
            val cp = t.codePointAt(i)
            val n = Character.charCount(cp)
            sym += t.substring(i, i + n)
            i += n
        }
        while (true) {
            var best = Float.NEGATIVE_INFINITY
            var bi = -1
            for (k in 0 until sym.size - 1) {
                val s = score[sym[k] + sym[k + 1]] ?: continue
                if (s > best) { best = s; bi = k }
            }
            if (bi < 0) break
            sym[bi] = sym[bi] + sym[bi + 1]
            sym.removeAt(bi + 1)
        }
        return sym.mapNotNull { vocab[it]?.toLong() }.toLongArray()
    }

    private fun prep(text: String): Pair<String, Int> {
        var t = text.replace('\n', ' ').trim().split(Regex("\\s+")).joinToString(" ")
        if (t.isNotEmpty() && !t[0].isUpperCase()) t = t[0].uppercaseChar() + t.substring(1)
        if (t.isNotEmpty() && t.last().isLetterOrDigit()) t += "."
        val words = t.split(' ').size
        return t to ((if (words <= 4) 3 else 1) + 2)
    }

    // ---------- generovani ----------
    private inner class LmState {
        private var owner: OrtSession.Result? = null
        var cur: Map<String, OnnxTensor> = lmInit

        /** spusti hlavni model, posune stav; vrati (conditioning, eos) - conditioning patri vysledku */
        fun step(seq: OnnxTensor, emb: OnnxTensor): Pair<FloatArray, Float> {
            val inputs = HashMap<String, OnnxTensor>(cur.size + 2)
            inputs["sequence"] = seq
            inputs["text_embeddings"] = emb
            inputs.putAll(cur)
            val r = main.run(inputs)
            val c = (r.get(0) as OnnxTensor).floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }
            val eos = (r.get(1) as OnnxTensor).floatBuffer.get(0)
            val next = HashMap<String, OnnxTensor>(lmStates.size)
            lmStates.forEachIndexed { k, s -> next[s.name] = r.get(2 + k) as OnnxTensor }
            owner?.close()
            owner = r
            cur = next
            return c to eos
        }

        fun close() { owner?.close(); owner = null; cur = lmInit }
    }

    /** Vygeneruje jednu vetu. [cancelled] se kontroluje kazdy snimek (80 ms zvuku). */
    fun synth(text: String, cancelled: () -> Boolean): FloatArray? {
        val v = voice ?: throw IllegalStateException("chybi vzorek hlasu")
        val (t, eosAfter) = prep(text)
        val ids = tokenize(t)
        if (ids.isEmpty()) return FloatArray(0)
        val idT = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong()))
        val tr = txt.run(mapOf("token_ids" to idT))
        val temb = tr.get(0) as OnnxTensor

        val st = LmState()
        val latents = ArrayList<FloatArray>()
        try {
            st.step(emptySeq, v)
            st.step(emptySeq, temb)
            tr.close(); idT.close()

            val maxFrames = minOf(500, 40 + t.length * 2)
            var cur = FloatArray(32) { Float.NaN }
            var eosStep = -1
            val std = sqrt(temperature)
            val dt = 1f / flowSteps
            for (step in 0 until maxFrames) {
                if (cancelled()) return null
                val seqT = floatTensor(cur, longArrayOf(1, 1, 32))
                val (c, eos) = st.step(seqT, emptyTxt)
                seqT.close()
                if (eosStep < 0 && eos > -4f) eosStep = step
                if (eosStep >= 0 && step >= eosStep + eosAfter) break

                val x = FloatArray(32) { (rnd.nextGaussian() * std).toFloat() }
                val cT = floatTensor(c, longArrayOf(1, 1024))
                for (i in 0 until flowSteps) {
                    val sT = floatTensor(floatArrayOf(i * dt), longArrayOf(1, 1))
                    val tT = floatTensor(floatArrayOf(i * dt + dt), longArrayOf(1, 1))
                    val xT = floatTensor(x.copyOf(), longArrayOf(1, 32))
                    val r = flow.run(mapOf("c" to cT, "s" to sT, "t" to tT, "x" to xT))
                    val d = (r.get(0) as OnnxTensor).floatBuffer
                    for (k in 0 until 32) x[k] += dt * d.get(k)
                    r.close(); sT.close(); tT.close(); xT.close()
                }
                cT.close()
                latents += x
                cur = x
            }
        } finally {
            st.close()
        }
        if (latents.isEmpty()) return FloatArray(0)
        return decode(latents, cancelled)
    }

    private fun decode(latents: List<FloatArray>, cancelled: () -> Boolean): FloatArray? {
        var states: Map<String, OnnxTensor> = mimiStates.associate { it.name to makeState(it) }
        val initial = states.values.toList()
        var owner: OrtSession.Result? = null
        val out = ArrayList<FloatArray>()
        try {
            var i = 0
            while (i < latents.size) {
                if (cancelled()) return null
                val n = minOf(15, latents.size - i)
                val buf = FloatArray(n * 32)
                for (k in 0 until n) System.arraycopy(latents[i + k], 0, buf, k * 32, 32)
                val lt = floatTensor(buf, longArrayOf(1, n.toLong(), 32))
                val inputs = HashMap<String, OnnxTensor>(states.size + 1)
                inputs["latent"] = lt
                inputs.putAll(states)
                val r = dec.run(inputs)
                val a = (r.get(0) as OnnxTensor).floatBuffer
                out += FloatArray(a.remaining()).also { a.get(it) }
                val next = HashMap<String, OnnxTensor>(mimiStates.size)
                mimiStates.forEachIndexed { k, s -> next[s.name] = r.get(1 + k) as OnnxTensor }
                owner?.close()
                owner = r
                states = next
                lt.close()
                i += n
            }
        } finally {
            owner?.close()
            initial.forEach { it.close() }
        }
        val total = out.sumOf { it.size }
        val res = FloatArray(total)
        var p = 0
        for (a in out) { System.arraycopy(a, 0, res, p, a.size); p += a.size }
        return res
    }

    override fun close() {
        voice?.close()
        lmInit.values.forEach { it.close() }
        emptySeq.close(); emptyTxt.close()
        enc.close(); txt.close(); main.close(); flow.close(); dec.close()
    }

    companion object {
        const val TAG = "hlas-cs-1"
        const val BASE_URL = "https://github.com/jirkalasota-glitch/Ctecka/releases/download/$TAG/"
        fun modelDir(filesDir: File) = File(filesDir, TAG)
        fun isDownloaded(filesDir: File) = File(modelDir(filesDir), ".hotovo").exists()
    }
}
