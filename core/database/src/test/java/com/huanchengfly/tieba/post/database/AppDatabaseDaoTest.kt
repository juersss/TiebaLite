package com.huanchengfly.tieba.post.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.huanchengfly.tieba.post.models.database.Account
import com.huanchengfly.tieba.post.models.database.Block
import com.huanchengfly.tieba.post.models.database.Draft
import com.huanchengfly.tieba.post.models.database.FollowedForum
import com.huanchengfly.tieba.post.models.database.History
import com.huanchengfly.tieba.post.models.database.SearchHistory
import com.huanchengfly.tieba.post.models.database.TopForum
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room DAO 层测试（2026-09-14 新增）。
 *
 * 跑在 JVM（Robolectric 提供平台 SQLite），因此**进入 run-tests.sh 门禁与 CI**，
 * 不需要真机/模拟器——此前本仓库 Room 层零测试，只有 prefs 形态的
 * `OpRecordRaceAndMigrationTest`。
 *
 * 只覆盖"写错就不可逆 / 语义靠约定而非类型保证"的路径，不追求 DAO 全量：
 * - [AccountDao.upsertByUid]：账号落库唯一入口（新增账号/切号/刷新资料都走它）。
 *   "同 uid 原地更新、不新增行"是账号隔离与六处账号态钩子的前提，退化会表现为
 *   切号后出现重复账号行。
 * - [DraftDao.upsert]：依赖 39→40 迁移新建的 `draft.hash` **唯一索引** 才有
 *   "一 hash 一行"语义，索引丢失会退化成草稿堆积（迁移测试在 MigrationTest 里另有覆盖）。
 * - [HistoryDao.upsert]：浏览历史 `count` 自增、新条目 count=1，是历史页排序依据。
 * - [SearchHistoryDao.upsert]：同关键词只保留一行并刷新时间戳。
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric 默认实例化清单里的 Hilt App，其初始化链走到 AppIconUtil.setIcon →
// 读设置（原为 AppPreferencesUtils.getContext，Phase 6.5 起为 core:data 的
// settingsRepository(context)）时 Application 尚未就绪抛 NPE。本类只需平台
// SQLite 与 Room，用原生 Application 绕开产品初始化链（09-14 §9.4 实证修法）。
@Config(application = android.app.Application::class)
class AppDatabaseDaoTest {

    private lateinit var db: AppDatabase

    /**
     * 两个账号 uid：六张表自 2026-09-26 起按账号隔离，所有查询都必须带 `ownerUid`。
     * 用两个值是为了让"隔离"这件事真的被断言到（只用一个的话，漏加过滤也照样绿）。
     */
    private val UID = "1001"
    private val UID_B = "2002"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun account(uid: String, name: String = "name-$uid") = Account(
        uid = uid,
        name = name,
        bduss = "bduss-$uid",
        tbs = "tbs-$uid",
        portrait = "portrait-$uid",
        sToken = "stoken-$uid",
        cookie = "cookie-$uid",
    )

