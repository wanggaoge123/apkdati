package com.hidden.dictation.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * WrongBookEntity —— 独立错题库（需求四.3）
 * 与 words 表解耦：只要某字词被答错过，就在此表留一条（去重按 hanzi）。
 * 错题权重恒为 10，保证"持续高概率重复出现"。
 */
@Entity(tableName = "wrong_book", primaryKeys = ["hanzi"])
data class WrongBookEntity(
    val hanzi: String,
    val pinyin: String,
    val mode: String,
    var wrongTimes: Int = 1,        // 累计错次
    var lastWrongAt: Long = System.currentTimeMillis()
)
