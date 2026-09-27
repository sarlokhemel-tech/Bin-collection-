package com.hemel.dailynotebook

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.hemel.dailynotebook.data.Notebook

class NotebookAdapter(
    private val onOpen: (Notebook) -> Unit,
    private val onLongPress: (Notebook) -> Unit,
    private val onSelectionToggled: (Long, Boolean) -> Unit
) : RecyclerView.Adapter<NotebookAdapter.VH>() {

    var items: List<Notebook> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    var selectionMode: Boolean = false
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    var selectedIds: MutableSet<Long> = mutableSetOf()

    inner class VH(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        val checkSelect: android.widget.CheckBox = itemView.findViewById(R.id.checkSelect)
        val titleText: TextView = itemView.findViewById(R.id.titleText)
        val dateText: TextView = itemView.findViewById(R.id.dateText)
        val previewText: TextView = itemView.findViewById(R.id.previewText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_notebook, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val nb = items[position]
        holder.titleText.text = if (nb.title.isBlank()) "শিরোনামহীন নোটবুক" else nb.title
        holder.dateText.text = nb.dateText
        holder.previewText.text = nb.previewText
        holder.previewText.visibility = if (nb.previewText.isBlank()) android.view.View.GONE else android.view.View.VISIBLE

        holder.checkSelect.visibility = if (selectionMode) android.view.View.VISIBLE else android.view.View.GONE
        holder.checkSelect.setOnCheckedChangeListener(null)
        holder.checkSelect.isChecked = selectedIds.contains(nb.id)
        holder.checkSelect.setOnCheckedChangeListener { _, checked ->
            onSelectionToggled(nb.id, checked)
        }

        holder.itemView.setOnClickListener {
            if (selectionMode) {
                holder.checkSelect.isChecked = !holder.checkSelect.isChecked
            } else {
                onOpen(nb)
            }
        }
        holder.itemView.setOnLongClickListener {
            onLongPress(nb)
            true
        }
    }

    override fun getItemCount() = items.size
}
