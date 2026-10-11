package com.hemel.nanolab

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun copyText(ctx: Context, text: String) {
    try {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("NanoLab", text))
        Toast.makeText(ctx, "কপি হয়েছে ✓", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(ctx, "কপি করা যায়নি", Toast.LENGTH_SHORT).show()
    }
}

fun shareText(ctx: Context, text: String) {
    try {
        val i = Intent(Intent.ACTION_SEND)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_TEXT, text)
        val c = Intent.createChooser(i, null)
        c.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(c)
    } catch (e: Exception) {
        Toast.makeText(ctx, "শেয়ার করা যায়নি", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun ImagePreview(att: Attachment, onClose: () -> Unit) {
    val bmpState = produceState<Bitmap?>(null, att.path) {
        value = withContext(Dispatchers.IO) { Media.loadThumb(att, 1600) }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(Color.Black).clickable { onClose() },
            contentAlignment = Alignment.Center
        ) {
            val b = bmpState.value
            if (b != null) {
                Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
fun Ico(
    @DrawableRes res: Int,
    desc: String? = null,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp
) {
    Icon(painterResource(res), desc, modifier.size(size), tint)
}

@Composable
fun IcoBtn(
    @DrawableRes res: Int,
    desc: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp,
    enabled: Boolean = true
) {
    IconButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Icon(painterResource(res), desc, Modifier.size(size), tint)
    }
}

@Composable
fun AutoField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    maxLines: Int = 10,
    password: Boolean = false,
    enabled: Boolean = true,
    placeholder: String? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = { if (placeholder != null) Text(placeholder) },
        minLines = minLines,
        maxLines = maxLines,
        singleLine = maxLines == 1,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None
    )
}

@Composable
fun ScreenTopBar(title: String, onMenu: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IcoBtn(R.drawable.ic_menu, "মেনু", onMenu)
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f).padding(start = 4.dp)
        )
        actions()
    }
}

@Composable
fun SectionCard(title: String, body: String, mono: Boolean = true) {
    val ctx = LocalContext.current
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IcoBtn(R.drawable.ic_copy, "কপি", { copyText(ctx, body) }, size = 20.dp)
            }
            SelectionContainer {
                Text(
                    body,
                    modifier = Modifier.padding(end = 10.dp),
                    fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                    fontSize = if (mono) 12.sp else 14.sp,
                    lineHeight = if (mono) 17.sp else 20.sp
                )
            }
        }
    }
}

@Composable
fun SectionList(sections: List<Section>) {
    for (s in sections) SectionCard(s.title, s.body)
}

@Composable
fun Thumb(att: Attachment, size: Dp, modifier: Modifier = Modifier) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bmp: State<Bitmap?> = produceState<Bitmap?>(Media.thumbCache.get(att.path), att.path) {
        if (value == null) {
            val b = withContext(Dispatchers.IO) { Media.loadThumb(att, px) }
            if (b != null) Media.thumbCache.put(att.path, b)
            value = b
        }
    }
    Box(
        modifier.size(size).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp.value
        if (b != null) {
            Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        if (att.kind == "video") {
            Box(
                Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x99000000)),
                contentAlignment = Alignment.Center
            ) { Ico(R.drawable.ic_play, tint = Color.White, size = 18.dp) }
        }
    }
}

// ---------------------------------------------------------------- Markdown

private sealed class Block {
    class Code(val lang: String, val code: String) : Block()
    class Head(val level: Int, val text: String) : Block()
    class Item(val marker: String, val text: String, val indent: Int) : Block()
    class Para(val text: String) : Block()
}

private fun parseBlocks(src: String): List<Block> {
    val out = ArrayList<Block>()
    val lines = src.split("\n")
    var i = 0
    val para = StringBuilder()
    fun flush() {
        if (para.isNotEmpty()) {
            out.add(Block.Para(para.toString().trimEnd()))
            para.setLength(0)
        }
    }
    while (i < lines.size) {
        val line = lines[i]
        val tl = line.trimStart()
        if (tl.startsWith("```")) {
            flush()
            val lang = tl.removePrefix("```").trim()
            val code = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                if (code.isNotEmpty()) code.append('\n')
                code.append(lines[i])
                i++
            }
            out.add(Block.Code(lang, code.toString()))
            i++
            continue
        }
        if (tl.isEmpty()) {
            flush()
            i++
            continue
        }
        val hm = Regex("^(#{1,4})\\s+(.*)$").find(tl)
        if (hm != null) {
            flush()
            out.add(Block.Head(hm.groupValues[1].length, hm.groupValues[2]))
            i++
            continue
        }
        val bm = Regex("^([-*•])\\s+(.*)$").find(tl)
        if (bm != null) {
            flush()
            out.add(Block.Item("•", bm.groupValues[2], (line.length - tl.length) / 2))
            i++
            continue
        }
        val nm = Regex("^(\\d+[.)])\\s+(.*)$").find(tl)
        if (nm != null) {
            flush()
            out.add(Block.Item(nm.groupValues[1], nm.groupValues[2], (line.length - tl.length) / 2))
            i++
            continue
        }
        if (para.isNotEmpty()) para.append('\n')
        para.append(line)
        i++
    }
    flush()
    return out
}

private fun inlineMd(s: String, codeBg: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        if (s.startsWith("**", i)) {
            val e = s.indexOf("**", i + 2)
            if (e > i + 2) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.substring(i + 2, e)) }
                i = e + 2
                continue
            }
        }
        if (s[i] == '`') {
            val e = s.indexOf('`', i + 1)
            if (e > i + 1) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) {
                    append(s.substring(i + 1, e))
                }
                i = e + 1
                continue
            }
        }
        if (s[i] == '*' && i + 1 < s.length && s[i + 1] != ' ' && s[i + 1] != '*') {
            val e = s.indexOf('*', i + 1)
            if (e > i + 1) {
                withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                    append(s.substring(i + 1, e))
                }
                i = e + 1
                continue
            }
        }
        append(s[i])
        i++
    }
}

@Composable
fun Markdown(text: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val blocks = remember(text) { parseBlocks(text) }
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    SelectionContainer {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (b in blocks) {
                when (b) {
                    is Block.Code -> {
                        Surface(shape = RoundedCornerShape(12.dp), color = codeBg) {
                            Column(Modifier.fillMaxWidth()) {
                                Row(
                                    Modifier.padding(start = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        b.lang.ifBlank { "code" },
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IcoBtn(R.drawable.ic_copy, "কপি", { copyText(ctx, b.code) }, size = 18.dp)
                                }
                                Text(
                                    b.code,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    modifier = Modifier.horizontalScroll(rememberScrollState())
                                        .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                                )
                            }
                        }
                    }
                    is Block.Head -> Text(
                        inlineMd(b.text, codeBg),
                        style = when (b.level) {
                            1 -> MaterialTheme.typography.titleLarge
                            2 -> MaterialTheme.typography.titleMedium
                            else -> MaterialTheme.typography.titleSmall
                        },
                        fontWeight = FontWeight.Bold
                    )
                    is Block.Item -> Row(Modifier.padding(start = (b.indent * 12).dp)) {
                        Text(b.marker + " ", fontWeight = FontWeight.Bold)
                        Text(inlineMd(b.text, codeBg), modifier = Modifier.weight(1f))
                    }
                    is Block.Para -> Text(inlineMd(b.text, codeBg), lineHeight = 23.sp)
                }
            }
        }
    }
}

@Composable
fun FullCenter(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = content
    )
}
