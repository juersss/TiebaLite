package com.huanchengfly.tieba.post.utils

import android.content.Context
import android.os.Build
import com.huanchengfly.tieba.post.core.network.model.protos.MyAgreeOp
import com.huanchengfly.tieba.post.core.network.model.protos.OpRecord
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

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
 * 对齐标记在数据重载([rebase])时更新为当前意图——重载后的服务端基准
 * 已包含本地已确认的操作;请求失败时意图回退到对齐标记,显示计数自动回到基准。
 * 服务端权威对齐([confirm])与无记录播种([seedMissing])也会同时改写意图与标记。
 * 注意 rebase 必须是"标记=意图"而不是"清除标记",否则重载后会重复叠加 delta。
 *
 * **在途识别不变式(E2 重构,取代旧"`my != server` 即在途")**:
 * `my != server` 有两种成因,刷新时需要**完全相反**的处理——旧模型用同一条件
 * 表达,与"Ok 不写记录"组合成死锁:请求已 Ok 的记录永远停在 `my != server`,
 * 每次刷新都跳过对齐,基准已含本次操作时 displayDelta 重复叠加,±1 漂移不自愈
 * (2026-09-12 实机 S5 黄金用例实证:613→乐观614→刷新618→再刷新619)。
 * 现以显式 [OpRecord.inFlight] 区分:
 * - inFlight=true(请求在途,尚未返回):刷新跳过。新基准"含不含本次操作"未知,
 *   且随后的失败回滚依赖 server 未被污染;
 * - inFlight=false 且 my != server(请求已 Ok 收尾、基准尚未刷新):刷新对齐
 *   srv=my。服务端已记账,新基准必含本次操作。
 * 进程崩溃遗留的未决记录不会有对应的在途请求,启动加载时按"视为已确认"对齐
 * (崩溃恢复口径不变),显示计数随之自愈。
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

/** 按账号分文件的旧文件迁移墓碑键(迁移完成时写入旧文件与新账号文件各一份,防止多次迁移) */
private const val LEGACY_MIGRATED_TOMBSTONE = "migrated_per_account_v1"

/**
 * 赞踩记录文件目录（相对 `filesDir`）：`filesDir/oprecords/<accountPrefsName>`。
 *
 * **备份规则按这个目录整体排除**（见 `res/xml/data_extraction_rules.xml` 与
 * `backup_descriptor.xml` 里的 `domain="file" path="oprecords"`）——这是本次搬迁的全部目的：
 * `SharedPreferences` 目录是平铺的、规则不支持通配符，带 uid 的文件名排不掉；
 * 换成 `files/` 下的独立目录后，"排掉整个目录"一条规则就够。
 */
