package com.huanchengfly.tieba.post.session.impl

import android.content.Context
import android.webkit.CookieManager
import android.widget.Toast
import com.huanchengfly.tieba.post.R
import com.huanchengfly.tieba.post.arch.GlobalEvent
import com.huanchengfly.tieba.post.arch.emitGlobalEvent
import com.huanchengfly.tieba.post.api.AgreeRateLimiter
import com.huanchengfly.tieba.post.api.OpResponseLog
import com.huanchengfly.tieba.post.api.interfaces.ITiebaApi
import com.huanchengfly.tieba.post.api.models.LoginBean
import com.huanchengfly.tieba.post.core.common.AppScope
import com.huanchengfly.tieba.post.models.database.Account
import com.huanchengfly.tieba.post.session.SessionManager
import com.huanchengfly.tieba.post.utils.BlockManager
import com.huanchengfly.tieba.post.utils.DatabaseUtil
import com.huanchengfly.tieba.post.utils.FollowedForumsCache
import com.huanchengfly.tieba.post.utils.OpRecordStore
import com.huanchengfly.tieba.post.utils.SofireUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.zip
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SessionManager] 的默认实现——`utils/AccountUtil` 会话逻辑整体搬迁（Phase 6，2026-09-17）。
 *
 * 搬迁口径：**行为逐字保留**。原 object 的 Compose `MutableState` 替换为
 * [MutableStateFlow]（`.value` 同步读语义一致，另多出 flow 广播供 UI 订阅；
 * Compose 侧的 `LocalAccount`/`AllAccounts` 由 `AccountUtil.LocalAccountProvider`
 * 读本类的 flow 下发）。四钩子与事件在 [switchAccount]/[exit] 内保持同步直调的时序不变。
 */
