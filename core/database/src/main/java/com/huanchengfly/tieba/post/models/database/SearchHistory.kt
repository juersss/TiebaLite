package com.huanchengfly.tieba.post.models.database

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Immutable
@Entity(tableName = "searchhistory")
data class SearchHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    /** 归属账号 uid（未登录 = 空串）。2026-09-26 起按账号隔离；列名口径见 [History.ownerUid] */
    @ColumnInfo(name = "owner_uid", defaultValue = "") val ownerUid: String = "",
)