internal const val OP_RECORDS_DIR = "oprecords"

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

    /**
     * **单个对象**的记录流,供 Compose 定点订阅。
     *
     * 直接订阅 [records] 时,Map 是整体替换的,任意一个对象的记录变化都会发射新的 Map,
     * 于是屏幕上**所有**订阅者(信息流里每一张可见卡片)一起重组。本流用
     * `map + distinctUntilChanged` 把与本对象无关的发射过滤掉,只有该对象自身记录
     * 变化时下游才会收到新值。
     *
     * 无该对象的记录时发射 null(调用方据此回退服务端回显,与 [agreeFlag] 口径一致)。
     */
    fun recordFlow(objType: Int, id: Long): Flow<OpRecord?> {
        val k = key(objType, id)
        return records.map { it[k] }.distinctUntilChanged()
    }

    @Volatile
    private var initialized = false

    /** 内存表世代号:每次账号切换递增。异步加载回写前核对,不符即丢弃(防跨账号污染)。
     *  所有读写都在 [lock] 内或经 [lock] 协议,无需 @Volatile。 */
    private var loadGeneration = 0L

    /**
     * 测试注入的存储后端;null = 生产模式(基于 Context 现取**文件后端**)。
     */
    @Volatile
    internal var injectedStorage: OpRecordStorage? = null

    /** 生产后端按名缓存：文件读取比 SharedPreferences 贵，不能每次调用都重建（见 [storage]） */
    @Volatile
    private var backendName: String? = null

    @Volatile
    private var backend: OpRecordStorage? = null

    /**
     * 取当前账号的存储后端。
     *
     * 2026-09-26 起生产实现是 [FileBackend]（`filesDir/oprecords/<name>`）而不是
     * SharedPreferences——原因见 [FileBackend] 的 KDoc（备份规则排不掉带 uid 的 prefs 文件）。
     * 文件读取有成本，故按"当前账号的文件名"缓存一个实例；切号时 [AccountUtil.getUid]
     * 变了、名字不同，自然换新后端（旧实例被丢弃）。
     */
    private fun storage(context: Context): OpRecordStorage {
        injectedStorage?.let { return it }
        val name = accountPrefsName(AccountUtil.getUid())
        backend?.let { if (backendName == name) return it }
        synchronized(lock) {
            if (backendName != name) {
                backend = FileBackend(context.applicationContext, name)
                backendName = name
            }
            return backend!!
        }
    }

    /** 进程启动后首次使用前调用一次 */
    fun init(context: Context) {
        // check-then-act 收进锁(R7-F2):虽然当前调用点全在主线程,防御未来非主线程 init
        synchronized(lock) {
            if (initialized) return
            initialized = true
        }
        val appContext = context.applicationContext
        // 世代号在**发起线程前**捕获(而非 loadAndMerge 内部):init 与加载线程入口之间
        // 也可能发生账号切换,入口后再读就晚了
        val gen = synchronized(lock) { loadGeneration }
        // 后台全量加载(R4-F3):agree_op_records 随使用历史无界增长,冷启动主线程同步
        // 读盘+解析的成本逐年增长。加载完成前 records 为空,读侧 agreeFlag 退回服务端
        // 回显(与"无记录"行为一致);加载窗口内的内存写入由 applyLoadedRecords 合并保留
        Thread {
            // storage() 解析(PrefsBackend 构造含 getSharedPreferences 首次读盘)也在兜底内(R7-F2)
            runCatching { loadAndMerge(storage(appContext), gen) }
        }.start()
    }

    /**
     * 账号切换/退出登录后调用:内存表整体重载为当前账号(新 [storage])的持久化记录。
     * 不重载的话,上一账号的内存记录会串进新账号的界面判定(外部审查-4)。
     */
    fun onAccountSwitched(context: Context) = onAccountSwitched(storage(context))

    internal fun onAccountSwitched(st: OpRecordStorage) {
        synchronized(lock) {
            // 世代号递增:此后任何以旧世代回写的异步加载结果一律丢弃
            loadGeneration++
            val loaded = runCatching { loadAll(st) }.getOrDefault(emptyMap())
            // 切换账号收口:残留按"视为已确认"对齐+清标志(与 loadAndMerge 的崩溃
            // 恢复同一不变式)。少了这步,账号 A 被切走时留下的 inf=1 收尾缺失记录,
            // 切回 A 后 rebase 会永远跳过该对象,漂移无愈合点。
            // 已知残留:不传 skipKeys——"切走再切回"时本会话在途记录也会被一并收口
            // (取舍见 normalizeStale KDoc),与 setPending 无 uid 绑定同属既定裁定。
            val normalized = runCatching { normalizeStale(loaded, st) }
                .getOrDefault(loaded)
            _records.value = normalized
        }
    }

    /**
     * 崩溃/被杀残留的"视为已确认"收口:my!=server 对齐 srv=my、在途标志清零,
     * 写回持久层后以**回读**结果返回(保证内存与盘同构)。
     *
     * 两入口对残留的定性不同:
     * - [loadAndMerge]:异步加载窗口内**当前会话**的在途请求(setPending 同步双写)
     *   同样满足 my!=server/inf=1,不能按崩溃残留收口——经 [skipKeys] 传入内存表
     *   在途键集合跳过(详见 loadAndMerge 注释);
     * - [onAccountSwitched]:不传跳过集,全部收口。已知残留——"切走再切回"时
     *   新账号文件即原账号文件,当前会话在途记录会被一并按已确认收口,其后该请求
     *   失败时 revertPending 读到已对齐的 srv 产生恒定偏移。不跳过的收益是旧会话
     *   崩溃残留有愈合点;两者的取舍与"setPending 不做 uid 绑定"同属既定设计裁定。
     *
     * @param skipKeys 跳过收口的对象键集合(loadAndMerge 传本会话在途键,切换路径传空)
     */
    private fun normalizeStale(
        loaded: Map<String, OpRecord>,
        st: OpRecordStorage,
        skipKeys: Set<String> = emptySet(),
    ): Map<String, OpRecord> {
        val stale = mutableMapOf<String, String>()
        for ((objKey, record) in loaded) {
            // 冷启动窗口守卫:当前会话在途对象跳过收口(loadAndMerge 传入,见其注释)
            if (objKey in skipKeys) continue
            if (record.my != record.server) stale["srv_$objKey"] = record.my.name
            if (record.inFlight) stale["inf_$objKey"] = "0"
        }
        if (stale.isEmpty()) return loaded
        st.putAll(stale)
        return loadAll(st)
    }

    /**
     * 加载收口线程体。prefs 底层异常(磁盘损坏/prefs 文件竞态删除等)在脱离任何
     * CoroutineScope 的裸线程里会直接崩进程(R5-F1),这里兜底:失败保持
     * "无记录回退回显"降级,本次启动不带历史记录。
     *
     * [gen] 为发起线程捕获的世代号(默认取当前值,测试直调即可):收口时若世代
     * 已变(加载期间发生账号切换),本次结果来自旧账号 prefs,整批丢弃。
     */
    internal fun loadAndMerge(st: OpRecordStorage, gen: Long = synchronized(lock) { loadGeneration }) {
        runCatching {
            // 启动对齐:进程重启后不存在任何在途请求,持久化里残留的 my!=server 记录
            // 是上次崩溃/被杀时未能收尾的乐观更新,按"视为已确认"对齐,显示计数随之自愈。
            // 例外——冷启动窗口守卫:setPending 是"写盘+写内存"同步双写,加载窗口内
            // 当前会话在途请求留在盘上的 my!=server/inf=1 同样满足"崩溃残留"特征,若被
            // 收口把 srv 抬到 my,随后的失败回滚 revertPending 读盘得到 my=server=已赞,
            // 服务端拒绝被本地"确认"且 rebase 因 my==server 永不自愈。按内存表在途键
            // 跳过收口(合并时内存值优先,对它们本就无效果,跳过保护的是盘不被污染)。
            // onAccountSwitched 路径不传跳过集:已知残留是"切走再切回"时,本会话
            // 在途记录会被按已确认收口(取舍见 normalizeStale KDoc)。
            val sessionInFlight = _records.value.filterValues { it.inFlight }.keys
            applyLoadedRecords(normalizeStale(loadAll(st), st, sessionInFlight), gen)
        }
        // 失败即静默降级(保持"无记录回显"行为)——本模块无日志依赖,且降级本身
        // 安全无副作用;不吞掉内存中已有记录,applyLoadedRecords 是纯合并
    }

    /**
     * 异步加载收口:加载结果与加载窗口内的内存写入合并,同一 key 内存值优先
     * (内存写是更新数据,且已同步落盘,不丢)。世代不符(加载期间账号已切换)则丢弃。
     */
    internal fun applyLoadedRecords(
        loaded: Map<String, OpRecord>,
        gen: Long = synchronized(lock) { loadGeneration },
    ) {
        synchronized(lock) {
            if (gen != loadGeneration) return
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
     * 与卡片显示**同源**回退服务端回显。该回退不仅影响展示——返回值同时作为 opType
     * (0=赞/1=撤销赞)直接发请求并决定写入的 my,误判时最坏退化为差分模型引入前的
     * 旧行为,由权威码自愈,是复核确认的既定取舍;而 currentMy 服务于**意图判定**
     * (配对撤销/撤销旗标),误判会造成服务端孤儿操作,故必须直读 prefs 真值。
     * 两者差异按用途刻意设计,不是遗漏。
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
                    } ?: MyAgreeOp.NONE,
                    inFlight = st.get("inf_$objKey") == "1"
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
            server = server?.let { runCatching { MyAgreeOp.valueOf(it) }.getOrNull() } ?: MyAgreeOp.NONE,
            inFlight = st.get("inf_$k") == "1"
        )
    }

    /** 乐观更新:只改我的意图,对齐标记不动(显示计数保留乐观偏移);置在途标志 */
    fun setPending(context: Context, objType: Int, id: Long, my: MyAgreeOp) =
        setPending(storage(context), objType, id, my)

    internal fun setPending(st: OpRecordStorage, objType: Int, id: Long, my: MyAgreeOp) {
        update(st, objType, id) { it.copy(my = my, inFlight = true) }
    }

    /** 服务端确认:意图与对齐标记同时对齐 */
    fun confirm(context: Context, objType: Int, id: Long, op: MyAgreeOp) =
        confirm(storage(context), objType, id, op)

    /**
     * 无记录时跳过(同 clearInFlight/revertPending):所有生产链路的 confirm 都以
     * setPending 为前奏(Started 先于终态被消费),记录必然存在;记录缺失只可能是
     * "发起→返回"窗口内遭遇账号切换、storage() 重解析落到了新账号文件——把旧账号
     * 的权威陈述(confirm/revert 皆然)写进新账号文件就是凭空造记录,会永久屏蔽
     * 该对象在新账号下的服务端回显。旧账号文件的在途残留由切换收口
     * ([normalizeStale])按"视为已确认"对齐,无自愈黑洞。
     */
    internal fun confirm(st: OpRecordStorage, objType: Int, id: Long, op: MyAgreeOp) {
        if (st.get("my_${key(objType, id)}") == null) return
        update(st, objType, id) { it.copy(my = op, server = op, inFlight = false) }
    }

    /**
     * 请求成功收尾:清在途标志,意图与对齐标记**都不动**。
     *
     * 为什么 Ok 不直接 confirm(对齐 srv=my):列表页点完赞后、下一次刷新前,
     * 页面基准计数尚未包含本次操作,此刻对齐会把乐观偏移抵消掉(数字弹回)。
     * 清标志后记录进入"已确认但基准未刷"态:显示继续保留乐观偏移,
     * 下一次数据重载时 [rebase] 检测到非在途且 my != server,对齐 srv=my——
     * 那时新基准已包含本次操作,对齐后 delta 归零、显示恰好等于新基准。
     * 这正是旧模型死锁(S5 实机实证 ±1 漂移不自愈)的解除点。
     *
     * 无记录时跳过:收尾请求可能在"发起→返回"窗口内遭遇账号切换,storage()
     * 重解析落到了新账号文件——对新文件凭空写出 my=server=NONE 的压制记录会
     * 永久屏蔽该对象的服务端回显(与 revertPending 同守卫)。
     */
    fun clearInFlight(context: Context, objType: Int, id: Long) =
        clearInFlight(storage(context), objType, id)

    internal fun clearInFlight(st: OpRecordStorage, objType: Int, id: Long) {
        if (st.get("my_${key(objType, id)}") == null) return
        update(st, objType, id) { it.copy(inFlight = false) }
    }

    /**
     * 回退未确认的乐观更新(请求失败/被拒)。无记录时跳过(同 clearInFlight:
     * 防账号切换窗口内把收尾动作写进新账号文件,凭空造出 NONE 压制记录)。
     */
    fun revertPending(context: Context, objType: Int, id: Long) =
        revertPending(storage(context), objType, id)

    internal fun revertPending(st: OpRecordStorage, objType: Int, id: Long) {
        if (st.get("my_${key(objType, id)}") == null) return
        update(st, objType, id) { it.copy(my = it.server, inFlight = false) }
    }

    /**
     * 数据重载后调用:新基准计数已包含本地已确认的操作,
     * 对齐标记一律对齐到当前意图(必须写入 srv=my,清除标记会重复叠加 delta)。
     *
     * [keys] 为本次重载实际涉及的对象([key] 格式,即 "`${objType}_${id}`"),
     * 只对齐这些对象:全表无差别对齐会把基准从未重载的历史对象也对齐掉,
     * 导致跨对象污染(在 A 帖点赞后进 B 帖翻页,A 的记录被对齐但基准没更新,
     * 出现"图标亮着但计数对不上")。
     *
     * **在途对象跳过(E2 语义)**:`inFlight=true` 说明该对象的请求尚未返回,
     * 新基准"含不含本次操作"未知,且随后的失败回滚依赖 server 未被污染——
     * 在途对象一律不碰,等请求收尾后,下一次刷新自然完成对齐。
     * `inFlight=false` 且 `my != server`(请求已 Ok、基准未刷)则**必须**对齐:
     * 旧版此处也一并跳过,与"Ok 不写记录"组合成死锁,±1 漂移永不自愈。
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
                if (st.get("inf_$objKey") == "1") continue // 在途:请求未返回,不动基准,等收尾
                if (myOp != serverOp) {
                    writes["srv_$objKey"] = my
                    updated[objKey] = OpRecord(my = myOp, server = myOp)
                }
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
            st.putAll(
                mapOf(
                    "my_$k" to next.my.name,
                    "srv_$k" to next.server.name,
                    "inf_$k" to if (next.inFlight) "1" else "0"
                )
            )
            _records.value = _records.value + (k to next)
        }
    }

    /**
     * 仅供单测:读取当前世代号(模拟"发起异步加载前捕获")。
     * 生产代码一律通过 init 的捕获逻辑取号,不走本钩子。
     */
    internal fun currentGenerationForTest(): Long = synchronized(lock) { loadGeneration }

    /**
     * 仅供单测:注入内存存储并清空全部状态;传 null 恢复生产默认。
     * 本对象是进程级单例,用例之间若不复位会互相污染。
     */
    internal fun resetForTest(storage: OpRecordStorage?) {
        synchronized(lock) {
            injectedStorage = storage
            _records.value = emptyMap()
            loadGeneration = 0
            initialized = storage != null
        }
    }
}

