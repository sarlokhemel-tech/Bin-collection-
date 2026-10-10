package com.hemel.nanolab

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import java.io.File

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.refreshModels()
        setContent { NanoApp(vm) }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshTrees()
    }
}

fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private val labelPalette = listOf(
    Color(0xFFBBDEFB), Color(0xFFC8E6C9), Color(0xFFFFE0B2), Color(0xFFE1BEE7),
    Color(0xFFCFD8DC), Color(0xFFFFCDD2), Color(0xFFFFF9C4), Color(0xFFD7CCC8)
)

private fun labelColor(i: Int): Color = labelPalette[i % labelPalette.size]

@Composable
fun NanoApp(vm: AppViewModel) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        var tab by remember { mutableIntStateOf(0) }
        val snack = remember { SnackbarHostState() }
        LaunchedEffect(vm.info) {
            val m = vm.info
            if (m != null) {
                snack.showSnackbar(m)
                vm.info = null
            }
        }
        val tabs = listOf("📦" to "মডেল", "🧪" to "টেস্ট", "💬" to "চ্যাট", "🔍" to "A11y")
        Scaffold(
            snackbarHost = { SnackbarHost(snack) },
            bottomBar = {
                NavigationBar {
                    tabs.forEachIndexed { i, t ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Text(t.first) },
                            label = { Text(t.second, maxLines = 1) }
                        )
                    }
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (tab) {
                    0 -> ModelsTab(vm)
                    1 -> TestTab(vm)
                    2 -> ChatTab(vm)
                    else -> A11yTab(vm)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Models

@Composable
fun ModelsTab(vm: AppViewModel) {
    var showImport by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("মডেল লাইব্রেরি", style = MaterialTheme.typography.titleLarge)
        Text(
            "Object detection / image classification / text classification — .tflite মডেল ইম্পোর্ট করে সেভ করুন।",
            style = MaterialTheme.typography.bodySmall
        )
        Button(onClick = { showImport = true }) { Text("➕ মডেল ইম্পোর্ট করুন") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.models, key = { it.id }) { m ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.name, style = MaterialTheme.typography.titleMedium)
                            Text(m.type.label, style = MaterialTheme.typography.bodySmall)
                            if (m.type != ModelType.TEXT) {
                                Text(
                                    if (m.labelsFile != null) "labels ✓" else "labels নেই (class N দেখাবে)",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        TextButton(onClick = { vm.deleteModel(m.id) }) { Text("মুছুন") }
                    }
                }
            }
        }
    }
    if (showImport) ImportDialog(vm) { showImport = false }
}

@Composable
fun ImportDialog(vm: AppViewModel, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(ModelType.DETECT) }
    var norm by remember { mutableStateOf("01") }
    var model by remember { mutableStateOf<Uri?>(null) }
    var aux1 by remember { mutableStateOf<Uri?>(null) }
    var aux2 by remember { mutableStateOf<Uri?>(null) }

    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) {
            model = u
            if (name.isBlank()) name = vm.store.displayName(u).substringBeforeLast('.')
        }
    }
    val pick1 = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) aux1 = u }
    val pick2 = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) aux2 = u }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("মডেল ইম্পোর্ট") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("মডেলের নাম") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Text("ধরন", style = MaterialTheme.typography.labelLarge)
                ModelType.values().forEach { t ->
                    FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.label) })
                }
                OutlinedButton(onClick = { pickModel.launch(arrayOf("*/*")) }) {
                    Text(if (model == null) "মডেল ফাইল বাছুন (.tflite / .zip)" else "✓ " + vm.store.displayName(model!!))
                }
                if (type == ModelType.TEXT) {
                    Text(
                        "ZIP এ tokenizer.json + config.json থাকলে নিচের দুটো লাগবে না।",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(onClick = { pick1.launch(arrayOf("*/*")) }) {
                        Text(if (aux1 == null) "tokenizer.json বাছুন" else "✓ " + vm.store.displayName(aux1!!))
                    }
                    OutlinedButton(onClick = { pick2.launch(arrayOf("*/*")) }) {
                        Text(if (aux2 == null) "config.json বাছুন" else "✓ " + vm.store.displayName(aux2!!))
                    }
                } else {
                    OutlinedButton(onClick = { pick1.launch(arrayOf("*/*")) }) {
                        Text(if (aux1 == null) "labels.txt বাছুন (ঐচ্ছিক)" else "✓ " + vm.store.displayName(aux1!!))
                    }
                    Text("ইনপুট নর্মালাইজেশন (float মডেলের জন্য)", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = norm == "01", onClick = { norm = "01" }, label = { Text("0–1") })
                        FilterChip(selected = norm == "pm1", onClick = { norm = "pm1" }, label = { Text("−1–1") })
                        FilterChip(selected = norm == "raw", onClick = { norm = "raw" }, label = { Text("0–255") })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = model != null && !vm.busy,
                onClick = {
                    vm.importModel(name, type, norm, model!!, aux1, aux2)
                    onClose()
                }
            ) { Text("ইম্পোর্ট") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("বাতিল") } }
    )
}

