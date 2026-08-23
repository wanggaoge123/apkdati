package com.hidden.dictation.db

import androidx.room.*

/**
 * WordDao —— 字词表数据访问
 * 核心：加权随机出题（需求四.2 固定权重 10/5/1）
 * 实现方式：按权重做"加权随机抽样"。Room 不直接支持加权随机，
 * 这里用"带权重的 ORDER BY RANDOM()*weight DESC"近似实现——
 * 权重越大的字词，随机值*权重越容易排到前面，从而高频出现。
 */
@Dao
interface WordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(word: WordEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(words: List<WordEntity>)

    @Update
    suspend fun update(word: WordEntity)

    @Query("SELECT * FROM words WHERE id = :id")
    suspend fun getById(id: Long): WordEntity?

    @Query("SELECT * FROM words")
    suspend fun getAll(): List<WordEntity>

    @Query("SELECT COUNT(*) FROM words")
    suspend fun count(): Int

    /**
     * 加权随机取 1 个词：按照 weight 加权，权重越高越容易被抽到。
     * 错题(weight=10)高概率、已对(weight=1)极少出现。
     */
    @Query("""
        SELECT * FROM words
        ORDER BY RANDOM() * weight DESC
        LIMIT 1
    """)
    suspend fun pickWeighted(): WordEntity?

    @Query("SELECT * FROM words WHERE wrongCount > 0 ORDER BY wrongCount DESC")
    suspend fun getWrongWords(): List<WordEntity>
}
