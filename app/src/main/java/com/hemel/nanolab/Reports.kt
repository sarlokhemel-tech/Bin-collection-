package com.hemel.nanolab

import java.util.Locale

class Section(val title: String, val body: String)

object Reports {
    private fun f(fmt: String, vararg a: Any?): String = String.format(Locale.US, fmt, *a)
    fun pct(x: Float): String = f("%.2f%%", x * 100f)

    fun allText(title: String, sections: List<Section>): String {
        val sb = StringBuilder()
        sb.append("=== ").append(title).append(" ===\n\n")
        for (s in sections) {
            sb.append("## ").append(s.title).append('\n').append(s.body).append("\n\n")
        }
        return sb.toString().trim()
    }

    private fun ioText(io: List<TensorInfo>): String {
        val sb = StringBuilder()
        for (t in io) {
            sb.append(t.role).append(": shape [").append(t.shape).append("]  type ").append(t.type)
            if (t.quant.isNotEmpty()) sb.append("  quant(").append(t.quant).append(')')
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    fun classify(r: ClassifyResult, topK: Int, modelName: String): List<Section> {
        val out = ArrayList<Section>()
        val t1 = r.scores.firstOrNull()
        val t2 = r.scores.getOrNull(1)
        var entropy = 0.0
        for (s in r.scores) {
            if (s.prob > 1e-9f) entropy -= s.prob * Math.log(s.prob.toDouble()) / Math.log(2.0)
        }
        val sb = StringBuilder()
        if (t1 != null) {
            sb.append("Top-1: ").append(t1.label).append(" (id ").append(t1.index).append(")  ").append(pct(t1.prob)).append('\n')
        }
        if (t1 != null && t2 != null) {
            sb.append("Top-2 এর সাথে ব্যবধান: ").append(pct(t1.prob - t2.prob)).append('\n')
        }
        sb.append("এন্ট্রপি: ").append(f("%.3f bits", entropy)).append("  (কম = মডেল নিশ্চিত)\n")
        sb.append("মোট ক্লাস: ").append(r.scores.size).append("   labels ফাইলে: ").append(r.labelCount).append('\n')
        sb.append("Softmax প্রয়োগ: ").append(if (r.softmaxApplied) "হ্যাঁ (raw আউটপুট থেকে)" else "না (মডেল নিজেই probability দিয়েছে)").append('\n')
        sb.append("ছবির সাইজ: ").append(r.imgW).append('×').append(r.imgH).append("   মডেল ইনপুট: ").append(r.inW).append('×').append(r.inH).append('\n')
        sb.append("সময়: প্রিপ্রসেস ").append(r.prepMs).append(" ms, ইনফারেন্স ").append(r.infMs).append(" ms")
        out.add(Section("সারাংশ — $modelName", sb.toString()))

        val tb = StringBuilder()
        r.scores.take(topK).forEachIndexed { i, s ->
            tb.append('#').append(i + 1).append("  ").append(s.label).append(" (id ").append(s.index).append(")  ")
                .append(pct(s.prob)).append("   raw=").append(f("%.5f", s.raw)).append('\n')
        }
        out.add(Section("Top-$topK ফলাফল", tb.toString().trimEnd()))

        out.add(
            Section(
                "মডেল ও ইনপুট তথ্য",
                "ইনপুট টাইপ: ${r.inType}   নর্মালাইজেশন: ${r.norm}\n" + ioText(r.io)
            )
        )
        return out
    }

    fun detect(r: DetectResult, dets: List<Det>, conf: Float, iou: Float, modelName: String): List<Section> {
        val out = ArrayList<Section>()
        val sb = StringBuilder()
        sb.append("ডিকোড মোড: ").append(r.mode).append('\n')
        sb.append("মোট ক্যান্ডিডেট: ").append(r.candidates).append("   কনফিডেন্স ≥ 5%: ").append(r.raw.size)
            .append("   ≥ ").append(f("%.0f%%", conf * 100f)).append(": ").append(r.raw.count { it.score >= conf })
            .append("   NMS এর পর: ").append(dets.size).append('\n')
        sb.append("থ্রেশহোল্ড: কনফিডেন্স ").append(f("%.2f", conf)).append(", IoU ").append(f("%.2f", iou)).append('\n')
        val hist = LinkedHashMap<String, Int>()
        for (d in dets) hist[d.label] = (hist[d.label] ?: 0) + 1
        if (hist.isNotEmpty()) {
            sb.append("ক্লাস অনুযায়ী: ").append(hist.entries.joinToString(", ") { it.key + " ×" + it.value }).append('\n')
        }
        sb.append("ছবির সাইজ: ").append(r.imgW).append('×').append(r.imgH).append("   মডেল ইনপুট: ").append(r.inW).append('×').append(r.inH).append('\n')
        sb.append("সময়: প্রিপ্রসেস ").append(r.prepMs).append(" ms, ইনফারেন্স ").append(r.infMs).append(" ms")
        out.add(Section("সারাংশ — $modelName", sb.toString()))

        val db = StringBuilder()
        if (dets.isEmpty()) db.append("কিছু ডিটেক্ট হয়নি (কনফিডেন্স থ্রেশহোল্ড কমিয়ে দেখুন)")
        dets.forEachIndexed { i, d ->
            val x1 = d.l * r.imgW; val y1 = d.t * r.imgH; val x2 = d.r * r.imgW; val y2 = d.b * r.imgH
            val area = (d.r - d.l) * (d.b - d.t) * 100f
            db.append('#').append(i + 1).append("  ").append(d.label).append(" (id ").append(d.cls).append(")  ")
                .append(pct(d.score)).append('\n')
            db.append("    বক্স px: (").append(f("%.0f,%.0f", x1, y1)).append(") → (").append(f("%.0f,%.0f", x2, y2)).append(")")
                .append("  সাইজ ").append(f("%.0f×%.0f", x2 - x1, y2 - y1)).append("  এলাকা ").append(f("%.1f%%", area)).append('\n')
            db.append("    বক্স %: (").append(f("%.1f,%.1f", d.l * 100f, d.t * 100f)).append(") → (")
                .append(f("%.1f,%.1f", d.r * 100f, d.b * 100f)).append(")\n")
        }
        out.add(Section("ডিটেকশন তালিকা", db.toString().trimEnd()))

        val dropped = r.raw.filter { d -> dets.none { it === d } }.take(10)
        if (dropped.isNotEmpty()) {
            val lb = StringBuilder()
            for (d in dropped) lb.append(d.label).append("  ").append(pct(d.score)).append('\n')
            out.add(Section("বাদ পড়া ক্যান্ডিডেট (থ্রেশহোল্ড/NMS এ), সেরা ১০", lb.toString().trimEnd()))
        }
        out.add(
            Section(
                "মডেল ও ইনপুট তথ্য",
                "ইনপুট টাইপ: ${r.inType}   নর্মালাইজেশন: ${r.norm}   labels: ${r.labelCount}\n" + ioText(r.io)
            )
        )
        return out
    }

    fun text(r: TextResult, input: String, modelName: String): List<Section> {
        val out = ArrayList<Section>()
        val n = r.cps.size
        val sb = StringBuilder()
        sb.append("মোট ক্যারেক্টার: ").append(n).append("   ইনপুট দৈর্ঘ্য: ").append(input.length).append('\n')
        sb.append("অজানা (unk) ক্যারেক্টার: ").append(r.unknownCount)
        if (r.unknownChars.isNotEmpty()) sb.append("  → ").append(r.unknownChars.joinToString(" "))
        sb.append('\n')
        sb.append("চাঙ্ক: ").append(r.chunks).append("   সিকোয়েন্স দৈর্ঘ্য: ").append(r.seqLen).append("   ওভারল্যাপ: ").append(r.overlap).append('\n')
        val top = r.overall.firstOrNull()
        if (top != null) sb.append("প্রধান লেবেল: ").append(top.label).append("  (গড় ").append(pct(top.prob)).append(")\n")
        if (r.perToken && n > 0) {
            var mean = 0f
            var mn = 1f
            for (c in r.conf) { mean += c; if (c < mn) mn = c }
            sb.append("আর্গম্যাক্স কনফিডেন্স: গড় ").append(pct(mean / n)).append(", সর্বনিম্ন ").append(pct(mn)).append('\n')
            val low = r.conf.count { it < 0.6f }
            sb.append("৬০% এর কম কনফিডেন্সের ক্যারেক্টার: ").append(low).append('\n')
        }
        sb.append("সময়: টোকেনাইজ ").append(r.prepMs).append(" ms, ইনফারেন্স ").append(r.infMs).append(" ms")
        out.add(Section("সারাংশ — $modelName", sb.toString()))

        val lb = StringBuilder()
        for (s in r.overall) {
            lb.append(s.label).append("  গড় probability ").append(pct(s.prob))
            if (r.perToken && n > 0) {
                val c = r.charsPerLabel[s.index]
                lb.append("   ক্যারেক্টার ").append(c).append(" (").append(f("%.1f%%", c * 100f / n)).append(")")
            }
            lb.append('\n')
        }
        out.add(Section("লেবেল পরিসংখ্যান", lb.toString().trimEnd()))

        if (r.perToken) {
            val gb = StringBuilder()
            for (s in r.segments.take(300)) {
                gb.append('[').append(s.label).append(' ').append(pct(s.conf)).append(" | min ").append(pct(s.minConf))
                    .append(" | ").append(s.start).append('-').append(s.end).append("] ")
                    .append(s.text.replace("\n", "⏎")).append('\n')
            }
            if (r.segments.size > 300) gb.append("… আরও ").append(r.segments.size - 300).append("টি সেগমেন্ট\n")
            out.add(Section("সেগমেন্ট (একই লেবেলের পাশাপাশি অংশ)", gb.toString().trimEnd()))

            val pb = StringBuilder()
            val lim = minOf(n, 400)
            for (i in 0 until lim) {
                val ch = String(Character.toChars(r.cps[i])).replace("\n", "⏎").replace(" ", "␣")
                pb.append(i).append("  '").append(ch).append("'  U+").append(String.format("%04X", r.cps[i]))
                    .append("  → ").append(r.labels[r.argIdx[i]]).append(' ').append(pct(r.conf[i])).append("  |")
                for (c in r.labels.indices) pb.append(' ').append(r.labels[c]).append('=').append(f("%.3f", r.probs[i][c]))
                pb.append('\n')
            }
            if (n > lim) pb.append("… আরও ").append(n - lim).append("টি ক্যারেক্টার\n")
            out.add(Section("প্রতি ক্যারেক্টার (প্রথম $lim)", pb.toString().trimEnd()))
        }
        out.add(Section("মডেল তথ্য", ioText(r.io)))
        return out
    }
}
