package com.huanchengfly.tieba.post.session

import android.content.Context
import com.huanchengfly.tieba.post.models.database.Account
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 会话/账号层（结构大改 Phase 6，2026-09-17）。
 *
 * 原 `utils/AccountUtil` object 单例的会话部分整体搬迁到本接口 + 实现
 * （[com.huanchengfly.tieba.post.session.impl.SessionManagerImpl]），由 Hilt 构造注入
 * （`di/SessionModule` @Binds）。`AccountUtil` 暂保留为薄转发门面，待 6.3 逐个消费者
 * 改为构造注入后删除——与 core:data 已完成的做法同口径（那里的 `AppPreferencesUtils`
 * 门面已在 Phase 6.5 删除，全部调用点改走 `SettingsRepository`）。
 *
 * 行为红线（搬迁逐字保留，勿"顺手优化"）：
 * - [switchAccount] / [exit] 之内四个内存钩子（OpRecordStore / FollowedForumsCache /
 *   AgreeRateLimiter / OpResponseLog）与 `AccountSwitched` 事件保持 **同步直调**、
 *   顺序不变——E2 差分模型不变式依赖切换账号的时序。
 * - 账号落盘名 `accountData` / Room 账号表全量不变。
 * - [fetchAccountFlow] 的单飞 Mutex（防冷启动与开机签到并发改写共享 Account 实例）保留。
 */
interface SessionManager {

    /** 当前账号广播流：UI 用 `collectAsState` 订阅；同步读用 `.value`。 */
    val currentAccount: StateFlow<Account?>

    /** 全部账号广播流。 */
    val allAccounts: StateFlow<List<Account>>

    /** 启动期恢复会话（`App.onCreate` 调用；必须在 `OpRecordStore.init` 之前）。 */
    fun init(context: Context)

    fun getLoginInfo(): Account?

    fun <T> getAccountInfo(getter: Account.() -> T): T?

    suspend fun getAccountInfoByUid(uid: String): Account?

    fun isLoggedIn(): Boolean

    /** 同步切换账号；四钩子 + 事件 + `accountData` 落盘顺序与迁移前一致。 */
    fun switchAccount(context: Context, id: Int): Boolean

    fun newAccount(uid: String, account: Account, callback: (Long) -> Unit)

    fun fetchAccountFlow(account: Account = getLoginInfo()!!): Flow<Account>

    fun fetchAccountFlow(bduss: String, sToken: String, cookie: String? = null): Flow<Account>

    fun parseCookie(cookie: String): Map<String, String>

    /** 同步退出当前账号；钩子与事件顺序同 [switchAccount]。 */
    fun exit(context: Context)

    // ── 凭据取值（`CredentialProvider` 的实现来源；Phase 3a 之后不再直达 AccountUtil）──
    fun getSToken(): String?

    fun getCookie(): String?

    fun getUid(): String?

    fun getBduss(): String?

    fun getBdussCookie(): String?

    fun getBdussCookie(bduss: String): String

    fun getTbs(): String?

    fun setTbs(tbs: String)

    fun getZId(): String

    fun getNameShow(): String?
}