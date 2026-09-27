package com.hemel.dailynotebook

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hemel.dailynotebook.data.AppDatabase
import com.hemel.dailynotebook.data.ElementsJson
import com.hemel.dailynotebook.data.Notebook
import com.hemel.dailynotebook.data.PageElement
import com.hemel.dailynotebook.data.TYPE_IMAGE
import com.hemel.dailynotebook.data.TYPE_TABLE
import com.hemel.dailynotebook.data.TYPE_TEXT
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID

class EditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NOTEBOOK_ID = "notebook_id"
        private const val DEFAULT_TEXT_W = 320
        private const val DEFAULT_TEXT_H = 150
        private const val DEFAULT_IMAGE_SIZE = 320
        private const val MIN_ELEMENT_W = 70
        private const val MIN_ELEMENT_H = 60
        private const val AUTOSAVE_INTERVAL_MS = 6000L

        private val TEXT_COLOR_PALETTE = listOf(
            "#0F172A", "#DC2626", "#EA580C", "#CA8A04", "#16A34A",
            "#0891B2", "#2563EB", "#7C3AED", "#DB2777", "#FFFFFF"
        )
        // last entry (transparent) clears the highlight
        private val HIGHLIGHT_PALETTE = listOf(
            "#FDE68A", "#FCA5A5", "#BBF7D0", "#BFDBFE", "#DDD6FE",
            "#FBCFE8", "#FED7AA", "#E2E8F0", "#00000000"
        )
    }

    private lateinit var db: AppDatabase
    private var notebookId: Long = -1L

    private lateinit var canvas: FrameLayout
    private lateinit var titleInput: EditText
    private lateinit var dateText: TextView
    private lateinit var btnEditDate: ImageButton
    private lateinit var freeWriteText: EditText
    private lateinit var draftStatusText: TextView
    private lateinit var toolbar: LinearLayout

    private var selectedView: View? = null
    private var addOffsetStep = 0
    private var currentDateFormatted: String = ""
    private val dateFmt = SimpleDateFormat("dd-MM-yyyy", Locale.US)

    // Stays true (temporary/auto-saved draft) until the user explicitly taps Save/Back,
    // or until an already-finalized note is loaded (then it starts false).
    private var isDraftFlag: Boolean = true
    private var autosaveJob: Job? = null
    // set right before the whole notebook is deleted, so the autosave loop
    // (onPause etc.) can't silently re-create it afterwards.
    private var isDeleted: Boolean = false

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val fileName = copyImageToInternal(uri)
            if (fileName != null) addImageElement(fileName)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)
        db = AppDatabase.getInstance(this)

        canvas = findViewById(R.id.canvas)
        titleInput = findViewById(R.id.titleInput)
        dateText = findViewById(R.id.dateText)
        btnEditDate = findViewById(R.id.btnEditDate)
        freeWriteText = findViewById(R.id.freeWriteText)
        draftStatusText = findViewById(R.id.draftStatusText)
        toolbar = findViewById(R.id.toolbar)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finalizeAndSave(andFinish = true) }
        findViewById<ImageButton>(R.id.btnSave).setOnClickListener { finalizeAndSave(andFinish = false) }
        findViewById<ImageButton>(R.id.btnDeleteNotebook).setOnClickListener { confirmDeleteNotebook() }

        canvas.setOnClickListener { clearSelection() }
        freeWriteText.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) clearSelection() }

        currentDateFormatted = dateFmt.format(Calendar.getInstance().time)
        dateText.text = currentDateFormatted
        dateText.setOnClickListener { showDatePicker() }
        btnEditDate.setOnClickListener { showDatePicker() }

        buildToolbar()

        notebookId = intent.getLongExtra(EXTRA_NOTEBOOK_ID, -1L)
        if (notebookId > 0) loadNotebook(notebookId) else updateDraftStatusVisibility()
    }

    override fun onResume() {
        super.onResume()
        autosaveJob = lifecycleScope.launch {
            while (true) {
                delay(AUTOSAVE_INTERVAL_MS)
                autosave()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        autosaveJob?.cancel()
        autosave()
    }

    override fun onBackPressed() {
        finalizeAndSave(andFinish = true)
    }

    // ---------- Toolbar ----------

    private fun buildToolbar() {
        addToolbarButton(R.drawable.ic_text, "লেখা যোগ করুন (বক্স)") { addTextElement() }
        addToolbarButton(R.drawable.ic_image, "ছবি যোগ করুন") { pickImage.launch("image/*") }
        addToolbarButton(R.drawable.ic_table, "টেবিল/কলাম যোগ করুন") { showTableDialog() }
        addToolbarButton(R.drawable.ic_font_up, "ফন্ট বড়") { changeFontSize(2f) }
        addToolbarButton(R.drawable.ic_font_down, "ফন্ট ছোট") { changeFontSize(-2f) }
        addToolbarButton(R.drawable.ic_color_dot, "লেখার রং", tint = "#DC2626") { showColorPalette(isHighlight = false) }
        addToolbarButton(R.drawable.ic_color_dot, "হাইলাইট করুন", tint = "#CA8A04") { showColorPalette(isHighlight = true) }
        addToolbarButton(R.drawable.ic_delete, "মুছে ফেলুন") { deleteSelected() }
        addToolbarButton(R.drawable.ic_copy, "সব লেখা কপি করুন") { copyAllText() }
        addToolbarButton(R.drawable.ic_pdf, "PDF এক্সপোর্ট") { exportThisNotebook() }
    }

    private fun addToolbarButton(iconRes: Int, desc: String, tint: String = "#1E3A8A", onClick: () -> Unit): ImageButton {
        val btn = ImageButton(this)
        btn.setImageResource(iconRes)
        btn.contentDescription = desc
        btn.setBackgroundResource(R.drawable.bg_toolbar_btn)
        btn.setColorFilter(Color.parseColor(tint))
        val size = dp(44)
        val lp = LinearLayout.LayoutParams(size, size)
        lp.marginEnd = dp(6)
        btn.layoutParams = lp
        btn.setPadding(dp(8), dp(8), dp(8), dp(8))
        btn.setOnClickListener { onClick() }
        toolbar.addView(btn)
        return btn
    }

    // ---------- Date ----------

    private fun showDatePicker() {
        val cal = Calendar.getInstance()
        try {
            dateFmt.parse(currentDateFormatted)?.let { cal.time = it }
        } catch (_: Exception) {}
        DatePickerDialog(this, { _, y, m, d ->
            val c = Calendar.getInstance()
            c.set(y, m, d)
            currentDateFormatted = dateFmt.format(c.time)
            dateText.text = currentDateFormatted
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    // ---------- Element creation ----------

    private fun nextOffset(): Int {
        val o = (addOffsetStep % 8) * dp(24)
        addOffsetStep++
        return o
    }

    private fun addTextElement() {
        val e = PageElement(
            type = TYPE_TEXT,
            x = dp(20) + nextOffset(), y = dp(20) + nextOffset(),
            w = dp(DEFAULT_TEXT_W), h = dp(DEFAULT_TEXT_H),
            fontSize = 16f, text = ""
        )
        createElementView(e)
    }

    private fun addImageElement(fileName: String) {
        val e = PageElement(
            type = TYPE_IMAGE,
            x = dp(20) + nextOffset(), y = dp(20) + nextOffset(),
            w = dp(DEFAULT_IMAGE_SIZE), h = dp(DEFAULT_IMAGE_SIZE),
            image = fileName
        )
        createElementView(e)
    }

    private fun showTableDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_table_size, null)
        val rowsInput = v.findViewById<EditText>(R.id.inputRows)
        val colsInput = v.findViewById<EditText>(R.id.inputCols)
        AlertDialog.Builder(this)
            .setView(v)
            .setPositiveButton("তৈরি করুন") { _, _ ->
                val rows = rowsInput.text.toString().toIntOrNull()?.coerceIn(1, 20) ?: 3
                val cols = colsInput.text.toString().toIntOrNull()?.coerceIn(1, 10) ?: 3
                addTableElement(rows, cols)
            }
            .setNegativeButton("বাতিল", null)
            .show()
    }

    private fun addTableElement(rows: Int, cols: Int) {
        val cellW = dp(90)
        val cellH = dp(48)
        val cells = MutableList(rows) { MutableList(cols) { "" } }
        val e = PageElement(
            type = TYPE_TABLE,
            x = dp(20) + nextOffset(), y = dp(20) + nextOffset(),
            w = cellW * cols, h = cellH * rows,
            rows = rows, cols = cols, cells = cells
        )
        createElementView(e)
    }

    // ---------- Interactive element view ----------

    private fun createElementView(e: PageElement) {
        val container = FrameLayout(this)
        container.tag = e
        container.setBackgroundResource(R.drawable.bg_element)
        container.setPadding(dp(4), dp(4), dp(4), dp(4))

        val content: View = when (e.type) {
            TYPE_TEXT -> buildTextContent(e)
            TYPE_IMAGE -> buildImageContent(e)
            TYPE_TABLE -> buildTableContent(e)
            else -> View(this)
        }
        container.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // move handle (top-left)
        val handle = View(this)
        handle.setBackgroundResource(R.drawable.bg_handle)
        val handleSize = dp(20)
        val handleLp = FrameLayout.LayoutParams(handleSize, handleSize)
        handleLp.gravity = Gravity.TOP or Gravity.START
        handleLp.leftMargin = -dp(6)
        handleLp.topMargin = -dp(6)
        container.addView(handle, handleLp)
        attachDrag(handle, container, e)

        // resize handle (bottom-right) — lets boxes, columns/tables and images be made bigger or smaller
        val resizeHandle = View(this)
        resizeHandle.setBackgroundResource(R.drawable.bg_resize_handle)
        val resizeSize = dp(22)
        val resizeLp = FrameLayout.LayoutParams(resizeSize, resizeSize)
        resizeLp.gravity = Gravity.BOTTOM or Gravity.END
        resizeLp.rightMargin = -dp(6)
        resizeLp.bottomMargin = -dp(6)
        container.addView(resizeHandle, resizeLp)
        attachResize(resizeHandle, container, e)

        // delete badge
        val deleteBadge = ImageView(this)
        deleteBadge.setImageResource(R.drawable.ic_delete)
        deleteBadge.setBackgroundResource(R.drawable.bg_delete_badge)
        deleteBadge.setColorFilter(Color.WHITE)
        deleteBadge.setPadding(dp(3), dp(3), dp(3), dp(3))
        val delSize = dp(22)
        val delLp = FrameLayout.LayoutParams(delSize, delSize)
        delLp.gravity = Gravity.TOP or Gravity.END
        delLp.rightMargin = -dp(6)
        delLp.topMargin = -dp(6)
        deleteBadge.layoutParams = delLp
        deleteBadge.visibility = View.GONE
        deleteBadge.setOnClickListener {
            canvas.removeView(container)
            if (selectedView == container) selectedView = null
        }
        container.addView(deleteBadge)

        content.setOnClickListener { selectElement(container) }
        container.tag = e
        container.setTag(R.id.tag_delete_badge, deleteBadge)

        val lp = FrameLayout.LayoutParams(e.w, e.h)
        lp.leftMargin = e.x
        lp.topMargin = e.y
        canvas.addView(container, lp)
        selectElement(container)
    }

    private fun buildTextContent(e: PageElement): EditText {
        val et = EditText(this)
        et.setText(e.text)
        applySpansFromJson(et, e.colorSpansJson)
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, e.fontSize)
        et.setTextColor(Color.parseColor("#0F172A"))
        et.gravity = Gravity.TOP or Gravity.START
        et.background = null
        et.setPadding(dp(6), dp(6), dp(6), dp(6))
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                e.text = s?.toString() ?: ""
            }
        })
        return et
    }

    private fun buildImageContent(e: PageElement): ImageView {
        val iv = ImageView(this)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        val f = File(File(filesDir, "images"), e.image)
        if (f.exists()) {
            iv.setImageBitmap(BitmapFactory.decodeFile(f.absolutePath))
        }
        return iv
    }

    private fun buildTableContent(e: PageElement): LinearLayout {
        val table = LinearLayout(this)
        table.orientation = LinearLayout.VERTICAL
        for (r in 0 until e.rows) {
            val rowLayout = LinearLayout(this)
            rowLayout.orientation = LinearLayout.HORIZONTAL
            for (c in 0 until e.cols) {
                val cellEt = EditText(this)
                cellEt.setText(e.cells.getOrNull(r)?.getOrNull(c) ?: "")
                cellEt.textSize = 12f
                cellEt.setPadding(dp(4), dp(4), dp(4), dp(4))
                cellEt.setBackgroundResource(android.R.color.transparent)
                cellEt.gravity = Gravity.CENTER
                val rIdx = r; val cIdx = c
                cellEt.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        while (e.cells.size <= rIdx) e.cells.add(mutableListOf())
                        val row = e.cells[rIdx]
                        while (row.size <= cIdx) row.add("")
                        row[cIdx] = s?.toString() ?: ""
                    }
                })
                val cellLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                rowLayout.addView(cellEt, cellLp)
                if (c < e.cols - 1) {
                    val divider = View(this)
                    divider.setBackgroundColor(Color.parseColor("#CBD5E1"))
                    rowLayout.addView(divider, LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT))
                }
            }
            table.addView(rowLayout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            if (r < e.rows - 1) {
                val hDivider = View(this)
                hDivider.setBackgroundColor(Color.parseColor("#CBD5E1"))
                table.addView(hDivider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            }
        }
        return table
    }

    // ---------- Selection / drag / resize ----------

    private fun selectElement(container: View) {
        clearSelection()
        selectedView = container
        container.setBackgroundResource(R.drawable.bg_element_selected)
        (container.getTag(R.id.tag_delete_badge) as? View)?.visibility = View.VISIBLE
    }

    private fun clearSelection() {
        val prev = selectedView ?: return
        prev.setBackgroundResource(R.drawable.bg_element)
        (prev.getTag(R.id.tag_delete_badge) as? View)?.visibility = View.GONE
        selectedView = null
    }

    private fun attachDrag(handle: View, container: View, element: PageElement) {
        var startRawX = 0f
        var startRawY = 0f
        var startLeft = 0
        var startTop = 0
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    selectElement(container)
                    startRawX = event.rawX
                    startRawY = event.rawY
                    val lp = container.layoutParams as FrameLayout.LayoutParams
                    startLeft = lp.leftMargin
                    startTop = lp.topMargin
                    (container.parent)?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startRawX).toInt()
                    val dy = (event.rawY - startRawY).toInt()
                    val lp = container.layoutParams as FrameLayout.LayoutParams
                    lp.leftMargin = (startLeft + dx).coerceAtLeast(0)
                    lp.topMargin = (startTop + dy).coerceAtLeast(0)
                    container.layoutParams = lp
                    element.x = lp.leftMargin
                    element.y = lp.topMargin
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    (container.parent)?.requestDisallowInterceptTouchEvent(false)
                    true
                }
                else -> false
            }
        }
    }

    private fun attachResize(handle: View, container: View, element: PageElement) {
        var startRawX = 0f
        var startRawY = 0f
        var startW = 0
        var startH = 0
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    selectElement(container)
                    startRawX = event.rawX
                    startRawY = event.rawY
                    val lp = container.layoutParams as FrameLayout.LayoutParams
                    startW = lp.width
                    startH = lp.height
                    (container.parent)?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startRawX).toInt()
                    val dy = (event.rawY - startRawY).toInt()
                    val lp = container.layoutParams as FrameLayout.LayoutParams
                    lp.width = (startW + dx).coerceAtLeast(dp(MIN_ELEMENT_W))
                    lp.height = (startH + dy).coerceAtLeast(dp(MIN_ELEMENT_H))
                    container.layoutParams = lp
                    element.w = lp.width
                    element.h = lp.height
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    (container.parent)?.requestDisallowInterceptTouchEvent(false)
                    true
                }
                else -> false
            }
        }
    }

    // ---------- Toolbar actions ----------

    private fun changeFontSize(delta: Float) {
        val sel = selectedView ?: run {
            Toast.makeText(this, "প্রথমে একটি লেখার বক্স নির্বাচন করুন", Toast.LENGTH_SHORT).show()
            return
        }
        val e = sel.tag as? PageElement ?: return
        if (e.type != TYPE_TEXT) return
        e.fontSize = (e.fontSize + delta).coerceIn(10f, 40f)
        val content = (sel as FrameLayout).getChildAt(0) as? EditText
        content?.setTextSize(TypedValue.COMPLEX_UNIT_SP, e.fontSize)
    }

    // returns the EditText that color/highlight actions should apply to: the selected
    // text box if one is selected, otherwise the free-write page itself.
    private fun currentColorTarget(): EditText {
        val sel = selectedView
        val e = sel?.tag as? PageElement
        if (sel != null && e?.type == TYPE_TEXT) {
            return (sel as FrameLayout).getChildAt(0) as EditText
        }
        return freeWriteText
    }

    private fun showColorPalette(isHighlight: Boolean) {
        val target = currentColorTarget()
        val colors = if (isHighlight) HIGHLIGHT_PALETTE else TEXT_COLOR_PALETTE

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val pad = dp(16)
        row.setPadding(pad, pad, pad, pad)
        val scroller = HorizontalScrollView(this)
        scroller.addView(row)

        var dialog: AlertDialog? = null
        for (hex in colors) {
            val swatch = View(this)
            val size = dp(40)
            val lp = LinearLayout.LayoutParams(size, size)
            lp.marginEnd = dp(10)
            swatch.layoutParams = lp
            val gd = GradientDrawable()
            gd.shape = GradientDrawable.OVAL
            gd.setColor(if (hex == "#00000000") Color.TRANSPARENT else Color.parseColor(hex))
            gd.setStroke(dp(1), Color.parseColor("#94A3B8"))
            swatch.background = gd
            swatch.contentDescription = if (hex == "#00000000") "হাইলাইট মুছে ফেলুন" else hex
            swatch.setOnClickListener {
                applyColorToTarget(target, Color.parseColor(hex), isHighlight)
                dialog?.dismiss()
            }
            row.addView(swatch)
        }

        dialog = AlertDialog.Builder(this)
            .setTitle(if (isHighlight) "হাইলাইট রং বাছাই করুন" else "লেখার রং বাছাই করুন")
            .setView(scroller)
            .setNegativeButton("বাতিল", null)
            .show()
    }

    private fun applyColorToTarget(target: EditText, color: Int, isHighlight: Boolean) {
        val editable = target.text
        if (editable == null || editable.isEmpty()) {
            Toast.makeText(this, "প্রথমে কিছু লিখুন", Toast.LENGTH_SHORT).show()
            return
        }
        var start = target.selectionStart
        var end = target.selectionEnd
        if (start > end) { val t = start; start = end; end = t }
        if (start < 0 || end < 0 || start == end) {
            // nothing selected -> apply to the whole box / page
            start = 0
            end = editable.length
        }
        if (isHighlight) {
            for (s in editable.getSpans(start, end, BackgroundColorSpan::class.java)) editable.removeSpan(s)
            if (color != Color.TRANSPARENT) {
                editable.setSpan(BackgroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else {
            for (s in editable.getSpans(start, end, ForegroundColorSpan::class.java)) editable.removeSpan(s)
            editable.setSpan(ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        target.invalidate()
    }

    // ---------- Color/highlight span persistence (own compact JSON format — Android's
    // built-in Html.fromHtml does not reliably round-trip background-color, so spans are
    // serialized manually to guarantee colors and highlights survive save/reload). ----------

    private fun hasColorSpans(editable: Editable): Boolean =
        editable.getSpans(0, editable.length, ForegroundColorSpan::class.java).isNotEmpty() ||
            editable.getSpans(0, editable.length, BackgroundColorSpan::class.java).isNotEmpty()

    private fun spansToJson(editable: Editable): String {
        val arr = JSONArray()
        for (sp in editable.getSpans(0, editable.length, ForegroundColorSpan::class.java)) {
            val start = editable.getSpanStart(sp)
            val end = editable.getSpanEnd(sp)
            if (start < 0 || end <= start) continue
            val o = JSONObject()
            o.put("s", start); o.put("e", end)
            o.put("fg", String.format("#%06X", 0xFFFFFF and sp.foregroundColor))
            arr.put(o)
        }
        for (sp in editable.getSpans(0, editable.length, BackgroundColorSpan::class.java)) {
            val start = editable.getSpanStart(sp)
            val end = editable.getSpanEnd(sp)
            if (start < 0 || end <= start) continue
            val o = JSONObject()
            o.put("s", start); o.put("e", end)
            o.put("bg", String.format("#%06X", 0xFFFFFF and sp.backgroundColor))
            arr.put(o)
        }
        return arr.toString()
    }

    private fun applySpansFromJson(target: EditText, json: String) {
        if (json.isBlank()) return
        val editable = target.text ?: return
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val s = o.optInt("s", -1)
                val e = o.optInt("e", -1)
                if (s < 0 || e <= s || e > editable.length) continue
                if (o.has("fg")) {
                    editable.setSpan(ForegroundColorSpan(Color.parseColor(o.getString("fg"))), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (o.has("bg")) {
                    editable.setSpan(BackgroundColorSpan(Color.parseColor(o.getString("bg"))), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun deleteSelected() {
        val sel = selectedView ?: run {
            Toast.makeText(this, "প্রথমে কোনো একটি বক্স নির্বাচন করুন", Toast.LENGTH_SHORT).show()
            return
        }
        canvas.removeView(sel)
        selectedView = null
    }

    private fun copyAllText() {
        val sb = StringBuilder()
        sb.append(titleInput.text.toString()).append("\n")
        sb.append(currentDateFormatted).append("\n\n")
        val free = freeWriteText.text?.toString() ?: ""
        if (free.isNotBlank()) sb.append(free).append("\n\n")
        for (i in 0 until canvas.childCount) {
            val child = canvas.getChildAt(i)
            val e = child.tag as? PageElement ?: continue
            when (e.type) {
                TYPE_TEXT -> sb.append(e.text).append("\n")
                TYPE_TABLE -> {
                    for (row in e.cells) sb.append(row.joinToString(" | ")).append("\n")
                }
            }
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Hemel Notebook", sb.toString()))
        Toast.makeText(this, "নোটবুকের লেখা কপি হয়েছে", Toast.LENGTH_SHORT).show()
    }

    // ---------- Image handling ----------

    private fun copyImageToInternal(uri: Uri): String? {
        return try {
            val dir = File(filesDir, "images")
            if (!dir.exists()) dir.mkdirs()
            val name = "img_${UUID.randomUUID()}.jpg"
            val outFile = File(dir, name)
            contentResolver.openInputStream(uri)?.use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
            name
        } catch (ex: Exception) {
            Toast.makeText(this, "ছবি যোগ করা যায়নি", Toast.LENGTH_SHORT).show()
            null
        }
    }

    // ---------- Save / Load / Autosave (temporary draft) ----------

    private fun collectElements(): MutableList<PageElement> {
        val list = mutableListOf<PageElement>()
        for (i in 0 until canvas.childCount) {
            val child = canvas.getChildAt(i)
            val e = child.tag as? PageElement ?: continue
            if (e.type == TYPE_TEXT) {
                val et = (child as? FrameLayout)?.getChildAt(0) as? EditText
                val editable = et?.text
                if (editable != null) {
                    e.text = editable.toString()
                    e.colorSpansJson = if (hasColorSpans(editable)) spansToJson(editable) else ""
                }
            }
            list.add(e)
        }
        return list
    }

    private fun currentFreeText(): String = freeWriteText.text?.toString() ?: ""

    private fun currentFreeTextSpans(): String {
        val editable = freeWriteText.text ?: return ""
        return if (hasColorSpans(editable)) spansToJson(editable) else ""
    }

    private fun hasAnyContent(): Boolean {
        if (titleInput.text.toString().isNotBlank()) return true
        if (currentFreeText().isNotBlank()) return true
        for (i in 0 until canvas.childCount) {
            val e = canvas.getChildAt(i).tag as? PageElement ?: continue
            when (e.type) {
                TYPE_TEXT -> if (e.text.isNotBlank()) return true
                TYPE_IMAGE -> return true
                TYPE_TABLE -> for (row in e.cells) for (c in row) if (c.isNotBlank()) return true
            }
        }
        return false
    }

    private fun updateDraftStatusVisibility() {
        draftStatusText.visibility = if (isDraftFlag) View.VISIBLE else View.GONE
    }

    // Silent background save: used every few seconds and on pause, so nothing is lost
    // even if the user forgets to tap Save. A brand-new, never-saved note is stored as
    // a temporary draft; an already-saved note just keeps being updated in place.
    private fun autosave() {
        if (isDeleted) return
        if (notebookId <= 0 && !hasAnyContent()) return
        persist(finalize = false)
    }

    private fun finalizeAndSave(andFinish: Boolean) {
        if (isDeleted) { if (andFinish) finish(); return }
        isDraftFlag = false
        persist(finalize = true, andFinish = andFinish)
    }

    private fun confirmDeleteNotebook() {
        AlertDialog.Builder(this)
            .setTitle("পুরো নোটবুক ডিলিট করবেন?")
            .setMessage("এই নোটবুকটি (শিরোনাম, লেখা, বক্স, ছবি সহ) স্থায়ীভাবে মুছে যাবে। এটি আর ফিরিয়ে আনা যাবে না।")
            .setPositiveButton("ডিলিট করুন") { _, _ -> deleteNotebookAndFinish() }
            .setNegativeButton("বাতিল", null)
            .show()
    }

    private fun deleteNotebookAndFinish() {
        isDeleted = true
        autosaveJob?.cancel()
        val idToDelete = notebookId
        lifecycleScope.launch {
            if (idToDelete > 0) db.notebookDao().deleteByIds(listOf(idToDelete))
            finish()
        }
    }

    private fun persist(finalize: Boolean, andFinish: Boolean = false) {
        val elements = collectElements()
        val title = titleInput.text.toString()
        val freeText = currentFreeText()
        val freeTextSpans = currentFreeTextSpans()
        val nb = Notebook(
            id = if (notebookId > 0) notebookId else 0,
            title = title,
            dateText = currentDateFormatted,
            elementsJson = ElementsJson.serialize(elements),
            freeText = freeText,
            freeTextSpans = freeTextSpans,
            searchText = ElementsJson.buildSearchText(title, currentDateFormatted, freeText, elements),
            previewText = ElementsJson.buildPreviewText(freeText, elements),
            isDraft = isDraftFlag,
            updatedAt = System.currentTimeMillis()
        )
        lifecycleScope.launch {
            if (nb.id > 0) {
                db.notebookDao().update(nb)
            } else {
                notebookId = db.notebookDao().insert(nb)
            }
            updateDraftStatusVisibility()
            if (finalize) {
                if (andFinish) finish()
                else Toast.makeText(this@EditorActivity, "সংরক্ষিত হয়েছে", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadNotebook(id: Long) {
        lifecycleScope.launch {
            val nb = db.notebookDao().getById(id) ?: return@launch
            titleInput.setText(nb.title)
            currentDateFormatted = nb.dateText.ifBlank { currentDateFormatted }
            dateText.text = currentDateFormatted
            freeWriteText.setText(nb.freeText)
            applySpansFromJson(freeWriteText, nb.freeTextSpans)
            val elements = ElementsJson.deserialize(nb.elementsJson)
            for (e in elements) createElementView(e)
            isDraftFlag = nb.isDraft
            updateDraftStatusVisibility()
            clearSelection()
        }
    }

    private fun exportThisNotebook() {
        val elements = collectElements()
        val title = titleInput.text.toString()
        val nb = Notebook(
            id = if (notebookId > 0) notebookId else 0,
            title = title,
            dateText = currentDateFormatted,
            elementsJson = ElementsJson.serialize(elements),
            freeText = currentFreeText(),
            freeTextSpans = currentFreeTextSpans()
        )
        finalizeAndSave(andFinish = false)
        PdfExporter.exportAndShare(this, listOf(nb))
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}
