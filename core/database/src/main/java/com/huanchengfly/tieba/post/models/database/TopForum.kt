package com.huanchengfly.tieba.post.models.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "topforum")
data class TopForum(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "forumid") val forumId: String,
    /** 归属账号 uid（未登录 = 空串）。2026-09-26 起置顶吧按账号隔离；列名口径见 [History.ownerUid] */
    @ColumnInfo(name = "owner_uid", defaultValue = "") val ownerUid: String = "",
)
