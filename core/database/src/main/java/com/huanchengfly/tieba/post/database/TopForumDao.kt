package com.huanchengfly.tieba.post.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.huanchengfly.tieba.post.models.database.TopForum

/** 置顶吧（**2026-09-26 起按账号隔离**）；`ownerUid` 必填的理由见 [HistoryDao]。 */
@Dao
interface TopForumDao {
    @Query("SELECT * FROM topforum WHERE owner_uid = :ownerUid")
    suspend fun getAll(ownerUid: String): List<TopForum>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(topForum: TopForum)

    @Query("DELETE FROM topforum WHERE owner_uid = :ownerUid AND forumid = :forumId")
    suspend fun deleteByForumId(ownerUid: String, forumId: String)
}
