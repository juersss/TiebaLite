package com.huanchengfly.tieba.post.utils

import android.content.Context
import com.huanchengfly.tieba.post.api.models.protos.MyAgreeOp
import com.huanchengfly.tieba.post.api.models.protos.OpRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 我的赞踩操作记录(持久层 + 全局共享状态)。
 *
 * pb 响应对"我是否赞/踩过"的回映不可靠:has_disagree 是客户端私有字段,刷新即被重置;
 * agree_type 未必回写;has_agree 甚至会被回显成 1(疑似"有过操作"的语义)。
 * 因此"我的态度"一律以本地记录为准,服务端计数仅作为基准值:
 *
 *     显示计数 = 服务端基准 diffAgreeNum + delta(myOp) - delta(baseOp)
 *
 * - [OpRecord.my]:用户当前意图(含尚未被服务端确认的乐观更新)
 * - [OpRecord.server]:基准计数所反映的操作(对齐标记)
 *
 * 对齐标记只在数据重载([rebase])时更新为当前意图——重载后的服务端基准
 * 已包含本地已确认的操作;请求失败时意图回退到对齐标记,显示计数自动回到基准。
 * 注意 rebase 必须是"标记=意图"而不是"清除标记",否则重载后会重复叠加 delta。
 *
 * **在途识别不变式(外部审查-5)**:`my != server` 当且仅当存在未决的乐观更新。
 * rebase 只对齐已对齐(my==server,即服务端已确认)的对象;在途对象一律跳过,
 * 否则刷新会把在途意图写成"服务端基准",随后请求失败时 revertPending 恢复到
 * 被污染的基准,回滚失效(复现时序:点赞→刷新 rebase→点赞被拒→停在 AGREE/AGREE)。
 * 进程崩溃遗留的未决记录不会有对应的在途请求,启动加载时按"视为已确认"对齐
 * (与旧 rebase 语义一致的崩溃恢复口径),显示计数随之自愈。
 *
 * **账号隔离(外部审查-4)**:记录按账号 UID 分文件持久化(`agree_op_records_u_<uid>`)。
 * 旧版单一文件 `agree_op_records` 不区分账号,切换账号后 A 的赞踩会被 B 误判;
 * 切换账号/退出登录时通过 [onAccountSwitched] 重载内存表。旧文件的存量记录在
 * 某账号首次落库时一次性迁移给当前活跃账号(旧格式无从区分归属,以迁移时刻的
 * 活跃账号为归属是力所能及的最优近似),迁移完成后写入墓碑键防止重复迁移。
 *
 * 记录表是进程级单例([records]),帖子页与楼中楼详情页共享同一份,
 * 任何一页的操作对另一页立即可见。
 *
 * ## 可测性
 *
 * 读写全走 [OpRecordStorage] 抽象:生产实现 [PrefsBackend] 直连 SharedPreferences,
 * 单测通过 [resetForTest] 注入内存实现后即可在纯 JVM 上验证状态机与并发。
 * 公开方法只做"Context → 存储后端"的解析再委托给 internal twin,逻辑零重复。
 */
private const val LEGACY_PREFS_NAME = "agree_op_records"

/** 按账号分文件的旧文件迁移墓碑键(写入旧文件本身,防止多次迁移) */
private const val LEGACY_MIGRATED_TOMBSTONE = "migrated_per_account_v1"

object OpRecordStore {
    /**
     * 保护"读 prefs → 变换 → 写 prefs → 更新内存表"这一整段复合操作。
     * 调用方在 Dispatchers.IO 线程池上,帖子页与楼中楼页两个 ViewModel 同时存活,
     * 无保护的 read-modify-write 会互相覆盖(丢记录,或写出 my/srv 不一致的组合)。
     * synchronized 可重入,update/rebase/seedMissing 互相嵌套调用不会死锁。
     */
    private val lock = Any()

    private val _records = MutableStateFlow<Map<String, OpRecord>>(emptyMap())

