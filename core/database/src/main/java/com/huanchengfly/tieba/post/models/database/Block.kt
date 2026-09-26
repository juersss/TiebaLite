package com.huanchengfly.tieba.post.models.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.huanchengfly.tieba.post.core.common.fromJson

@Entity(tableName = "block")
data class Block @JvmOverloads constructor(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val category: Int = 0,
    val type: Int = 0,
    val keywords: String? = null,
    val username: String? = null,
    /** ⚠️ **被屏蔽用户的 uid**（不是账号维度！账号维度见 [ownerUid]） */
    val uid: String? = null,
    @ColumnInfo(name = "isregex") val isRegex: Boolean = false,
    /**
     * **归属账号**的 uid（未登录 = 空串）。2026-09-26 起黑/白名单按账号隔离。
     *
     * 这个表里同时有两个 uid 语义（[uid] = 被屏蔽的人，本字段 = 记录属于谁），
     * 所以列名与属性名都刻意区分开；口径见 [History.ownerUid]。
     */
    @ColumnInfo(name = "owner_uid", defaultValue = "") val ownerUid: String = "",
) {
    companion object {
        const val CATEGORY_BLACK_LIST = 10
        const val CATEGORY_WHITE_LIST = 11
        const val TYPE_KEYWORD = 0
        const val TYPE_USER = 1
        fun Block.getKeywords(): List<String> = keywords?.fromJson() ?: emptyList()
    }
}
