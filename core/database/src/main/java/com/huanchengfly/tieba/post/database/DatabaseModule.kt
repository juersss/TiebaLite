package com.huanchengfly.tieba.post.database

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// internal 而非 private:迁移测试(Migration39To40Test)必须引用**生产同一条**迁移实例，
// 而不是在测试里复制一份 SQL——复制出来的 SQL 不会随生产改动而红，等于没测。
// (本仓库既有先例:AgreeRateLimiter.resetForTest / OpResponseLog.resetForTest 均为 internal 供单测使用)
internal val MIGRATION_39_40 = Migration(39, 40) { db ->
    db.execSQL("CREATE TABLE IF NOT EXISTS `draft_new` (`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, `hash` TEXT NOT NULL, `content` TEXT NOT NULL)")
    db.execSQL("INSERT OR REPLACE INTO `draft_new` (`id`, `hash`, `content`) SELECT `id`, `hash`, `content` FROM `draft` WHERE `id` IN (SELECT MAX(`id`) FROM `draft` GROUP BY `hash`)")
    db.execSQL("DROP TABLE IF EXISTS `draft`")
    db.execSQL("ALTER TABLE `draft_new` RENAME TO `draft`")
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_draft_hash` ON `draft` (`hash`)")
}

/**
 * 41 = 40 + `followed_forum` 表（关注吧按账号持久化，2026-09-26，§8.7 候选 5）。
 *
 * **纯新增表**：不触碰任何既有表，所以没有数据搬运步骤——对既有用户数据风险最低。
 * SQL 必须与 Room 生成的建表语句**逐字一致**，否则 Room 打开库时会抛
 * "Migration didn't properly handle"（`Migration40To41Test` 用生产这一条迁移实例验，
 * `RoomSchemaExportGuardTest` 用导出的 41.json 兜底）。
 */
internal val MIGRATION_40_41 = Migration(40, 41) { db ->
    db.execSQL("CREATE TABLE IF NOT EXISTS `followed_forum` (`uid` TEXT NOT NULL, `forum_id` INTEGER NOT NULL, `forum_name` TEXT NOT NULL, `avatar` TEXT NOT NULL, `level_id` INTEGER NOT NULL, `level_name` TEXT NOT NULL, `is_sign` INTEGER NOT NULL, `is_forbidden` INTEGER NOT NULL, `is_official_forum` INTEGER, `hot_num` INTEGER NOT NULL, `member_count` INTEGER NOT NULL, `thread_num` INTEGER NOT NULL, `day_thread_num` INTEGER NOT NULL, `top_sort_value` INTEGER NOT NULL, PRIMARY KEY(`uid`, `forum_id`))")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_followed_forum_uid` ON `followed_forum` (`uid`)")
}