    /** 进程内共享的记录表,UI 层收集它推导点亮状态与显示计数 */
    val records: StateFlow<Map<String, OpRecord>> = _records

    @Volatile
    private var initialized = false

    /**
     * 测试注入的存储后端;null = 生产模式(每次调用基于 Context 现取
     * SharedPreferences,与引入该字段之前的行为完全一致)。
     */
    @Volatile
    internal var injectedStorage: OpRecordStorage? = null

    private fun storage(context: Context): OpRecordStorage =
        injectedStorage ?: PrefsBackend(context, accountPrefsName(AccountUtil.getUid()))

    /** 进程启动后首次使用前调用一次 */
    fun init(context: Context) {
        // check-then-act 收进锁(R7-F2):虽然当前调用点全在主线程,防御未来非主线程 init
        synchronized(lock) {
            if (initialized) return
            initialized = true
        }
        val appContext = context.applicationContext
        // 后台全量加载(R4-F3):agree_op_records 随使用历史无界增长,冷启动主线程同步
        // 读盘+解析的成本逐年增长。加载完成前 records 为空,读侧 agreeFlag 退回服务端
        // 回显(与"无记录"行为一致);加载窗口内的内存写入由 applyLoadedRecords 合并保留
        Thread {
            // storage() 解析(PrefsBackend 构造含 getSharedPreferences 首次读盘)也在兜底内(R7-F2)
            runCatching { loadAndMerge(storage(appContext)) }
        }.start()
    }

    /**
     * 账号切换/退出登录后调用:内存表整体重载为当前账号(新 [storage])的持久化记录。
     * 不重载的话,上一账号的内存记录会串进新账号的界面判定(外部审查-4)。
     */
    fun onAccountSwitched(context: Context) {
        synchronized(lock) {
            _records.value = runCatching { loadAll(storage(context)) }.getOrDefault(emptyMap())
        }
    }

    /**
     * 加载收口线程体。prefs 底层异常(磁盘损坏/prefs 文件竞态删除等)在脱离任何
     * CoroutineScope 的裸线程里会直接崩进程(R5-F1),这里兜底:失败保持
     * "无记录回退回显"降级,本次启动不带历史记录。
     */
    internal fun loadAndMerge(st: OpRecordStorage) {
        runCatching {
            val loaded = loadAll(st)
            // 启动对齐(外部审查-5):进程重启后不存在任何在途请求,持久化里残留的
            // my!=server 记录是上次崩溃/被杀时未能收尾的乐观更新,按"视为已确认"
            // 对齐(与旧 rebase 的崩溃恢复口径一致),显示计数随之自愈
            val stale = mutableMapOf<String, String>()
            for ((objKey, record) in loaded) {
                if (record.my != record.server) stale["srv_$objKey"] = record.my.name
            }
            if (stale.isNotEmpty()) st.putAll(stale)
            applyLoadedRecords(if (stale.isEmpty()) loaded else loadAll(st))
        }
        // 失败即静默降级(保持"无记录回显"行为)——本模块无日志依赖,且降级本身
        // 安全无副作用;不吞掉内存中已有记录,applyLoadedRecords 是纯合并
    }

    /**
     * 异步加载收口:加载结果与加载窗口内的内存写入合并,同一 key 内存值优先
     * (内存写是更新数据,且已同步落盘,不丢)。
     */
    internal fun applyLoadedRecords(loaded: Map<String, OpRecord>) {
        synchronized(lock) {
            _records.value = loaded + _records.value
        }
    }

    /** objType 与 AgreeParams 一致:1=楼层 2=楼中楼 3=主帖 */
    fun key(objType: Int, id: Long): String = "${objType}_$id"

