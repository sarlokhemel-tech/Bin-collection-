package com.hemel.nanolab

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.json.JSONObject
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

data class TensorInfo(val role: String, val shape: String, val type: String, val quant: String)
data class Det(val label: String, val cls: Int, val score: Float, val l: Float, val t: Float, val r: Float, val b: Float)
data class ClassScore(val index: Int, val label: String, val prob: Float, val raw: Float)

class ClassifyResult(
    val scores: List<ClassScore>,
    val softmaxApplied: Boolean,
    val io: List<TensorInfo>,
    val imgW: Int, val imgH: Int, val inW: Int, val inH: Int,
    val inType: String, val norm: String,
    val prepMs: Long, val infMs: Long, val labelCount: Int
)

class DetectResult(
    val raw: List<Det>,
    val mode: String,
    val io: List<TensorInfo>,
    val imgW: Int, val imgH: Int, val inW: Int, val inH: Int,
    val inType: String, val norm: String,
    val prepMs: Long, val infMs: Long, val candidates: Int, val labelCount: Int
)

data class Segment(
    val text: String, val labelIndex: Int, val label: String,
    val conf: Float, val minConf: Float, val start: Int, val end: Int
)

class TextResult(
    val perToken: Boolean,
    val labels: List<String>,
    val overall: List<ClassScore>,
    val segments: List<Segment>,
    val cps: IntArray,
    val argIdx: IntArray,
    val conf: FloatArray,
    val probs: Array<FloatArray>,
    val charsPerLabel: IntArray,
    val unknownCount: Int,
    val unknownChars: List<String>,
    val chunks: Int,
    val seqLen: Int,
    val overlap: Int,
    val io: List<TensorInfo>,
    val prepMs: Long,
    val infMs: Long
)

object TensorUtil {
    fun newBuffer(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

    fun info(role: String, t: Tensor): TensorInfo {
        val q = t.quantizationParams()
        val quant = if (q.scale != 0f) "scale=" + q.scale + ", zeroPoint=" + q.zeroPoint else ""
        return TensorInfo(role, t.shape().joinToString("×"), t.dataType().toString(), quant)
    }

    fun read(t: Tensor, buf: ByteBuffer): FloatArray {
        buf.rewind()
        val n = t.numElements()
        val out = FloatArray(n)
        when (t.dataType()) {
            DataType.FLOAT32 -> buf.asFloatBuffer().get(out)
            DataType.UINT8 -> {
                val q = t.quantizationParams()
                for (i in 0 until n) out[i] = ((buf.get(i).toInt() and 0xFF) - q.zeroPoint) * q.scale
            }
            DataType.INT8 -> {
                val q = t.quantizationParams()
                for (i in 0 until n) out[i] = (buf.get(i).toInt() - q.zeroPoint) * q.scale
            }
            DataType.INT32 -> {
                val ib = buf.asIntBuffer()
                for (i in 0 until n) out[i] = ib.get(i).toFloat()
            }
            DataType.INT64 -> {
                val lb = buf.asLongBuffer()
                for (i in 0 until n) out[i] = lb.get(i).toFloat()
            }
            else -> {}
        }
        return out
    }

    fun softmax(x: FloatArray): FloatArray {
        var m = Float.NEGATIVE_INFINITY
        for (v in x) if (v > m) m = v
        var s = 0f
        val e = FloatArray(x.size)
        for (i in x.indices) { e[i] = exp(x[i] - m); s += e[i] }
        for (i in e.indices) e[i] = e[i] / s
        return e
    }

    fun readLabels(f: File?): List<String> {
        if (f == null || !f.exists()) return emptyList()
        return f.readLines().map { it.trim() }.filter { it.isNotEmpty() }
    }
}

object ImageEngine {
    private fun open(f: File): Interpreter =
        Interpreter(f, Interpreter.Options().apply { setNumThreads(4) })

    private fun isNchw(shape: IntArray): Boolean = shape.size == 4 && shape[1] == 3 && shape[3] != 3

