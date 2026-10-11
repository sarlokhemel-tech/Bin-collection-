package com.hemel.nanolab

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ModelsScreen(vm: AppViewModel, openDrawer: () -> Unit) {
    var showImport by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar("মডেল লাইব্রেরি", openDrawer)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Object detection, image classification, text classification (.tflite) এবং লোকাল LLM (.task / .bin) মডেল এখানে ইম্পোর্ট করে সেভ করুন।",
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = { showImport = true }) {
                Ico(R.drawable.ic_download, size = 20.dp)
                Spacer(Modifier.size(8.dp))
                Text("মডেল ইম্পোর্ট করুন")
            }
            if (vm.importing) {
                Text("ইম্পোর্ট হচ্ছে… (বড় ফাইলে কয়েক মিনিট লাগতে পারে)", style = MaterialTheme.typography.labelMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (vm.models.isEmpty()) Text("কোনো মডেল নেই।")
            for (m in vm.models) key(m.id) { ModelCard(vm, m) }
        }
    }
    if (showImport) ImportDialog(vm, ModelType.DETECT) { showImport = false }
}

@Composable
fun ModelCard(vm: AppViewModel, m: ModelMeta) {
    val sizeMb = remember(m.id) { vm.store.sizeOf(m.id) / (1024.0 * 1024.0) }
    var confirm by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Ico(
                when (m.type) {
                    ModelType.LLM -> R.drawable.ic_chip
                    ModelType.TEXT -> R.drawable.ic_chat
                    else -> R.drawable.ic_image
                },
                tint = MaterialTheme.colorScheme.primary
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(m.name, style = MaterialTheme.typography.titleMedium)
                val extra = when (m.type) {
                    ModelType.LLM -> "টেমপ্লেট: " + m.template
                    ModelType.TEXT -> "tokenizer ✓" + (if (m.configFile != null) "  config ✓" else "")
                    else -> if (m.labelsFile != null) "labels ✓" else "labels নেই (class N দেখাবে)"
                }
                Text(
                    m.type.label + " · " + String.format("%.1f MB", sizeMb) + " · " + extra,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            IcoBtn(R.drawable.ic_delete, "মুছুন", { confirm = true })
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("মডেল মুছবেন?") },
            text = { Text(m.name) },
            confirmButton = { TextButton(onClick = { confirm = false; vm.deleteModel(m.id) }) { Text("মুছুন") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("বাতিল") } }
        )
    }
}

@Composable
fun ImportDialog(vm: AppViewModel, initialType: ModelType, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(initialType) }
    var norm by remember { mutableStateOf("01") }
    var template by remember { mutableStateOf("gemma") }
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
                AutoField(name, { name = it }, "মডেলের নাম", maxLines = 2)
                Text("ধরন", style = MaterialTheme.typography.labelLarge)
                for (t in ModelType.values()) {
                    FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.label) })
                }
                OutlinedButton(onClick = { pickModel.launch(arrayOf("*/*")) }) {
                    val mu = model
                    Text(
                        if (mu == null) {
                            if (type == ModelType.LLM) "মডেল ফাইল বাছুন (.task / .bin)" else "মডেল ফাইল বাছুন (.tflite / .zip)"
                        } else "✓ " + vm.store.displayName(mu)
                    )
                }
                if (type == ModelType.LLM) {
                    Text("প্রম্পট টেমপ্লেট (মডেলের ধরন অনুযায়ী)", style = MaterialTheme.typography.labelLarge)
                    for (pair in listOf("gemma" to "Gemma", "chatml" to "ChatML (Qwen ইত্যাদি)", "llama3" to "Llama 3", "plain" to "সাধারণ (User/Assistant)")) {
                        FilterChip(selected = template == pair.first, onClick = { template = pair.first }, label = { Text(pair.second) })
                    }
                    Text(
                        "MediaPipe এর .task / .bin ফরম্যাটের মডেল (যেমন Gemma ছোট ভার্সন) চলবে। বড় মডেলে মেমোরি লাগে, ফোনের RAM এর কথা মাথায় রাখুন।",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else if (type == ModelType.TEXT) {
                    Text("ZIP এ tokenizer.json + config.json থাকলে নিচের দুটো লাগবে না।", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { pick1.launch(arrayOf("*/*")) }) {
                        val u = aux1
                        Text(if (u == null) "tokenizer.json বাছুন" else "✓ " + vm.store.displayName(u))
                    }
                    OutlinedButton(onClick = { pick2.launch(arrayOf("*/*")) }) {
                        val u = aux2
                        Text(if (u == null) "config.json বাছুন" else "✓ " + vm.store.displayName(u))
                    }
                } else {
                    OutlinedButton(onClick = { pick1.launch(arrayOf("*/*")) }) {
                        val u = aux1
                        Text(if (u == null) "labels.txt বাছুন (ঐচ্ছিক)" else "✓ " + vm.store.displayName(u))
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
                enabled = model != null && !vm.importing,
                onClick = {
                    val mu = model
                    if (mu != null) vm.importModel(name, type, norm, mu, aux1, aux2, template)
                    onClose()
                }
            ) { Text("ইম্পোর্ট") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("বাতিল") } }
    )
}
