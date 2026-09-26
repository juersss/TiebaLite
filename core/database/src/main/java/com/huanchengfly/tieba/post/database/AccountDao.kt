package com.huanchengfly.tieba.post.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.huanchengfly.tieba.post.models.database.Account
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM account ORDER BY id ASC")
    suspend fun getAll(): List<Account>

    @Query("SELECT * FROM account WHERE id = :id LIMIT 1")
    suspend fun getById(id: Int): Account?

    @Query("SELECT * FROM account WHERE uid = :uid LIMIT 1")
    suspend fun getByUid(uid: String): Account?

    @Query("SELECT * FROM account WHERE bduss = :bduss LIMIT 1")
    suspend fun getByBduss(bduss: String): Account?

    @Insert
    suspend fun insert(account: Account): Long

    @Update
    suspend fun update(account: Account)

    @Delete
    suspend fun delete(account: Account)

    @Query("DELETE FROM account WHERE uid = :uid")
    suspend fun deleteByUid(uid: String)

    @Query("SELECT * FROM account")
    fun getAllFlow(): Flow<List<Account>>

    @Transaction
    suspend fun upsertByUid(account: Account): Long {
        val existing = getByUid(account.uid)
        return if (existing != null) {
            update(account.copy(id = existing.id))
            existing.id.toLong()
        } else {
            insert(account.copy(id = 0))
        }
    }

    /**
     * 刷新专用：**只更新、绝不插入**（2026-09-26，挂账 §三-16 / §8.7 候选 4 收口）。
     *
     * 为什么不能复用 [upsertByUid]：`:oksign` 是独立进程，签到前会走
     * `SessionManager.fetchAccountFlow` 刷新账号；而主进程可能正在退出删号。
     * `upsertByUid` 在行不存在时**会 insert**，于是"退出删号 + 在途刷新"能把刚删的
     * 账号**复活**——进程内 Mutex 不跨进程，拦不住。本方法在行不存在时直接返回 false，
     * `update` 命中的是主键（行已删则影响 0 行），因此**任何时序下都不会复活账号**。
     *
     * 首次登录的落库不由本方法负责：`LoginPage` 收流后走
     * `AccountUtil.newAccount`（内部即 [upsertByUid]）。
     *
     * @return true = 命中既有行并已更新；false = 行不存在，未做任何写入
     */
    @Transaction
    suspend fun refreshByUid(account: Account): Boolean {
        val existing = getByUid(account.uid) ?: return false
        update(account.copy(id = existing.id))
        return true
    }
}
