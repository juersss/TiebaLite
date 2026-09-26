package com.huanchengfly.tieba.post.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import com.huanchengfly.tieba.post.models.database.Block
import kotlinx.coroutines.flow.Flow
import androidx.room.Query

/**
 * 黑/白名单（**2026-09-26 起按账号隔离**）；`ownerUid` 必填的理由见 [HistoryDao]。
 *
 * 注意本表有两个 uid 语义：`Block.uid` = **被屏蔽用户的 uid**，`owner_uid` = **记录归属账号**。
 */
@Dao
interface BlockDao {
    @Query("SELECT * FROM block WHERE owner_uid = :ownerUid")
    fun getAllFlow(ownerUid: String): Flow<List<Block>>

    @Query("SELECT * FROM block WHERE owner_uid = :ownerUid")
    suspend fun getAll(ownerUid: String): List<Block>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(block: Block): Long

    @Query("DELETE FROM block WHERE id = :id")
    suspend fun deleteById(id: Long)
}
