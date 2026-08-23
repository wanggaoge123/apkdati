package com.hidden.dictation.db

import androidx.room.*

@Dao
interface AnswerRecordDao {
    @Insert
    suspend fun insert(rec: AnswerRecordEntity)

    @Query("SELECT COUNT(*) FROM answer_records WHERE isCorrect = 1")
    suspend fun countRight(): Int

    @Query("SELECT COUNT(*) FROM answer_records WHERE isCorrect = 0")
    suspend fun countWrong(): Int

    @Query("SELECT * FROM answer_records ORDER BY timestamp DESC LIMIT 50")
    suspend fun recent(): List<AnswerRecordEntity>
}
