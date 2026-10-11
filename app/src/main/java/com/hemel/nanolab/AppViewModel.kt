package com.hemel.nanolab

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

fun decodeBitmap(ctx: Context, uri: Uri, maxSide: Int = 1600): Bitmap? {
    return try {
        if (Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(ctx.contentResolver, uri)
            ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                val m = maxOf(w, h)
                if (m > maxSide) {
                    val s = maxSide.toFloat() / m
                    decoder.setTargetSize(maxOf(1, (w * s).toInt()), maxOf(1, (h * s).toInt()))
                }
            }
        } else {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
            val o = BitmapFactory.Options()
            o.inSampleSize = sample
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        }
    } catch (e: Exception) {
        null
    }
}

class SavedPrompt(val id: String, val name: String, val text: String)

class TestState {
    var bitmap by mutableStateOf<Bitmap?>(null)
    var imageName by mutableStateOf("")
    var text by mutableStateOf("")
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var classify by mutableStateOf<ClassifyResult?>(null)
    var detect by mutableStateOf<DetectResult?>(null)
    var textRes by mutableStateOf<TextResult?>(null)
    var textUsed by mutableStateOf("")
    var conf by mutableStateOf(0.40f)
    var iou by mutableStateOf(0.45f)
    var maxDet by mutableStateOf(20f)
    var topK by mutableStateOf(5f)
}

