package com.huanchengfly.tieba.post.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room 迁移 41 → 42 的 JVM 测试（2026-09-26，六张表按账号隔离）。
 *
 * 这是本仓库**风险最高的一次迁移**：它把六张"设备级偏好"表（history/topforum/block/draft/
 * searchhistory/searchposthistory）加上账号维度。列加错、索引没换、尤其是**回填的 uid 取错**，
 * 用户升级后看到的现象是"**历史/草稿/黑名单全没了**"（数据其实还在，只是归到了别的账号名下）。
 *
 * 所以本测试守四件事：
 * 1. 六张表都拿到了 `owner_uid` 列，且**既有数据一行不少**；
 * 2. 既有行被回填成**当前登录账号**（用 `accountData.now` → account 表解析出来的那个 uid）——
 *    这条是本次迁移的命门，专门用一个"已登录"的夹具来验；
 * 3. 未登录（`now` 缺失/为 -1）时回填空串 = "未登录桶"，而不是崩掉或丢掉；
 * 4. `draft` 的唯一索引从 `hash` 换成 (`owner_uid`,`hash`)——旧索引必须消失，
 *    否则 Room 打开库时会因"实际索引集合与声明不符"报 "Migration didn't properly handle"。
 *
 * 与 [Migration39To40Test] 同口径：不用 `MigrationTestHelper`（它走 assets 查找），
 * 而是按 Room 导出的 41.json **原样搭出升级前的库**，再用**生产那一整条迁移链**打开。
 */
@RunWith(RobolectricTestRunner::class)
// 同 AppDatabaseDaoTest：绕开清单里 Hilt App 的初始化链（Application 未就绪时抛 NPE）
@Config(application = android.app.Application::class)
class Migration41To42Test {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun schemaFile(version: Int): File {
        val root = requireNotNull(System.getProperty("tieba.schemas.dir")) {
            "缺少 tieba.schemas.dir 系统属性——见 app/build.gradle.kts 的 testOptions"
        }
        return File(File(root, AppDatabase::class.java.name), "$version.json")
    }