/**
 * 记录表的持久化后端。键格式与 SharedPreferences 时代完全一致:
 * `my_${objType}_${id}` / `srv_${objType}_${id}`,值为 [MyAgreeOp.name];
 * `inf_${objType}_${id}` 为在途标志("1"=请求在途),历史数据无此键视为非在途。
 */
internal interface OpRecordStorage {
    fun get(key: String): String?

    /** 全部字符串键值(非字符串脏值视为不存在,与旧 loadAll 的 `v is String` 过滤一致) */
    fun all(): Map<String, String>

    /** 批量写入(生产实现对应一次 editor.apply,保持单条记录 my/srv 的原子落盘) */
    fun putAll(entries: Map<String, String>)
}

/**
 * 账号 → 记录文件名（位于 `filesDir/oprecords/`，见 [FileBackend]）。uid 为空(未登录)时
 * 回落旧单文件名 `agree_op_records`——未登录不存在赞踩操作，仅作兜底。注意迁移是"复制+墓碑",
 * 旧文件的原存量保留不清,退出最后一个账号时 [OpRecordStore.onAccountSwitched] 会把内存表
 * 重载为该文件内容(既定边界)。
 */
internal fun accountPrefsName(uid: String?): String {
    val trimmed = uid?.trim().orEmpty()
    return if (trimmed.isEmpty()) LEGACY_PREFS_NAME else "${LEGACY_PREFS_NAME}_u_$trimmed"
}