    /**
     * 列表页"当前是否已赞"的统一判定(返回 1/0,喂给 Agree 意图的 hasAgree 参数)。
     *
     * 列表页旧代码直接读服务端回显 `agree.hasAgree`——该字段已知不可靠(踩过也可能回 1),
     * 会把"刚在帖子页踩过的楼"判成"已赞",再点赞就发成"取消赞"请求,还会被权威响应
     * 覆盖掉踩记录。有本地记录时一律以记录为准,无记录才回退服务端回显(旧行为)。
     *
     * 与 [currentMy] 的分工(两函数注释口径统一):本函数读内存 records,异步加载窗口内
     * 与卡片显示**同源**回退服务端回显——误判只影响展示与回显旗标,由权威码自愈,是
     * 复核确认的既定取舍;而 currentMy 服务于**意图判定**(配对撤销/撤销旗标),误判会
     * 造成服务端孤儿操作,故必须直读 prefs 真值。两者差异按用途刻意设计,不是遗漏。
     */
    fun agreeFlag(objType: Int, id: Long, serverEchoHasAgree: Int): Int {
        val record = records.value[key(objType, id)] ?: return serverEchoHasAgree
        return if (record.my == MyAgreeOp.AGREE) 1 else 0
    }

    /**
     * 意图判定专用(配对撤销/意图旗标的决策源):直读 prefs 后端而非内存镜像。
     * 异步 init 完成前内存 records 为空,若判定读内存表会把 prefs 里已有的踩判成无,
     * 配对撤销失效→服务端孤儿踩(R8 链 C);SharedPreferences 框架缓存已被异步加载
     * 触发预热,此处为纯内存查表。加载完成后与内存表恒等(update 双写同步)。
     */
    fun currentMy(context: Context, objType: Int, id: Long): MyAgreeOp =
        get(storage(context), objType, id).my

    fun loadAll(context: Context): Map<String, OpRecord> = loadAll(storage(context))

    internal fun loadAll(st: OpRecordStorage): Map<String, OpRecord> {
        val result = mutableMapOf<String, OpRecord>()
        for ((k, v) in st.all()) {
            if (k.startsWith("my_")) {
                val objKey = k.removePrefix("my_")
                val server = st.get("srv_$objKey")
                result[objKey] = OpRecord(
                    my = runCatching { MyAgreeOp.valueOf(v) }.getOrDefault(MyAgreeOp.NONE),
                    server = server?.let {
                        runCatching { MyAgreeOp.valueOf(it) }.getOrDefault(MyAgreeOp.NONE)
                    } ?: MyAgreeOp.NONE
                )
            }
        }
        return result
    }

    fun get(context: Context, objType: Int, id: Long): OpRecord =
        get(storage(context), objType, id)

    internal fun get(st: OpRecordStorage, objType: Int, id: Long): OpRecord {
        val k = key(objType, id)
        val my = st.get("my_$k")
        val server = st.get("srv_$k")
        return OpRecord(
            my = my?.let { runCatching { MyAgreeOp.valueOf(it) }.getOrNull() } ?: MyAgreeOp.NONE,
            server = server?.let { runCatching { MyAgreeOp.valueOf(it) }.getOrNull() } ?: MyAgreeOp.NONE
        )
    }

    /** 乐观更新:只改我的意图,对齐标记不动(显示计数保留乐观偏移) */
    fun setPending(context: Context, objType: Int, id: Long, my: MyAgreeOp) =
        setPending(storage(context), objType, id, my)

    internal fun setPending(st: OpRecordStorage, objType: Int, id: Long, my: MyAgreeOp) {
        update(st, objType, id) { it.copy(my = my) }
    }

    /** 服务端确认:意图与对齐标记同时对齐 */
    fun confirm(context: Context, objType: Int, id: Long, op: MyAgreeOp) =
        confirm(storage(context), objType, id, op)

    internal fun confirm(st: OpRecordStorage, objType: Int, id: Long, op: MyAgreeOp) {
        update(st, objType, id) { it.copy(my = op, server = op) }
    }

    /** 回退未确认的乐观更新(请求失败/被拒) */
    fun revertPending(context: Context, objType: Int, id: Long) =
        revertPending(storage(context), objType, id)

    internal fun revertPending(st: OpRecordStorage, objType: Int, id: Long) {
        update(st, objType, id) { it.copy(my = it.server) }
    }

