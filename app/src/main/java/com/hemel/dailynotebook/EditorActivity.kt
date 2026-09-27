package com.hemel.dailynotebook

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
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
import kotlinx.coroutines.launch
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
    }

    private lateinit var db: AppDatabase
    private var notebookId: Long = -1L
    private var createdAt: Long = System.currentTimeMillis()

    private lateinit var canvas: FrameLayout
    private lateinit var titleInput: EditText
    private lateinit var dateText: TextView
    private lateinit var toolbar: LinearLayout

    private var selectedView: View? = null
    private var addOffsetStep = 0
    private var currentDateFormatted: String = ""
    private val dateFmt = SimpleDateFormat("dd-MM-yyyy", Locale.US)

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
        toolbar = findViewById(R.id.toolbar)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { saveNotebook(andFinish = true) }
        findViewById<ImageButton>(R.id.btnSave).setOnClickListener { saveNotebook(andFinish = false) }

        canvas.setOnClickListener { clearSelection() }

        currentDateFormatted = dateFmt.format(Calendar.getInstance().time)
        dateText.text = currentDateFormatted
        dateText.setOnClickListener { showDatePicker() }

        buildToolbar()

        notebookId = intent.getLongExtra(EXTRA_NOTEBOOK_ID, -1L)
        if (notebookId > 0) loadNotebook(notebookId)
    }

    override fun onBackPressed() {
        saveNotebook(andFinish = true)
    }

    // ---------- Toolbar ----------

    private fun buildToolbar() {
        addToolbarButton(R.drawable.ic_text, "লেখা যোগ করুন") { addTextElement() }
        addToolbarButton(R.drawable.ic_image, "ছবি যোগ করুন") { pickImage.launch("image/*") }
        addToolbarButton(R.drawable.ic_table, "টেবিল যোগ করুন") { showTableDialog() }
        addToolbarButton(R.drawable.ic_font_up, "ফন্ট বড়") { changeFontSize(2f) }
        addToolbarButton(R.drawable.ic_font_down, "ফন্ট ছোট") { changeFontSize(-2f) }
        addToolbarButton(R.drawable.ic_delete, "মুছে ফেলুন") { deleteSelected() }
        addToolbarButton(R.drawable.ic_copy, "সব লেখা কপি করুন") { copyAllText() }
        addToolbarButton(R.drawable.ic_pdf, "PDF এক্সপোর্ট") { exportThisNotebook() }
    }

    private fun addToolbarButton(iconRes: Int, desc: String, onClick: () -> Unit): ImageButton {
        val btn = ImageButton(this)
        btn.setImageResource(iconRes)
        btn.contentDescription = desc
        btn.setBackgroundResource(R.drawable.bg_toolbar_btn)
        btn.setColorFilter(Color.parseColor("#1E3A8A"))
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

        // drag handle
        val handle = View(this)
        handle.setBackgroundResource(R.drawable.bg_handle)
        val handleSize = dp(20)
        val handleLp = FrameLayout.LayoutParams(handleSize, handleSize)
        handleLp.gravity = Gravity.TOP or Gravity.START
        handleLp.leftMargin = -dp(6)
        handleLp.topMargin = -dp(6)
        container.addView(handle, handleLp)
        attachDrag(handle, container, e)

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

    // ---------- Selection / drag ----------

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

    // ---------- Save / Load ----------

    private fun collectElements(): MutableList<PageElement> {
        val list = mutableListOf<PageElement>()
        for (i in 0 until canvas.childCount) {
            val child = canvas.getChildAt(i)
            val e = child.tag as? PageElement ?: continue
            list.add(e)
        }
        return list
    }

    private fun saveNotebook(andFinish: Boolean) {
        val elements = collectElements()
        val title = titleInput.text.toString()
        val nb = Notebook(
            id = if (notebookId > 0) notebookId else 0,
            title = title,
            dateText = currentDateFormatted,
            elementsJson = ElementsJson.serialize(elements),
            searchText = ElementsJson.buildSearchText(title, currentDateFormatted, elements),
            previewText = ElementsJson.buildPreviewText(elements),
            updatedAt = System.currentTimeMillis()
        )
        lifecycleScope.launch {
            if (nb.id > 0) {
                db.notebookDao().update(nb)
            } else {
                notebookId = db.notebookDao().insert(nb)
            }
            if (andFinish) finish()
            else Toast.makeText(this@EditorActivity, "সংরক্ষিত হয়েছে", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadNotebook(id: Long) {
        lifecycleScope.launch {
            val nb = db.notebookDao().getById(id) ?: return@launch
            titleInput.setText(nb.title)
            currentDateFormatted = nb.dateText.ifBlank { currentDateFormatted }
            dateText.text = currentDateFormatted
            val elements = ElementsJson.deserialize(nb.elementsJson)
            for (e in elements) createElementView(e)
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
            elementsJson = ElementsJson.serialize(elements)
        )
        saveNotebook(andFinish = false)
        PdfExporter.exportAndShare(this, listOf(nb))
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}