@Singleton
class SessionManagerImpl @Inject constructor(
    // Phase 6.3：静态 TiebaApi 访问改构造注入（ITiebaApi 由 core:network 的
    // `TiebaApi` @Provides 模块提供，零新增基建）
    private val tiebaApi: ITiebaApi,
) : SessionManager {

    /** 登录刷新单飞锁:防冷启动与开机签到并发收集 fetchAccountFlow、交错改写共享 Account(R9-F5) */
    private val fetchAccountMutex = Mutex()

    override val currentAccount: MutableStateFlow<Account?> = MutableStateFlow(null)
    override val allAccounts: MutableStateFlow<List<Account>> = MutableStateFlow(emptyList())

    override fun init(context: Context) {
        val account = runCatching {
            val loginUser =
                context.getSharedPreferences("accountData", Context.MODE_PRIVATE).getInt("now", -1)
            if (loginUser == -1) {
                null
            } else getAccountInfo(loginUser)
        }.getOrNull()
        currentAccount.value = account
        allAccounts.value = runBlocking(Dispatchers.IO) { DatabaseUtil.getAllAccounts() }
        // 冷启动预载关注吧落盘副本（2026-09-26）：`isFollowed` 依赖它，
        // 不预载就要等首页慢路径全量同步（54 个串行请求）才有正确结果。
        // 异步执行、只填空不覆盖（见 FollowedForumsCache.preload）。
        FollowedForumsCache.preload(account?.uid)
    }

    override fun getLoginInfo(): Account? {
        return currentAccount.value
    }

    override fun <T> getAccountInfo(getter: Account.() -> T): T? {
        return currentAccount.value?.getter()
    }

    override fun newAccount(uid: String, account: Account, callback: (Long) -> Unit) {
        // 兜底(R7-⑤,09-06 收口):裸 GlobalScope 协程体内异常默认直达 CoroutineExceptionHandler
        // 缺失路径崩进程,与 R5-F1/R7-F1 同口径静默降级
        AppScope.launch(Dispatchers.IO) {
            runCatching {
                val newId = DatabaseUtil.upsertAccountByUid(account)
                allAccounts.value = DatabaseUtil.getAllAccounts()
                callback(newId)
            }
        }
    }

    // 主线程同步 DB 读(已审计取舍,协程治理轮):主键小表查询毫秒级,调用方为
    // 启动期与用户主动切换/退出的同步 API,转 suspend 需连带改 UI 状态机,不成比例
    private fun getAccountInfo(accountId: Int): Account? {
        // 外部审查-6:缺失的数据库行返回 null,不再构造空 Account()——空账号曾让
        // switchAccount"成功"切到不存在的账号,形成"已登录但凭据为空"的假状态
        return runBlocking(Dispatchers.IO) { DatabaseUtil.getAccountById(accountId) }
    }

    override suspend fun getAccountInfoByUid(uid: String): Account? = DatabaseUtil.getAccountByUid(uid)

    override fun isLoggedIn(): Boolean {
        return getLoginInfo() != null
    }

    override fun switchAccount(context: Context, id: Int): Boolean {
        // getAccountInfo 对缺失行返回 null(外部审查-6):runCatching 结果为 null
        // 与失败同路,拒绝切到不存在的账号
        val account = runCatching { getAccountInfo(id) }.getOrNull() ?: return false
        currentAccount.value = account
        // 赞踩记录内存表按账号重载(外部审查-4):不重载则上一账号的记录串进新账号
        OpRecordStore.onAccountSwitched(context)
        // 关注吧缓存是账号私有数据:不清则新账号的 isFollowed 判定
        // ("只看关注吧"过滤/关注状态显示)全部按上一个账号的吧单来。
        // 2026-09-26 起传入 uid：清空后立刻按新 uid 从 Room **预载**，
        // 不必等首页慢路径那次 54 请求的全量同步就有正确的关注态。
        FollowedForumsCache.onAccountSwitched(account.uid)
        // 黑/白名单的内存镜像同样按账号重载（2026-09-26 起 DB 侧已按账号隔离）
        BlockManager.onAccountSwitched()
        // 限流配额服务端按账号计,新账号不该继承旧账号的 3s/60s 误伤
        AgreeRateLimiter.onAccountSwitched()
        // 诊断日志无账号维度,切号后旧账号响应会误导 AgreeDebug 排障
        OpResponseLog.onAccountSwitched()
        // 事件发射走应用级作用域(协程治理);下游异常仍由 runCatching 兜底
        AppScope.launch {
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

    override fun fetchAccountFlow(account: Account): Flow<Account> {
        return fetchAccountFlow(account.bduss, account.sToken, account.cookie)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun fetchAccountFlow(
        bduss: String,
        sToken: String,
        cookie: String?
    ): Flow<Account> {
        // 单飞串行(R9-F5):冷启动与开机签到并发触发时,两个收集者会并发改写共享的
        // Account 实例(撕裂读)。Mutex 串行化收集,登录往返不再并发交错。
        return flow {
            fetchAccountMutex.withLock {
                emitAll(
                    tiebaApi
                        .loginFlow(bduss, sToken)
                        .zip(tiebaApi.initNickNameFlow(bduss, sToken)) { loginBean, _ ->
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
                            // uid 转换守卫:此处在 flatMapConcat transform 内求值,
                            // 抛出的异常在内层 .catch 之外、外层链也无 catch——
                            // OKSigner 等收集方会直接崩。解析失败跳过资料补全。
                            val uid = account.uid.toLongOrNull()
                            if (uid == null) {
                                flowOf(account)
                            } else {
                                tiebaApi
                                    .getUserInfoFlow(uid, account.bduss, account.sToken)
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
                        }
                        .onEach { account ->
                            // 写入口径收口(2026-09-26,挂账 §三-16 / §8.7 候选 4):
                            // 刷新路径**只 update 不 insert**。行不存在只有两种情形——
                            // (a) 首次登录:落库由 LoginPage → AccountUtil.newAccount 负责;
                            // (b) 账号刚被退出删除(可能发生在另一个进程,如 :oksign 在途刷新),
                            //     此时若照旧 upsert 就会把它**插回来**=账号复活。
                            // 详见 core:database 的 AccountDao.refreshByUid。
                            if (DatabaseUtil.refreshAccountByUid(account)) {
                                allAccounts.value = DatabaseUtil.getAllAccounts()
                            }
                        }
                        .flowOn(Dispatchers.IO)
                )
            }
        }
    }

    override fun parseCookie(cookie: String): Map<String, String> {
        return cookie
            .split(";")
            .map { it.trim().split("=") }
            .filter { it.size > 1 }
            .associate { it.first() to it.drop(1).joinToString("=") }
    }

    // 主线程同步 DB 写/读(已审计取舍,协程治理轮):用户主动退出路径,小表毫秒级;
    // 转 suspend 需连带改调用方 UI 状态机,不成比例
    override fun exit(context: Context) {
        val account = getLoginInfo() ?: return
        // 外部审查-6:删除后从数据库重新查询剩余账号并刷新状态流。旧实现重读
        // allAccounts——那只是 allAccounts 的快照,deleteAccount 不刷新它,
        // 结果拿到含已删账号的旧列表并切回已删除账号,再经 getAccountInfo 的空
        // Account() 兜底,形成"已登录但凭据为空"的假状态。
        // 删除+重读必须在同一临界区:分开时在途 fetchAccountFlow 的 upsert 可把
        // 刚删的账号复活进 remaining(见 DatabaseUtil.deleteAccountAndReload)
        val remaining = runBlocking(Dispatchers.IO) { DatabaseUtil.deleteAccountAndReload(account) }
        CookieManager.getInstance().removeAllCookies(null)
        allAccounts.value = remaining
        if (remaining.isNotEmpty()) {
            val next = remaining.first()
            switchAccount(context, next.id)
            Toast.makeText(context, "退出登录成功，已切换至账号 " + next.nameShow, Toast.LENGTH_SHORT).show()
            return
        }
        currentAccount.value = null
        context.getSharedPreferences("accountData", Context.MODE_PRIVATE).edit().clear().commit()
        // 退出最后一个账号:uid 解析为空,内存表重载为空(外部审查-4)
        OpRecordStore.onAccountSwitched(context)
        // 关注吧缓存同属已退出账号:必须一并清空(与 switchAccount 路径同钩子)。
        // 传 null = 无归属账号：缓存清空且不预载（落盘副本保留在 Room 里，
        // 该账号下次登录时 preload 直接可用——这也是落盘的价值之一）
        FollowedForumsCache.onAccountSwitched(null)
        // 退出最后一个账号同样要清掉黑名单内存镜像（否则未登录状态下仍按已退出账号的名单过滤）
        BlockManager.onAccountSwitched()
        // 限流配额与诊断日志同理,两钩子点同口径收口
        AgreeRateLimiter.onAccountSwitched()
        OpResponseLog.onAccountSwitched()
        Toast.makeText(context, R.string.toast_exit_account_success, Toast.LENGTH_SHORT).show()
    }

    override fun getSToken(): String? {
        val account = getLoginInfo()
        return account?.sToken
    }

    override fun getCookie(): String? {
        val account = getLoginInfo()
        return account?.cookie
    }

    override fun getUid(): String? {
        val account = getLoginInfo()
        return account?.uid
    }

    override fun getBduss(): String? {
        val account = getLoginInfo()
        return account?.bduss
    }

    override fun getBdussCookie(): String? {
        val bduss = getBduss()
        return if (bduss != null) {
            getBdussCookie(bduss)
        } else null
    }

    override fun getBdussCookie(bduss: String): String {
        // Secure 标记(外部审查-1):禁止 WebView 把 BDUSS 随明文 http 请求发出;
        // 域保持 .baidu.com(百度登录协议要求,无法再收窄)
        return "BDUSS=$bduss; Path=/; Max-Age=315360000; Domain=.baidu.com; Httponly; Secure"
    }

    override fun getTbs(): String? = getLoginInfo()?.tbs

    override fun setTbs(tbs: String) {
        getLoginInfo()?.tbs = tbs
    }

    override fun getZId(): String = getAccountInfo { zid }.orEmpty()

    override fun getNameShow(): String? = getAccountInfo { nameShow }
}