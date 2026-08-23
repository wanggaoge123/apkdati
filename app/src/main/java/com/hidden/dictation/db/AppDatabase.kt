package com.hidden.dictation.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * AppDatabase —— Room 数据库（业务数据永久本地存储）
 * 版本 1；entities 包含全部四类表。
 */
@Database(
    entities = [
        WordEntity::class,
        AnswerRecordEntity::class,
        WrongBookEntity::class,
        StatsEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun wordDao(): WordDao
    abstract fun answerRecordDao(): AnswerRecordDao
    abstract fun wrongBookDao(): WrongBookDao
    abstract fun statsDao(): StatsDao
}
