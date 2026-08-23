package com.hidden.dictation.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * StatsEntity —— 全局统计（整体正确率等）
 * 用单一主键(固定 id=1)记录累计正确/错误总数，便于直接换算正确率。
 */
@Entity(tableName = "stats")
data class StatsEntity(
    @PrimaryKey val id: Int = 1,
    var totalRight: Int = 0,
    var totalWrong: Int = 0,
    var totalTriggers: Int = 0      // 累计弹窗触发次数
) {
    fun accuracy(): Float {
        val total = totalRight + totalWrong
        if (total == 0) return 0f
        return totalRight.toFloat() / total
    }
}
