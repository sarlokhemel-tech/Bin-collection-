package com.hemel.nanolab

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.File
import java.util.zip.ZipInputStream

enum class ModelType(val label: String) {
    DETECT("Nano Object Detection"),
    CLASSIFY("ইমেজ ক্লাসিফিকেশন"),
    TEXT("টেক্সট ক্লাসিফিকেশন")
}

data class ModelMeta(
    val id: String,
    val name: String,
    val type: ModelType,
    val modelFile: String,
    val labelsFile: String?,
    val tokenizerFile: String?,
    val configFile: String?,
    val norm: String,
    val builtin: Boolean
)

class ModelStore(private val ctx: Context) {
    private val root = File(ctx.filesDir, "models").apply { mkdirs() }

    fun dir(id: String): File = File(root, id)

    fun list(): List<ModelMeta> {
        ensureBuiltin()
        val out = ArrayList<ModelMeta>()
        for (d in root.listFiles().orEmpty()) {
            val f = File(d, "meta.json")
            if (!d.isDirectory || !f.exists()) continue
            try {
                out.add(readMeta(f.readText()))
            } catch (e: Exception) {
                // ignore broken entry
            }
        }
        return out.sortedBy { it.name.lowercase() }
    }

    fun delete(id: String) {
        dir(id).deleteRecursively()
    }

    fun displayName(uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) return c.getString(i) ?: "file"
            }
        } catch (e: Exception) {
        }
        return uri.lastPathSegment ?: "file"
    }

    private fun copy(uri: Uri, dest: File) {
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("ফাইল খোলা যায়নি")
        input.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
    }

    fun importModel(name: String, type: ModelType, norm: String, model: Uri, aux1: Uri?, aux2: Uri?): ModelMeta {
        val id = "m" + System.currentTimeMillis()
        val d = File(root, id)
        d.mkdirs()
        try {
            var labels: String? = null
            var tok: String? = null
            var cfg: String? = null
            val modelName = displayName(model)

            if (modelName.endsWith(".zip", ignoreCase = true)) {
                val tflites = LinkedHashMap<String, ByteArray>()
                val input = ctx.contentResolver.openInputStream(model) ?: throw IllegalStateException("ZIP খোলা যায়নি")
                input.use { ins ->
                    ZipInputStream(ins).use { zin ->
                        var e = zin.nextEntry
                        while (e != null) {
                            val base = e.name.substringAfterLast('/').lowercase()
                            if (!e.isDirectory) {
                                if (base.endsWith(".tflite")) {
                                    tflites[base] = zin.readBytes()
                                } else if (base == "tokenizer.json") {
                                    File(d, "tokenizer.json").writeBytes(zin.readBytes())
                                    tok = "tokenizer.json"
                                } else if (base == "config.json") {
                                    File(d, "config.json").writeBytes(zin.readBytes())
                                    cfg = "config.json"
                                } else if (base.endsWith(".txt") && base.contains("label")) {
                                    File(d, "labels.txt").writeBytes(zin.readBytes())
                                    labels = "labels.txt"
                                }
                            }
                            e = zin.nextEntry
                        }
                    }
                }
                val key = tflites.keys.firstOrNull { it.contains("fp32") } ?: tflites.keys.firstOrNull()
                    ?: throw IllegalStateException("ZIP এর ভেতরে .tflite ফাইল নেই")
                File(d, "model.tflite").writeBytes(tflites[key]!!)
            } else {
                copy(model, File(d, "model.tflite"))
                if (type == ModelType.TEXT) {
                    if (aux1 != null) { copy(aux1, File(d, "tokenizer.json")); tok = "tokenizer.json" }
                    if (aux2 != null) { copy(aux2, File(d, "config.json")); cfg = "config.json" }
                } else {
                    if (aux1 != null) { copy(aux1, File(d, "labels.txt")); labels = "labels.txt" }
                }
            }

            if (type == ModelType.TEXT && tok == null) {
                throw IllegalStateException("টেক্সট মডেলের জন্য tokenizer.json লাগবে")
            }

            // validate that the model really loads
            val chk = Interpreter(File(d, "model.tflite"))
            chk.close()

            val meta = ModelMeta(
                id = id,
                name = name.ifBlank { modelName.substringBeforeLast('.') },
                type = type,
                modelFile = "model.tflite",
                labelsFile = labels,
                tokenizerFile = tok,
                configFile = cfg,
                norm = norm,
                builtin = false
            )
            File(d, "meta.json").writeText(writeMeta(meta))
            return meta
        } catch (e: Throwable) {
            d.deleteRecursively()
            throw e
        }
    }

    private fun ensureBuiltin() {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean("builtin_done", false)) return
        val d = File(root, "builtin-prismnano")
        d.mkdirs()
        for (f in listOf("model.tflite", "tokenizer.json", "config.json")) {
            ctx.assets.open("prismnano/$f").use { i -> File(d, f).outputStream().use { o -> i.copyTo(o) } }
        }
        val meta = ModelMeta(
            "builtin-prismnano", "PrismNano (বিল্ট-ইন)", ModelType.TEXT,
            "model.tflite", null, "tokenizer.json", "config.json", "01", true
        )
        File(d, "meta.json").writeText(writeMeta(meta))
        prefs.edit().putBoolean("builtin_done", true).apply()
    }

    private fun writeMeta(m: ModelMeta): String {
        val j = JSONObject()
        j.put("id", m.id)
        j.put("name", m.name)
        j.put("type", m.type.name)
        j.put("modelFile", m.modelFile)
        j.put("labelsFile", m.labelsFile ?: JSONObject.NULL)
        j.put("tokenizerFile", m.tokenizerFile ?: JSONObject.NULL)
        j.put("configFile", m.configFile ?: JSONObject.NULL)
        j.put("norm", m.norm)
        j.put("builtin", m.builtin)
        return j.toString()
    }

    private fun readMeta(s: String): ModelMeta {
        val j = JSONObject(s)
        fun opt(k: String): String? = if (j.isNull(k)) null else j.getString(k)
        return ModelMeta(
            id = j.getString("id"),
            name = j.getString("name"),
            type = ModelType.valueOf(j.getString("type")),
            modelFile = j.getString("modelFile"),
            labelsFile = opt("labelsFile"),
            tokenizerFile = opt("tokenizerFile"),
            configFile = opt("configFile"),
            norm = j.optString("norm", "01"),
            builtin = j.optBoolean("builtin", false)
        )
    }

    companion object {
        const val PREFS = "nanolab"
    }
}
