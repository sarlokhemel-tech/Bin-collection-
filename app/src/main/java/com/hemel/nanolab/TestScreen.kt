package com.hemel.nanolab

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

private val labelPalette = listOf(
    Color(0xFFBBDEFB), Color(0xFFC8E6C9), Color(0xFFFFE0B2), Color(0xFFE1BEE7),
    Color(0xFFCFD8DC), Color(0xFFFFCDD2), Color(0xFFFFF9C4), Color(0xFFD7CCC8)
)

fun labelColor(i: Int): Color = labelPalette[i % labelPalette.size]

@Composable
fun TestScreen(vm: AppViewModel, openDrawer: () -> Unit) {
    val list = vm.models.filter { it.type != ModelType.LLM }
    val sel = list.firstOrNull { it.id == vm.testModelId } ?: list.firstOrNull()
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar("মডেল টেস্ট", openDrawer)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { menu = true }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Ico(
                        if (sel != null && sel.type == ModelType.TEXT) R.drawable.ic_chat else R.drawable.ic_image,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(sel?.name ?: "কোনো মডেল নেই", style = MaterialTheme.typography.titleMedium)
                        if (sel != null) Text(sel.type.label, style = MaterialTheme.typography.labelMedium)
                    }
                    Ico(R.drawable.ic_dropdown)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    for (m in list) {
                        DropdownMenuItem(
                            text = { Text(m.name + " · " + m.type.label) },
                            onClick = { vm.testModelId = m.id; menu = false }
                        )
                    }
                }
            }
            if (sel == null) {
                Text("আগে ‘মডেল লাইব্রেরি’ থেকে একটা মডেল ইম্পোর্ট করুন।")
            } else {
                val st = vm.testState(sel.id)
                if (sel.type == ModelType.TEXT) TextTestBody(vm, sel, st) else ImageTestBody(vm, sel, st)
            }
        }
    }
}

