package com.hemel.nanolab

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ChatMsg(val role: String, val text: String)

object ChatApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

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

    fun gemini(key: String, model: String, system: String, msgs: List<ChatMsg>): String {
        val body = JSONObject()
        if (system.isNotBlank()) {
            body.put(
                "system_instruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))
            )
        }
        val contents = JSONArray()
        for (m in msgs) {
            contents.put(
                JSONObject()
                    .put("role", if (m.role == "user") "user" else "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", m.text)))
            )
        }
        body.put("contents", contents)
        val name = model.trim().removePrefix("models/")
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$name:generateContent")
            .header("x-goog-api-key", key.trim())
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        val j = JSONObject(send(req))
        val cands = j.optJSONArray("candidates")
        if (cands == null || cands.length() == 0) {
            val fb = j.optJSONObject("promptFeedback")?.toString() ?: j.toString().take(300)
            return "(উত্তর আসেনি) $fb"
        }
        val parts = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
        val sb = StringBuilder()
        if (parts != null) {
            for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
        }
        return sb.toString().ifBlank { "(খালি উত্তর)" }
    }

    fun groq(key: String, model: String, system: String, msgs: List<ChatMsg>): String {
        val arr = JSONArray()
        if (system.isNotBlank()) arr.put(JSONObject().put("role", "system").put("content", system))
        for (m in msgs) arr.put(JSONObject().put("role", m.role).put("content", m.text))
        val body = JSONObject().put("model", model.trim()).put("messages", arr)
        val req = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .header("Authorization", "Bearer " + key.trim())
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        val j = JSONObject(send(req))
        val choices = j.optJSONArray("choices")
        if (choices == null || choices.length() == 0) return "(উত্তর আসেনি)"
        return choices.getJSONObject(0).optJSONObject("message")?.optString("content").orEmpty()
            .ifBlank { "(খালি উত্তর)" }
    }

    fun listModels(provider: Int, key: String): List<String> {
        if (provider == 0) {
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200")
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
