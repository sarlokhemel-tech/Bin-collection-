package com.hemel.dailynotebook.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notebooks")
data class Notebook(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "",
    val dateText: String = "",
    val elementsJson: String = "[]",
    // Free-form text written directly on the page, without creating any box.
    val freeText: String = "",
    // Compact JSON list of colored/highlighted ranges within freeText. Empty when no
    // coloring was applied.
    val freeTextSpans: String = "",
    val searchText: String = "",
    val previewText: String = "",
    // true = only auto-saved (temporary/draft), not yet explicitly saved by the user.
    val isDraft: Boolean = false,
    // How many screen-heights tall the writable page is. Starts at 1 (a single page,
    // matching what fits on screen); only grows when the user explicitly taps "add page".
    val pageCount: Int = 1,
    val updatedAt: Long = System.currentTimeMillis()
)