    /**
     * 数据重载后调用:新基准计数已包含本地已确认的操作,
     * 对齐标记一律对齐到当前意图(必须写入 srv=my,清除标记会重复叠加 delta)。
     *
     * [keys] 为本次重载实际涉及的对象([key] 格式,即 "`${objType}_${id}`"),
     * 只对齐这些对象:全表无差别对齐会把基准从未重载的历史对象也对齐掉,
     * 导致在途请求失败后 revertPending 变空操作(计数永久偏移)、以及跨对象污染。
     *
     * **在途对象跳过(外部审查-5)**:`my != server` 说明该对象还有未决的乐观更新
     * (请求尚未返回)。重载不能证明服务端已应用在途意图,把 srv 写成 my 会让
     * 随后的 revertPending 恢复到被污染的基准,回滚失效——在途对象一律不碰,
     * 等请求 confirm/revert 收尾后,下一次刷新自然完成对齐。
     */
    fun rebase(context: Context, keys: Set<String>) = rebase(storage(context), keys)

    internal fun rebase(st: OpRecordStorage, keys: Set<String>) {
        if (keys.isEmpty()) return
        // 整段加锁:与 update 串行化,避免读到的 prefs 在写完前被另一个线程改写
        synchronized(lock) {
            val writes = mutableMapOf<String, String>()
            val updated = mutableMapOf<String, OpRecord>()
            for (objKey in keys) {
                val my = st.get("my_$objKey") ?: continue
                val server = st.get("srv_$objKey")
                val serverOp = server?.let {
                    runCatching { MyAgreeOp.valueOf(it) }.getOrDefault(MyAgreeOp.NONE)
                } ?: MyAgreeOp.NONE
                val myOp = runCatching { MyAgreeOp.valueOf(my) }.getOrDefault(MyAgreeOp.NONE)
                if (myOp != serverOp) continue // 在途:不动基准,等请求收尾
                writes["srv_$objKey"] = my
                updated[objKey] = OpRecord(my = myOp, server = myOp)
            }
            if (writes.isNotEmpty()) st.putAll(writes)
            _records.value = _records.value + updated
        }
    }

    /**
     * 批量播种:为**无记录**的对象按服务端回显写入初始记录(my=server=回显态,
     * 即 confirm 语义)。已有记录的对象一律跳过(本地此后为准)。
     *
     * 与逐条 confirm 的区别在于整批只写一次 prefs、只发射一次状态流——
     * 一页 30 楼不再产生 30 轮"写盘 + 全卡片失效"。
     * [seedsByObjKey] 为 key → 回显推断出的态度;NONE 由调用方先行过滤亦可,
     * 这里对 NONE 同样跳过(无态度不写记录)。
     */
    fun seedMissing(context: Context, seedsByObjKey: Map<String, MyAgreeOp>) =
        seedMissing(storage(context), seedsByObjKey)

    internal fun seedMissing(st: OpRecordStorage, seedsByObjKey: Map<String, MyAgreeOp>) {
        if (seedsByObjKey.isEmpty()) return
        synchronized(lock) {
            val writes = mutableMapOf<String, String>()
            val updated = mutableMapOf<String, OpRecord>()
            for ((objKey, op) in seedsByObjKey) {
                if (op == MyAgreeOp.NONE) continue
                if (st.get("my_$objKey") != null) continue // 已有记录,回显不再可信也不再覆盖
                writes["my_$objKey"] = op.name
                writes["srv_$objKey"] = op.name
                updated[objKey] = OpRecord(my = op, server = op)
            }
            if (writes.isEmpty()) return
            st.putAll(writes)
            _records.value = _records.value + updated
        }
    }

    fun update(
        context: Context,
        objType: Int,
        id: Long,
        transform: (OpRecord) -> OpRecord
    ) = update(storage(context), objType, id, transform)

