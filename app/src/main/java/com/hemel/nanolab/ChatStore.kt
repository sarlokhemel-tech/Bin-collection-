package com.hemel.nanolab

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(14)

class Attachment(
    val id: String,
    val kind: String,
    val path: String,
    val name: String,
    val mime: String,
    var remoteUri: String? = null,
    var remoteTime: Long = 0L
)

class ChatMessage(
    val id: String,
    initialRole: String,
    initialText: String,
    val attachments: List<Attachment>,
    val time: Long,
    initialModel: String = ""
) {
    var role by mutableStateOf(initialRole)
    var text by mutableStateOf(initialText)
    var model by mutableStateOf(initialModel)
}

class ChatSession(val id: String, val kind: String, val createdAt: Long) {
    var title by mutableStateOf("নতুন চ্যাট")
    var updatedAt by mutableStateOf(createdAt)
    var provider by mutableStateOf(0)
    var modelName by mutableStateOf("")
    var systemPrompt by mutableStateOf("")
    var promptName by mutableStateOf("")
    var draft by mutableStateOf("")
    var sending by mutableStateOf(false)
    var status by mutableStateOf("")
    var gen: Int = 0
    val messages = mutableStateListOf<ChatMessage>()
    val pending = mutableStateListOf<Attachment>()
}

class ChatStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "chats").apply { mkdirs() }
    val mediaRoot = File(ctx.filesDir, "media").apply { mkdirs() }
    private val exec = Executors.newSingleThreadExecutor()

    fun mediaDir(sessionId: String): File = File(mediaRoot, sessionId).apply { mkdirs() }

    private fun attToJson(a: Attachment): JSONObject = JSONObject()
        .put("id", a.id).put("kind", a.kind).put("path", a.path).put("name", a.name).put("mime", a.mime)
        .put("remoteUri", a.remoteUri ?: JSONObject.NULL).put("remoteTime", a.remoteTime)

    private fun attFromJson(j: JSONObject): Attachment = Attachment(
        j.getString("id"), j.getString("kind"), j.getString("path"), j.getString("name"), j.getString("mime"),
        if (j.isNull("remoteUri")) null else j.getString("remoteUri"), j.optLong("remoteTime", 0L)
    )

    private fun toJson(s: ChatSession): String {
        val j = JSONObject()
        j.put("id", s.id).put("kind", s.kind).put("createdAt", s.createdAt)
        j.put("title", s.title).put("updatedAt", s.updatedAt)
        j.put("provider", s.provider).put("modelName", s.modelName)
        j.put("systemPrompt", s.systemPrompt).put("promptName", s.promptName).put("draft", s.draft)
        val arr = JSONArray()
        for (m in s.messages.toList()) {
            val mj = JSONObject()
            mj.put("id", m.id).put("role", m.role).put("text", m.text).put("time", m.time).put("model", m.model)
            val aa = JSONArray()
            for (a in m.attachments) aa.put(attToJson(a))
            mj.put("attachments", aa)
            arr.put(mj)
        }
        j.put("messages", arr)
        return j.toString()
    }

    fun save(s: ChatSession) {
        if (s.messages.isEmpty()) return
        val json = toJson(s)
        val f = File(dir, s.id + ".json")
        exec.execute {
            try {
                val tmp = File(dir, s.id + ".tmp")
                tmp.writeText(json)
                tmp.renameTo(f)
            } catch (e: Exception) {
            }
        }
    }

    fun delete(s: ChatSession) {
        exec.execute {
            File(dir, s.id + ".json").delete()
            File(mediaRoot, s.id).deleteRecursively()
        }
    }

    fun loadAll(): List<ChatSession> {
        val out = ArrayList<ChatSession>()
        for (f in dir.listFiles().orEmpty()) {
            if (!f.name.endsWith(".json")) continue
            try {
                val j = JSONObject(f.readText())
                val s = ChatSession(j.getString("id"), j.getString("kind"), j.optLong("createdAt", 0L))
                s.title = j.optString("title", "চ্যাট")
                s.updatedAt = j.optLong("updatedAt", s.createdAt)
                s.provider = j.optInt("provider", 0)
                s.modelName = j.optString("modelName", "")
                s.systemPrompt = j.optString("systemPrompt", "")
                s.promptName = j.optString("promptName", "")
                s.draft = j.optString("draft", "")
                val arr = j.getJSONArray("messages")
                for (i in 0 until arr.length()) {
                    val mj = arr.getJSONObject(i)
                    val atts = ArrayList<Attachment>()
                    val aa = mj.optJSONArray("attachments")
                    if (aa != null) for (k in 0 until aa.length()) atts.add(attFromJson(aa.getJSONObject(k)))
                    s.messages.add(
                        ChatMessage(
                            mj.getString("id"), mj.getString("role"), mj.optString("text", ""),
                            atts, mj.optLong("time", 0L), mj.optString("model", "")
                        )
                    )
                }
                out.add(s)
            } catch (e: Exception) {
            }
        }
        return out
    }
}
