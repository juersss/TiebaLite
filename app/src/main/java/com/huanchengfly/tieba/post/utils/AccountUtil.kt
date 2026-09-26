package com.huanchengfly.tieba.post.utils

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.huanchengfly.tieba.post.models.database.Account
import com.huanchengfly.tieba.post.session.SessionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 会话层**转发门面**（结构大改 Phase 6，2026-09-17）。
 *
 * 真实逻辑已整体搬入 [SessionManager]（`session/impl/SessionManagerImpl`，Hilt 构造注入）；
 * `App.onCreate` 调 [attach] 注入实例后，其余全部成员逐字转发，行为与迁移前一致。
 * 本门面是过渡件：其余消费者逐个改构造注入后删除
 * （与 core:data 已完成的做法同口径——那里的 `AppPreferencesUtils` 门面已在 Phase 6.5
 * 删除，全部调用点改走 `SettingsRepository`）。
 *
 * Compose 侧保留：`LocalAccount` / `AllAccounts` / [LocalAccountProvider] 由本门面
 * 读取 [SessionManager] 的 flow 下发，页面里的 `LocalAccount.current` 调用点零改动。
 */
@Stable
object AccountUtil {
    const val TAG = "AccountUtil"
    // ACTION_SWITCH_ACCOUNT(09-06 删):应用内无接收者的死广播信号,外部自动化亦未使用(用户确认),
    // 按 R13 留档项处置;如未来需要对外提供切换账号事件,重新定义并加 RECEIVER_NOT_EXPORTED 守卫

    val LocalAccount = staticCompositionLocalOf<Account?> { null }
    val AllAccounts = staticCompositionLocalOf<List<Account>> { emptyList() }

    @Composable
    fun LocalAccountProvider(content: @Composable () -> Unit) {
        val sessionManager = delegate
        val account by sessionManager.currentAccount.collectAsState()
        val allAccounts by sessionManager.allAccounts.collectAsState()
        CompositionLocalProvider(
            LocalAccount provides account,
            AllAccounts provides allAccounts
        ) {
            content()
        }
    }

    val currentAccount: Account?
        get() = delegate.currentAccount.value

    val allAccounts: List<Account>
        get() = delegate.allAccounts.value

    /**
     * 会话账号的 **flow**（2026-09-26 新增）。
     *
     * 给 `DatabaseUtil` 的按账号 Flow 查询做 `flatMapLatest` 的 key 用：
     * 六张"设备级偏好"表改成按账号隔离后，已经建好的 Flow 必须能在切号时重订阅，
     * 否则数据库分开了、界面还显示上一个账号的数据。
     */
    val currentAccountFlow: StateFlow<Account?>
        get() = delegate.currentAccount

    /** Hilt 注入的 [SessionManager]；`App.onCreate` 经 [attach] 安装，此前不可触达。 */
    @Volatile
    private var delegateValue: SessionManager? = null

    var delegate: SessionManager
        get() = checkNotNull(delegateValue) {
            "AccountUtil 未 attach：需在 App.onCreate 调用 AccountUtil.attach(sessionManager)"
        }
        private set(value) {
            delegateValue = value
        }

    fun attach(sessionManager: SessionManager) {
        delegateValue = sessionManager
    }

    /** 启动期恢复会话——转发 [SessionManager.init]（必须在 `OpRecordStore.init` 之前）。 */
    fun init(context: Context) {
        delegate.init(context)
    }

    @JvmStatic
    fun getLoginInfo(): Account? {
        return delegate.getLoginInfo()
    }

    @JvmStatic
    fun <T> getAccountInfo(getter: Account.() -> T): T? {
        return delegate.getAccountInfo(getter)
    }

    @JvmStatic
    suspend fun getAccountInfoByUid(uid: String): Account? = delegate.getAccountInfoByUid(uid)

    @JvmStatic
    fun isLoggedIn(): Boolean {
        return delegate.isLoggedIn()
    }

    @JvmStatic
    fun switchAccount(context: Context, id: Int): Boolean {
        return delegate.switchAccount(context, id)
    }

    fun newAccount(uid: String, account: Account, callback: (Long) -> Unit) {
        delegate.newAccount(uid, account, callback)
    }

    @JvmStatic
    fun fetchAccountFlow(account: Account = getLoginInfo()!!): Flow<Account> {
        return delegate.fetchAccountFlow(account)
    }

    @JvmStatic
    fun fetchAccountFlow(
        bduss: String,
        sToken: String,
        cookie: String? = null
    ): Flow<Account> {
        return delegate.fetchAccountFlow(bduss, sToken, cookie)
    }

    fun parseCookie(cookie: String): Map<String, String> {
        return delegate.parseCookie(cookie)
    }

    fun exit(context: Context) {
        delegate.exit(context)
    }

    fun getSToken(): String? {
        return delegate.getSToken()
    }

    fun getCookie(): String? {
        return delegate.getCookie()
    }

    fun getUid(): String? {
        return delegate.getUid()
    }

    fun getBduss(): String? {
        return delegate.getBduss()
    }

    @JvmStatic
    fun getBdussCookie(): String? {
        return delegate.getBdussCookie()
    }

    fun getBdussCookie(bduss: String): String {
        return delegate.getBdussCookie(bduss)
    }
}