// ------------------------------------------------------------------ Test

@Composable
fun TestTab(vm: AppViewModel) {
    var menu by remember { mutableStateOf(false) }
    val sel = vm.selected
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("টেস্ট", style = MaterialTheme.typography.titleLarge)
        Box {
            OutlinedButton(onClick = { menu = true }) {
                Text(if (sel != null) sel.name + " · " + sel.type.label else "মডেল বাছুন")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                vm.models.forEach { m ->
                    DropdownMenuItem(
                        text = { Text(m.name + " · " + m.type.label) },
                        onClick = { vm.select(m.id); menu = false }
                    )
                }
            }
        }
        if (sel == null) {
            Text("আগে 'মডেল' ট্যাবে গিয়ে একটা মডেল ইম্পোর্ট করুন।")
        } else if (sel.type == ModelType.TEXT) {
            TextTest(vm, sel)
        } else {
            ImageTest(vm, sel)
        }
    }
}

@Composable
fun ImageTest(vm: AppViewModel, sel: ModelMeta) {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) vm.loadImage(u)
    }
    Button(onClick = { pick.launch("image/*") }) { Text("🖼️ ছবি বাছুন") }
    val bmp = vm.inputBitmap
    if (bmp != null) {
        val shown = vm.imageResult?.image ?: bmp
        Image(
            bitmap = shown.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.Fit
        )
    }
    Button(enabled = bmp != null && !vm.busy, onClick = { vm.runImage(sel) }) { Text("▶ রান") }
    if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    val r = vm.imageResult
    if (r != null) {
        Text("সময়: ${r.ms} ms", style = MaterialTheme.typography.labelMedium)
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                r.lines.forEach { Text(it) }
            }
        }
    }
}

