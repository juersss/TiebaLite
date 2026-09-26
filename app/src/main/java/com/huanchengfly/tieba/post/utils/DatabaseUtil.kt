package com.huanchengfly.tieba.post.utils

import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.database.AppDatabase
import com.huanchengfly.tieba.post.database.AppDatabaseEntryPoint
import com.huanchengfly.tieba.post.models.database.Account
import com.huanchengfly.tieba.post.models.database.Block
import com.huanchengfly.tieba.post.models.database.Draft
import com.huanchengfly.tieba.post.models.database.FollowedForum
import com.huanchengfly.tieba.post.models.database.History
import com.huanchengfly.tieba.post.models.database.SearchHistory
import com.huanchengfly.tieba.post.models.database.SearchPostHistory
import com.huanchengfly.tieba.post.models.database.TopForum
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object DatabaseUtil {
    /** 历史记录 upsert 串行锁(R9-F3):HistoryDao.upsert 是"查→改/插"事务,并发收集者
     *  对同一 data 快速双击进入时两个事务都查空→重复行;Mutex 串行化消除 */
    private val historyMutex = Mutex()

    /** 账号/搜索历史同型锁:SearchHistoryDao/AccountDao 的 upsert 同为"查→改/插",
     *  并发时双双查空→重复行;搜索历史的清空与写入也必须同锁,否则 DELETE 先提交、
     *  在途 INSERT 后提交,刚清空的关键词被"复活" */
    private val accountMutex = Mutex()
    private val searchHistoryMutex = Mutex()
    private val appDatabase: AppDatabase by lazy {
        EntryPointAccessors.fromApplication(
            App.INSTANCE,
            AppDatabaseEntryPoint::class.java,
        ).appDatabase()
    }

    // ── Account ─────────────────────────────────────────────────
    suspend fun getAllAccounts(): List<Account> = appDatabase.accountDao().getAll()

    suspend fun getAccountById(id: Int): Account? = appDatabase.accountDao().getById(id)

    suspend fun getAccountByUid(uid: String): Account? = appDatabase.accountDao().getByUid(uid)

    suspend fun getAccountByBduss(bduss: String): Account? = appDatabase.accountDao().getByBduss(bduss)

    suspend fun upsertAccountByUid(account: Account) =
        accountMutex.withLock { appDatabase.accountDao().upsertByUid(account) }

    /**
     * 刷新专用（**绝不 insert**）：账号行已不存在时返回 false 且不写库。
     * 跨进程说明见 [com.huanchengfly.tieba.post.database.AccountDao.refreshByUid]。
     */
    suspend fun refreshAccountByUid(account: Account): Boolean =
        accountMutex.withLock { appDatabase.accountDao().refreshByUid(account) }

    /**
     * 退出登录专用:删除与"重读剩余"必须在同一临界区。两步分开时,在途的
     * fetchAccountFlow 刷新(同为 accountMutex 持有者)可插到 delete 与 read
     * 之间,把刚删的账号混回 remaining——accountMutex 只保证单次写原子,
     * 复合语义需整体持锁。刷新路径已改为 update-only（不会复活账号），
     * 本临界区仍保留：它保证的是"删+读"这一复合语义的原子性。
     */
    suspend fun deleteAccountAndReload(account: Account): List<Account> =
        accountMutex.withLock {
            appDatabase.accountDao().delete(account)
            appDatabase.accountDao().getAll()
        }

    suspend fun updateAccount(account: Account) = appDatabase.accountDao().update(account)

    suspend fun deleteAccount(account: Account) =
        accountMutex.withLock { appDatabase.accountDao().delete(account) }

    // ── 按账号隔离的六张表：uid 在这里统一解析 ─────────────────────
    // 2026-09-26 起 history/topforum/block/draft/searchhistory/searchposthistory 按账号隔离
    // （产品决策：切号后各看各的）。uid **不暴露成参数**——调用点太多（HistoryUtil 22 处 /
    // BlockManager 11 处 / UI 若干），而语义永远是"当前账号"；在组合层解析一次，UI 零改动。
    // 需要"按指定账号"的只有关注吧，那一组本来就是显式传 uid 的。

    /** 当前账号 uid；未登录 = 空串 = "未登录桶"（合法归属，不是错误） */
    private fun ownerUid(): String = AccountUtil.getUid().orEmpty()

    /**
     * 同上，但做成 Flow：**Flow 查询必须以它 `flatMapLatest`**。
     *
     * 否则切号后，已经建好的 Flow（如历史页的 `getHistoryFlowByType`）会继续查**上一个账号**——
     * 数据库分开了、界面还显示旧账号的数据，这正是"按账号隔离"最容易被忽略的漏点。
     */
    private fun ownerUidFlow(): Flow<String> =
        AccountUtil.currentAccountFlow.map { it?.uid.orEmpty() }

    // ── History ─────────────────────────────────────────────────
    suspend fun getAllHistory(): List<History> = appDatabase.historyDao().getAll(ownerUid())

    suspend fun getHistoryByType(type: Int, pageSize: Int = 100, offset: Int = 0): List<History> =
        appDatabase.historyDao().getByType(ownerUid(), type, pageSize, offset)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getHistoryFlowByType(type: Int, pageSize: Int = 100, offset: Int = 0): Flow<List<History>> =
        ownerUidFlow().flatMapLatest { uid ->
            appDatabase.historyDao().getFlowByType(uid, type, pageSize, offset)
        }

    suspend fun upsertHistory(history: History) =
        historyMutex.withLock {
            // 归属账号由组合层统一填：调用点不必知道这件事，也就不会漏填
            appDatabase.historyDao().upsert(history.copy(ownerUid = ownerUid()))
        }

    // 删除与写入同锁:无锁 DELETE 与在途 upsert 并发时,DELETE 先提交、upsert 后提交,
    // 刚删掉的记录被"复活"(与搜索历史同型竞态)
    suspend fun deleteHistoryById(id: Long) =
        historyMutex.withLock { appDatabase.historyDao().deleteById(id) }

    suspend fun deleteAllHistory() =
        historyMutex.withLock { appDatabase.historyDao().deleteAll(ownerUid()) }

    // ── Block ───────────────────────────────────────────────────
    suspend fun getAllBlocks(): List<Block> = appDatabase.blockDao().getAll(ownerUid())

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getAllBlocksFlow(): Flow<List<Block>> =
        ownerUidFlow().flatMapLatest { uid -> appDatabase.blockDao().getAllFlow(uid) }

    suspend fun insertBlock(block: Block): Long =
        appDatabase.blockDao().insert(block.copy(ownerUid = ownerUid()))

    suspend fun deleteBlockById(id: Long) = appDatabase.blockDao().deleteById(id)

    // ── Draft ───────────────────────────────────────────────────
    suspend fun getAllDrafts(): List<Draft> = appDatabase.draftDao().getAll(ownerUid())

    suspend fun getDraft(hash: String): Draft? =
        appDatabase.draftDao().getByHash(ownerUid(), hash)

    suspend fun saveDraft(hash: String, content: String) {
        appDatabase.draftDao().upsert(
            Draft(hash = hash, content = content, ownerUid = ownerUid())
        )
    }

    suspend fun deleteDraft(hash: String) = appDatabase.draftDao().deleteByHash(ownerUid(), hash)

    // ── TopForum ────────────────────────────────────────────────
    suspend fun getTopForums(): List<TopForum> = appDatabase.topForumDao().getAll(ownerUid())

    suspend fun addTopForum(forumId: String) {
        appDatabase.topForumDao().insertOrReplace(
            TopForum(forumId = forumId, ownerUid = ownerUid())
        )
    }

    suspend fun deleteTopForum(forumId: String) =
        appDatabase.topForumDao().deleteByForumId(ownerUid(), forumId)

    // ── SearchHistory ───────────────────────────────────────────
    suspend fun getAllSearchHistories(): List<SearchHistory> =
        appDatabase.searchHistoryDao().getAll(ownerUid())

    suspend fun saveSearchHistory(content: String) =
        searchHistoryMutex.withLock {
            appDatabase.searchHistoryDao().upsert(
                SearchHistory(content = content, ownerUid = ownerUid())
            )
        }

    suspend fun deleteSearchHistory(id: Long) =
        searchHistoryMutex.withLock { appDatabase.searchHistoryDao().deleteById(id) }

    suspend fun clearSearchHistory() =
        searchHistoryMutex.withLock { appDatabase.searchHistoryDao().deleteAll(ownerUid()) }

    // ── SearchPostHistory ───────────────────────────────────────
    suspend fun getAllSearchPostHistories(): List<SearchPostHistory> =
        appDatabase.searchPostHistoryDao().getAll(ownerUid())

    suspend fun saveSearchPostHistory(content: String, forumName: String) =
        searchHistoryMutex.withLock {
            appDatabase.searchPostHistoryDao().upsert(
                SearchPostHistory(
                    content = content,
                    forumName = forumName,
                    ownerUid = ownerUid(),
                )
            )
        }

    suspend fun deleteSearchPostHistory(id: Long) =
        searchHistoryMutex.withLock { appDatabase.searchPostHistoryDao().deleteById(id) }

    suspend fun clearSearchPostHistory() =
        searchHistoryMutex.withLock { appDatabase.searchPostHistoryDao().deleteAll(ownerUid()) }

    // ── FollowedForum（关注吧按账号持久化，2026-09-26）───────────
    // 全部带 uid：关注吧是账号私有数据，漏 uid 就串号（与 Account 的 uid 口径一致）。
    // 写侧不加 Mutex：调用方是 FollowedForumsCache 的 fire-and-forget 写穿透，
    // 语义上是"整表替换/增量覆盖"，同一 uid 的并发写最终收敛到最后一次（后写覆盖前写）。
    suspend fun getFollowedForums(uid: String): List<FollowedForum> =
        appDatabase.followedForumDao().getAll(uid)

    suspend fun replaceFollowedForums(uid: String, rows: List<FollowedForum>) =
        appDatabase.followedForumDao().replaceAll(uid, rows)

    suspend fun mergeFollowedForums(uid: String, rows: List<FollowedForum>) =
        appDatabase.followedForumDao().mergeAll(uid, rows)

    suspend fun deleteFollowedForum(uid: String, forumId: Long) =
        appDatabase.followedForumDao().delete(uid, forumId)
}
