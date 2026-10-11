package com.hemel.nanolab

import android.content.Context
import android.content.ContextWrapper
import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
fun A11yScreen(vm: AppViewModel, openDrawer: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar("Accessibility Tree", openDrawer) {
            IcoBtn(R.drawable.ic_refresh, "রিফ্রেশ", { vm.refreshTrees() })
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
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
                }) {
                    Ico(R.drawable.ic_play, size = 20.dp)
                    Spacer(Modifier.size(6.dp))
                    Text("রান")
                }
                OutlinedButton(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("সেটিংস খুলুন") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "সেভ করা ডেটা (" + vm.trees.size + ")",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (vm.trees.isNotEmpty()) TextButton(onClick = { vm.clearTrees() }) { Text("সব মুছুন") }
            }
            for (f in vm.trees) TreeItem(f, vm)
        }
    }
}

fun compactTree(text: String): String {
    return text.lines()
        .filter { it.startsWith("#") || it.contains("text=\"") || it.contains("desc=\"") || it.contains(" id=") }
        .joinToString("\n") { it.trimEnd() }
}

@Composable
fun TreeItem(f: File, vm: AppViewModel) {
    val ctx = LocalContext.current
    var open by remember(f.name) { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(f.name, style = MaterialTheme.typography.titleSmall)
            Text((f.length() / 1024).toString() + " KB", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { copyText(ctx, f.readText()) }) {
                    Ico(R.drawable.ic_copy, size = 18.dp)
                    Spacer(Modifier.size(6.dp))
                    Text("কপি")
                }
                OutlinedButton(onClick = { copyText(ctx, compactTree(f.readText())) }) { Text("সংক্ষিপ্ত কপি") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { open = !open }) { Text(if (open) "লুকান" else "দেখুন") }
                TextButton(onClick = { vm.deleteTree(f) }) { Text("মুছুন") }
            }
            if (open) {
                val text = remember(f.name) { f.readText().take(30000) }
                Box(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer {
                        Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
                    }
                }
            }
        }
    }
}
