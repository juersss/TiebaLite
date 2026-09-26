package com.huanchengfly.tieba.post.models.database

import androidx.compose.runtime.Stable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * 关注吧的**按账号持久化**缓存（2026-09-26 新增，§8.7 候选 5 的第一步）。
 *
 * 为什么需要它：`FollowedForumsCache` 是纯内存单例，冷启动/切号后要等首页"慢路径"
 * 全量同步（54 个串行请求）完成才有数据。这段窗口里它是空的，于是
 * "只看关注吧"过滤与关注态判定（`PersonalizedViewModel.isFollowed`）**按空表判**——
 * 表现是关注吧列表被过滤成空、关注状态显示不对。落到 Room 后冷启动可直接预载，
 * 不必等一次网络全量同步。
 *
 * **账号维度**：关注吧是账号私有数据，主键是 (`uid`, `forum_id`)——切号后按新 uid 预载，
 * 不串号（与 `OpRecordStore` 的 `agree_op_records_u_<uid>` 同一口径）。
 *
 * 字段与 `ForumGuideBean.LikeForum` 一一对应（那是服务端模型，不能直接当 Room 实体）。
 */
@Stable
@Entity(
    tableName = "followed_forum",
    primaryKeys = ["uid", "forum_id"],
    indices = [Index(value = ["uid"])],
)
data class FollowedForum @JvmOverloads constructor(
    @ColumnInfo(name = "uid") val uid: String = "",
    @ColumnInfo(name = "forum_id") val forumId: Long = 0L,
    @ColumnInfo(name = "forum_name") val forumName: String = "",
    @ColumnInfo(name = "avatar") val avatar: String = "",
    @ColumnInfo(name = "level_id") val levelId: Int = 0,
    @ColumnInfo(name = "level_name") val levelName: String = "",
    @ColumnInfo(name = "is_sign") val isSign: Int = -1,
    @ColumnInfo(name = "is_forbidden") val isForbidden: Int = 0,
    @ColumnInfo(name = "is_official_forum") val isOfficialForum: Int? = null,
    @ColumnInfo(name = "hot_num") val hotNum: Int = 0,
    @ColumnInfo(name = "member_count") val memberCount: Int = 0,
    @ColumnInfo(name = "thread_num") val threadNum: Int = 0,
    @ColumnInfo(name = "day_thread_num") val dayThreadNum: Int = 0,
    @ColumnInfo(name = "top_sort_value") val topSortValue: Int = 0,
)
