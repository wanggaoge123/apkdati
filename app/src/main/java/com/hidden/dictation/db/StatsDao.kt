package com.hidden.dictation.db

import androidx.room.*

@Dao
interface StatsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: StatsEntity)

    @Query("SELECT * FROM stats WHERE id = 1")
    suspend fun get(): StatsEntity?

    @Query("UPDATE stats SET totalRight = totalRight + 1 WHERE id = 1")
    suspend fun incRight()

    @Query("UPDATE stats SET totalWrong = totalWrong + 1 WHERE id = 1")
    suspend fun incWrong()

    @Query("UPDATE stats SET totalTriggers = totalTriggers + 1 WHERE id = 1")
    suspend fun incTriggers()
}
