package com.hemel.nanolab

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ChatScreen(vm: AppViewModel, kind: String, openDrawer: () -> Unit) {
    val s = vm.activeSession(kind)
    if (s == null) {
        FullCenter { CircularProgressIndicator() }
    } else {
        ChatBody(vm, s, kind, openDrawer)
    }
}

@Composable
fun ChatBody(vm: AppViewModel, s: ChatSession, kind: String, openDrawer: () -> Unit) {
    val ctx = LocalContext.current
    var showModels by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var renameDlg by remember { mutableStateOf(false) }

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) vm.addAttachments(s, uris, "image")
    }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) vm.addAttachments(s, listOf(u), "video")
    }

    LaunchedEffect(vm.models, s.id) { vm.autoPickLocal(s) }

    Column(Modifier.fillMaxSize()) {
        // ---- top bar
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IcoBtn(R.drawable.ic_menu, "মেনু", openDrawer)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Row(
                    Modifier.clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { showModels = true }
                        .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val icon = when {
                        kind == "local" -> R.drawable.ic_chip
                        s.provider == 0 -> R.drawable.ic_sparkle
                        else -> R.drawable.ic_bolt
                    }
                    Ico(icon, tint = MaterialTheme.colorScheme.primary, size = 18.dp)
                    Text(
                        vm.modelLabel(s),
                        modifier = Modifier.padding(start = 8.dp).widthIn(max = 170.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Ico(R.drawable.ic_dropdown, size = 22.dp)
                }
            }
            IcoBtn(R.drawable.ic_edit, "নতুন চ্যাট", { vm.newChat(kind) })
            Box {
                IcoBtn(R.drawable.ic_more, "আরও", { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("পুরো চ্যাট কপি") },
                        leadingIcon = { Ico(R.drawable.ic_copy, size = 20.dp) },
                        onClick = { menu = false; copyText(ctx, vm.chatAsText(s)) }
                    )
                    DropdownMenuItem(
                        text = { Text("শেয়ার করুন") },
                        leadingIcon = { Ico(R.drawable.ic_send, size = 20.dp) },
                        onClick = { menu = false; shareText(ctx, vm.chatAsText(s)) }
                    )
                    DropdownMenuItem(
                        text = { Text("নাম বদলান") },
                        leadingIcon = { Ico(R.drawable.ic_edit, size = 20.dp) },
                        onClick = { menu = false; renameDlg = true }
                    )
                    DropdownMenuItem(
                        text = { Text("এই চ্যাট মুছুন") },
                        leadingIcon = { Ico(R.drawable.ic_delete, size = 20.dp) },
                        onClick = { menu = false; vm.deleteSession(s) }
                    )
                }
            }
        }

        // ---- body
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (s.messages.isEmpty()) {
                EmptyState(vm, s, kind)
            } else {
                MessageList(vm, s)
            }
        }

        // ---- input
        ChatInput(
            s = s,
            allowAttach = kind == "cloud",
            onSend = { vm.send(s) },
            onStop = { vm.stop(s) },
            onPickImages = { pickImages.launch("image/*") },
            onPickVideo = { pickVideo.launch("video/*") },
            onRemove = { vm.removePending(s, it) }
        )
    }

    if (showModels) {
        if (kind == "cloud") {
            CloudModelSheet(vm, s) { showModels = false }
        } else {
            LocalModelSheet(vm, s, onImport = { showModels = false; showImport = true }) { showModels = false }
        }
    }
    if (showImport) ImportDialog(vm, ModelType.LLM) { showImport = false }
    if (renameDlg) {
        RenameDialog(s.title, { vm.renameSession(s, it); renameDlg = false }) { renameDlg = false }
    }
}

@Composable
fun RenameDialog(initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var t by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("নাম বদলান") },
        text = { AutoField(t, { t = it }, "চ্যাটের নাম", maxLines = 3) },
        confirmButton = { TextButton(onClick = { onDone(t) }) { Text("ঠিক আছে") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("বাতিল") } }
    )
}