    private fun prepare(bmp: Bitmap, inT: Tensor, norm: String): ByteBuffer {
        val shape = inT.shape()
        val nchw = isNchw(shape)
        val h = if (nchw) shape[2] else shape[1]
        val w = if (nchw) shape[3] else shape[2]
        val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
        val px = IntArray(w * h)
        scaled.getPixels(px, 0, w, 0, 0, w, h)
        val dt = inT.dataType()
        val bpe = if (dt == DataType.FLOAT32) 4 else 1
        val buf = TensorUtil.newBuffer(w * h * 3 * bpe)

        fun put(v: Int) {
            when (dt) {
                DataType.FLOAT32 -> {
                    val f = when (norm) {
                        "pm1" -> (v - 127.5f) / 127.5f
                        "raw" -> v.toFloat()
                        else -> v / 255f
                    }
                    buf.putFloat(f)
                }
                DataType.UINT8 -> buf.put(v.toByte())
                else -> buf.put((v - 128).toByte())
            }
        }

        if (nchw) {
            for (c in 0 until 3) {
                for (p in px) {
                    val v = when (c) {
                        0 -> (p shr 16) and 0xFF
                        1 -> (p shr 8) and 0xFF
                        else -> p and 0xFF
                    }
                    put(v)
                }
            }
        } else {
            for (p in px) {
                put((p shr 16) and 0xFF)
                put((p shr 8) and 0xFF)
                put(p and 0xFF)
            }
        }
        buf.rewind()
        return buf
    }

    private fun ioOf(interp: Interpreter): List<TensorInfo> {
        val list = ArrayList<TensorInfo>()
        for (i in 0 until interp.inputTensorCount) list.add(TensorUtil.info("input#$i", interp.getInputTensor(i)))
        for (i in 0 until interp.outputTensorCount) list.add(TensorUtil.info("output#$i", interp.getOutputTensor(i)))
        return list
    }

    fun classify(dir: File, meta: ModelMeta, bmp: Bitmap): ClassifyResult {
        val labels = TensorUtil.readLabels(meta.labelsFile?.let { File(dir, it) })
        val interp = open(File(dir, meta.modelFile))
        try {
            val inT = interp.getInputTensor(0)
            val shape = inT.shape()
            val nchw = isNchw(shape)
            val inH = if (nchw) shape[2] else shape[1]
            val inW = if (nchw) shape[3] else shape[2]

            val tp = System.nanoTime()
            val input = prepare(bmp, inT, meta.norm)
            val prepMs = (System.nanoTime() - tp) / 1000000L

            val outT = interp.getOutputTensor(0)
            val outBuf = TensorUtil.newBuffer(outT.numBytes())
            val ti = System.nanoTime()
            interp.run(input, outBuf)
            val infMs = (System.nanoTime() - ti) / 1000000L

            val raw = TensorUtil.read(outT, outBuf)
            var sum = 0f
            var neg = false
            for (v in raw) { sum += v; if (v < 0f) neg = true }
            val applied = neg || Math.abs(sum - 1f) > 0.05f
            val p = if (applied) TensorUtil.softmax(raw) else raw
            val scores = p.indices.sortedByDescending { p[it] }.map {
                ClassScore(it, labels.getOrNull(it) ?: "class $it", p[it], raw[it])
            }
            return ClassifyResult(
                scores, applied, ioOf(interp), bmp.width, bmp.height, inW, inH,
                inT.dataType().toString(), meta.norm, prepMs, infMs, labels.size
            )
        } finally {
            interp.close()
        }
    }

