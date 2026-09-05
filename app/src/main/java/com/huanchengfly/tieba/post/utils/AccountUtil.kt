package com.huanchengfly.tieba.post.utils

import android.content.Context
import android.webkit.CookieManager
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.api.TiebaApi
import com.huanchengfly.tieba.post.api.models.LoginBean
import com.huanchengfly.tieba.post.arch.GlobalEvent
import com.huanchengfly.tieba.post.arch.emitGlobalEvent
import com.huanchengfly.tieba.post.models.database.Account
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.zip
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.UUID

@Stable
object AccountUtil {
    const val TAG = "AccountUtil"
    // ACTION_SWITCH_ACCOUNT(09-06 删):应用内无接收者的死广播信号,外部自动化亦未使用(用户确认),
    // 按 R13 留档项处置;如未来需要对外提供切换账号事件,重新定义并加 RECEIVER_NOT_EXPORTED 守卫

    /** 登录刷新单飞锁:防冷启动与开机签到并发收集 fetchAccountFlow、交错改写共享 Account(R9-F5) */
    private val fetchAccountMutex = Mutex()

    val LocalAccount = staticCompositionLocalOf<Account?> { null }
    val AllAccounts = staticCompositionLocalOf<List<Account>> { emptyList() }

    @Composable
    fun LocalAccountProvider(content: @Composable () -> Unit) {
        val account by mutableCurrentAccountState
        val allAccounts by mutableAllAccountsState
        CompositionLocalProvider(
            LocalAccount provides account,
            AllAccounts provides allAccounts
        ) {
            content()
        }
    }

    @set:Synchronized
    private var mutableCurrentAccountState: MutableState<Account?> = mutableStateOf(null)

    private var mutableAllAccountsState: MutableState<List<Account>> = mutableStateOf(emptyList())

    val currentAccount
        get() = mutableCurrentAccountState.value

    val allAccounts: List<Account>
        get() = mutableAllAccountsState.value

    fun init(context: Context) {
        val account = runCatching {
            val loginUser =
                context.getSharedPreferences("accountData", Context.MODE_PRIVATE).getInt("now", -1)
            if (loginUser == -1) {
                null
            } else getAccountInfo(loginUser)
        }.getOrNull()
        mutableCurrentAccountState.value = account
        mutableAllAccountsState.value = runBlocking(Dispatchers.IO) { DatabaseUtil.getAllAccounts() }
    }

    @JvmStatic
    fun getLoginInfo(): Account? {
        return currentAccount
    }

    @JvmStatic
    fun <T> getAccountInfo(getter: Account.() -> T): T? {
        return currentAccount?.getter()
    }

    fun newAccount(uid: String, account: Account, callback: (Long) -> Unit) {
        // 兜底(R7-⑤,09-06 收口):裸 GlobalScope 协程体内异常默认直达 CoroutineExceptionHandler
        // 缺失路径崩进程,与 R5-F1/R7-F1 同口径静默降级
        GlobalScope.launch(Dispatchers.IO) {
            runCatching {
                val newId = DatabaseUtil.upsertAccountByUid(account)
                mutableAllAccountsState.value = DatabaseUtil.getAllAccounts()
                callback(newId)
            }
        }
    }

    private fun getAccountInfo(accountId: Int): Account {
        return runBlocking(Dispatchers.IO) { DatabaseUtil.getAccountById(accountId) } ?: Account()
    }

    @JvmStatic
    fun getAccountInfoByUid(uid: String): Account? {
        return runBlocking(Dispatchers.IO) { DatabaseUtil.getAccountByUid(uid) }
    }

    @JvmStatic
    fun getAccountInfoByBduss(bduss: String): Account {
        return runBlocking(Dispatchers.IO) { DatabaseUtil.getAccountByBduss(bduss) } ?: Account()
    }

    @JvmStatic
    fun isLoggedIn(): Boolean {
        return getLoginInfo() != null
    }

    @JvmStatic
    fun switchAccount(context: Context, id: Int): Boolean {
        val account = runCatching { getAccountInfo(id) }.getOrNull() ?: return false
        mutableCurrentAccountState.value = account
        // 兜底(R7-⑤):事件发射的下游异常不应崩在裸 GlobalScope 协程上
        GlobalScope.launch {
            runCatching { emitGlobalEvent(GlobalEvent.AccountSwitched) }
        }
        return context.getSharedPreferences("accountData", Context.MODE_PRIVATE).edit()
            .putInt("now", id).commit()
    }

    private fun updateAccount(
        account: Account,
        loginBean: LoginBean,
    ) {
        account.apply {
            uid = loginBean.user.id
            name = loginBean.user.name
            portrait = loginBean.user.portrait
            tbs = loginBean.anti.tbs
            if (uuid.isNullOrBlank()) uuid = UUID.randomUUID().toString()
        }
    }

