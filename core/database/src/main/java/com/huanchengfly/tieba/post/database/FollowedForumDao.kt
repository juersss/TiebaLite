package com.huanchengfly.tieba.post.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.huanchengfly.tieba.post.models.database.FollowedForum
import kotlinx.coroutines.flow.Flow

/**
 * 关注吧的按账号持久化（2026-09-26，§8.7 候选 5）。
 *
 * 口径：**所有读写都带 uid**——关注吧是账号私有数据，漏 uid 就会串号。
 * 写入侧与 `FollowedForumsCache` 的两条路径一一对应：
 * - 慢路径（全量同步，结果权威）→ [replaceAll]（先删后插，同一事务）
 * - 快路径（前几页，可能截断）→ [mergeAll]（只覆盖传入的 forum_id，绝不整体替换）
 */
@Dao
interface FollowedForumDao {

    @Query("SELECT * FROM followed_forum WHERE uid = :uid ORDER BY forum_id ASC")
    suspend fun getAll(uid: String): List<FollowedForum>

    @Query("SELECT * FROM followed_forum WHERE uid = :uid ORDER BY forum_id ASC")
    fun getAllFlow(uid: String): Flow<List<FollowedForum>>

    @Query("SELECT COUNT(*) FROM followed_forum WHERE uid = :uid")
    suspend fun count(uid: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<FollowedForum>)

    @Query("DELETE FROM followed_forum WHERE uid = :uid")
    suspend fun deleteAll(uid: String)

    @Query("DELETE FROM followed_forum WHERE uid = :uid AND forum_id = :forumId")
    suspend fun delete(uid: String, forumId: Long)

    /**
     * 全量替换某账号的关注吧。**同一事务**：先删后插，中途失败不会留下半套数据。
     * `rows` 为空表示该账号确实没有关注吧（合法状态，等同清空）。
     */
    @Transaction
    suspend fun replaceAll(uid: String, rows: List<FollowedForum>) {
        deleteAll(uid)
        if (rows.isNotEmpty()) insertAll(rows)
    }

    /**
     * 增量合并（快路径）：只覆盖传入的 forum_id，不动该账号的其他行。
     *
     * **不能**用 [replaceAll] 代替——快路径只拉前几页，可能提前截断，
     * 整体替换会把后面几页的关注吧静默删掉（`HomeViewModel` 的慢路径注释同口径）。
     */
    suspend fun mergeAll(uid: String, rows: List<FollowedForum>) {
        if (rows.isEmpty()) return
        insertAll(rows)
    }
}
