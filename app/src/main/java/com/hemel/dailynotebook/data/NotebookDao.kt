package com.hemel.dailynotebook.data

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface NotebookDao {

    @Query("SELECT * FROM notebooks ORDER BY updatedAt DESC")
    fun getAll(): LiveData<List<Notebook>>

    @Query("""SELECT * FROM notebooks WHERE 
        title LIKE '%' || :q || '%' OR 
        dateText LIKE '%' || :q || '%' OR 
        searchText LIKE '%' || :q || '%'
        ORDER BY updatedAt DESC""")
    fun search(q: String): LiveData<List<Notebook>>

    @Query("SELECT * FROM notebooks WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): Notebook?

    @Query("SELECT * FROM notebooks WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<Notebook>

    @Insert
    suspend fun insert(notebook: Notebook): Long

    @Update
    suspend fun update(notebook: Notebook)

    @Delete
    suspend fun delete(notebook: Notebook)
}
