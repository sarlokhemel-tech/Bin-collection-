package com.hemel.nanolab

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context = app.applicationContext
    val store = ModelStore(ctx)
    private val prefs = ctx.getSharedPreferences(ModelStore.PREFS, Context.MODE_PRIVATE)

    var info by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)

    // ---- models / test ----
    var models by mutableStateOf<List<ModelMeta>>(emptyList())
    var selectedId by mutableStateOf<String?>(null)
    var inputBitmap by mutableStateOf<Bitmap?>(null)
    var imageResult by mutableStateOf<ImageResult?>(null)
    var textInput by mutableStateOf("")
    var textResult by mutableStateOf<TextResult?>(null)

    val selected: ModelMeta?
        get() = models.firstOrNull { it.id == selectedId }

    fun refreshModels() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { store.list() }
            models = list
            if (list.none { it.id == selectedId }) selectedId = list.firstOrNull()?.id
        }
    }

    fun select(id: String) {
        selectedId = id
        imageResult = null
        textResult = null
    }

    fun importModel(name: String, type: ModelType, norm: String, model: Uri, aux1: Uri?, aux2: Uri?) {
        busy = true
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { store.importModel(name, type, norm, model, aux1, aux2) }
            }
            r.onSuccess {
                info = "ইম্পোর্ট হয়েছে: ${it.name}"
                selectedId = it.id
            }.onFailure {
                info = "ইম্পোর্ট ব্যর্থ: ${it.message}"
            }
            busy = false
            refreshModels()
        }
    }

    fun deleteModel(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.delete(id) }
            refreshModels()
        }
    }

    fun loadImage(uri: Uri) {
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) { decodeBitmap(ctx, uri) }
            if (bmp == null) {
                info = "ছবি লোড করা যায়নি"
            } else {
                inputBitmap = bmp
                imageResult = null
            }
        }
    }

    fun runImage(meta: ModelMeta) {
        val bmp = inputBitmap ?: return
        busy = true
        viewModelScope.launch {
            val r = withContext(Dispatchers.Default) {
                runCatching {
                    val d = store.dir(meta.id)
                    if (meta.type == ModelType.DETECT) ImageEngine.detect(d, meta, bmp)
                    else ImageEngine.classify(d, meta, bmp)
                }
            }
            r.onSuccess { imageResult = it }.onFailure { info = "ত্রুটি: ${it.message}" }
            busy = false
        }
    }

    fun runText(meta: ModelMeta) {
        val text = textInput
        busy = true
        viewModelScope.launch {
            val r = withContext(Dispatchers.Default) {
                runCatching { TextEngine.run(store.dir(meta.id), meta, text) }
            }
            r.onSuccess { textResult = it }.onFailure { info = "ত্রুটি: ${it.message}" }
            busy = false
        }
    }

    // ---- chat ----
    var provider by mutableStateOf(prefs.getInt("provider", 0))
    private var geminiKey by mutableStateOf(prefs.getString("gemini_key", "") ?: "")
    private var groqKey by mutableStateOf(prefs.getString("groq_key", "") ?: "")
    private var geminiModel by mutableStateOf(prefs.getString("gemini_model", "gemini-2.5-flash") ?: "gemini-2.5-flash")
    private var groqModel by mutableStateOf(prefs.getString("groq_model", "llama-3.3-70b-versatile") ?: "llama-3.3-70b-versatile")
    var systemPrompt by mutableStateOf(prefs.getString("system_prompt", "") ?: "")
    val messages = mutableStateListOf<ChatMsg>()
    var sending by mutableStateOf(false)
    var remoteModels by mutableStateOf<List<String>>(emptyList())

    fun key(): String = if (provider == 0) geminiKey else groqKey
    fun model(): String = if (provider == 0) geminiModel else groqModel

    fun selectProvider(p: Int) {
        provider = p
        remoteModels = emptyList()
        prefs.edit().putInt("provider", p).apply()
    }

    fun setKey(v: String) {
        if (provider == 0) {
            geminiKey = v
            prefs.edit().putString("gemini_key", v).apply()
        } else {
            groqKey = v
            prefs.edit().putString("groq_key", v).apply()
        }
    }

    fun setModel(v: String) {
        if (provider == 0) {
            geminiModel = v
            prefs.edit().putString("gemini_model", v).apply()
        } else {
            groqModel = v
            prefs.edit().putString("groq_model", v).apply()
        }
    }

    fun updateSystemPrompt(v: String) {
        systemPrompt = v
        prefs.edit().putString("system_prompt", v).apply()
    }

    fun newChat() {
        messages.clear()
    }

    fun fetchModels() {
        val p = provider
        val k = key()
        if (k.isBlank()) {
            info = "আগে API key দিন"
            return
        }
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { ChatApi.listModels(p, k) } }
            r.onSuccess { remoteModels = it }.onFailure { info = "লিস্ট আনা যায়নি: ${it.message}" }
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank() || sending) return
        if (key().isBlank()) {
            info = "আগে API key দিন"
            return
        }
        messages.add(ChatMsg("user", text.trim()))
        val history = messages.filter { it.role != "error" }
        val p = provider
        val k = key()
        val m = model()
        val sys = systemPrompt
        sending = true
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    if (p == 0) ChatApi.gemini(k, m, sys, history) else ChatApi.groq(k, m, sys, history)
                }
            }
            r.onSuccess { messages.add(ChatMsg("assistant", it)) }
                .onFailure { messages.add(ChatMsg("error", it.message ?: "অজানা ত্রুটি")) }
            sending = false
        }
    }

    // ---- accessibility ----
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