internal val MIGRATION_38_39 = Migration(38, 39) { db ->
    // 1. 重建 account 表
    db.execSQL("ALTER TABLE `account` RENAME TO `account_old`")
    db.execSQL("CREATE TABLE IF NOT EXISTS `account` (`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, `uid` TEXT NOT NULL, `name` TEXT NOT NULL, `bduss` TEXT NOT NULL, `tbs` TEXT NOT NULL, `portrait` TEXT NOT NULL, `stoken` TEXT NOT NULL, `cookie` TEXT NOT NULL, `nameshow` TEXT, `intro` TEXT, `sex` TEXT, `fansnum` TEXT, `postnum` TEXT, `threadnum` TEXT, `concernnum` TEXT, `tbage` TEXT, `age` TEXT, `birthdayshowstatus` TEXT, `birthdaytime` TEXT, `constellation` TEXT, `tiebauid` TEXT, `loadsuccess` INTEGER NOT NULL, `uuid` TEXT, `zid` TEXT)")
    db.execSQL("INSERT INTO `account` (`id`, `uid`, `name`, `bduss`, `tbs`, `portrait`, `stoken`, `cookie`, `nameshow`, `intro`, `sex`, `fansnum`, `postnum`, `threadnum`, `concernnum`, `tbage`, `age`, `birthdayshowstatus`, `birthdaytime`, `constellation`, `tiebauid`, `loadsuccess`, `uuid`, `zid`) SELECT `id`, IFNULL(`uid`, ''), IFNULL(`name`, ''), IFNULL(`bduss`, ''), IFNULL(`tbs`, ''), IFNULL(`portrait`, ''), IFNULL(`stoken`, ''), IFNULL(`cookie`, ''), `nameshow`, `intro`, `sex`, `fansnum`, `postnum`, `threadnum`, `concernnum`, `tbage`, `age`, `birthdayshowstatus`, `birthdaytime`, `constellation`, `tiebauid`, IFNULL(`loadsuccess`, 0), `uuid`, `zid` FROM `account_old`")
    db.execSQL("DROP TABLE IF EXISTS `account_old`")

    // 2. 重建 draft 表
    db.execSQL("ALTER TABLE `draft` RENAME TO `draft_old`")
    db.execSQL("CREATE TABLE IF NOT EXISTS `draft` (`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, `hash` TEXT NOT NULL, `content` TEXT NOT NULL)")
    db.execSQL("INSERT INTO `draft` (`id`, `hash`, `content`) SELECT `id`, IFNULL(`hash`, ''), IFNULL(`content`, '') FROM `draft_old`")
    db.execSQL("DROP TABLE IF EXISTS `draft_old`")

    // 3. 重建 history 表
    db.execSQL("ALTER TABLE `history` RENAME TO `history_old`")
    db.execSQL("CREATE TABLE IF NOT EXISTS `history` (`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, `title` TEXT NOT NULL, `data` TEXT NOT NULL, `type` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `count` INTEGER NOT NULL, `extras` TEXT, `avatar` TEXT, `username` TEXT)")
    db.execSQL("INSERT INTO `history` (`id`, `title`, `data`, `type`, `timestamp`, `count`, `extras`, `avatar`, `username`) SELECT `id`, IFNULL(`title`, ''), IFNULL(`data`, ''), IFNULL(`type`, 0), IFNULL(`timestamp`, 0), IFNULL(`count`, 0), `extras`, `avatar`, `username` FROM `history_old`")
    db.execSQL("DROP TABLE IF EXISTS `history_old`")

    // 4. 重建 block 表
    db.execSQL("ALTER TABLE `block` RENAME TO `block_old`")
    db.execSQL("CREATE TABLE IF NOT EXISTS `block` (`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, `category` INTEGER NOT NULL, `type` INTEGER NOT NULL, `keywords` TEXT, `username` TEXT, `uid` TEXT, `isregex` INTEGER NOT NULL)")
    db.execSQL("INSERT INTO `block` (`id`, `category`, `type`, `keywords`, `username`, `uid`, `isregex`) SELECT `id`, IFNULL(`category`, 0), IFNULL(`type`, 0), `keywords`, `username`, `uid`, IFNULL(`isregex`, 0) FROM `block_old`")
    db.execSQL("DROP TABLE IF EXISTS `block_old`")

    // 5. 重建 topforum 表
    db.execSQL("ALTER TABLE `topforum` RENAME TO `topforum_old`")
    db.execSQL("CREATE TABLE IF NOT EXISTS `topforum` (`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, `forumid` TEXT NOT NULL)")
    db.execSQL("INSERT INTO `topforum` (`id`, `forumid`) SELECT `id`, IFNULL(`forumid`, '') FROM `topforum_old`")
    db.execSQL("DROP TABLE IF EXISTS `topforum_old`")

    // 6. 重建 searchhistory 表
    db.execSQL("ALTER TABLE `searchhistory` RENAME TO `searchhistory_old`")
    db.execSQL("CREATE TABLE IF NOT EXISTS `searchhistory` (`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, `content` TEXT NOT NULL, `timestamp` INTEGER NOT NULL)")
    db.execSQL("INSERT INTO `searchhistory` (`id`, `content`, `timestamp`) SELECT `id`, IFNULL(`content`, ''), IFNULL(`timestamp`, 0) FROM `searchhistory_old`")
    db.execSQL("DROP TABLE IF EXISTS `searchhistory_old`")

    // 7. 重建 searchposthistory 表
    db.execSQL("ALTER TABLE `searchposthistory` RENAME TO `searchposthistory_old`")
    db.execSQL("CREATE TABLE IF NOT EXISTS `searchposthistory` (`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, `content` TEXT NOT NULL, `forumname` TEXT NOT NULL, `timestamp` INTEGER NOT NULL)")
    db.execSQL("INSERT INTO `searchposthistory` (`id`, `content`, `forumname`, `timestamp`) SELECT `id`, IFNULL(`content`, ''), IFNULL(`forumname`, ''), IFNULL(`timestamp`, 0) FROM `searchposthistory_old`")
    db.execSQL("DROP TABLE IF EXISTS `searchposthistory_old`")
}

/** 需要补 `owner_uid` 的六张表（2026-09-26 起按账号隔离）。 */
private val OWNER_UID_TABLES = listOf(
    "history", "topforum", "block", "draft", "searchhistory", "searchposthistory",
)