@Composable
fun EmptyState(vm: AppViewModel, s: ChatSession, kind: String) {
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Ico(R.drawable.ic_sparkle, tint = MaterialTheme.colorScheme.primary, size = 48.dp)
        Spacer(Modifier.height(12.dp))
        Text(
            if (kind == "cloud") "আজ কীভাবে সাহায্য করতে পারি?" else "লোকাল মডেলের সাথে কথা বলুন",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        if (kind == "local" && vm.models.none { it.type == ModelType.LLM }) {
            Spacer(Modifier.height(8.dp))
            Text(
                "এখনো কোনো LLM মডেল নেই। উপরের মডেল বাটনে চেপে .task / .bin মডেল ইম্পোর্ট করুন।",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(16.dp))
        Box {
            AssistChip(
                onClick = { menu = true },
                label = { Text("সিস্টেম প্রম্পট: " + s.promptName.ifBlank { "নেই" }) }
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("নেই") }, onClick = { vm.applyPrompt(s, null); menu = false })
                for (p in vm.prompts) {
                    DropdownMenuItem(text = { Text(p.name) }, onClick = { vm.applyPrompt(s, p); menu = false })
                }
                DropdownMenuItem(
                    text = { Text("সেটিংসে পরিচালনা করুন…") },
                    onClick = { menu = false; vm.screen = 5 }
                )
            }
        }
        if (kind == "cloud") {
            Spacer(Modifier.height(12.dp))
            for (q in listOf("একটা ছবি বিশ্লেষণ করে বুঝিয়ে দাও", "এই ভিডিওতে কী হচ্ছে?", "Kotlin এ একটা ছোট কোড লিখে দাও")) {
                SuggestionChip(onClick = { s.draft = q }, label = { Text(q) })
            }
        }
    }
}

@Composable
fun MessageList(vm: AppViewModel, s: ChatSession) {
    val listState = rememberLazyListState()
    val rev = s.messages.asReversed()
    LaunchedEffect(s.messages.size) { listState.scrollToItem(0) }
    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(rev.size, key = { rev[it].id }) { i ->
            MessageItem(vm, s, rev[i], i == 0)
        }
    }
}