/**
 * 旧单文件(不区分账号)的存量记录迁移:全部记入 [current](迁移时刻的活跃账号),
 * 完成后在旧文件与新账号文件各写一个墓碑键,防止其他账号再次迁移同一批数据。
 * 注意迁移是"复制+墓碑",旧文件的原存量并不清除——退出最后一个账号回落该文件时,
 * 内存表会重载为这些存量(账号隔离的既定边界,见 accountPrefsName 注释)。幂等:
 * 墓碑已存在或旧文件无 my_ 记录时跳过。
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

/**
 * 生产实现（2026-09-26 起）：**普通文件**，落在 `filesDir/oprecords/<name>`。
 *
 * ### 为什么从 SharedPreferences 搬走
 *
 * 赞踩记录改为"按账号分文件"后，文件名带 uid（`agree_op_records_u_<uid>`）。而 Android 的
 * 备份规则（`fullBackupContent` / `dataExtractionRules`）**只支持写死路径、不支持通配符**——
 * 规则里只能排掉不带 uid 的旧单文件，**带 uid 的那些全都会被云备份 / 换机迁移带走**。
 * 这与规则里那句注释的本意（"赞踩本地记录与账号/设备的服务端状态绑定,换机恢复只会制造脏记录"）
 * 正好相反：换机后重新登录同一账号，旧记录会被当成本地真相，赞踩数可能短暂偏差到下次 rebase。
 *
 * `SharedPreferences` 目录是平铺的，没法只排掉其中几个文件；`files/` 下**目录可以整体排除**，
 * 所以搬到这里（见 [OP_RECORDS_DIR]）。
 *
 * ### 格式与写入
 *
 * 每行 `key=value`。值只有 [MyAgreeOp.name] 与 `"0"/"1"`，键是 `my_/srv_/inf_<objType>_<id>`，
 * 都不含换行与等号，行式存储足够。
 *
 * 写入走"**先写临时文件、再原子替换**"：直接覆写的话，写到一半被杀会留下一份半截文件，
 * 而解析是"尽力而为"的——半截文件会静默丢掉后半段记录。API 26+ 用 `Files.move(REPLACE_EXISTING)`
 * （原子替换，且在 Windows 上也可靠）；更低版本退回 `delete + renameTo`。
 *
 * 内存缓存一份，读不落盘（与 SharedPreferences 的内存缓存同口径）。
 */