    fun detect(dir: File, meta: ModelMeta, bmp: Bitmap): DetectResult {
        val labels = TensorUtil.readLabels(meta.labelsFile?.let { File(dir, it) })
        fun lab(i: Int) = labels.getOrNull(i) ?: "class $i"
        val interp = open(File(dir, meta.modelFile))
        try {
            val inT = interp.getInputTensor(0)
            val shape = inT.shape()
            val nchw = isNchw(shape)
            val inH = if (nchw) shape[2] else shape[1]
            val inW = if (nchw) shape[3] else shape[2]

            val tp = System.nanoTime()
            val input = prepare(bmp, inT, meta.norm)
            val prepMs = (System.nanoTime() - tp) / 1000000L

            val outs = HashMap<Int, Any>()
            val bufs = ArrayList<ByteBuffer>()
            val tensors = ArrayList<Tensor>()
            for (i in 0 until interp.outputTensorCount) {
                val t = interp.getOutputTensor(i)
                val b = TensorUtil.newBuffer(t.numBytes())
                outs[i] = b
                bufs.add(b)
                tensors.add(t)
            }
            val ti = System.nanoTime()
            interp.runForMultipleInputsOutputs(arrayOf<Any>(input), outs)
            val infMs = (System.nanoTime() - ti) / 1000000L
            val data = tensors.mapIndexed { i, t -> TensorUtil.read(t, bufs[i]) }

            val dets = ArrayList<Det>()
            var mode = "?"
            var candidates = 0
            val minScore = 0.05f
            val boxIdx = tensors.indexOfFirst { it.shape().size == 3 && it.shape().last() == 4 }

            if (tensors.size >= 3 && boxIdx >= 0) {
                mode = "SSD (boxes/classes/scores)"
                val boxes = data[boxIdx]
                val others = tensors.indices.filter { it != boxIdx && tensors[it].numElements() > 1 }
                var clsI = -1
                var scoreI = -1
                for (i in others) {
                    val allInt = data[i].all { it == Math.round(it).toFloat() }
                    if (allInt && clsI < 0) clsI = i else if (scoreI < 0) scoreI = i
                }
                if (clsI >= 0 && scoreI >= 0) {
                    val n = tensors[boxIdx].shape()[1]
                    candidates = n
                    for (i in 0 until n) {
                        val s = data[scoreI][i]
                        if (s < minScore) continue
                        val y1 = boxes[i * 4]; val x1 = boxes[i * 4 + 1]
                        val y2 = boxes[i * 4 + 2]; val x2 = boxes[i * 4 + 3]
                        val c = data[clsI][i].toInt()
                        dets.add(Det(lab(c), c, s, clamp(x1), clamp(y1), clamp(x2), clamp(y2)))
                    }
                }
            } else {
                val sh = tensors[0].shape()
                val o = data[0]
                val a = sh[sh.size - 2]
                val b = sh[sh.size - 1]
                val chFirst = a < b
                val c = if (chFirst) a else b
                val n = if (chFirst) b else a
                candidates = n
                fun v(ch: Int, i: Int): Float = if (chFirst) o[ch * n + i] else o[i * c + ch]

                if (!chFirst && c == 6 && labels.size != 2) {
                    mode = "End-to-end (x1,y1,x2,y2,score,class)"
                    var big = false
                    for (i in 0 until n) if (v(2, i) > 2f) { big = true; break }
                    val sx = if (big) inW.toFloat() else 1f
                    val sy = if (big) inH.toFloat() else 1f
                    for (i in 0 until n) {
                        val s = v(4, i)
                        if (s < minScore) continue
                        val cl = v(5, i).toInt()
                        dets.add(Det(lab(cl), cl, s, clamp(v(0, i) / sx), clamp(v(1, i) / sy), clamp(v(2, i) / sx), clamp(v(3, i) / sy)))
                    }
                } else {
                    val hasObj = labels.isNotEmpty() && labels.size == c - 5
                    mode = if (hasObj) "YOLOv5-style (obj × class)" else "YOLOv8-style (cx,cy,w,h + classes)"
                    val first = if (hasObj) 5 else 4
                    var maxCoord = 0f
                    for (i in 0 until min(n, 2000)) maxCoord = max(maxCoord, max(v(2, i), v(3, i)))
                    val pixel = maxCoord > 2f
                    val sx = if (pixel) inW.toFloat() else 1f
                    val sy = if (pixel) inH.toFloat() else 1f
                    val cand = ArrayList<Det>()
                    for (i in 0 until n) {
                        var best = -1
                        var bs = 0f
                        for (k in first until c) {
                            val s = v(k, i)
                            if (s > bs) { bs = s; best = k - first }
                        }
                        if (best < 0) continue
                        val score = if (hasObj) bs * v(4, i) else bs
                        if (score < minScore) continue
                        val cx = v(0, i) / sx; val cy = v(1, i) / sy
                        val w = v(2, i) / sx; val h = v(3, i) / sy
                        cand.add(Det(lab(best), best, score, clamp(cx - w / 2), clamp(cy - h / 2), clamp(cx + w / 2), clamp(cy + h / 2)))
                    }
                    dets.addAll(cand.sortedByDescending { it.score }.take(600))
                }
            }

            return DetectResult(
                dets.sortedByDescending { it.score }.take(600), mode, ioOf(interp),
                bmp.width, bmp.height, inW, inH, inT.dataType().toString(), meta.norm,
                prepMs, infMs, candidates, labels.size
            )
        } finally {
            interp.close()
        }
    }

