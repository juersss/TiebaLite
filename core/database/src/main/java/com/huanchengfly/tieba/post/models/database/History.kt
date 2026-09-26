package com.huanchengfly.tieba.post.models.database

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Immutable
@Entity(tableName = "history")
data class History(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String = "",
    val data: String = "",
    val type: Int = 0,
    val timestamp: Long = 0,
    val count: Int = 0,
    val extras: String? = null,
    val avatar: String? = null,
    val username: String? = null,
    /**
     * 归属账号的 uid（未登录 = 空串，对应"未登录桶"）。**2026-09-26 起浏览历史按账号隔离**
     * （产品决策：切号后各看各的历史）。
     *
     * 列名用 `owner_uid` 而不是 `uid`：`Block` 里已有一个语义完全不同的 `uid`
     * （"被屏蔽用户的 uid"），六个表统一用 `owner_uid` 才不会看错。
     *
     * `defaultValue = ""` 是给迁移用的——迁移走 `ALTER TABLE ADD COLUMN`（见 `MIGRATION_41_42`），
     * SQL 默认值必须与实体声明一致，否则 Room 打开库时会报 "Migration didn't properly handle"。
     */
    @ColumnInfo(name = "owner_uid", defaultValue = "") val ownerUid: String = "",
)