internal class FileBackend(
    context: Context,
    fileName: String,
) : OpRecordStorage {
    private val dir = File(context.applicationContext.filesDir, OP_RECORDS_DIR)
    private val file = File(dir, fileName)

    /**
     * 串行化"读-改-写"。**必须声明在 [init] 之前**——Kotlin 的属性初始化器与 init 块
     * 按声明顺序执行，声明在 init 之后的话 init 里用到的就是 null（`synchronized(null)` 直接 NPE）。
     */
    private val ioLock = Any()

    @Volatile
    private var cache: Map<String, String>? = null

    init {
        if (fileName != LEGACY_PREFS_NAME) {
            synchronized(migrationLock) {
                runCatching {
                    // 1) SharedPreferences 时代的**本账号**副本 → 文件（2026-09-26 搬迁，一次性）
                    if (all().isEmpty()) migrateFromPrefsIfNeeded(context, fileName)
                    // 2) 更老的"不分账号单文件"存量 → 本账号（沿用既有迁移与墓碑口径）
                    if (all().isEmpty()) {
                        migrateLegacyStorageIfNeeded(
                            current = this,
                            legacy = PrefsBackend(context, LEGACY_PREFS_NAME),
                        )
                    }
                }
            }
        }
    }

    override fun get(key: String): String? = load()[key]

    override fun all(): Map<String, String> = load()

    override fun putAll(entries: Map<String, String>) {
        if (entries.isEmpty()) return
        synchronized(ioLock) {
            val merged = load().toMutableMap()
            merged.putAll(entries)
            writeFile(merged)
            cache = merged
        }
    }

    private fun load(): Map<String, String> {
        cache?.let { return it }
        synchronized(ioLock) {
            cache?.let { return it }
            val loaded = runCatching { readFile() }.getOrDefault(emptyMap())
            cache = loaded
            return loaded
        }
    }

    private fun readFile(): Map<String, String> {
        if (!file.isFile) return emptyMap()
        return file.readLines()
            .asSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
            }
            .toMap()
    }

    private fun writeFile(data: Map<String, String>) {
        dir.mkdirs()
        val tmp = File(dir, "${file.name}.tmp")
        tmp.writeText(data.entries.joinToString("\n") { "${it.key}=${it.value}" })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        } else {
            file.delete()
            tmp.renameTo(file)
        }
    }

    /**
     * SharedPreferences 时代（≤ 2026-09-26）的按账号文件 → [file] 的一次性搬迁。
     *
     * **顺序不可颠倒**：先落文件、再清 prefs。反过来（先清 prefs 再写文件）如果在两步之间
     * 被杀，记录就**真的丢了**；而按现在的顺序最坏只是"迁移没完成，下次启动重来"（幂等：
     * 文件已有内容就不会再读 prefs）。
     *
     * **旧文件是"删除"而不是"清空"**：`clear()` 会留下一个空的 `.xml`（实测 65 字节的空 map），
     * 它仍带着 uid 文件名、仍会被备份带走。虽然里面没数据了，但"shared_prefs 里不再有任何
     * 带 uid 的记录文件"才是这次搬迁可验证的终态。
     */
    private fun migrateFromPrefsIfNeeded(context: Context, prefsName: String) {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val existing = prefs.all.entries
            .mapNotNull { (k, v) -> if (v is String) k to v else null }
            .toMap()
        if (existing.isEmpty()) return
        putAll(existing)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // API 24+：顺带把 SharedPreferences 的内存缓存一起失效，别留一个指向已删文件的实例
            context.deleteSharedPreferences(prefsName)
        } else {
            File(File(context.applicationContext.filesDir.parentFile, "shared_prefs"), "$prefsName.xml")
                .delete()
        }
    }

    companion object {
        /** 与 [PrefsBackend] 的迁移锁分开：两者写同一份墓碑是幂等的，独立锁只为省 IO */
        private val migrationLock = Any()
    }
}
