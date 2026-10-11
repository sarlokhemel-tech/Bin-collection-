package com.hemel.nanolab

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class Turn(val role: String, val text: String, val attachments: List<Attachment>)

object ChatApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    private const val G_BASE = "https://generativelanguage.googleapis.com"

    private fun errText(body: String): String {
        return try {
            val j = JSONObject(body)
            val e = j.optJSONObject("error")
            e?.optString("message")?.ifBlank { null } ?: body.take(300)
        } catch (ex: Exception) {
            body.take(300)
        }
    }

    private fun send(req: Request): String {
        client.newCall(req).execute().use { resp ->
            val s = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${errText(s)}")
            return s
        }
    }

    private fun streamSse(req: Request, register: (Call) -> Unit, onData: (JSONObject) -> Unit) {
        val call = client.newCall(req)
        register(call)
        call.execute().use { resp ->
            if (!resp.isSuccessful) {
                val s = resp.body?.string().orEmpty()
                throw IOException("HTTP ${resp.code}: ${errText(s)}")
            }
            val src = resp.body?.source() ?: throw IOException("খালি উত্তর")
            while (true) {
                val line = src.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.substring(5).trim()
                if (data.isEmpty()) continue
                if (data == "[DONE]") break
                val j = try { JSONObject(data) } catch (e: Exception) { null }
                if (j != null) onData(j)
            }
        }
    }

    // ------------------------------------------------------------ Gemini

    private fun uploadGeminiVideo(key: String, att: Attachment, onStatus: (String) -> Unit, register: (Call) -> Unit) {
        val now = System.currentTimeMillis()
        if (att.remoteUri != null && now - att.remoteTime < 44L * 3600L * 1000L) return
        val f = File(att.path)
        if (!f.exists()) throw IOException("ভিডিও ফাইল পাওয়া যায়নি: ${att.name}")
        onStatus("ভিডিও আপলোড হচ্ছে…")

        val startBody = JSONObject().put("file", JSONObject().put("display_name", att.name))
            .toString().toRequestBody(JSON_TYPE)
        val startReq = Request.Builder()
            .url("$G_BASE/upload/v1beta/files")
            .header("x-goog-api-key", key.trim())
            .header("X-Goog-Upload-Protocol", "resumable")
            .header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", f.length().toString())
            .header("X-Goog-Upload-Header-Content-Type", att.mime)
            .post(startBody)
            .build()
        val startCall = client.newCall(startReq)
        register(startCall)
        val uploadUrl: String = startCall.execute().use { r ->
            if (!r.isSuccessful) throw IOException("আপলোড শুরু ব্যর্থ: HTTP ${r.code}: ${errText(r.body?.string().orEmpty())}")
            r.header("x-goog-upload-url") ?: throw IOException("আপলোড URL পাওয়া যায়নি")
        }

        val upReq = Request.Builder()
            .url(uploadUrl)
            .header("X-Goog-Upload-Offset", "0")
            .header("X-Goog-Upload-Command", "upload, finalize")
            .post(f.asRequestBody(att.mime.toMediaType()))
            .build()
        val upCall = client.newCall(upReq)
        register(upCall)
        val upBody: String = upCall.execute().use { r ->
            val s = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("আপলোড ব্যর্থ: HTTP ${r.code}: ${errText(s)}")
            s
        }
        val fileJson = JSONObject(upBody).getJSONObject("file")
        val name = fileJson.getString("name")
        val uri = fileJson.getString("uri")
        var state = fileJson.optString("state", "ACTIVE")

        var tries = 0
        while (state == "PROCESSING" && tries < 90) {
            onStatus("ভিডিও প্রসেস হচ্ছে…")
            Thread.sleep(2000)
            val gr = Request.Builder().url("$G_BASE/v1beta/$name").header("x-goog-api-key", key.trim()).build()
            val gc = client.newCall(gr)
            register(gc)
            val body = gc.execute().use { r ->
                val s = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw IOException("স্ট্যাটাস ব্যর্থ: HTTP ${r.code}: ${errText(s)}")
                s
            }
            state = JSONObject(body).optString("state", "ACTIVE")
            tries++
        }
        if (state != "ACTIVE") throw IOException("ভিডিও প্রসেস হয়নি (state=$state)")
        att.remoteUri = uri
        att.remoteTime = System.currentTimeMillis()
    }

    fun geminiStream(
        key: String, model: String, system: String, turns: List<Turn>, temperature: Float,
        onStatus: (String) -> Unit, onDelta: (String) -> Unit, register: (Call) -> Unit
    ) {
        for (t in turns) {
            for (a in t.attachments) {
                if (a.kind == "video") uploadGeminiVideo(key, a, onStatus, register)
            }
        }
        onStatus("")

        val body = JSONObject()
        if (system.isNotBlank()) {
            body.put(
                "system_instruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))
            )
        }
        val contents = JSONArray()
        for (t in turns) {
            val parts = JSONArray()
            for (a in t.attachments) {
                if (a.kind == "image") {
                    parts.put(
                        JSONObject().put(
                            "inline_data",
                            JSONObject().put("mime_type", a.mime).put("data", Media.fileBase64(a.path))
                        )
                    )
                } else if (a.kind == "video" && a.remoteUri != null) {
                    parts.put(
                        JSONObject().put(
                            "file_data",
                            JSONObject().put("mime_type", a.mime).put("file_uri", a.remoteUri)
                        )
                    )
                }
            }
            if (t.text.isNotBlank() || parts.length() == 0) parts.put(JSONObject().put("text", t.text.ifBlank { " " }))
            contents.put(
                JSONObject().put("role", if (t.role == "user") "user" else "model").put("parts", parts)
            )
        }
        body.put("contents", contents)
        body.put("generationConfig", JSONObject().put("temperature", temperature.toDouble()))

        val name = model.trim().removePrefix("models/")
        val req = Request.Builder()
            .url("$G_BASE/v1beta/models/$name:streamGenerateContent?alt=sse")
            .header("x-goog-api-key", key.trim())
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()

        var emitted = false
        var blocked: String? = null
        streamSse(req, register) { j ->
            val cands = j.optJSONArray("candidates")
            if (cands != null && cands.length() > 0) {
                val c0 = cands.getJSONObject(0)
                val parts = c0.optJSONObject("content")?.optJSONArray("parts")
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val p = parts.getJSONObject(i)
                        val tx = p.optString("text", "")
                        if (tx.isNotEmpty() && !p.optBoolean("thought", false)) {
                            emitted = true
                            onDelta(tx)
                        }
                    }
                }
                val fr = c0.optString("finishReason", "")
                if (fr.isNotEmpty() && fr != "STOP" && fr != "MAX_TOKENS" && !emitted) blocked = fr
            } else {
                val pf = j.optJSONObject("promptFeedback")
                if (pf != null) blocked = pf.toString()
            }
        }
        if (!emitted) throw IOException("উত্তর আসেনি" + (if (blocked != null) " ($blocked)" else ""))
    }

    // ------------------------------------------------------------ Groq

    fun groqStream(
        key: String, model: String, system: String, turns: List<Turn>, temperature: Float,
        onStatus: (String) -> Unit, onDelta: (String) -> Unit, register: (Call) -> Unit
    ) {
        val arr = JSONArray()
        if (system.isNotBlank()) arr.put(JSONObject().put("role", "system").put("content", system))
        val lastUser = turns.indexOfLast { it.role == "user" }
        var imageBudget = 5
        turns.forEachIndexed { idx, t ->
            val role = if (t.role == "user") "user" else "assistant"
            if (t.attachments.isEmpty()) {
                arr.put(JSONObject().put("role", role).put("content", t.text))
            } else if (idx != lastUser || role != "user") {
                arr.put(JSONObject().put("role", role).put("content", t.text + "\n[সংযুক্তি ছিল]"))
            } else {
                onStatus("মিডিয়া প্রস্তুত হচ্ছে…")
                val parts = JSONArray()
                if (t.text.isNotBlank()) parts.put(JSONObject().put("type", "text").put("text", t.text))
                for (a in t.attachments) {
                    if (a.kind == "image" && imageBudget > 0) {
                        parts.put(imagePart(Media.fileBase64(a.path)))
                        imageBudget--
                    }
                }
                for (a in t.attachments) {
                    if (a.kind == "video" && imageBudget > 0) {
                        val frames = Media.extractFrames(a.path, minOf(imageBudget, 4))
                        for (fr in frames) {
                            parts.put(imagePart(Media.bytesBase64(fr)))
                            imageBudget--
                        }
                        parts.put(JSONObject().put("type", "text").put("text", "(উপরের ছবিগুলো ভিডিও ‘${a.name}’ থেকে নেওয়া ফ্রেম)"))
                    }
                }
                if (parts.length() == 0) parts.put(JSONObject().put("type", "text").put("text", " "))
                arr.put(JSONObject().put("role", role).put("content", parts))
            }
        }
        onStatus("")
        val body = JSONObject().put("model", model.trim()).put("messages", arr).put("stream", true)
            .put("temperature", temperature.toDouble())
        val req = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .header("Authorization", "Bearer " + key.trim())
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        var emitted = false
        streamSse(req, register) { j ->
            val ch = j.optJSONArray("choices")
            if (ch != null && ch.length() > 0) {
                val d = ch.getJSONObject(0).optJSONObject("delta")
                val tx = if (d == null || d.isNull("content")) "" else d.optString("content", "")
                if (tx.isNotEmpty()) {
                    emitted = true
                    onDelta(tx)
                }
            }
        }
        if (!emitted) throw IOException("উত্তর আসেনি")
    }

    private fun imagePart(b64: String): JSONObject =
        JSONObject().put("type", "image_url")
            .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))

    // ------------------------------------------------------------ model list

    fun listModels(provider: Int, key: String): List<String> {
        if (provider == 0) {
            val req = Request.Builder()
                .url("$G_BASE/v1beta/models?pageSize=200")
                .header("x-goog-api-key", key.trim())
                .build()
            val arr = JSONObject(send(req)).optJSONArray("models") ?: return emptyList()
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val methods = m.optJSONArray("supportedGenerationMethods")
                var ok = false
                if (methods != null) {
                    for (k in 0 until methods.length()) if (methods.getString(k) == "generateContent") ok = true
                }
                if (ok) out.add(m.getString("name").removePrefix("models/"))
            }
            return out
        } else {
            val req = Request.Builder()
                .url("https://api.groq.com/openai/v1/models")
                .header("Authorization", "Bearer " + key.trim())
                .build()
            val arr = JSONObject(send(req)).optJSONArray("data") ?: return emptyList()
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) out.add(arr.getJSONObject(i).getString("id"))
            return out.sorted()
        }
    }
}