    fun fetchAccountFlow(account: Account = getLoginInfo()!!): Flow<Account> {
        return fetchAccountFlow(account.bduss, account.sToken, account.cookie)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun fetchAccountFlow(
        bduss: String,
        sToken: String,
        cookie: String? = null
    ): Flow<Account> {
        // 单飞串行(R9-F5):冷启动与开机签到并发触发时,两个收集者会并发改写共享的
        // Account 实例(撕裂读)。Mutex 串行化收集,登录往返不再并发交错。
        return flow {
            fetchAccountMutex.withLock {
                emitAll(
                    TiebaApi.getInstance()
                        .loginFlow(bduss, sToken)
                        .zip(TiebaApi.getInstance().initNickNameFlow(bduss, sToken)) { loginBean, _ ->
                            getAccountInfoByUid(loginBean.user.id)?.apply {
                                this.bduss = bduss
                                this.sToken = sToken
                                this.cookie = cookie ?: getBdussCookie(bduss)
                                updateAccount(this, loginBean)
                            } ?: Account(
                                uid = loginBean.user.id,
                                name = loginBean.user.name,
                                bduss = bduss,
                                tbs = loginBean.anti.tbs,
                                portrait = loginBean.user.portrait,
                                sToken = sToken,
                                cookie = cookie ?: getBdussCookie(bduss),
                            )
                        }
                        .zip(SofireUtils.fetchZid()) { account, zid ->
                            account.apply { this.zid = zid }
                        }
                        .flatMapConcat { account ->
                            TiebaApi.getInstance()
                                .getUserInfoFlow(account.uid.toLong(), account.bduss, account.sToken)
                                .map { checkNotNull(it.data_?.user) }
                                .map { user ->
                                    account.apply {
                                        nameShow = user.nameShow
                                        portrait = user.portrait
                                    }
                                }
                                .catch {
                                    emit(account)
                                }
                        }
                        .onEach { account ->
                            DatabaseUtil.upsertAccountByUid(account)
                            mutableAllAccountsState.value = DatabaseUtil.getAllAccounts()
                        }
                        .flowOn(Dispatchers.IO)
                )
            }
        }
    }

    fun parseCookie(cookie: String): Map<String, String> {
        return cookie
            .split(";")
            .map { it.trim().split("=") }
            .filter { it.size > 1 }
            .associate { it.first() to it.drop(1).joinToString("=") }
    }

    @JvmStatic
    fun updateLoginInfo(cookie: String): Boolean {
        val cookies = parseCookie(cookie).mapKeys { it.key.uppercase() }
        val bduss = cookies["BDUSS"]
        val sToken = cookies["STOKEN"]
        if (bduss != null && sToken != null) {
            val account = getAccountInfoByBduss(bduss)
            runBlocking(Dispatchers.IO) {
                DatabaseUtil.updateAccount(
                    account.apply {
                        this.sToken = sToken
                        this.cookie = cookie
                    }
                )
            }
            return true
        }
        return false
    }

    fun exit(context: Context) {
        var accounts = allAccounts
        var account = getLoginInfo() ?: return
        runBlocking(Dispatchers.IO) { DatabaseUtil.deleteAccount(account) }
        CookieManager.getInstance().removeAllCookies(null)
        if (accounts.size > 1) {
            accounts = allAccounts
            account = accounts[0]
            switchAccount(context, account.id)
            Toast.makeText(context, "退出登录成功，已切换至账号 " + account.nameShow, Toast.LENGTH_SHORT).show()
            return
        }
        mutableCurrentAccountState.value = null
        context.getSharedPreferences("accountData", Context.MODE_PRIVATE).edit().clear().commit()
        Toast.makeText(context, R.string.toast_exit_account_success, Toast.LENGTH_SHORT).show()
    }

    fun getSToken(): String? {
        val account = getLoginInfo()
        return account?.sToken
    }

    fun getCookie(): String? {
        val account = getLoginInfo()
        return account?.cookie
    }

    fun getUid(): String? {
        val account = getLoginInfo()
        return account?.uid
    }

    fun getBduss(): String? {
        val account = getLoginInfo()
        return account?.bduss
    }

    @JvmStatic
    fun getBdussCookie(): String? {
        val bduss = getBduss()
        return if (bduss != null) {
            getBdussCookie(bduss)
        } else null
    }

    fun getBdussCookie(bduss: String): String {
        return "BDUSS=$bduss; Path=/; Max-Age=315360000; Domain=.baidu.com; Httponly"
    }
}
