package com.huanchengfly.tieba.post.models.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 草稿。**2026-09-26 起按账号隔离**：唯一索引从 `hash` 变成 (`owner_uid`, `hash`) ——
 * "一个 hash 一行"要变成"**每个账号**一个 hash 一行"，否则两个账号保存同一个帖子草稿会互相顶掉。
 *
 * ⚠️ 迁移 `MIGRATION_41_42` 必须 DROP 旧索引 `index_draft_hash` 再建 `index_draft_owner_uid_hash`：
 * Room 打开库时会把实际索引集合与实体声明比对，**多一个旧索引同样会报
 * "Migration didn't properly handle"**。
 */
@Entity(
    tableName = "draft",
    indices = [Index(value = ["owner_uid", "hash"], unique = true)]
)
data class Draft(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val hash: String,
    val content: String,
    /** 归属账号 uid（未登录 = 空串）；列名口径见 [History.ownerUid] */
    @ColumnInfo(name = "owner_uid", defaultValue = "") val ownerUid: String = "",
)