    private fun rowCount(table: String): Int =
        db.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM $table")
            .use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }

    // ── account ─────────────────────────────────────────────────────────────

    @Test
    fun accountUpsertByUid_insertsWhenAbsent() = runBlocking {
        val id = db.accountDao().upsertByUid(account("1001"))

        assertTrue("新账号应拿到自增 id，实际 $id", id > 0)
        assertEquals(1, rowCount("account"))
    }

    @Test
    fun accountUpsertByUid_updatesInPlaceForSameUid() = runBlocking {
        val firstId = db.accountDao().upsertByUid(account("1001", name = "old"))
        val secondId = db.accountDao().upsertByUid(account("1001", name = "new"))

        // 同 uid 必须原地更新：id 不变、行数仍为 1。若这里变成插入，
        // 切号/刷新资料会不断堆积重复账号行（账号隔离的前提被破坏）。
        assertEquals("同 uid 应更新同一行", firstId, secondId)
        assertEquals(1, rowCount("account"))
        assertEquals("new", db.accountDao().getByUid("1001")?.name)
    }

    @Test
    fun accountUpsertByUid_doesNotTouchOtherAccounts() = runBlocking {
        db.accountDao().upsertByUid(account("1001", name = "a"))
        db.accountDao().upsertByUid(account("2002", name = "b"))
        db.accountDao().upsertByUid(account("1001", name = "a2"))

        assertEquals(listOf("1001", "2002"), db.accountDao().getAll().map { it.uid })
        assertEquals("a2", db.accountDao().getByUid("1001")?.name)
        assertEquals("b", db.accountDao().getByUid("2002")?.name)
    }

    @Test
    fun accountDeleteByUid_removesOnlyTargetAccount() = runBlocking {
        db.accountDao().upsertByUid(account("1001"))
        db.accountDao().upsertByUid(account("2002"))

        db.accountDao().deleteByUid("1001")

        assertNull(db.accountDao().getByUid("1001"))
        assertEquals(listOf("2002"), db.accountDao().getAll().map { it.uid })
    }

    @Test
    fun accountFlow_emitsFreshListAfterWrite() = runBlocking {
        val dao = db.accountDao()

        assertEquals(0, dao.getAllFlow().first().size)
        dao.upsertByUid(account("1001"))
        assertEquals(listOf("1001"), dao.getAllFlow().first().map { it.uid })
    }

    @Test
    fun accountRefreshByUid_updatesExistingRowWithoutInserting() = runBlocking {
        db.accountDao().upsertByUid(account("1001", name = "old"))

        val hit = db.accountDao().refreshByUid(account("1001", name = "new"))

        assertTrue("行存在时应命中并更新", hit)
        assertEquals(1, rowCount("account"))
        assertEquals("new", db.accountDao().getByUid("1001")?.name)
    }

    @Test
    fun accountRefreshByUid_neverResurrectsDeletedAccount() = runBlocking {
        db.accountDao().upsertByUid(account("1001"))
        db.accountDao().deleteByUid("1001")

        val hit = db.accountDao().refreshByUid(account("1001", name = "ghost"))

        // 跨进程回归防线（2026-09-26 挂账 §三-16 / §8.7 候选 4 收口）：
        // :oksign 是独立进程，签到前会刷新账号；主进程可能同时在退出删号。
        // 刷新路径必须是 update-only——把它换回 upsertByUid，本断言立刻变红
        // （行数 1、账号复活），那正是线上"退出后被在途刷新复活"的复现。
        assertFalse("行已删除时不得写入", hit)
        assertEquals(0, rowCount("account"))
        assertNull(db.accountDao().getByUid("1001"))
    }

    // ── followed_forum（关注吧按账号持久化，2026-09-26）──────────────────────

    private fun followed(uid: String, forumId: Long, name: String = "吧-$forumId") =
        FollowedForum(uid = uid, forumId = forumId, forumName = name)

    @Test
    fun followedForum_replaceAll_isIsolatedByUid() = runBlocking {
        val dao = db.followedForumDao()
        dao.replaceAll("1001", listOf(followed("1001", 1L), followed("1001", 2L)))
        dao.replaceAll("2002", listOf(followed("2002", 9L)))

        // 切号后按新 uid 整体替换：绝不能动到另一个账号的行（账号隔离）
        dao.replaceAll("1001", listOf(followed("1001", 3L)))

        assertEquals(listOf(3L), dao.getAll("1001").map { it.forumId })
        assertEquals("另一个账号的行不得被波及", listOf(9L), dao.getAll("2002").map { it.forumId })
    }

    @Test
    fun followedForum_mergeAll_doesNotDeleteRowsMissingFromBatch() = runBlocking {
        val dao = db.followedForumDao()
        // 慢路径先落全量
        dao.replaceAll("1001", (1L..5L).map { followed("1001", it) })

        // 快路径只拿到前两页（可能提前截断）→ 增量合并绝不能把后面几页删掉。
        // 这里若误用 replaceAll，count 会掉到 2 —— 正是 HomeViewModel 注释里
        // "静默丢吧会让关注状态误判" 的复现。
        dao.mergeAll("1001", listOf(followed("1001", 1L, "改名"), followed("1001", 2L)))

        assertEquals(5, dao.count("1001"))
        assertEquals("改名", dao.getAll("1001").first { it.forumId == 1L }.forumName)
    }

    @Test
    fun followedForum_replaceAllWithEmptyList_clearsOnlyThatUid() = runBlocking {
        val dao = db.followedForumDao()
        dao.replaceAll("1001", listOf(followed("1001", 1L)))
        dao.replaceAll("2002", listOf(followed("2002", 2L)))

        // 服务端确实返回"没有关注吧"是合法状态：清空该账号，但不影响别的账号
        dao.replaceAll("1001", emptyList())

        assertEquals(0, dao.count("1001"))
        assertEquals(1, dao.count("2002"))
    }

    // ── draft（39→40 唯一索引；41→42 起按账号隔离）────────────────────────────

    @Test
    fun draftUpsert_keepsSingleRowPerHash() = runBlocking {
        val dao = db.draftDao()

        dao.upsert(Draft(hash = "h1", content = "first", ownerUid = UID))
        dao.upsert(Draft(hash = "h1", content = "second", ownerUid = UID))

        assertEquals("同 hash 应被 REPLACE 为最新内容", "second", dao.getByHash(UID, "h1")?.content)
        assertEquals("(owner_uid,hash) 唯一索引保证一账号一 hash 一行", 1, rowCount("draft"))
    }

    @Test
    fun draftUpsert_sameHashInDifferentAccountsCoexist() = runBlocking {
        val dao = db.draftDao()

        // 按账号隔离的核心语义：两个账号保存**同一个帖子**的草稿互不顶掉
        // （索引仍是 unique，但键扩成了 (owner_uid, hash)）
        dao.upsert(Draft(hash = "h1", content = "A 的草稿", ownerUid = UID))
        dao.upsert(Draft(hash = "h1", content = "B 的草稿", ownerUid = UID_B))

        assertEquals(2, rowCount("draft"))
        assertEquals("A 的草稿", dao.getByHash(UID, "h1")?.content)
        assertEquals("B 的草稿", dao.getByHash(UID_B, "h1")?.content)

        dao.deleteByHash(UID, "h1")
        assertNull(dao.getByHash(UID, "h1"))
        assertEquals("删 A 的草稿不得动到 B", "B 的草稿", dao.getByHash(UID_B, "h1")?.content)
    }

    @Test
    fun draftUpsert_keepsDistinctHashesAndDeletesOne() = runBlocking {
        val dao = db.draftDao()

        dao.upsert(Draft(hash = "h1", content = "c1", ownerUid = UID))
        dao.upsert(Draft(hash = "h2", content = "c2", ownerUid = UID))
        assertEquals(2, rowCount("draft"))

        dao.deleteByHash(UID, "h1")
        assertNull(dao.getByHash(UID, "h1"))
        assertEquals("c2", dao.getByHash(UID, "h2")?.content)
    }

    // ── history（2026-09-26 起按账号隔离）──────────────────────────────────

    @Test
    fun historyUpsert_newEntryStartsAtCountOne() = runBlocking {
        val dao = db.historyDao()

        dao.upsert(History(title = "t", data = "data-1", type = 1, count = 99, ownerUid = UID))

        val stored = dao.getByData(UID, "data-1")
        assertEquals("新条目 count 应被规范化为 1", 1, stored?.count)
        assertEquals("t", stored?.title)
    }

    @Test
    fun historyUpsert_sameDataIncrementsCountAndKeepsRow() = runBlocking {
        val dao = db.historyDao()

        dao.upsert(History(title = "old", data = "data-1", type = 1, ownerUid = UID))
        dao.upsert(History(title = "new", data = "data-1", type = 1, ownerUid = UID))

        val stored = dao.getByData(UID, "data-1")
        assertEquals("同 data 再次浏览应累加访问次数", 2, stored?.count)
        assertEquals("标题应更新为最新", "new", stored?.title)
        assertEquals("不得新增行", 1, rowCount("history"))
    }

    @Test
    fun history_isIsolatedByOwnerUid() = runBlocking {
        val dao = db.historyDao()

        // 同一个帖子在两个账号下各有一条历史 —— 这正是"按账号隔离"要允许的
        // （隔离前同 data 会被判为同一条并累加 count，跨账号串在一起）
        dao.upsert(History(title = "A 看过", data = "data-1", type = 1, ownerUid = UID))
        dao.upsert(History(title = "B 看过", data = "data-1", type = 1, ownerUid = UID_B))

        assertEquals(2, rowCount("history"))
        assertEquals(listOf("A 看过"), dao.getAll(UID).map { it.title })
        assertEquals(listOf("B 看过"), dao.getAll(UID_B).map { it.title })
        assertEquals("按类型分页查询同样要按账号过滤", 1, dao.getByType(UID, 1).size)

        // 清空只作用于本账号
        dao.deleteAll(UID)
        assertEquals(0, dao.getAll(UID).size)
        assertEquals("清空 A 的历史不得动到 B", 1, dao.getAll(UID_B).size)
    }

    @Test
    fun historyDeleteById_removesOnlyThatRow() = runBlocking {
        val dao = db.historyDao()
        dao.upsert(History(title = "a", data = "data-a", type = 1, ownerUid = UID))
        dao.upsert(History(title = "b", data = "data-b", type = 1, ownerUid = UID))

        dao.deleteById(dao.getByData(UID, "data-a")!!.id)

        assertNull(dao.getByData(UID, "data-a"))
        assertEquals(1, rowCount("history"))
    }

    // ── searchhistory ──────────────────────────────────────────────────────

    @Test
    fun searchHistoryUpsert_keepsSingleRowPerContentPerAccount() = runBlocking {
        val dao = db.searchHistoryDao()

        dao.upsert(SearchHistory(content = "斗图", ownerUid = UID))
        dao.upsert(SearchHistory(content = "斗图", ownerUid = UID))
        dao.upsert(SearchHistory(content = "猫", ownerUid = UID))
        // 另一个账号可以有同一个关键词，互不顶掉
        dao.upsert(SearchHistory(content = "斗图", ownerUid = UID_B))

        assertEquals(3, rowCount("searchhistory"))
        assertEquals(2, dao.getAll(UID).size)
        assertEquals(1, dao.getAll(UID_B).size)
    }

    // ── block / topforum ───────────────────────────────────────────────────

    @Test
    fun blockInsertAndDelete_roundTrip() = runBlocking {
        val dao = db.blockDao()
        val id = dao.insert(
            Block(
                category = Block.CATEGORY_BLACK_LIST,
                type = Block.TYPE_KEYWORD,
                keywords = "[\"广告\"]",
                ownerUid = UID,
            )
        )

        assertEquals(1, dao.getAll(UID).size)
        assertEquals(1, dao.getAllFlow(UID).first().size)
        assertEquals("别的账号看不到这条", 0, dao.getAll(UID_B).size)

        dao.deleteById(id)
        assertEquals(0, dao.getAll(UID).size)
    }

    @Test
    fun topForumInsertOrReplaceAndDelete() = runBlocking {
        val dao = db.topForumDao()
        dao.insertOrReplace(TopForum(forumId = "f1", ownerUid = UID))
        dao.insertOrReplace(TopForum(forumId = "f2", ownerUid = UID))
        assertEquals(listOf("f1", "f2"), dao.getAll(UID).map { it.forumId })

        dao.deleteByForumId(UID, "f1")
        assertEquals(listOf("f2"), dao.getAll(UID).map { it.forumId })
        assertEquals("别的账号看不到这两条", 0, dao.getAll(UID_B).size)
    }
}