@Composable
fun TextTest(vm: AppViewModel, sel: ModelMeta) {
    OutlinedTextField(
        value = vm.textInput,
        onValueChange = { vm.textInput = it },
        label = { Text("টেক্সট / বাক্য লিখুন") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 3
    )
    Button(enabled = vm.textInput.isNotBlank() && !vm.busy, onClick = { vm.runText(sel) }) { Text("▶ রান") }
    if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    val r = vm.textResult
    if (r != null) {
        Text("সময়: ${r.ms} ms", style = MaterialTheme.typography.labelMedium)
        r.overall.forEach { s ->
            Text(String.format("%s  %.1f%%", s.label, s.score * 100f))
            LinearProgressIndicator(progress = { s.score.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
        if (r.perToken && r.segments.isNotEmpty()) {
            Text("ক্যারেক্টার-লেভেল লেবেল", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                r.labels.forEachIndexed { i, l ->
                    Text(" $l ", color = Color.Black, modifier = Modifier.background(labelColor(i)))
                }
            }
            val annotated = buildAnnotatedString {
                r.segments.forEach { s ->
                    withStyle(SpanStyle(background = labelColor(s.labelIndex), color = Color.Black)) {
                        append(s.text)
                    }
                }
            }
            SelectionContainer { Text(annotated) }
            Text("সেগমেন্ট", style = MaterialTheme.typography.titleSmall)
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    r.segments.take(200).forEach { s ->
                        Text(String.format("[%s %.0f%%] %s", s.label, s.conf * 100f, s.text.replace("\n", "⏎")))
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Chat

@Composable
fun ChatTab(vm: AppViewModel) {
    var showSettings by remember { mutableStateOf(vm.key().isBlank()) }
    var input by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(vm.messages.size, vm.sending) {
        val count = vm.messages.size + (if (vm.sending) 1 else 0)
        if (count > 0) listState.scrollToItem(count - 1)
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = vm.provider == 0, onClick = { vm.setProvider(0) }, label = { Text("Gemini") })
            FilterChip(selected = vm.provider == 1, onClick = { vm.setProvider(1) }, label = { Text("Groq") })
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showSettings = !showSettings }) { Text("⚙") }
            TextButton(onClick = { vm.newChat() }) { Text("নতুন চ্যাট") }
        }
        if (showSettings) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = vm.key(), onValueChange = { vm.setKey(it) },
                        label = { Text(if (vm.provider == 0) "Gemini API key" else "Groq API key") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = vm.model(), onValueChange = { vm.setModel(it) },
                            label = { Text("মডেলের নাম") }, singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Box {
                            TextButton(onClick = { vm.fetchModels(); menu = true }) { Text("লিস্ট") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                if (vm.remoteModels.isEmpty()) {
                                    DropdownMenuItem(text = { Text("লোড হচ্ছে… (key ঠিক আছে?)") }, onClick = { menu = false })
                                }
                                vm.remoteModels.forEach { m ->
                                    DropdownMenuItem(text = { Text(m) }, onClick = { vm.setModel(m); menu = false })
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = vm.systemPrompt, onValueChange = { vm.updateSystemPrompt(it) },
                        label = { Text(if (vm.messages.isEmpty()) "সিস্টেম প্রম্পট" else "সিস্টেম প্রম্পট (নতুন চ্যাটে বদলানো যাবে)") },
                        enabled = vm.messages.isEmpty(),
                        minLines = 2, maxLines = 5,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(vm.messages.size) { idx ->
                val m = vm.messages[idx]
                val user = m.role == "user"
                val container = when (m.role) {
                    "user" -> MaterialTheme.colorScheme.primaryContainer
                    "error" -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
                Box(Modifier.fillMaxWidth(), contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart) {
                    Card(
                        modifier = Modifier.widthIn(max = 330.dp),
                        colors = CardDefaults.cardColors(containerColor = container)
                    ) {
                        SelectionContainer { Text(m.text, Modifier.padding(10.dp)) }
                    }
                }
            }
            if (vm.sending) {
                item { Text("⏳ উত্তর আসছে…", style = MaterialTheme.typography.bodySmall) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                placeholder = { Text("মেসেজ লিখুন…") },
                modifier = Modifier.weight(1f), maxLines = 4
            )
            Button(
                enabled = input.isNotBlank() && !vm.sending,
                onClick = { vm.sendMessage(input); input = "" }
            ) { Text("পাঠান") }
        }
    }
}

// ------------------------------------------------------------------ Accessibility

@Composable
fun A11yTab(vm: AppViewModel) {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Accessibility Tree বিশ্লেষণ", style = MaterialTheme.typography.titleLarge)
        Text(
            "‘রান’ চাপলে অ্যাপ ব্যাকগ্রাউন্ডে যাবে এবং স্ক্রিনে একটা ভাসমান বাটন আসবে। যেকোনো স্ক্রিনে 💾 সেভ চাপলে তখনকার " +
                "accessibility tree সেভ হবে। পরে এখানে এসে কপি করুন। বাটনটা টেনে সরানো যায়; ✕ চাপলে বন্ধ হবে; 📱 চাপলে এই অ্যাপে ফেরা যায়।",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            if (vm.a11yOn) "✅ Accessibility সার্ভিস চালু আছে" else "⚠️ Accessibility সার্ভিস বন্ধ — আগে সেটিংসে NanoLab চালু করুন",
            style = MaterialTheme.typography.titleSmall
        )
        if (!vm.a11yOn) {
            Text(
                "Android 13+ এ সেটিংসে সার্ভিসটা ধূসর/ব্লক থাকলে: Settings → Apps → NanoLab → ⋮ (উপরে ডানে) → " +
                    "‘Allow restricted settings’ চাপুন, তারপর আবার Accessibility সেটিংসে গিয়ে NanoLab চালু করুন।",
                style = MaterialTheme.typography.bodySmall
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val svc = NanoAccessibilityService.instance
                if (svc != null) {
                    svc.showOverlay()
                    ctx.findActivity()?.moveTaskToBack(true)
                } else {
                    vm.info = "আগে Accessibility সেটিংসে NanoLab চালু করুন"
                    ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }) { Text("▶ রান") }
            OutlinedButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("সেটিংস খুলুন") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("সেভ করা ডেটা (${vm.trees.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.refreshTrees() }) { Text("রিফ্রেশ") }
            if (vm.trees.isNotEmpty()) TextButton(onClick = { vm.clearTrees() }) { Text("সব মুছুন") }
        }
        vm.trees.forEach { f -> TreeItem(f, vm) }
    }
}

@Composable
fun TreeItem(f: File, vm: AppViewModel) {
    var open by remember(f.name) { mutableStateOf(false) }
    val clip = LocalClipboardManager.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(f.name, style = MaterialTheme.typography.titleSmall)
            Text("${f.length() / 1024} KB", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = {
                    clip.setText(AnnotatedString(f.readText()))
                    vm.info = "কপি হয়েছে ✓"
                }) { Text("📋 কপি") }
                OutlinedButton(onClick = { open = !open }) { Text(if (open) "লুকান" else "দেখুন") }
                TextButton(onClick = { vm.deleteTree(f) }) { Text("মুছুন") }
            }
            if (open) {
                val text = remember(f.name) { f.readText().take(30000) }
                Box(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer {
                        Text(text, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
