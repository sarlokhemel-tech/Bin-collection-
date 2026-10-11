package com.hemel.nanolab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(vm: AppViewModel, openDrawer: () -> Unit) {
    var showKeys by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SavedPrompt?>(null) }
    var creating by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar("সেটিংস", openDrawer)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---- API keys
            Text("API Key", style = MaterialTheme.typography.titleMedium)
            AutoField(
                value = vm.geminiKey,
                onValueChange = { vm.geminiKey = it; vm.persistKeys() },
                label = "Gemini API key",
                maxLines = 1,
                password = !showKeys
            )
            AutoField(
                value = vm.groqKey,
                onValueChange = { vm.groqKey = it; vm.persistKeys() },
                label = "Groq API key",
                maxLines = 1,
                password = !showKeys
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = showKeys, onCheckedChange = { showKeys = it })
                Text("  key দেখান", style = MaterialTheme.typography.bodyMedium)
            }

            // ---- system prompts
            Spacer(Modifier.height(8.dp))
            Text("সিস্টেম প্রম্পট", style = MaterialTheme.typography.titleMedium)
            Text(
                "এখানে প্রম্পট সেভ করুন। যেটা ‘সক্রিয়’ থাকবে, নতুন চ্যাট সেটা দিয়ে শুরু হবে। চ্যাটের শুরুতে চাইলে অন্য প্রম্পটও বেছে নেওয়া যায়।",
                style = MaterialTheme.typography.bodySmall
            )
            PromptRow(
                title = "কোনো সিস্টেম প্রম্পট নয়",
                preview = "",
                active = vm.activePromptId.isEmpty(),
                onActivate = { vm.activatePrompt("") },
                onEdit = null,
                onDelete = null
            )
            for (p in vm.prompts) {
                PromptRow(
                    title = p.name,
                    preview = p.text,
                    active = vm.activePromptId == p.id,
                    onActivate = { vm.activatePrompt(p.id) },
                    onEdit = { editing = p },
                    onDelete = { vm.deletePrompt(p.id) }
                )
            }
            OutlinedButton(onClick = { creating = true }) {
                Ico(R.drawable.ic_add, size = 20.dp)
                Spacer(Modifier.size(8.dp))
                Text("নতুন প্রম্পট")
            }

            // ---- chat generation
            Spacer(Modifier.height(8.dp))
            Text("চ্যাট (Gemini / Groq)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Temperature: " + (Math.round(vm.temperature * 10) / 10.0),
                style = MaterialTheme.typography.labelLarge
            )
            Slider(
                value = vm.temperature,
                onValueChange = { vm.temperature = it },
                valueRange = 0f..2f,
                onValueChangeFinished = { vm.persistLlmSettings() }
            )
            Text(
                "কম = নির্দিষ্ট ও সংযত উত্তর, বেশি = সৃজনশীল ও বৈচিত্র্যময়।",
                style = MaterialTheme.typography.bodySmall
            )

            // ---- local llm
            Spacer(Modifier.height(8.dp))
            Text("লোকাল LLM", style = MaterialTheme.typography.titleMedium)
            Text(
                "সর্বোচ্চ টোকেন (প্রম্পট + উত্তর মিলিয়ে): " + vm.llmMaxTokens.toInt(),
                style = MaterialTheme.typography.labelLarge
            )
            Slider(
                value = vm.llmMaxTokens,
                onValueChange = { vm.llmMaxTokens = it },
                valueRange = 256f..4096f,
                steps = 14,
                onValueChangeFinished = { vm.persistLlmSettings() }
            )
            Text(
                "বেশি হলে লম্বা কথোপকথন মনে রাখে, তবে বেশি RAM লাগে ও ধীর হয়।",
                style = MaterialTheme.typography.bodySmall
            )

            // ---- data
            Spacer(Modifier.height(8.dp))
            Text("ডেটা", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { confirmClear = true }) {
                Ico(R.drawable.ic_delete, size = 20.dp)
                Spacer(Modifier.size(8.dp))
                Text("সব চ্যাট ইতিহাস মুছুন")
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("সব চ্যাট মুছবেন?") },
            text = { Text("চ্যাট ইতিহাস ও সংযুক্ত ছবি/ভিডিও সব মুছে যাবে। এটা আর ফেরানো যাবে না।") },
            confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearAllChats() }) { Text("মুছুন") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("বাতিল") } }
        )
    }
    if (creating) {
        PromptDialog(null, { n, t -> vm.upsertPrompt(null, n, t); creating = false }) { creating = false }
    }
    val e = editing
    if (e != null) {
        PromptDialog(e, { n, t -> vm.upsertPrompt(e.id, n, t); editing = null }) { editing = null }
    }
}

@Composable
fun PromptRow(
    title: String,
    preview: String,
    active: Boolean,
    onActivate: () -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(start = 14.dp, top = 8.dp, bottom = 4.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (active) Ico(R.drawable.ic_check, tint = MaterialTheme.colorScheme.primary, size = 20.dp)
                Spacer(Modifier.size(8.dp))
            }
            if (preview.isNotEmpty()) {
                Text(preview, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!active) TextButton(onClick = onActivate) { Text("সক্রিয় করুন") }
                if (onEdit != null) TextButton(onClick = onEdit) { Text("সম্পাদনা") }
                if (onDelete != null) TextButton(onClick = onDelete) { Text("মুছুন") }
            }
        }
    }
}

@Composable
fun PromptDialog(initial: SavedPrompt?, onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var text by remember { mutableStateOf(initial?.text ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "নতুন সিস্টেম প্রম্পট" else "প্রম্পট সম্পাদনা") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AutoField(name, { name = it }, "প্রম্পটের নাম", maxLines = 2)
                AutoField(text, { text = it }, "সিস্টেম প্রম্পট", minLines = 5, maxLines = 14)
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onSave(name, text) }) { Text("সেভ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("বাতিল") } }
    )
}
