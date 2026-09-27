package com.hemel.dailynotebook

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
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
    private lateinit var emptyView: View
    private lateinit var searchInput: android.widget.EditText
    private lateinit var fabExport: FloatingActionButton
    private var currentLiveData: androidx.lifecycle.LiveData<List<Notebook>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        db = AppDatabase.getInstance(this)

        recyclerView = findViewById(R.id.recyclerView)
        emptyView = findViewById(R.id.emptyView)
        searchInput = findViewById(R.id.searchInput)
        fabExport = findViewById(R.id.fabExportSelected)
        val fabAdd = findViewById<FloatingActionButton>(R.id.fabAdd)

        adapter = NotebookAdapter(
            onOpen = { nb -> openEditor(nb.id) },
            onLongPress = { nb ->
                adapter.selectionMode = true
                adapter.selectedIds.add(nb.id)
                fabExport.visibility = View.VISIBLE
            },
            onSelectionToggled = { id, checked ->
                if (checked) adapter.selectedIds.add(id) else adapter.selectedIds.remove(id)
                if (adapter.selectedIds.isEmpty()) {
                    adapter.selectionMode = false
                    fabExport.visibility = View.GONE
                }
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
                adapter.selectionMode = false
                adapter.selectedIds.clear()
                fabExport.visibility = View.GONE
            }
        }

        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                observeList(s?.toString()?.trim() ?: "")
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        observeList("")
    }

    private fun observeList(query: String) {
        currentLiveData?.removeObservers(this)
        val liveData = if (query.isBlank()) db.notebookDao().getAll()
        else db.notebookDao().search(query.lowercase())
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