@Composable
fun ConfidenceBars(title: String, items: List<Pair<String, Float>>) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            for (row in items) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(row.first, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(Reports.pct(row.second), style = MaterialTheme.typography.labelLarge)
                }
                LinearProgressIndicator(progress = { row.second.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
fun ErrorLine(st: TestState) {
    val e = st.error
    if (e != null) Text(e, color = MaterialTheme.colorScheme.error)
}

@Composable
fun ImageTestBody(vm: AppViewModel, sel: ModelMeta, st: TestState) {
    val ctx = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) vm.loadTestImage(st, u)
    }
    val bmp = st.bitmap
    val dres = st.detect
    val cres = st.classify
    val dets = remember(dres, st.conf, st.iou, st.maxDet) {
        if (dres == null) emptyList<Det>() else ImageEngine.postprocess(dres.raw, st.conf, st.iou, st.maxDet.toInt())
    }
    val shown = remember(bmp, dets, dres) {
        if (bmp != null && dres != null) ImageEngine.draw(bmp, dets) else bmp
    }

    Button(onClick = { pick.launch("image/*") }) {
        Ico(R.drawable.ic_image, size = 20.dp)
        Spacer(Modifier.size(8.dp))
        Text(if (bmp == null) "ছবি বাছুন" else "অন্য ছবি বাছুন")
    }
    if (shown != null) {
        Image(
            bitmap = shown.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)),
            contentScale = ContentScale.FillWidth
        )
        Text(st.imageName + "  (" + shown.width + "×" + shown.height + ")", style = MaterialTheme.typography.labelMedium)
    }

    if (sel.type == ModelType.DETECT) {
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("কনফিডেন্স থ্রেশহোল্ড: " + Math.round(st.conf * 100) + "%", style = MaterialTheme.typography.labelLarge)
                Slider(value = st.conf, onValueChange = { st.conf = it }, valueRange = 0.05f..0.95f)
                Text("NMS IoU: " + Math.round(st.iou * 100) + "%", style = MaterialTheme.typography.labelLarge)
                Slider(value = st.iou, onValueChange = { st.iou = it }, valueRange = 0.1f..0.9f)
                Text("সর্বোচ্চ ফলাফল: " + st.maxDet.toInt(), style = MaterialTheme.typography.labelLarge)
                Slider(value = st.maxDet, onValueChange = { st.maxDet = it }, valueRange = 1f..50f)
            }
        }
    } else {
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("Top-K: " + st.topK.toInt(), style = MaterialTheme.typography.labelLarge)
                Slider(value = st.topK, onValueChange = { st.topK = it }, valueRange = 1f..20f)
            }
        }
    }

    Button(enabled = bmp != null && !st.busy, onClick = { vm.runImageTest(sel, st) }) {
        Text(if (st.busy) "চলছে…" else "▶ রান")
    }
    if (st.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    ErrorLine(st)

    if (dres != null && sel.type == ModelType.DETECT) {
        val sections = remember(dres, st.conf, st.iou, st.maxDet, dets) {
            Reports.detect(dres, dets, st.conf, st.iou, sel.name)
        }
        if (dets.isNotEmpty()) {
            ConfidenceBars(
                "কনফিডেন্স",
                dets.take(12).mapIndexed { i, d -> ("#" + (i + 1) + " " + d.label) to d.score }
            )
        }
        OutlinedButton(onClick = { copyText(ctx, Reports.allText(sel.name, sections)) }) {
            Ico(R.drawable.ic_copy, size = 20.dp)
            Spacer(Modifier.size(8.dp))
            Text("সব ফলাফল কপি")
        }
        SectionList(sections)
    }
    if (cres != null && sel.type == ModelType.CLASSIFY) {
        val k = st.topK.toInt()
        val sections = remember(cres, k) { Reports.classify(cres, k, sel.name) }
        ConfidenceBars("Top-$k কনফিডেন্স", cres.scores.take(k).map { it.label to it.prob })
        OutlinedButton(onClick = { copyText(ctx, Reports.allText(sel.name, sections)) }) {
            Ico(R.drawable.ic_copy, size = 20.dp)
            Spacer(Modifier.size(8.dp))
            Text("সব ফলাফল কপি")
        }
        SectionList(sections)
    }
}

@Composable
fun TextTestBody(vm: AppViewModel, sel: ModelMeta, st: TestState) {
    val ctx = LocalContext.current
    AutoField(
        value = st.text,
        onValueChange = { st.text = it },
        label = "টেক্সট / বাক্য লিখুন",
        minLines = 3,
        maxLines = 12
    )
    Button(enabled = st.text.isNotBlank() && !st.busy, onClick = { vm.runTextTest(sel, st) }) {
        Text(if (st.busy) "চলছে…" else "▶ রান")
    }
    if (st.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    ErrorLine(st)

    val r = st.textRes
    if (r != null) {
        val sections = remember(r) { Reports.text(r, st.textUsed, sel.name) }
        ConfidenceBars("লেবেল কনফিডেন্স (গড়)", r.overall.map { it.label to it.prob })

        if (r.perToken && r.segments.isNotEmpty()) {
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ক্যারেক্টার-লেভেল লেবেল", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        r.labels.forEachIndexed { i, l ->
                            Text(
                                " " + l + " ",
                                color = Color.Black,
                                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(labelColor(i))
                            )
                        }
                    }
                    val annotated = buildAnnotatedString {
                        for (s in r.segments) {
                            withStyle(SpanStyle(background = labelColor(s.labelIndex), color = Color.Black)) {
                                append(s.text)
                            }
                        }
                    }
                    SelectionContainer { Text(annotated) }
                }
            }
        }
        OutlinedButton(onClick = { copyText(ctx, Reports.allText(sel.name, sections)) }) {
            Ico(R.drawable.ic_copy, size = 20.dp)
            Spacer(Modifier.size(8.dp))
            Text("সব ফলাফল কপি")
        }
        SectionList(sections)
    }
}
