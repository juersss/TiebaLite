package com.huanchengfly.tieba.post.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.huanchengfly.tieba.post.models.database.History
import kotlinx.coroutines.flow.Flow

/**
 * 浏览历史（**2026-09-26 起按账号隔离**）。
 *
 * 口径：**每个方法都必填 `ownerUid`**（未登录 = 空串）。做成必填参数而不是"内部取当前账号"，
 * 是为了让"漏加账号过滤"这件事**编译不过**——这是本次改造最重要的一道防线，
 * 少一个 `WHERE owner_uid` 就是一条跨账号泄漏。
 *
 * `deleteById` 不需要 uid：id 是全局自增主键，一行只属于一个账号，UI 也只会拿到当前账号的行。
 */
@Dao
interface HistoryDao {
    @Query("SELECT * FROM history WHERE owner_uid = :ownerUid ORDER BY timestamp DESC, count DESC LIMIT 100")
    suspend fun getAll(ownerUid: String): List<History>

    @Query("SELECT * FROM history WHERE owner_uid = :ownerUid AND type = :type ORDER BY timestamp DESC, count DESC LIMIT :pageSize OFFSET :offset")
    suspend fun getByType(ownerUid: String, type: Int, pageSize: Int = 100, offset: Int = 0): List<History>

    @Query("SELECT * FROM history WHERE owner_uid = :ownerUid AND type = :type ORDER BY timestamp DESC, count DESC LIMIT :pageSize OFFSET :offset")
    fun getFlowByType(ownerUid: String, type: Int, pageSize: Int = 100, offset: Int = 0): Flow<List<History>>

    @Query("SELECT * FROM history WHERE owner_uid = :ownerUid AND data = :data LIMIT 1")
    suspend fun getByData(ownerUid: String, data: String): History?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: History): Long

    @Update
    suspend fun update(history: History)

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM history WHERE owner_uid = :ownerUid")
    suspend fun deleteAll(ownerUid: String)

    @Transaction
    suspend fun upsert(history: History) {
        // 归属账号从**实体**取（DatabaseUtil 统一填好），不额外传参：
        // 避免出现"查询用一个 uid、插入用另一个"的口径分裂
        val existing = getByData(history.ownerUid, history.data)
        if (existing != null) {
            update(
                history.copy(
                    id = existing.id,
                    timestamp = System.currentTimeMillis(),
                    title = history.title,
                    extras = history.extras,
                    avatar = history.avatar,
                    username = history.username,
                    count = existing.count + 1,
                )
            )
        } else {
            insert(history.copy(count = 1, timestamp = System.currentTimeMillis()))
        }
    }
}
