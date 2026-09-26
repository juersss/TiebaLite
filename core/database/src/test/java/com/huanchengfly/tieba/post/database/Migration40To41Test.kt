package com.huanchengfly.tieba.post.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.huanchengfly.tieba.post.models.database.FollowedForum
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room 迁移 40 → 41 的 JVM 测试（2026-09-26 新增，§8.7 候选 5）。
 *
 * `MIGRATION_40_41` 是**纯新增表**（`followed_forum`），不改动任何既有表——所以本测试的
 * 重点不是"数据搬运对不对"，而是两条容易出错的：
 *
 * 1. **建表 SQL 必须与 Room 生成的一致**（列名/类型/非空/复合主键/索引）。写错的典型表现
 *    是 Room 打开库时抛 `Migration didn't properly handle`，而生产上是**用户升级即崩**；
 * 2. **既有表数据一字不动**（新增表不该有任何 `ALTER`/`RENAME`/`DROP` 的副作用）。
 *
 * 与 [Migration39To40Test] 同口径：不用 `MigrationTestHelper`（它走 assets 查找），
 * 而是按 Room 导出的 40.json **原样搭出升级前的库**，再用**生产同一条** `MIGRATION_40_41`
 * 打开——打开过程本身就会触发 Room 对迁移后 schema 的校验。
 */
@RunWith(RobolectricTestRunner::class)
// 同 AppDatabaseDaoTest：Robolectric 清单里的 Hilt App 初始化链会因 Application 未就绪抛 NPE，
// 本类只需平台 SQLite 与 Room，用原生 Application 绕开。
@Config(application = android.app.Application::class)
class Migration40To41Test {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun schemaFile(version: Int): File {
        val root = requireNotNull(System.getProperty("tieba.schemas.dir")) {
            "缺少 tieba.schemas.dir 系统属性——见 app/build.gradle.kts 的 testOptions"
        }
        return File(File(root, AppDatabase::class.java.name), "$version.json")
    }

    /** 按 Room 导出的 v40 schema JSON 原样搭出升级前的数据库（含 room_master_table 身份哈希） */
    private fun createDatabaseAtV40(name: String) {
        val dbJson = JSONObject(schemaFile(40).readText(Charsets.UTF_8)).getJSONObject("database")
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        file.delete()

        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            val entities = dbJson.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    db.execSQL(
                        indices.getJSONObject(j).getString("createSql")
                            .replace("\${TABLE_NAME}", table)
                    )
                }
            }
            val setupQueries = dbJson.getJSONArray("setupQueries")
            for (i in 0 until setupQueries.length()) {
                db.execSQL(setupQueries.getString(i))
            }
            db.version = 40
        } finally {
            db.close()
        }
    }

    /** 以生产同一条**迁移链**打开：Room 在 onUpgrade 里执行迁移并校验迁移后 schema */
    private fun openWithProductionMigration(name: String): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*allMigrations(context))
            .allowMainThreadQueries()
            .build()

    private fun execOnFixture(name: String, statements: String) {
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(name).path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { db ->
            // Android 的 execSQL 只允许单条语句（多语句串只执行第一条，09-14 §9.4 实证）
            statements.split(";")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { db.execSQL(it) }
        }
    }

    private fun tableExists(db: AppDatabase, table: String): Boolean =
        db.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='$table'")
            .use { cursor -> cursor.moveToFirst(); cursor.getInt(0) > 0 }

    private fun indexNames(db: AppDatabase, table: String): List<String> =
        db.openHelper.readableDatabase.query("PRAGMA index_list('$table')").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val nameIndex = cursor.getColumnIndex("name")
                    if (nameIndex >= 0) add(cursor.getString(nameIndex))
                }
            }
        }

    @Test
    fun migration40To41_createsFollowedForumTableAndIndex() = runBlocking {
        val name = "migration-40-41-create.db"
        createDatabaseAtV40(name)

        val db = openWithProductionMigration(name) // 打开即迁移 + Room schema 校验
        try {
            assertTrue(
                "followed_forum 表没建出来——关注吧持久化整条链路会直接抛 no such table",
                tableExists(db, "followed_forum"),
            )
            assertTrue(
                "followed_forum.uid 索引缺失——按 uid 预载会全表扫（2700+ 行）",
                indexNames(db, "followed_forum").any { it.contains("index_followed_forum_uid") },
            )
            assertEquals("新表必须是空的（迁移不搬数据）", 0, db.followedForumDao().count("1001"))
        } finally {
            db.close()
        }
    }

    @Test
    fun migration40To41_preservesExistingTablesData() = runBlocking {
        val name = "migration-40-41-preserve.db"
        createDatabaseAtV40(name)
        execOnFixture(
            name,
            """
            INSERT INTO account (uid, name, bduss, tbs, portrait, stoken, cookie, loadsuccess)
                VALUES ('1001', '用户甲', 'bduss-a', 'tbs-a', 'p-a', 's-a', 'c-a', 1);
            INSERT INTO history (title, data, type, timestamp, count) VALUES ('帖A', 'data-a', 1, 1, 3);
            INSERT INTO draft (hash, content) VALUES ('h1', '草稿一');
            INSERT INTO searchhistory (content, timestamp) VALUES ('斗图', 1);
            INSERT INTO block (category, type, keywords, isregex) VALUES (10, 0, '["广告"]', 0);
            INSERT INTO topforum (forumid) VALUES ('forum-1');
            """.trimIndent(),
        )

        val db = openWithProductionMigration(name)
        try {
            assertEquals("account 应原样保留", listOf("1001"), db.accountDao().getAll().map { it.uid })
            assertEquals("凭据/用户名不能被截断", "用户甲", db.accountDao().getByUid("1001")?.name)
            // 下面四张表 41→42 起按账号隔离；夹具无 accountData.now → 归"未登录桶"（空串）
            assertEquals(3, db.historyDao().getByData("", "data-a")?.count)
            assertEquals("草稿应原样保留", "草稿一", db.draftDao().getByHash("", "h1")?.content)
            assertEquals(1, db.searchHistoryDao().getAll("").size)
            assertEquals(1, db.blockDao().getAll("").size)
            assertEquals(listOf("forum-1"), db.topForumDao().getAll("").map { it.forumId })
        } finally {
            db.close()
        }
    }

    @Test
    fun migration40To41_followedForumRoundTripAfterUpgrade() = runBlocking {
        val name = "migration-40-41-roundtrip.db"
        createDatabaseAtV40(name)

        val db = openWithProductionMigration(name)
        try {
            val dao = db.followedForumDao()
            // 迁移后的表必须真的可写可读（列名/类型写错会在这里以约束或列缺失的形式炸出来）
            dao.replaceAll(
                "1001",
                listOf(
                    FollowedForum(uid = "1001", forumId = 1L, forumName = "吧一", avatar = "a1"),
                    FollowedForum(uid = "1001", forumId = 2L, forumName = "吧二", avatar = "a2"),
                ),
            )
            assertEquals(listOf(1L, 2L), dao.getAll("1001").map { it.forumId })
            assertEquals("吧一", dao.getAll("1001").first().forumName)

            // 复合主键 (uid, forum_id) 生效：同 uid 同 forumId 再写是 REPLACE 而非新增
            dao.mergeAll("1001", listOf(FollowedForum(uid = "1001", forumId = 1L, forumName = "吧一改名")))
            assertEquals(2, dao.count("1001"))
            assertEquals("吧一改名", dao.getAll("1001").first { it.forumId == 1L }.forumName)
        } finally {
            db.close()
        }
    }
}
