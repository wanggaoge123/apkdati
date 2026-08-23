package com.hidden.dictation.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * AnswerRecordEntity —— 单次答题记录（需求四.3：整体正确率统计来源）
 * 每次提交判定都落一条记录，便于统计"整体正确率"。
 */
@Entity(tableName = "answer_records")
data class AnswerRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val wordId: Long,        // 关联 words.id
    val hanzi: String,
    val isCorrect: Boolean,  // 最终是否写对（含罚写重试后的结果）
    val mode: String,
    val timestamp: Long = System.currentTimeMillis()
)
