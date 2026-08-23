package com.hidden.dictation.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * WordEntity —— 字词表（题库 + 权重 + 累计对错次数）
 * 设计原则（需求四）：
 *  - 权重固定三档：错题=10 / 未作答新词=5 / 已答对=1
 *  - 答对后自动降权（weight 变为 1）；答错后提升到 10 并计入 wrongCount
 *  - 该表即"题库"，也是"错题库"的来源（wrongCount>0 即错题）
 *  - 数据永久存于 Room，不依赖 WebView 缓存（需求三.3）
 */
@Entity(tableName = "words")
data class WordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hanzi: String,        // 汉字或词语（单字模式一个字，词语模式一个词）
    val pinyin: String,       // 拼音
    val mode: String = "char",// char=单字 / word=词语
    var weight: Int = 5,      // 出题权重：错误10 / 新词5 / 已对1（初始按新词5）
    var wrongCount: Int = 0,  // 累计答错次数（>0 即为错题）
    var rightCount: Int = 0,  // 累计答对次数
    var answered: Int = 0     // 是否已作答过（0=新词未作答）
) {
    companion object {
        // 权重常量（需求四.2 固定值，禁止随意改）
        const val WEIGHT_WRONG = 10   // 错题权重（最高频）
        const val WEIGHT_NEW = 5      // 未作答新词权重（中频）
        const val WEIGHT_RIGHT = 1    // 已答对字词权重（低频极少出现）
    }
}
