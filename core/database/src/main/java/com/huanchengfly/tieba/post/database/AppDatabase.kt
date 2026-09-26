package com.huanchengfly.tieba.post.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.huanchengfly.tieba.post.models.database.Account
import com.huanchengfly.tieba.post.models.database.Block
import com.huanchengfly.tieba.post.models.database.Draft
import com.huanchengfly.tieba.post.models.database.FollowedForum
import com.huanchengfly.tieba.post.models.database.History
import com.huanchengfly.tieba.post.models.database.SearchHistory
import com.huanchengfly.tieba.post.models.database.SearchPostHistory
import com.huanchengfly.tieba.post.models.database.TopForum
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Database(
    entities = [
        Account::class,
        Draft::class,
        History::class,
        Block::class,
        TopForum::class,
        SearchHistory::class,
        SearchPostHistory::class,
        FollowedForum::class,
    ],
    // 41 = 40 + followed_forum 表（关注吧按账号持久化，2026-09-26）。
    // 42 = 41 + 六张表的 owner_uid（历史/置顶吧/黑名单/草稿/两种搜索历史按账号隔离，2026-09-26）。
    // 均见 DatabaseModule 里对应迁移的 KDoc。
    version = 42,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun draftDao(): DraftDao
    abstract fun historyDao(): HistoryDao
    abstract fun blockDao(): BlockDao
    abstract fun topForumDao(): TopForumDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun searchPostHistoryDao(): SearchPostHistoryDao
    abstract fun followedForumDao(): FollowedForumDao
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppDatabaseEntryPoint {
    fun appDatabase(): AppDatabase
}