@Composable
fun MessageItem(vm: AppViewModel, s: ChatSession, m: ChatMessage, isLast: Boolean) {
    val ctx = LocalContext.current
    var preview by remember { mutableStateOf<Attachment?>(null) }
    when (m.role) {
        "user" -> {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Column(Modifier.widthIn(max = 320.dp), horizontalAlignment = Alignment.End) {
                    if (m.attachments.isNotEmpty()) {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for (a in m.attachments) {
                                Thumb(a, 96.dp, Modifier.clickable { if (a.kind == "image") preview = a })
                            }
                        }
                    }
                    if (m.text.isNotEmpty()) {
                        Box(
                            Modifier.clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            SelectionContainer {
                                Text(m.text, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    }
                    IcoBtn(R.drawable.ic_copy, "কপি", { copyText(ctx, m.text) }, size = 18.dp)
                }
            }
        }
        "error" -> {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("⚠️ ত্রুটি", style = MaterialTheme.typography.titleSmall)
                    SelectionContainer { Text(m.text, style = MaterialTheme.typography.bodyMedium) }
                    Row {
                        IcoBtn(R.drawable.ic_copy, "কপি", { copyText(ctx, m.text) }, size = 20.dp)
                        if (isLast && !s.sending) {
                            IcoBtn(R.drawable.ic_refresh, "আবার চেষ্টা", { vm.regenerate(s) }, size = 20.dp)
                        }
                    }
                }
            }
        }
        else -> {
            val streaming = s.sending && isLast
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Ico(
                    R.drawable.ic_sparkle,
                    tint = MaterialTheme.colorScheme.primary,
                    size = 22.dp,
                    modifier = Modifier.padding(top = 3.dp)
                )
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    if (m.text.isEmpty() && streaming) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                "  " + s.status.ifBlank { "ভাবছে…" },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    } else {
                        Markdown(m.text)
                    }
                    if (!streaming) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IcoBtn(R.drawable.ic_copy, "কপি", { copyText(ctx, m.text) }, size = 20.dp)
                            if (isLast && !s.sending) {
                                IcoBtn(R.drawable.ic_refresh, "আবার তৈরি করো", { vm.regenerate(s) }, size = 20.dp)
                            }
                            if (m.model.isNotEmpty()) {
                                Text(
                                    m.model,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    val pv = preview
    if (pv != null) ImagePreview(pv) { preview = null }
}

@Composable
fun ChatInput(
    s: ChatSession,
    allowAttach: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPickImages: () -> Unit,
    onPickVideo: () -> Unit,
    onRemove: (Attachment) -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val canSend = s.draft.isNotBlank() || s.pending.isNotEmpty()
    val active = s.sending || canSend
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            if (s.pending.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (a in s.pending) {
                        Box {
                            Thumb(a, 64.dp)
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(2.dp).size(20.dp).clip(CircleShape)
                                    .background(Color(0xCC000000)).clickable { onRemove(a) },
                                contentAlignment = Alignment.Center
                            ) { Ico(R.drawable.ic_close, tint = Color.White, size = 14.dp) }
                        }
                    }
                }
            }
            if (s.status.isNotEmpty() && !s.sending) {
                Text(s.status, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 12.dp, bottom = 2.dp))
            }
            Row(verticalAlignment = Alignment.Bottom) {
                if (allowAttach) {
                    Box {
                        IcoBtn(R.drawable.ic_add, "সংযুক্ত করুন", { menu = true })
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("ছবি") },
                                leadingIcon = { Ico(R.drawable.ic_image, size = 20.dp) },
                                onClick = { menu = false; onPickImages() }
                            )
                            DropdownMenuItem(
                                text = { Text("ভিডিও") },
                                leadingIcon = { Ico(R.drawable.ic_video, size = 20.dp) },
                                onClick = { menu = false; onPickVideo() }
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.size(12.dp))
                }
                BasicTextField(
                    value = s.draft,
                    onValueChange = { s.draft = it },
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 12.dp),
                    textStyle = LocalTextStyle.current.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp,
                        lineHeight = 22.sp
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    minLines = 1,
                    maxLines = 7,
                    decorationBox = { inner ->
                        Box {
                            if (s.draft.isEmpty()) {
                                Text(
                                    "মেসেজ লিখুন…",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 16.sp
                                )
                            }
                            inner()
                        }
                    }
                )
                Box(
                    Modifier.padding(4.dp).size(44.dp).clip(CircleShape)
                        .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .clickable(enabled = active) { if (s.sending) onStop() else onSend() },
                    contentAlignment = Alignment.Center
                ) {
                    Ico(
                        if (s.sending) R.drawable.ic_stop else R.drawable.ic_send,
                        tint = if (active) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        size = 22.dp
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudModelSheet(vm: AppViewModel, s: ChatSession, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        vm.loadRemoteModels(0)
        vm.loadRemoteModels(1)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("মডেল বাছুন", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            AutoField(query, { query = it }, "খুঁজুন বা নিজে মডেলের নাম লিখুন", maxLines = 1)
            Spacer(Modifier.height(4.dp))
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                for (p in 0..1) {
                    val all = vm.remote[p] ?: (if (p == 0) DEFAULT_GEMINI else DEFAULT_GROQ)
                    val list = all.filter { query.isBlank() || it.contains(query.trim(), ignoreCase = true) }
                    item(key = "h$p") {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Ico(
                                if (p == 0) R.drawable.ic_sparkle else R.drawable.ic_bolt,
                                tint = MaterialTheme.colorScheme.primary,
                                size = 18.dp
                            )
                            Text(
                                if (p == 0) "  Gemini" else "  Groq",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f)
                            )
                            if (vm.remoteLoading[p] == true) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else if (vm.keyFor(p).isNotBlank()) {
                                TextButton(onClick = { vm.loadRemoteModels(p, true) }) { Text("রিফ্রেশ") }
                            }
                        }
                        if (vm.keyFor(p).isBlank()) {
                            Text(
                                "সেটিংসে API key দিলে পুরো লিস্ট আসবে",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    items(list, key = { "$p:$it" }) { name ->
                        val selected = s.provider == p && s.modelName == name
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .clickable { vm.selectCloudModel(s, p, name); onDismiss() }
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (selected) Ico(R.drawable.ic_check, tint = MaterialTheme.colorScheme.primary, size = 20.dp)
                        }
                    }
                }
                if (query.isNotBlank()) {
                    item(key = "custom") {
                        Column(Modifier.padding(top = 8.dp)) {
                            TextButton(onClick = { vm.selectCloudModel(s, 0, query); onDismiss() }) {
                                Text("‘" + query.trim() + "’ — Gemini তে ব্যবহার করুন")
                            }
                            TextButton(onClick = { vm.selectCloudModel(s, 1, query); onDismiss() }) {
                                Text("‘" + query.trim() + "’ — Groq এ ব্যবহার করুন")
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalModelSheet(vm: AppViewModel, s: ChatSession, onImport: () -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val llms = vm.models.filter { it.type == ModelType.LLM }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("লোকাল মডেল", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            if (llms.isEmpty()) {
                Text("কোনো LLM মডেল সেভ করা নেই।", style = MaterialTheme.typography.bodyMedium)
            }
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(llms, key = { it.id }) { m ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable { vm.selectLocalModel(s, m.id); onDismiss() }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(m.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "টেমপ্লেট: " + m.template,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        if (s.modelName == m.id) {
                            Ico(R.drawable.ic_check, tint = MaterialTheme.colorScheme.primary, size = 20.dp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onImport) { Text("➕ LLM মডেল ইম্পোর্ট") }
        }
    }
}