    internal fun update(
        st: OpRecordStorage,
        objType: Int,
        id: Long,
        transform: (OpRecord) -> OpRecord
    ) {
        // 整段加锁:读→变换→写 prefs→更新内存表必须是原子的,否则并发 update 互相覆盖
        synchronized(lock) {
            val k = key(objType, id)
            val next = transform(get(st, objType, id))
            st.putAll(mapOf("my_$k" to next.my.name, "srv_$k" to next.server.name))
            _records.value = _records.value + (k to next)
        }
    }

    /**
     * 仅供单测:注入内存存储并清空全部状态;传 null 恢复生产默认。
     * 本对象是进程级单例,用例之间若不复位会互相污染。
     */
    internal fun resetForTest(storage: OpRecordStorage?) {
        synchronized(lock) {
            injectedStorage = storage
            _records.value = emptyMap()
            initialized = storage != null
        }
    }
}

/**
 * 记录表的持久化后端。键格式与 SharedPreferences 时代完全一致:
 * `my_${objType}_${id}` / `srv_${objType}_${id}`,值为 [MyAgreeOp.name]。
 */
internal interface OpRecordStorage {
    fun get(key: String): String?

    /** 全部字符串键值(非字符串脏值视为不存在,与旧 loadAll 的 `v is String` 过滤一致) */
    fun all(): Map<String, String>

    /** 批量写入(生产实现对应一次 editor.apply,保持单条记录 my/srv 的原子落盘) */
    fun putAll(entries: Map<String, String>)
}

/**
 * 账号 → prefs 文件名。uid 为空(未登录)时回落旧单文件——未登录不存在赞踩操作,
 * 该文件在迁移后通常为空,仅作兜底。
 */
internal fun accountPrefsName(uid: String?): String {
    val trimmed = uid?.trim().orEmpty()
    return if (trimmed.isEmpty()) LEGACY_PREFS_NAME else "${LEGACY_PREFS_NAME}_u_$trimmed"
}

/**
 * 旧单文件(不区分账号)的存量记录迁移:全部记入 [current](迁移时刻的活跃账号),
 * 完成后在旧文件写墓碑键,防止其他账号再次迁移同一批数据。幂等:墓碑已存在或
 * 旧文件无 my_ 记录时跳过。
 */
internal fun migrateLegacyStorageIfNeeded(current: OpRecordStorage, legacy: OpRecordStorage) {
    if (legacy.get(LEGACY_MIGRATED_TOMBSTONE) != null) return
    val loaded = OpRecordStore.loadAll(legacy)
    if (loaded.isEmpty()) {
        // 无存量也要落墓碑:否则每个新账号首访都重扫一遍旧文件
        legacy.putAll(mapOf(LEGACY_MIGRATED_TOMBSTONE to "1"))
        return
    }
    val writes = mutableMapOf<String, String>(LEGACY_MIGRATED_TOMBSTONE to "1")
    for ((objKey, record) in loaded) {
        writes["my_$objKey"] = record.my.name
        writes["srv_$objKey"] = record.server.name
    }
    current.putAll(writes)
    legacy.putAll(mapOf(LEGACY_MIGRATED_TOMBSTONE to "1"))
}

/** 生产实现:直连 SharedPreferences(按账号分文件) */
private class PrefsBackend(
    context: Context,
    prefsName: String,
) : OpRecordStorage {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    companion object {
        /** 串行化迁移检查,避免并发构造时双重迁移(双写幂等,这里只为省 IO) */
        private val migrationLock = Any()
    }

    init {
        if (prefsName != LEGACY_PREFS_NAME) {
            synchronized(migrationLock) {
                runCatching {
                    if (prefs.all.isEmpty()) {
                        migrateLegacyStorageIfNeeded(
                            current = this@PrefsBackend,
                            legacy = PrefsBackend(context, LEGACY_PREFS_NAME)
                        )
                    }
                }
            }
        }
    }

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun all(): Map<String, String> =
        prefs.all.entries.mapNotNull { (k, v) -> if (v is String) k to v else null }.toMap()

    override fun putAll(entries: Map<String, String>) {
        val editor = prefs.edit()
        for ((k, v) in entries) editor.putString(k, v)
        editor.apply()
    }
}
