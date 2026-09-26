package com.huanchengfly.tieba.post.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.huanchengfly.tieba.post.models.database.Draft

/**
 * 草稿（**2026-09-26 起按账号隔离**）；`ownerUid` 必填的理由见 [HistoryDao]。
 *
 * 唯一索引是 (`owner_uid`,`hash`)：两个账号保存同一个帖子的草稿互不顶掉。
 */
@Dao
interface DraftDao {
    @Query("SELECT * FROM draft WHERE owner_uid = :ownerUid")
    suspend fun getAll(ownerUid: String): List<Draft>

    @Query("SELECT * FROM draft WHERE owner_uid = :ownerUid AND hash = :hash LIMIT 1")
    suspend fun getByHash(ownerUid: String, hash: String): Draft?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(draft: Draft)

    @Query("DELETE FROM draft WHERE owner_uid = :ownerUid AND hash = :hash")
    suspend fun deleteByHash(ownerUid: String, hash: String)
}