    /** 按 Room 导出的 v41 schema JSON 原样搭出升级前的数据库（含 room_master_table 身份哈希） */
    private fun createDatabaseAtV41(name: String) {
        val dbJson = JSONObject(schemaFile(41).readText(Charsets.UTF_8)).getJSONObject("database")
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
            db.version = 41
        } finally {
            db.close()
        }
    }

    private fun execOnFixture(name: String, statements: String) {
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(name).path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { db ->
            // Android 的 execSQL 只允许单条语句（09-14 §9.4 实证），按分号拆开逐条执行
            statements.split(";")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { db.execSQL(it) }
        }
    }

    /** 写"当前账号指针"（v41 的库与它同代，所以夹具里也要有 account 行） */
    private fun setCurrentAccountPointer(accountId: Int) {
        context.getSharedPreferences("accountData", Context.MODE_PRIVATE)
            .edit().putInt("now", accountId).commit()
    }

    private fun openWithProductionMigrations(name: String): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*allMigrations(context))
            .allowMainThreadQueries()
            .build()

    private fun rowCount(db: AppDatabase, table: String): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst(); cursor.getInt(0)
        }

    private fun columnNames(db: AppDatabase, table: String): List<String> =
        db.openHelper.readableDatabase.query("PRAGMA table_info('$table')").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndex("name")))
            }
        }

    private fun ownerUids(db: AppDatabase, table: String): List<String> =
        db.openHelper.readableDatabase
            .query("SELECT DISTINCT owner_uid FROM $table")
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }

    private fun indexNames(db: AppDatabase, table: String): List<String> =
        db.openHelper.readableDatabase.query("PRAGMA index_list('$table')").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val i = cursor.getColumnIndex("name")
                    if (i >= 0) add(cursor.getString(i))
                }
            }
        }

    private val ownerUidTables =
        listOf("history", "topforum", "block", "draft", "searchhistory", "searchposthistory")

    /**
     * 六张表都必须有 `owner_uid` 列（与行数无关）；**有行的表**其回填值必须全等于 [expectedUid]。
     *
     * 分开断言是因为"表是空的"和"列没加上"是两件事：只看 DISTINCT 的话，空表会得到空集，
     * 反而让"列没建出来"这种情况溜过去。
     */
    private fun assertOwnerUidColumnAndBackfill(db: AppDatabase, expectedUid: String) {
        for (table in ownerUidTables) {
            assertTrue(
                "表 $table 缺 owner_uid 列——迁移没生效",
                columnNames(db, table).contains("owner_uid"),
            )
            val uids = ownerUids(db, table)
            if (uids.isEmpty()) continue // 该表夹具里本来就没有行
            assertEquals(
                "表 $table 的回填 uid 应为 '$expectedUid'" +
                    "（取错 = 用户看到'历史/草稿/黑名单全丢'）",
                listOf(expectedUid),
                uids,
            )
        }
    }

    @Test
    fun migration41To42_backfillsExistingRowsToCurrentAccount() = runBlocking {
        val name = "migration-41-42-backfill.db"
        createDatabaseAtV41(name)
        execOnFixture(
            name,
            """
            INSERT INTO account (uid, name, bduss, tbs, portrait, stoken, cookie, loadsuccess)
                VALUES ('1001', '用户甲', 'bduss-a', 'tbs-a', 'p-a', 's-a', 'c-a', 1);
            INSERT INTO history (title, data, type, timestamp, count) VALUES ('帖A', 'data-a', 1, 1, 3);
            INSERT INTO history (title, data, type, timestamp, count) VALUES ('帖B', 'data-b', 1, 2, 1);
            INSERT INTO draft (hash, content) VALUES ('h1', '草稿一');
            INSERT INTO block (category, type, keywords, isregex) VALUES (10, 0, '["广告"]', 0);
            INSERT INTO topforum (forumid) VALUES ('forum-1');
            INSERT INTO searchhistory (content, timestamp) VALUES ('斗图', 1);
            INSERT INTO searchposthistory (content, forumname, timestamp) VALUES ('猫', '吧一', 1);
            """.trimIndent(),
        )
        setCurrentAccountPointer(1) // account 表的 id=1 → uid=1001

        val db = openWithProductionMigrations(name) // 打开即迁移 + Room schema 校验
        try {
            // 数据一行不少（"迁移把历史清了"是最可怕的表现，这里钉死）
            assertEquals(2, rowCount(db, "history"))
            assertEquals(1, rowCount(db, "draft"))
            assertEquals(1, rowCount(db, "block"))
            assertEquals(1, rowCount(db, "topforum"))
            assertEquals(1, rowCount(db, "searchhistory"))
            assertEquals(1, rowCount(db, "searchposthistory"))

            assertOwnerUidColumnAndBackfill(db, "1001")

            // 迁移后按账号查得到（这是用户升级后第一眼看到的东西）。
            // 排序口径沿用 DAO 的 `timestamp DESC, count DESC` → 帖B(ts=2) 在前
            assertEquals(
                listOf("帖B", "帖A"),
                db.historyDao().getAll("1001").map { it.title },
            )
            assertEquals("草稿一", db.draftDao().getByHash("1001", "h1")?.content)
            assertEquals(listOf("forum-1"), db.topForumDao().getAll("1001").map { it.forumId })
            assertEquals(1, db.searchHistoryDao().getAll("1001").size)
            assertEquals(1, db.searchPostHistoryDao().getAll("1001").size)
            assertEquals(1, db.blockDao().getAll("1001").size)

            // 别的账号查不到（隔离真的生效）
            assertEquals(0, db.historyDao().getAll("2002").size)
            assertEquals(0, db.blockDao().getAll("2002").size)
        } finally {
            db.close()
        }
    }

    @Test
    fun migration41To42_withoutLoggedInAccountBackfillsToEmptyBucket() = runBlocking {
        val name = "migration-41-42-no-account.db"
        createDatabaseAtV41(name)
        execOnFixture(
            name,
            "INSERT INTO history (title, data, type, timestamp, count) VALUES ('帖A', 'data-a', 1, 1, 3)",
        )
        // 不写 accountData.now → 视为未登录

        val db = openWithProductionMigrations(name)
        try {
            assertEquals("未登录时回填空串（未登录桶），数据不能丢", 1, rowCount(db, "history"))
            assertOwnerUidColumnAndBackfill(db, "")
            assertEquals("未登录桶里查得到", 1, db.historyDao().getAll("").size)
        } finally {
            db.close()
        }
    }

    @Test
    fun migration41To42_swapsDraftUniqueIndexToOwnerUidHash() = runBlocking {
        val name = "migration-41-42-draft-index.db"
        createDatabaseAtV41(name)

        val db = openWithProductionMigrations(name)
        try {
            val indices = indexNames(db, "draft")
            assertTrue(
                "新索引 (owner_uid,hash) 必须存在——否则两个账号的同一帖子草稿会互相顶掉",
                indices.any { it.contains("index_draft_owner_uid_hash") },
            )
            assertFalse(
                "旧索引 index_draft_hash 必须消失——Room 比对实际索引集合，" +
                    "多一个旧索引同样会判迁移不合规（打不开库）",
                indices.any { it == "index_draft_hash" },
            )

            // 新索引真的按 (owner_uid,hash) 去重：同一 hash 在两个账号下可共存
            db.draftDao().upsert(
                com.huanchengfly.tieba.post.models.database.Draft(
                    hash = "h1", content = "A", ownerUid = "1001",
                )
            )
            db.draftDao().upsert(
                com.huanchengfly.tieba.post.models.database.Draft(
                    hash = "h1", content = "B", ownerUid = "2002",
                )
            )
            assertEquals(2, rowCount(db, "draft"))
        } finally {
            db.close()
        }
    }
}