val DEFAULT_GEMINI = listOf("gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.5-flash-lite")
val DEFAULT_GROQ = listOf(
    "llama-3.3-70b-versatile",
    "meta-llama/llama-4-scout-17b-16e-instruct",
    "llama-3.1-8b-instant"
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context = app.applicationContext
    val store = ModelStore(ctx)
    val chatStore = ChatStore(ctx)
    private val prefs = ctx.getSharedPreferences(ModelStore.PREFS, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())

    // 0 chat, 1 local llm, 2 test, 3 models, 4 accessibility, 5 settings
    var screen by mutableStateOf(0)
    var info by mutableStateOf<String?>(null)
    var loaded by mutableStateOf(false)

    // ------------------------------------------------ settings
    var geminiKey by mutableStateOf(prefs.getString("gemini_key", "") ?: "")
    var groqKey by mutableStateOf(prefs.getString("groq_key", "") ?: "")
    var llmMaxTokens by mutableStateOf(prefs.getInt("llm_max_tokens", 1024).toFloat())
    var temperature by mutableStateOf(prefs.getFloat("temperature", 1.0f))
    var prompts by mutableStateOf<List<SavedPrompt>>(emptyList())
    var activePromptId by mutableStateOf(prefs.getString("active_prompt", "") ?: "")

    fun persistKeys() {
        prefs.edit().putString("gemini_key", geminiKey).putString("groq_key", groqKey).apply()
    }

    fun persistLlmSettings() {
        prefs.edit().putInt("llm_max_tokens", llmMaxTokens.toInt()).putFloat("temperature", temperature).apply()
    }

    private fun loadPrompts() {
        val s = prefs.getString("prompts_json", "[]") ?: "[]"
        val out = ArrayList<SavedPrompt>()
        try {
            val a = JSONArray(s)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(SavedPrompt(o.getString("id"), o.getString("name"), o.getString("text")))
            }
        } catch (e: Exception) {
        }
        prompts = out
    }

    private fun savePrompts() {
        val a = JSONArray()
        for (p in prompts) a.put(JSONObject().put("id", p.id).put("name", p.name).put("text", p.text))
        prefs.edit().putString("prompts_json", a.toString()).putString("active_prompt", activePromptId).apply()
    }

    fun upsertPrompt(id: String?, name: String, text: String) {
        val nid = id ?: newId()
        val np = SavedPrompt(nid, name.ifBlank { "প্রম্পট" }, text)
        prompts = if (id != null && prompts.any { it.id == id }) prompts.map { if (it.id == id) np else it } else prompts + np
        if (activePromptId.isEmpty()) activePromptId = nid
        savePrompts()
    }

    fun deletePrompt(id: String) {
        prompts = prompts.filter { it.id != id }
        if (activePromptId == id) activePromptId = ""
        savePrompts()
    }

    fun activatePrompt(id: String) {
        activePromptId = id
        savePrompts()
    }

    fun keyFor(p: Int): String = if (p == 0) geminiKey else groqKey

    // ------------------------------------------------ models (tflite + llm)
    var models by mutableStateOf<List<ModelMeta>>(emptyList())
    var importing by mutableStateOf(false)

    fun refreshModels() {
        viewModelScope.launch {
            models = withContext(Dispatchers.IO) { store.list() }
        }
    }

    fun importModel(
        name: String, type: ModelType, norm: String, model: Uri, aux1: Uri?, aux2: Uri?, template: String
    ) {
        importing = true
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { store.importModel(name, type, norm, model, aux1, aux2, template) }
            }
            r.onSuccess {
                info = "ইম্পোর্ট হয়েছে: ${it.name}"
                if (it.type == ModelType.LLM) {
                    val ls = activeSession("local")
                    if (ls != null && ls.messages.isEmpty()) ls.modelName = it.id
                } else {
                    testModelId = it.id
                }
            }.onFailure {
                info = "ইম্পোর্ট ব্যর্থ: ${it.message}"
            }
            importing = false
            refreshModels()
        }
    }

    fun deleteModel(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.delete(id) }
            LocalLlm.unload()
            refreshModels()
        }
    }

    // ------------------------------------------------ test mode
    var testModelId by mutableStateOf<String?>(null)
    private val tests = HashMap<String, TestState>()

    fun testState(id: String): TestState {
        var t = tests[id]
        if (t == null) {
            t = TestState()
            tests[id] = t
        }
        return t
    }

    fun loadTestImage(st: TestState, uri: Uri) {
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) { decodeBitmap(ctx, uri) }
            if (bmp == null) {
                info = "ছবি লোড করা যায়নি"
            } else {
                st.bitmap = bmp
                st.imageName = Media.displayName(ctx, uri)
                st.classify = null
                st.detect = null
                st.error = null
            }
        }
    }

    fun runImageTest(meta: ModelMeta, st: TestState) {
        val bmp = st.bitmap ?: return
        if (st.busy) return
        st.busy = true
        st.error = null
        viewModelScope.launch {
            val r = withContext(Dispatchers.Default) {
                runCatching<Any> {
                    val d = store.dir(meta.id)
                    if (meta.type == ModelType.DETECT) ImageEngine.detect(d, meta, bmp)
                    else ImageEngine.classify(d, meta, bmp)
                }
            }
            r.onSuccess {
                if (it is DetectResult) {
                    st.detect = it
                    st.classify = null
                } else if (it is ClassifyResult) {
                    st.classify = it
                    st.detect = null
                }
            }.onFailure { st.error = "ত্রুটি: ${it.message}" }
            st.busy = false
        }
    }

    fun runTextTest(meta: ModelMeta, st: TestState) {
        if (st.busy) return
        val text = st.text
        st.busy = true
        st.error = null
        viewModelScope.launch {
            val r = withContext(Dispatchers.Default) {
                runCatching { TextEngine.run(store.dir(meta.id), meta, text) }
            }
            r.onSuccess {
                st.textRes = it
                st.textUsed = text
            }.onFailure { st.error = "ত্রুটি: ${it.message}" }
            st.busy = false
        }
    }

    // ------------------------------------------------ chat sessions
    val sessions = mutableStateListOf<ChatSession>()
    var activeCloudId by mutableStateOf<String?>(null)
    var activeLocalId by mutableStateOf<String?>(null)
    val remote = mutableStateMapOf<Int, List<String>>()
    val remoteLoading = mutableStateMapOf<Int, Boolean>()

    private val jobs = ConcurrentHashMap<String, Job>()
    private val calls = ConcurrentHashMap<String, Call>()

    init {
        loadPrompts()
        refreshModels()
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { chatStore.loadAll() }
            sessions.addAll(list.sortedByDescending { it.updatedAt })
            for (s in list) {
                if (s.sending) s.sending = false
            }
            activeCloudId = sessions.firstOrNull { it.kind == "cloud" }?.id
            activeLocalId = sessions.firstOrNull { it.kind == "local" }?.id
            if (activeCloudId == null) newChat("cloud")
            if (activeLocalId == null) newChat("local")
            loaded = true
        }
    }

    fun activeSession(kind: String): ChatSession? {
        val id = if (kind == "cloud") activeCloudId else activeLocalId
        return sessions.firstOrNull { it.id == id }
    }

    fun defaultModel(p: Int): String {
        val saved = prefs.getString("last_model_$p", "") ?: ""
        if (saved.isNotEmpty()) return saved
        return if (p == 0) DEFAULT_GEMINI[0] else DEFAULT_GROQ[0]
    }

    fun newChat(kind: String): ChatSession {
        val cur = activeSession(kind)
        if (cur != null && cur.messages.isEmpty() && cur.pending.isEmpty()) return cur
        val s = ChatSession(newId(), kind, System.currentTimeMillis())
        if (kind == "cloud") {
            s.provider = prefs.getInt("last_provider", 0)
            s.modelName = defaultModel(s.provider)
        } else {
            val last = prefs.getString("last_local", "") ?: ""
            s.modelName = if (models.any { it.id == last }) last else models.firstOrNull { it.type == ModelType.LLM }?.id ?: ""
        }
        val ap = prompts.firstOrNull { it.id == activePromptId }
        s.systemPrompt = ap?.text ?: ""
        s.promptName = ap?.name ?: ""
        sessions.add(0, s)
        if (kind == "cloud") activeCloudId = s.id else activeLocalId = s.id
        return s
    }

    fun openSession(s: ChatSession) {
        if (s.kind == "cloud") {
            activeCloudId = s.id
            screen = 0
        } else {
            activeLocalId = s.id
            screen = 1
        }
    }

    fun deleteSession(s: ChatSession) {
        stop(s)
        sessions.remove(s)
        chatStore.delete(s)
        if (activeCloudId == s.id) {
            activeCloudId = null
            newChat("cloud")
        }
        if (activeLocalId == s.id) {
            activeLocalId = null
            newChat("local")
        }
    }

    fun clearAllChats() {
        for (s in sessions.toList()) {
            stop(s)
            chatStore.delete(s)
        }
        sessions.clear()
        activeCloudId = null
        activeLocalId = null
        newChat("cloud")
        newChat("local")
    }

    fun renameSession(s: ChatSession, title: String) {
        s.title = title.ifBlank { s.title }
        chatStore.save(s)
    }

    fun selectCloudModel(s: ChatSession, provider: Int, name: String) {
        s.provider = provider
        s.modelName = name.trim()
        prefs.edit().putInt("last_provider", provider).putString("last_model_$provider", s.modelName).apply()
        chatStore.save(s)
    }

    fun selectLocalModel(s: ChatSession, id: String) {
        s.modelName = id
        prefs.edit().putString("last_local", id).apply()
        chatStore.save(s)
    }

    fun autoPickLocal(s: ChatSession) {
        if (s.kind == "local" && s.modelName.isEmpty()) {
            val m = models.firstOrNull { it.type == ModelType.LLM }
            if (m != null) s.modelName = m.id
        }
    }

    fun applyPrompt(s: ChatSession, p: SavedPrompt?) {
        s.systemPrompt = p?.text ?: ""
        s.promptName = p?.name ?: ""
    }

    fun modelLabel(s: ChatSession): String {
        if (s.kind == "cloud") return s.modelName.ifBlank { "মডেল বাছুন" }
        val m = models.firstOrNull { it.id == s.modelName }
        return m?.name ?: "মডেল বাছুন"
    }

    fun loadRemoteModels(p: Int, force: Boolean = false) {
        val k = keyFor(p)
        if (k.isBlank()) return
        if (!force && remote[p] != null) return
        if (remoteLoading[p] == true) return
        remoteLoading[p] = true
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { ChatApi.listModels(p, k) } }
            r.onSuccess { remote[p] = it }.onFailure { info = "মডেল লিস্ট আনা যায়নি: ${it.message}" }
            remoteLoading[p] = false
        }
    }

    fun addAttachments(s: ChatSession, uris: List<Uri>, kind: String) {
        viewModelScope.launch {
            for (u in uris) {
                if (s.pending.size >= 10) {
                    info = "একবারে সর্বোচ্চ ১০টি সংযুক্তি"
                    break
                }
                s.status = if (kind == "video") "ভিডিও যোগ হচ্ছে…" else "ছবি যোগ হচ্ছে…"
                val r = withContext(Dispatchers.IO) {
                    runCatching {
                        val d = chatStore.mediaDir(s.id)
                        if (kind == "video") Media.importVideo(ctx, d, u) else Media.importImage(ctx, d, u)
                    }
                }
                r.onSuccess { a ->
                    if (a != null) {
                        s.pending.add(a)
                    } else {
                        info = "ছবি লোড করা যায়নি"
                    }
                }.onFailure { info = "সংযুক্তি ব্যর্থ: ${it.message}" }
                s.status = ""
            }
        }
    }

    fun removePending(s: ChatSession, a: Attachment) {
        s.pending.remove(a)
        File(a.path).delete()
    }

    private fun touch(s: ChatSession) {
        s.updatedAt = System.currentTimeMillis()
        chatStore.save(s)
    }

    fun send(s: ChatSession) {
        val text = s.draft.trim()
        if (s.sending) return
        if (text.isEmpty() && s.pending.isEmpty()) return
        if (s.kind == "cloud") {
            if (keyFor(s.provider).isBlank()) {
                info = "আগে সেটিংসে " + (if (s.provider == 0) "Gemini" else "Groq") + " API key দিন"
                return
            }
            if (s.modelName.isBlank()) {
                info = "আগে একটা মডেল বাছুন"
                return
            }
        } else {
            if (models.none { it.id == s.modelName }) {
                info = "আগে একটা লোকাল LLM মডেল বাছুন বা ইম্পোর্ট করুন"
                return
            }
        }
        val atts = s.pending.toList()
        s.pending.clear()
        s.messages.add(ChatMessage(newId(), "user", text, atts, System.currentTimeMillis()))
        s.draft = ""
        if (s.messages.size == 1) {
            s.title = if (text.isNotEmpty()) text.replace("\n", " ").take(40) else "মিডিয়া চ্যাট"
        }
        startGeneration(s)
    }

    fun regenerate(s: ChatSession) {
        if (s.sending) return
        val last = s.messages.lastOrNull() ?: return
        if (last.role == "user") {
            startGeneration(s)
            return
        }
        s.messages.remove(last)
        if (s.messages.lastOrNull()?.role == "user") startGeneration(s)
    }

    fun stop(s: ChatSession) {
        s.gen += 1
        calls[s.id]?.cancel()
        jobs[s.id]?.cancel()
        s.sending = false
        s.status = ""
        val last = s.messages.lastOrNull()
        if (last != null && last.role == "assistant" && last.text.isEmpty()) s.messages.remove(last)
        touch(s)
    }

    private fun startGeneration(s: ChatSession) {
        val reply = ChatMessage(newId(), "assistant", "", emptyList(), System.currentTimeMillis(), modelLabel(s))
        s.gen += 1
        val myGen = s.gen
        val turns = s.messages
            .filter { it.role != "error" && !(it.role == "assistant" && it.text.isEmpty()) }
            .map { Turn(it.role, it.text, it.attachments) }
        s.messages.add(reply)
        s.sending = true
        s.status = ""
        touch(s)

        val kind = s.kind
        val provider = s.provider
        val modelName = s.modelName
        val sys = s.systemPrompt
        val gKey = geminiKey
        val qKey = groqKey
        val localMeta = models.firstOrNull { it.id == modelName }
        val maxTok = llmMaxTokens.toInt()
        val temp = temperature

        jobs[s.id] = viewModelScope.launch(Dispatchers.IO) {
            fun alive(): Boolean = s.gen == myGen
            val register: (Call) -> Unit = { c -> calls[s.id] = c }
            val onDelta: (String) -> Unit = { d -> if (alive()) reply.text = reply.text + d }
            val onStatus: (String) -> Unit = { t -> if (alive()) s.status = t }
            try {
                if (kind == "cloud") {
                    if (provider == 0) ChatApi.geminiStream(gKey, modelName, sys, turns, temp, onStatus, onDelta, register)
                    else ChatApi.groqStream(qKey, modelName, sys, turns, temp, onStatus, onDelta, register)
                } else {
                    val meta = localMeta ?: throw IOException("লোকাল মডেল পাওয়া যায়নি")
                    onStatus("মডেল লোড হচ্ছে ও উত্তর তৈরি হচ্ছে… (বড় মডেলে সময় লাগে)")
                    val prompt = LocalLlm.buildPrompt(meta.template, sys, turns, maxTok)
                    val out = LocalLlm.generate(ctx, meta.id, File(store.dir(meta.id), meta.modelFile), maxTok, prompt)
                    if (alive()) reply.text = out
                }
                if (alive() && reply.text.isBlank()) {
                    reply.role = "error"
                    reply.text = "খালি উত্তর এসেছে"
                }
            } catch (e: Throwable) {
                if (alive() && e !is CancellationException) {
                    val msg = e.message ?: e.toString()
                    if (reply.text.isEmpty()) {
                        reply.role = "error"
                        reply.text = msg
                    } else {
                        reply.text = reply.text + "\n\n⚠️ " + msg
                    }
                }
            } finally {
                if (alive()) {
                    s.sending = false
                    s.status = ""
                }
                calls.remove(s.id)
                jobs.remove(s.id)
                mainHandler.post { touch(s) }
            }
        }
    }

    fun chatAsText(s: ChatSession): String {
        val sb = StringBuilder()
        sb.append(s.title).append("\n\n")
        for (m in s.messages) {
            val who = when (m.role) {
                "user" -> "আমি"
                "error" -> "ত্রুটি"
                else -> m.model.ifBlank { "AI" }
            }
            sb.append(who).append(":\n").append(m.text)
            if (m.attachments.isNotEmpty()) sb.append("\n[সংযুক্তি: ").append(m.attachments.joinToString(", ") { it.name }).append(']')
            sb.append("\n\n")
        }
        return sb.toString().trim()
    }

    // ------------------------------------------------ accessibility
    var trees by mutableStateOf<List<File>>(emptyList())
    var a11yOn by mutableStateOf(false)

    fun refreshTrees() {
        a11yOn = NanoAccessibilityService.instance != null
        trees = NanoAccessibilityService.treesDir(ctx.filesDir).listFiles().orEmpty()
            .filter { it.isFile }
            .sortedByDescending { it.name }
    }

    fun deleteTree(f: File) {
        f.delete()
        refreshTrees()
    }

    fun clearTrees() {
        for (f in trees) f.delete()
        refreshTrees()
    }
}
