package com.huanchengfly.tieba.post.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.huanchengfly.tieba.post.models.database.SearchHistory
import com.huanchengfly.tieba.post.models.database.SearchPostHistory

/** 搜索历史（**2026-09-26 起按账号隔离**）；`ownerUid` 必填的理由见 [HistoryDao]。 */
@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM searchhistory WHERE owner_uid = :ownerUid ORDER BY timestamp DESC")
    suspend fun getAll(ownerUid: String): List<SearchHistory>

    @Query("DELETE FROM searchhistory WHERE owner_uid = :ownerUid")
    suspend fun deleteAll(ownerUid: String)

    @Query("DELETE FROM searchhistory WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM searchhistory WHERE owner_uid = :ownerUid AND content = :content LIMIT 1")
    suspend fun getByContent(ownerUid: String, content: String): SearchHistory?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: SearchHistory): Long

    @Transaction
    suspend fun upsert(history: SearchHistory) {
        val existing = getByContent(history.ownerUid, history.content)
        if (existing != null) {
            insert(history.copy(id = existing.id, timestamp = System.currentTimeMillis()))
        } else {
            insert(history.copy(timestamp = System.currentTimeMillis()))
        }
    }
}

/** 帖子内搜索历史（**2026-09-26 起按账号隔离**）。 */
@Dao
interface SearchPostHistoryDao {
    @Query("SELECT * FROM searchposthistory WHERE owner_uid = :ownerUid ORDER BY timestamp DESC")
    suspend fun getAll(ownerUid: String): List<SearchPostHistory>

    @Query("DELETE FROM searchposthistory WHERE owner_uid = :ownerUid")
    suspend fun deleteAll(ownerUid: String)

    @Query("DELETE FROM searchposthistory WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM searchposthistory WHERE owner_uid = :ownerUid AND content = :content LIMIT 1")
    suspend fun getByContent(ownerUid: String, content: String): SearchPostHistory?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: SearchPostHistory): Long

    @Transaction
    suspend fun upsert(history: SearchPostHistory) {
        val existing = getByContent(history.ownerUid, history.content)
        if (existing != null) {
            insert(history.copy(id = existing.id, timestamp = System.currentTimeMillis()))
        } else {
            insert(history.copy(timestamp = System.currentTimeMillis()))
        }
    }
}