/**
 * 42 = 41 + 六张"设备级偏好"表的**账号维度**（2026-09-26；产品决策：切号后各看各的
 * 历史/草稿/黑名单/置顶吧/两种搜索历史）。
 *
 * 迁移只做两件事（**不重建表**，比 38→39 那种"重建+搬运"风险低得多）：
 * 1. 六张表各 `ALTER TABLE ADD COLUMN owner_uid TEXT NOT NULL DEFAULT ''`，
 *    再把现有行**回填成当前登录账号**的 uid；
 * 2. `draft` 的唯一索引从 `hash` 换成 (`owner_uid`,`hash`)。
 *
 * ⚠️ **回填的 uid 取错 = 用户"历史/草稿/黑名单全丢"的表象**（数据还在，只是归到了别的账号名下、
 * 当前账号查不到）。所以 uid 在**迁移执行那一刻**解析，而不是在 `provideAppDatabase` 那一刻
 * （库是懒打开的，两次之间用户可能已经切号）。取不到（未登录 / 库里没有该行）就回填空串，
 * 那是"未登录桶"，也是合法归属。
 *
 * SQL 的 `DEFAULT ''` 必须与实体上的 `@ColumnInfo(defaultValue = "")` 一致，否则 Room 打开库时
 * 会报 "Migration didn't properly handle"（`Migration41To42Test` 用生产这一条迁移验）。
 */
internal fun migrationTo42(context: Context) = Migration(41, 42) { db ->
    val ownerUid = resolveMigrationOwnerUid(context, db)
    for (table in OWNER_UID_TABLES) {
        db.execSQL("ALTER TABLE `$table` ADD COLUMN `owner_uid` TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE `$table` SET `owner_uid` = ?", arrayOf(ownerUid))
    }
    // draft：'一 hash 一行' → '每账号一 hash 一行'。
    // 旧索引**必须 DROP**：Room 会把实际索引集合与实体声明比对，多一个旧索引同样判定迁移不合规。
    db.execSQL("DROP INDEX IF EXISTS `index_draft_hash`")
    db.execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_draft_owner_uid_hash` " +
            "ON `draft` (`owner_uid`, `hash`)"
    )
}

/**
 * 迁移执行那一刻的"当前账号 uid"；取不到返回空串（未登录桶）。
 *
 * 读 `accountData` 的 `now` 指针——与 `SessionManagerImpl.init` 同一个键、同一语义
 * （`-1` = 未登录）。库里查不到该 id 也按未登录处理，不抛。
 */
private fun resolveMigrationOwnerUid(context: Context, db: SupportSQLiteDatabase): String =
    runCatching {
        val accountId =
            context.getSharedPreferences("accountData", Context.MODE_PRIVATE).getInt("now", -1)
        if (accountId < 0) return@runCatching ""
        db.query("SELECT uid FROM account WHERE id = ?", arrayOf(accountId)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
        }
    }.getOrDefault("")

/**
 * **生产迁移链的唯一事实源**（2026-09-26）。
 *
 * 为什么要有这个函数：`@Database(version)` 一升级，任何"只注册了某一段迁移"的测试
 * 都会以 `A migration from X to Y was required but not found` 挂掉——41 落地时就实测到了
 * （`Migration39To40Test` 只注册 39→40，而库声明 41，打开即抛）。
 * 迁移测试必须引用**生产这一份链**，而不是各自拼自己那段：这样以后再加版本，
 * 老迁移测试自动跟着链走，不会因为"没同步更新测试"而假红。
 *
 * 带 `context` 参数是因为 41→42 要在迁移执行时读 `accountData` 解析归属账号（见 [migrationTo42]）。
 */
internal fun allMigrations(context: Context): Array<Migration> = arrayOf(
    MIGRATION_38_39,
    MIGRATION_39_40,
    MIGRATION_40_41,
    migrationTo42(context),
)

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(context, AppDatabase::class.java, "tblite.db")
            .addMigrations(*allMigrations(context))
            .build()
    }

    @Provides
    fun provideAccountDao(db: AppDatabase) = db.accountDao()

    @Provides
    fun provideDraftDao(db: AppDatabase) = db.draftDao()

    @Provides
    fun provideHistoryDao(db: AppDatabase) = db.historyDao()

    @Provides
    fun provideBlockDao(db: AppDatabase) = db.blockDao()

    @Provides
    fun provideTopForumDao(db: AppDatabase) = db.topForumDao()

    @Provides
    fun provideSearchHistoryDao(db: AppDatabase) = db.searchHistoryDao()

    @Provides
    fun provideSearchPostHistoryDao(db: AppDatabase) = db.searchPostHistoryDao()

    @Provides
    fun provideFollowedForumDao(db: AppDatabase) = db.followedForumDao()
}
