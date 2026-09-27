package com.hemel.dailynotebook

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.SpannableStringBuilder
import android.text.Spannable
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import com.hemel.dailynotebook.data.ElementsJson
import com.hemel.dailynotebook.data.Notebook
import com.hemel.dailynotebook.data.PageElement
import com.hemel.dailynotebook.data.TYPE_IMAGE
import com.hemel.dailynotebook.data.TYPE_TABLE
import com.hemel.dailynotebook.data.TYPE_TEXT
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream

object PdfExporter {

    // Rebuilds a colored/highlighted Spannable from plain text + the compact JSON span
    // format written by EditorActivity (see spansToJson there).
    private fun spannedFromJson(text: String, json: String): CharSequence {
        if (json.isBlank()) return text
        val sb = SpannableStringBuilder(text)
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val s = o.optInt("s", -1)
                val e = o.optInt("e", -1)
                if (s < 0 || e <= s || e > sb.length) continue
                if (o.has("fg")) {
                    sb.setSpan(ForegroundColorSpan(Color.parseColor(o.getString("fg"))), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (o.has("bg")) {
                    sb.setSpan(BackgroundColorSpan(Color.parseColor(o.getString("bg"))), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        } catch (_: Exception) {
        }
        return sb
    }

    private const val PAGE_WIDTH_PX = 1080
    private const val HEADER_HEIGHT = 150
    private const val PAGE_WIDTH_PT = 595
    private const val PAGE_HEIGHT_PT = 842

    fun exportAndShare(context: Context, notebooks: List<Notebook>) {
        if (notebooks.isEmpty()) return
        val pdf = PdfDocument()

        for (nb in notebooks) {
            val elements = ElementsJson.deserialize(nb.elementsJson)
            val bitmap = renderNotebookBitmap(context, nb, elements)
            addBitmapAsPages(pdf, bitmap)
        }

        val dir = File(context.getExternalFilesDir(null), "exports")
        if (!dir.exists()) dir.mkdirs()
        val fileName = if (notebooks.size == 1) {
            sanitizeFileName(notebooks[0].title.ifBlank { "notebook" }) + ".pdf"
        } else {
            "Hemel_Export_${System.currentTimeMillis()}.pdf"
        }
        val file = File(dir, fileName)
        FileOutputStream(file).use { pdf.writeTo(it) }
        pdf.close()
        shareFile(context, file)
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9\\u0980-\\u09FF _-]"), "_").take(60)

    private fun renderNotebookBitmap(context: Context, nb: Notebook, elements: List<PageElement>): Bitmap {
        var maxBottom = 200
        for (e in elements) maxBottom = maxOf(maxBottom, e.y + e.h)

        val container = FrameLayout(context)
        container.setBackgroundColor(Color.WHITE)

        val title = TextView(context).apply {
            text = nb.title.ifBlank { "শিরোনামহীন" }
            textSize = 20f
            setTextColor(Color.parseColor("#0F172A"))
            setTypeface(typeface, Typeface.BOLD)
        }
        container.addView(title, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = 30; topMargin = 20 })

        val date = TextView(context).apply {
            text = nb.dateText
            textSize = 14f
            setTextColor(Color.parseColor("#3B82F6"))
        }
        container.addView(date, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = 30; topMargin = 70 })

        // Free-write text (written directly on the page, without any box)
        val freeWidthPx = PAGE_WIDTH_PX - 60
        var freeTextBottom = 0
        if (nb.freeText.isNotBlank()) {
            val freeTv = TextView(context).apply {
                text = spannedFromJson(nb.freeText, nb.freeTextSpans)
                textSize = 16f
                setTextColor(Color.parseColor("#0F172A"))
            }
            val wSpec = View.MeasureSpec.makeMeasureSpec(freeWidthPx, View.MeasureSpec.AT_MOST)
            val hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            freeTv.measure(wSpec, hSpec)
            freeTextBottom = freeTv.measuredHeight + 24
            container.addView(freeTv, FrameLayout.LayoutParams(freeWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = 30; topMargin = HEADER_HEIGHT
            })
        }
        maxBottom = maxOf(maxBottom, freeTextBottom)

        val pageHeightPx = maxBottom + HEADER_HEIGHT + 60

        for (e in elements) {
            val v: View = when (e.type) {
                TYPE_TEXT -> TextView(context).apply {
                    text = spannedFromJson(e.text, e.colorSpansJson)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, e.fontSize)
                    setTextColor(Color.parseColor("#0F172A"))
                    setPadding(10, 10, 10, 10)
                }
                TYPE_IMAGE -> ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    val f = File(File(context.filesDir, "images"), e.image)
                    if (f.exists()) setImageBitmap(android.graphics.BitmapFactory.decodeFile(f.absolutePath))
                }
                TYPE_TABLE -> buildStaticTable(context, e)
                else -> View(context)
            }
            container.addView(v, FrameLayout.LayoutParams(e.w, e.h).apply {
                leftMargin = e.x
                topMargin = e.y + HEADER_HEIGHT
            })
        }

        val widthSpec = View.MeasureSpec.makeMeasureSpec(PAGE_WIDTH_PX, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(pageHeightPx, View.MeasureSpec.EXACTLY)
        container.measure(widthSpec, heightSpec)
        container.layout(0, 0, PAGE_WIDTH_PX, pageHeightPx)

        val bitmap = Bitmap.createBitmap(PAGE_WIDTH_PX, pageHeightPx, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        container.draw(canvas)
        return bitmap
    }

    private fun buildStaticTable(context: Context, e: PageElement): View {
        val table = LinearLayout(context)
        table.orientation = LinearLayout.VERTICAL
        for (r in 0 until e.rows) {
            val rowLayout = LinearLayout(context)
            rowLayout.orientation = LinearLayout.HORIZONTAL
            for (c in 0 until e.cols) {
                val cellText = e.cells.getOrNull(r)?.getOrNull(c) ?: ""
                val tv = TextView(context).apply {
                    text = cellText
                    textSize = 12f
                    setPadding(8, 8, 8, 8)
                    setTextColor(Color.parseColor("#0F172A"))
                    setBackgroundColor(Color.parseColor("#F1F5F9"))
                    gravity = Gravity.CENTER
                }
                rowLayout.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            }
            table.addView(rowLayout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        return table
    }

    private fun addBitmapAsPages(pdf: PdfDocument, bitmap: Bitmap) {
        val scale = PAGE_WIDTH_PT / bitmap.width.toFloat()
        val scaledFullHeight = bitmap.height * scale
        if (scaledFullHeight <= PAGE_HEIGHT_PT) {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH_PT, PAGE_HEIGHT_PT, pdf.pages.size + 1).create())
            val m = Matrix()
            m.setScale(scale, scale)
            page.canvas.drawBitmap(bitmap, m, null)
            pdf.finishPage(page)
        } else {
            val sliceHeightPx = (PAGE_HEIGHT_PT / scale).toInt().coerceAtLeast(1)
            var yOff = 0
            while (yOff < bitmap.height) {
                val h = minOf(sliceHeightPx, bitmap.height - yOff)
                val slice = Bitmap.createBitmap(bitmap, 0, yOff, bitmap.width, h)
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH_PT, PAGE_HEIGHT_PT, pdf.pages.size + 1).create())
                val m = Matrix()
                m.setScale(scale, scale)
                page.canvas.drawBitmap(slice, m, null)
                pdf.finishPage(page)
                yOff += h
            }
        }
    }

    private fun shareFile(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "PDF শেয়ার / সংরক্ষণ করুন").apply {
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
