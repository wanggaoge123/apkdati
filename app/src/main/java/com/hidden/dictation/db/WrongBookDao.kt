package com.hidden.dictation.db

import androidx.room.*

@Dao
interface WrongBookDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(w: WrongBookEntity)

    @Query("UPDATE wrong_book SET wrongTimes = wrongTimes + 1, lastWrongAt = :ts WHERE hanzi = :hanzi")
    suspend fun incWrong(hanzi: String, ts: Long = System.currentTimeMillis())

    @Query("SELECT * FROM wrong_book ORDER BY wrongTimes DESC")
    suspend fun all(): List<WrongBookEntity>

    @Query("SELECT COUNT(*) FROM wrong_book")
    suspend fun count(): Int
}
