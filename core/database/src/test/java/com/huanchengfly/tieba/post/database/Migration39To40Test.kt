package com.huanchengfly.tieba.post.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room 迁移 39 → 40 的 JVM 测试（2026-09-14 新增）。
 *
 * 为什么专门测这一条：`MIGRATION_39_40` 是**手写**的"重建表 + 去重 + 补唯一索引"迁移，
 * 逻辑上最容易出错也最不可逆——它把 `draft` 里同 `hash` 的多行折叠为 `MAX(id)` 一行，
 * 再建 `draft.hash` 唯一索引（v39 的 39.json 实测 `draft` 无任何索引，所以这个语义
 * **完全依赖这条迁移**）。写错的表现是：要么草稿被静默丢错行，要么草稿堆积。
 *
 * 为什么不直接用 `MigrationTestHelper`：它的 schema 走 **assets** 查找（会把 schema 塞进
 * APK 资源或要求 androidTest 资产目录），而这里用 Room 导出的 schema JSON **原样搭出升级前
 * 的库**（`createSql` 含 `${TABLE_NAME}` 占位）、再用**生产同一条** `MIGRATION_39_40` 打开——
 * 打开过程本身就会触发 Room 对迁移后 schema 的校验（`Migration didn't properly handle …`），
 * 校验通过与去重/索引断言合起来才算完整。测试因而不依赖 assets，也不需要真机/模拟器。
 */
@RunWith(RobolectricTestRunner::class)
// 同 AppDatabaseDaoTest：Robolectric 实例化清单里的 Hilt App 时，
// App.getResources → appPreferences.fontScale 走 DataStore 单例委托，
// Application 未就绪即抛 NPE。本类只需平台 SQLite 与 Room，用原生 Application。
@Config(application = android.app.Application::class)
class Migration39To40Test {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun schemaFile(version: Int): File {
        val root = requireNotNull(System.getProperty("tieba.schemas.dir")) {
            "缺少 tieba.schemas.dir 系统属性——见 app/build.gradle.kts 的 testOptions"
        }
        return File(File(root, AppDatabase::class.java.name), "$version.json")
    }

