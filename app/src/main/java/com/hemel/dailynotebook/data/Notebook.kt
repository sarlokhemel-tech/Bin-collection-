package com.hemel.dailynotebook.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notebooks")
data class Notebook(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "",
    val dateText: String = "",
    val elementsJson: String = "[]",
    val searchText: String = "",
    val previewText: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)