    private fun clamp(x: Float): Float = max(0f, min(1f, x))

    private fun iou(a: Det, b: Det): Float {
        val l = max(a.l, b.l); val t = max(a.t, b.t)
        val r = min(a.r, b.r); val bt = min(a.b, b.b)
        val inter = max(0f, r - l) * max(0f, bt - t)
        val ua = (a.r - a.l) * (a.b - a.t) + (b.r - b.l) * (b.b - b.t) - inter
        return if (ua <= 0f) 0f else inter / ua
    }

    fun postprocess(raw: List<Det>, conf: Float, iouThr: Float, maxN: Int): List<Det> {
        val keep = ArrayList<Det>()
        for (d in raw.filter { it.score >= conf }.sortedByDescending { it.score }) {
            if (keep.none { it.label == d.label && iou(it, d) > iouThr }) keep.add(d)
            if (keep.size >= maxN) break
        }
        return keep
    }

    private val palette = intArrayOf(
        0xFFE53935.toInt(), 0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFFFB8C00.toInt(),
        0xFF8E24AA.toInt(), 0xFF00ACC1.toInt(), 0xFFD81B60.toInt(), 0xFF6D4C41.toInt()
    )

    fun draw(src: Bitmap, dets: List<Det>): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val w = out.width.toFloat()
        val h = out.height.toFloat()
        val stroke = max(3f, max(w, h) / 200f)
        val boxP = Paint().apply { style = Paint.Style.STROKE; strokeWidth = stroke; isAntiAlias = true }
        val bgP = Paint().apply { style = Paint.Style.FILL }
        val txtP = Paint().apply { color = Color.WHITE; textSize = stroke * 5f; isAntiAlias = true }
        dets.forEachIndexed { i, d ->
            val col = palette[i % palette.size]
            boxP.color = col
            bgP.color = col
            val l = d.l * w; val t = d.t * h; val r = d.r * w; val b = d.b * h
            canvas.drawRect(l, t, r, b, boxP)
            val label = "#" + (i + 1) + " " + d.label + " " + Math.round(d.score * 100f) + "%"
            val tw = txtP.measureText(label)
            val th = txtP.textSize * 1.3f
            val top = if (t - th < 0f) t else t - th
            canvas.drawRect(l, top, l + tw + 12f, top + th, bgP)
            canvas.drawText(label, l + 6f, top + th * 0.78f, txtP)
        }
        return out
    }
}