    /**
     * 按 Room 导出的 v39 schema JSON 原样搭出升级前的数据库：
     * `entities[].createSql` + `entities[].indices[].createSql` + `database.setupQueries`
     * （后者建 `room_master_table` 并写入 v39 的 identity_hash），最后把 `user_version` 置 39。
     */
    private fun createDatabaseAtV39(name: String) {
        val dbJson = JSONObject(schemaFile(39).readText(Charsets.UTF_8)).getJSONObject("database")
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
            db.version = 39
        } finally {
            db.close()
        }
    }

    /**
     * 以生产同一条**迁移链**打开：Room 会在 onUpgrade 里依次执行并校验迁移后 schema。
     *
     * 用 `allMigrations(context)`（生产那一份链）而不是只注册 `MIGRATION_39_40`：`@Database` 升到 41 后，
     * 只注册 39→40 会让打开直接抛 "A migration from 39 to 41 was required but not found"
     * （2026-09-26 实测）。本类断言的是 39→40 那一步的语义，链上多一段 40→41 不影响它们。
     */
    private fun openWithProductionMigrations(name: String): AppDatabase =
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
            // Android 的 execSQL 只允许单条语句（多语句串只会执行第一条，09-14 §9.4 实证），
            // 故按分号拆开逐条执行。fixture SQL 的值里不含分号，直接切分即可。
            statements.split(";")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { db.execSQL(it) }
        }
    }

    private fun rowCount(db: AppDatabase, table: String): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun indexNames(db: AppDatabase, table: String): List<String> =
        db.openHelper.readableDatabase.query("PRAGMA index_list('$table')").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val nameIndex = cursor.getColumnIndex("name")
                    if (nameIndex >= 0) add(cursor.getString(nameIndex))
                }
            }
        }

    /**
     * 该表上所有**唯一**索引的列组合。
     *
     * 为什么不按索引名断言：`draft` 的唯一索引在 41→42（按账号隔离）时从 `(hash)` 扩成了
     * `(owner_uid,hash)`、名字也从 `index_draft_hash` 变成 `index_draft_owner_uid_hash`。
     * 按列组合断言才能既守住"唯一性语义在"、又不在索引改名时假红。
     */
    private fun uniqueIndexColumns(db: AppDatabase, table: String): List<List<String>> {
        val raw = db.openHelper.readableDatabase
        return raw.query("PRAGMA index_list('$table')").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    if (cursor.getInt(cursor.getColumnIndex("unique")) != 1) continue
                    val indexName = cursor.getString(cursor.getColumnIndex("name"))
                    add(
                        raw.query("PRAGMA index_info('$indexName')").use { info ->
                            buildList {
                                while (info.moveToNext()) {
                                    add(info.getString(info.getColumnIndex("name")))
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    @Test
    fun migration39To40_dedupesDraftsAndAddsUniqueIndex() = runBlocking {
        val name = "migration-39-40-draft.db"
        createDatabaseAtV39(name)
        // v39 无唯一索引 → 同 hash 可以有多行（这正是迁移要收拾的历史数据）
        execOnFixture(
            name,
            "INSERT INTO draft (hash, content) VALUES ('h1','老内容'), ('h1','新内容'), ('h2','另一条')",
        )

        val db = openWithProductionMigrations(name) // 打开即迁移 + Room 校验
        try {
            val dao = db.draftDao()
            // 41→42 起草稿按账号隔离；本夹具没写 accountData.now → 迁移回填空串（未登录桶）
            assertEquals("同 hash 应保留 MAX(id) 那行", "新内容", dao.getByHash("", "h1")?.content)
            assertEquals("其他 hash 不受影响", "另一条", dao.getByHash("", "h2")?.content)
            assertEquals("三条应折叠为两行", 2, rowCount(db, "draft"))

            assertTrue(
                "draft 缺 (owner_uid,hash) 唯一索引——'同 hash 折叠为一行'的语义靠它，" +
                    "丢了会退化成草稿堆积（注意 41→42 起索引从 (hash) 扩成了 (owner_uid,hash)）",
                uniqueIndexColumns(db, "draft").contains(listOf("owner_uid", "hash")),
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun migration39To40_enforcesUniqueHashAfterwards() {
        val name = "migration-39-40-unique.db"
        createDatabaseAtV39(name)
        execOnFixture(name, "INSERT INTO draft (hash, content) VALUES ('h1','a')")

        val db = openWithProductionMigrations(name)
        try {
            // Room.databaseBuilder(...).build() 并不会打开底层库，迁移要等首次真正访问
            // 才执行（§9.4 实证：不强制打开则插入发生在迁移前，唯一索引断言假绿）。
            db.openHelper.writableDatabase
            // 迁移后唯一索引生效：绕过 DAO 直接插同 hash 必须被约束拦下
            assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
                execOnFixture(name, "INSERT INTO draft (hash, content) VALUES ('h1','b')")
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun migration39To40_preservesOtherTablesData() = runBlocking {
        val name = "migration-39-40-preserve.db"
        createDatabaseAtV39(name)
        execOnFixture(
            name,
            """
            INSERT INTO account (uid, name, bduss, tbs, portrait, stoken, cookie, loadsuccess)
                VALUES ('1001', '用户甲', 'bduss-a', 'tbs-a', 'p-a', 's-a', 'c-a', 1);
            INSERT INTO history (title, data, type, timestamp, count) VALUES ('帖A', 'data-a', 1, 1, 3);
            INSERT INTO searchhistory (content, timestamp) VALUES ('斗图', 1);
            INSERT INTO block (category, type, keywords, isregex) VALUES (10, 0, '["广告"]', 0);
            INSERT INTO topforum (forumid) VALUES ('forum-1');
            """.trimIndent(),
        )

        val db = openWithProductionMigrations(name)
        try {
            assertEquals("account 数据应原样保留", listOf("1001"), db.accountDao().getAll().map { it.uid })
            assertEquals("用户名/凭据不能被迁移截断", "用户甲", db.accountDao().getByUid("1001")?.name)
            // 下面四张表 41→42 起按账号隔离；夹具无 accountData.now → 归"未登录桶"（空串）
            assertEquals(3, db.historyDao().getByData("", "data-a")?.count)
            assertEquals(1, db.searchHistoryDao().getAll("").size)
            assertEquals(1, db.blockDao().getAll("").size)
            assertEquals(listOf("forum-1"), db.topForumDao().getAll("").map { it.forumId })
        } finally {
            db.close()
        }
    }
}
