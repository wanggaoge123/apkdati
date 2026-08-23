package com.hidden.dictation.db

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * DictationRepository —— 数据层门面
 * 职责：
 *  1) 初始化 Room（首次启动写入内置字词库，支持用户后续扩展）；
 *  2) 加权出题（权重固定 10/5/1，需求四.2）；
 *  3) 答题结果回写：答对降权、答错升权并写入错题库；
 *  4) 统计整体正确率。
 * 所有数据存 Room，不依赖 WebView 缓存（需求三.3）。
 *
 * 注意：本类是"题库数据中枢"。前端 writing-core.js 的 window.WORD_LIST
 * 由这里通过 JS 桥注入，前端不自定语库（需求四.3 永久存储于安卓）。
 */
class DictationRepository(private val db: AppDatabase) {

    /** 默认内置题库（可扩展）。单字模式为主，词语模式附少量示例。 */
    private val seedWords = listOf(
        WordEntity(hanzi = "天", pinyin = "tiān", mode = "char", weight = WordEntity.WEIGHT_NEW, answered = 0),
        WordEntity(hanzi = "地", pinyin = "dì", mode = "char", weight = WordEntity.WEIGHT_NEW, answered = 0),
        WordEntity(hanzi = "人", pinyin = "rén", mode = "char", weight = WordEntity.WEIGHT_NEW, answered = 0),
        WordEntity(hanzi = "你", pinyin = "nǐ", mode = "char", weight = WordEntity.WEIGHT_NEW, answered = 0),
        WordEntity(hanzi = "我", pinyin = "wǒ", mode = "char", weight = WordEntity.WEIGHT_NEW, answered = 0),
        WordEntity(hanzi = "学习", pinyin = "xué xí", mode = "word", weight = WordEntity.WEIGHT_NEW, answered = 0),
        WordEntity(hanzi = "努力", pinyin = "nǔ lì", mode = "word", weight = WordEntity.WEIGHT_NEW, answered = 0),
        WordEntity(hanzi = "坚持", pinyin = "jiān chí", mode = "word", weight = WordEntity.WEIGHT_NEW, answered = 0)
    )

    companion object {
        @Volatile private var INSTANCE: DictationRepository? = null

        fun get(context: Context): DictationRepository {
            return INSTANCE ?: synchronized(this) {
                val database = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "dictation.db"
                )
                    // Room 2.6+ 已移除 fallbackToDestructiveMigration(Boolean) 入参，改用无参版本
                    // 作用：数据库版本下降/结构不兼容时直接重建（开发期可接受，避免升级异常崩溃）
                    .fallbackToDestructiveMigration()
                    .build()
                val repo = DictationRepository(database)
                INSTANCE = repo
                repo
            }
        }
    }

    /** 首次启动：写入种子词库 + 初始化统计行（在 IO 线程） */
    suspend fun ensureSeed() = withContext(Dispatchers.IO) {
        if (db.wordDao().count() == 0) {
            db.wordDao().insertAll(seedWords)
        }
        if (db.statsDao().get() == null) {
            db.statsDao().upsert(StatsEntity())
        }
    }

    /**
     * 加权随机取词（供 JS 桥注入 window.WORD_LIST 用）。
     * 返回形如 [{hanzi, pinyin}] 的列表（这里取多个候选，前端按 IS_RANDOM_MODE 抽取）。
     */
    suspend fun getWordListForFrontend(limit: Int = 30): List<Map<String, String>> =
        withContext(Dispatchers.IO) {
            val all = db.wordDao().getAll()
            if (all.isEmpty()) return@withContext emptyList()
            // 按权重重复展开，再随机抽 limit 个（权重越高出现越多）
            val expanded = mutableListOf<WordEntity>()
            all.forEach { w ->
                val copies = w.weight.coerceAtLeast(1)
                repeat(copies) { expanded.add(w) }
            }
            expanded.shuffle()
            expanded.take(limit).distinctBy { it.hanzi }.map {
                mapOf("hanzi" to it.hanzi, "pinyin" to it.pinyin, "mode" to it.mode)
            }
        }

    /** 加权随机挑 1 个（兜底：万一前端要直接取单个词） */
    suspend fun pickOne(): WordEntity? = withContext(Dispatchers.IO) {
        db.wordDao().pickWeighted()
    }

    /**
     * 答题结果回写（核心权重更新逻辑，需求四.3）：
     *  - 答对：answered=1，rightCount++，weight 降为 1（低频极少出现）；
     *  - 答错：wrongCount++，weight 升为 10（最高频），并写入独立错题库。
     * 同时更新全局统计（正确率）。
     */
    suspend fun reportResult(hanzi: String, isCorrect: Boolean) = withContext(Dispatchers.IO) {
        // 更新字词权重
        val matched = db.wordDao().getAll().firstOrNull { it.hanzi == hanzi }
        if (matched != null) {
            if (isCorrect) {
                matched.answered = 1
                matched.rightCount += 1
                matched.weight = WordEntity.WEIGHT_RIGHT
            } else {
                matched.wrongCount += 1
                matched.weight = WordEntity.WEIGHT_WRONG
                db.wrongBookDao().upsert(
                    WrongBookEntity(hanzi = matched.hanzi, pinyin = matched.pinyin, mode = matched.mode)
                )
                db.wrongBookDao().incWrong(matched.hanzi)
            }
            db.wordDao().update(matched)
        }
        // 写单次记录
        db.answerRecordDao().insert(
            AnswerRecordEntity(wordId = matched?.id ?: 0, hanzi = hanzi, isCorrect = isCorrect,
                mode = matched?.mode ?: "char")
        )
        // 更新全局统计
        if (isCorrect) db.statsDao().incRight() else db.statsDao().incWrong()
    }

    /** 触发次数 +1（每次弹窗调用） */
    suspend fun reportTrigger() = withContext(Dispatchers.IO) {
        if (db.statsDao().get() == null) db.statsDao().upsert(StatsEntity())
        db.statsDao().incTriggers()
    }

    /** 取得整体正确率（0~1） */
    suspend fun accuracy(): Float = withContext(Dispatchers.IO) {
        db.statsDao().get()?.accuracy() ?: 0f
    }

    /** 取得统计概况文本（用于调试/未来扩展展示） */
    suspend fun summary(): String = withContext(Dispatchers.IO) {
        val stats = db.statsDao().get() ?: StatsEntity()
        val wrong = db.wrongBookDao().count()
        "正确率=${String.format("%.1f", stats.accuracy() * 100)}% 总触发=${stats.totalTriggers} 错词=${wrong}"
    }
}