object TextEngine {
    fun run(dir: File, meta: ModelMeta, text: String): TextResult {
        val tp = System.nanoTime()

        val tok = JSONObject(File(dir, meta.tokenizerFile!!).readText())
        val padId = tok.optInt("pad_id", 0)
        val unkId = tok.optInt("unk_id", 1)
        val vocabArr = tok.getJSONArray("vocab")
        val map = HashMap<String, Int>()
        for (i in 0 until vocabArr.length()) map[vocabArr.getString(i)] = i

        var cfgOverlap = 0
        var cfgLabels: List<String> = emptyList()
        if (meta.configFile != null) {
            val cfg = JSONObject(File(dir, meta.configFile).readText())
            cfgOverlap = cfg.optInt("chunk_overlap", 0)
            val arr = cfg.optJSONArray("labels")
            if (arr != null) cfgLabels = List(arr.length()) { arr.getString(it) }
        }

        val cps = ArrayList<Int>()
        var pos = 0
        while (pos < text.length) {
            val cp = text.codePointAt(pos)
            cps.add(cp)
            pos += Character.charCount(cp)
        }
        val cpArr = cps.toIntArray()
        var unknownCount = 0
        val unknownSet = LinkedHashSet<String>()
        val ids = IntArray(cpArr.size) {
            val ch = String(Character.toChars(cpArr[it]))
            val id = map[ch]
            if (id == null) {
                unknownCount++
                if (unknownSet.size < 30) unknownSet.add(ch)
                unkId
            } else id
        }
        val n = ids.size
        val prepMs0 = (System.nanoTime() - tp) / 1000000L

        val interp = Interpreter(File(dir, meta.modelFile), Interpreter.Options().apply { setNumThreads(2) })
        try {
            val inT = interp.getInputTensor(0)
            val outT = interp.getOutputTensor(0)
            val len = inT.shape()[1]
            val oshape = outT.shape()
            val k = oshape[oshape.size - 1]
            val perToken = oshape.size == 3
            val labels = if (cfgLabels.size == k) cfgLabels else List(k) { "L$it" }
            val int64 = inT.dataType() == DataType.INT64
            val io = listOf(TensorUtil.info("input#0", inT), TensorUtil.info("output#0", outT))

            if (n == 0) {
                return TextResult(
                    perToken, labels, emptyList(), emptyList(), cpArr, IntArray(0), FloatArray(0),
                    arrayOf(), IntArray(k), 0, emptyList(), 0, len, cfgOverlap, io, prepMs0, 0
                )
            }

            val inBuf = TensorUtil.newBuffer(len * (if (int64) 8 else 4))
            val outBuf = TensorUtil.newBuffer(outT.numBytes())
            val stride = max(1, len - cfgOverlap)

            val sum: Array<FloatArray> = if (perToken) Array(n) { FloatArray(k) } else arrayOf()
            val cnt = IntArray(n)
            val doc = FloatArray(k)
            var docChunks = 0
            var chunks = 0
            var infNs = 0L

            var start = 0
            while (true) {
                val end = min(n, start + len)
                inBuf.clear()
                for (j in 0 until len) {
                    val id = if (start + j < end) ids[start + j] else padId
                    if (int64) inBuf.putLong(id.toLong()) else inBuf.putInt(id)
                }
                inBuf.rewind()
                outBuf.clear()
                val t1 = System.nanoTime()
                interp.run(inBuf, outBuf)
                infNs += System.nanoTime() - t1
                chunks++
                val o = TensorUtil.read(outT, outBuf)
                if (perToken) {
                    for (j in 0 until end - start) {
                        for (c in 0 until k) sum[start + j][c] += o[j * k + c]
                        cnt[start + j]++
                    }
                } else {
                    for (c in 0 until k) doc[c] += o[c]
                    docChunks++
                }
                if (end >= n) break
                start += stride
            }
            val infMs = infNs / 1000000L
            val unk = unknownSet.toList()

            if (!perToken) {
                val overall = (0 until k).map {
                    val p = doc[it] / max(1, docChunks)
                    ClassScore(it, labels[it], p, p)
                }.sortedByDescending { it.prob }
                return TextResult(
                    false, labels, overall, emptyList(), cpArr, IntArray(0), FloatArray(0),
                    arrayOf(), IntArray(k), unknownCount, unk, chunks, len, cfgOverlap, io, prepMs0, infMs
                )
            }

            val avg = Array(n) { i ->
                FloatArray(k) { c -> if (cnt[i] > 0) sum[i][c] / cnt[i] else 0f }
            }
            val total = FloatArray(k)
            val arg = IntArray(n)
            val conf = FloatArray(n)
            val perLabel = IntArray(k)
            for (i in 0 until n) {
                var best = 0
                for (c in 0 until k) {
                    total[c] += avg[i][c]
                    if (avg[i][c] > avg[i][best]) best = c
                }
                arg[i] = best
                conf[i] = avg[i][best]
                perLabel[best]++
            }
            val overall = (0 until k).map { ClassScore(it, labels[it], total[it] / n, total[it] / n) }
                .sortedByDescending { it.prob }

            val segs = ArrayList<Segment>()
            var i = 0
            while (i < n) {
                var j = i
                var confSum = 0f
                var minC = 1f
                while (j < n && arg[j] == arg[i]) {
                    confSum += conf[j]
                    if (conf[j] < minC) minC = conf[j]
                    j++
                }
                segs.add(Segment(String(cpArr, i, j - i), arg[i], labels[arg[i]], confSum / (j - i), minC, i, j))
                i = j
            }
            return TextResult(
                true, labels, overall, segs, cpArr, arg, conf, avg, perLabel,
                unknownCount, unk, chunks, len, cfgOverlap, io, prepMs0, infMs
            )
        } finally {
            interp.close()
        }
    }
}
