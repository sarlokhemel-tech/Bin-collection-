package com.hemel.dailynotebook

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.hemel.dailynotebook.data.AppDatabase
import com.hemel.dailynotebook.data.Notebook
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var db: AppDatabase
    private lateinit var adapter: NotebookAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: android.widget.TextView
    private lateinit var searchInput: android.widget.EditText
    private lateinit var fabExport: FloatingActionButton
    private lateinit var fabFinalize: FloatingActionButton
    private lateinit var fabDelete: FloatingActionButton
    private lateinit var tabSaved: android.widget.TextView
    private lateinit var tabDrafts: android.widget.TextView
    private var currentLiveData: androidx.lifecycle.LiveData<List<Notebook>>? = null

    // false = the main "saved" list (temporary drafts hidden); true = the temporary/draft
    // list — notes that were only auto-saved and are waiting to be saved permanently.
    private var showingDrafts = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        db = AppDatabase.getInstance(this)

        recyclerView = findViewById(R.id.recyclerView)
        emptyView = findViewById(R.id.emptyView)
        searchInput = findViewById(R.id.searchInput)
        fabExport = findViewById(R.id.fabExportSelected)
        fabFinalize = findViewById(R.id.fabFinalizeSelected)
        fabDelete = findViewById(R.id.fabDeleteSelected)
        tabSaved = findViewById(R.id.tabSaved)
        tabDrafts = findViewById(R.id.tabDrafts)
        val fabAdd = findViewById<FloatingActionButton>(R.id.fabAdd)

        adapter = NotebookAdapter(
            onOpen = { nb -> openEditor(nb.id) },
            onLongPress = { nb ->
                adapter.selectionMode = true
                adapter.selectedIds.add(nb.id)
                updateActionFabs()
            },
            onSelectionToggled = { id, checked ->
                if (checked) adapter.selectedIds.add(id) else adapter.selectedIds.remove(id)
                if (adapter.selectedIds.isEmpty()) {
                    adapter.selectionMode = false
                }
                updateActionFabs()
            }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        fabAdd.setOnClickListener { openEditor(-1L) }

        fabExport.setOnClickListener {
            val ids = adapter.selectedIds.toList()
            if (ids.isEmpty()) return@setOnClickListener
            lifecycleScope.launch {
                val notebooks = db.notebookDao().getByIds(ids)
                PdfExporter.exportAndShare(this@MainActivity, notebooks)
                exitSelectionMode()
            }
        }

        fabFinalize.setOnClickListener {
            val ids = adapter.selectedIds.toList()
            if (ids.isEmpty()) return@setOnClickListener
            lifecycleScope.launch {
                db.notebookDao().finalizeDrafts(ids)
                exitSelectionMode()
            }
        }

        fabDelete.setOnClickListener {
            val ids = adapter.selectedIds.toList()
            if (ids.isEmpty()) return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle("মুছে ফেলবেন?")
                .setMessage("নির্বাচিত নোটবুক(গুলো) স্থায়ীভাবে মুছে যাবে।")
                .setPositiveButton("মুছে ফেলুন") { _, _ ->
                    lifecycleScope.launch {
                        db.notebookDao().deleteByIds(ids)
                        exitSelectionMode()
                    }
                }
                .setNegativeButton("বাতিল", null)
                .show()
        }

        tabSaved.setOnClickListener { switchTab(drafts = false) }
        tabDrafts.setOnClickListener { switchTab(drafts = true) }

        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                observeList(s?.toString()?.trim() ?: "")
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        updateTabStyles()
        observeList("")
    }

    override fun onResume() {
        super.onResume()
        // the editor auto-saves drafts in the background, so refresh whichever list is showing
        observeList(searchInput.text.toString().trim())
    }

    private fun switchTab(drafts: Boolean) {
        if (showingDrafts == drafts) return
        showingDrafts = drafts
        exitSelectionMode()
        updateTabStyles()
        observeList(searchInput.text.toString().trim())
    }

    private fun updateTabStyles() {
        val activeColor = ContextCompat.getColor(this, R.color.primary)
        val inactiveColor = android.graphics.Color.parseColor("#94A3B8")
        tabSaved.setTextColor(if (!showingDrafts) activeColor else inactiveColor)
        tabDrafts.setTextColor(if (showingDrafts) activeColor else inactiveColor)
        emptyView.text = if (showingDrafts)
            "কোনো অস্থায়ী (খসড়া) নোট নেই।"
        else
            "কোনো নোটবুক নেই। নিচের + বাটনে চাপুন।"
    }

    private fun exitSelectionMode() {
        adapter.selectionMode = false
        adapter.selectedIds.clear()
        updateActionFabs()
    }

    private fun updateActionFabs() {
        val hasSelection = adapter.selectedIds.isNotEmpty()
        // export only makes sense for finalized notes; finalize only makes sense for drafts;
        // delete (the "delete whole notebook" option) is available in both lists.
        fabExport.visibility = if (hasSelection && !showingDrafts) View.VISIBLE else View.GONE
        fabFinalize.visibility = if (hasSelection && showingDrafts) View.VISIBLE else View.GONE
        fabDelete.visibility = if (hasSelection) View.VISIBLE else View.GONE
    }

    private fun observeList(query: String) {
        currentLiveData?.removeObservers(this)
        val liveData = if (showingDrafts) {
            if (query.isBlank()) db.notebookDao().getDrafts() else db.notebookDao().searchDrafts(query.lowercase())
        } else {
            if (query.isBlank()) db.notebookDao().getAll() else db.notebookDao().search(query.lowercase())
        }
        currentLiveData = liveData

        liveData.observe(this) { list ->
            adapter.items = list
            emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun openEditor(id: Long) {
        val intent = Intent(this, EditorActivity::class.java)
        if (id > 0) intent.putExtra(EditorActivity.EXTRA_NOTEBOOK_ID, id)
        startActivity(intent)
    }
}
