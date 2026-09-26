package com.huanchengfly.tieba.post.utils

import com.huanchengfly.tieba.post.core.network.model.protos.MyAgreeOp
import com.huanchengfly.tieba.post.core.network.model.protos.OpRecord
import com.huanchengfly.tieba.post.core.network.model.protos.displayDelta
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * 差分计数模型持久层的 JVM 单测。
 *
 * [OpRecordStore] 直连 Context/SharedPreferences(Android-only),无法在 JVM 上构造。
 * 依赖 [OpRecordStore.resetForTest] 注入内存存储后,通过 internal twin 方法
 * (与公开方法共享同一套加锁实现)验证状态机与并发语义。
 *
 * 本对象是进程级单例,每个用例结束后必须复位(null = 恢复生产默认)。
 */
class OpRecordStoreTest {

    /** 内存存储:键值语义与 SharedPreferences 一致,单线程访问(调用方已加锁) */
    private class MemoryStorage : OpRecordStorage {
        val map = LinkedHashMap<String, String>()
        var putAllCount = 0
        override fun get(key: String): String? = map[key]
        override fun all(): Map<String, String> = map.toMap()
        override fun putAll(entries: Map<String, String>) {
            putAllCount++
            map.putAll(entries)
        }
    }

    private lateinit var st: MemoryStorage

    @Before
    fun setUp() {
        st = MemoryStorage()
        OpRecordStore.resetForTest(st)
    }

    @After
    fun tearDown() {
        OpRecordStore.resetForTest(null)
    }

    private fun recordOf(objType: Int, id: Long): OpRecord? =
        OpRecordStore.records.value[OpRecordStore.key(objType, id)]

    /**
     * 生产序列等价构造:setPending(Started 消费)前奏 + confirm 终态。
     * confirm 自 2026-09-13 起带无记录守卫(与 revertPending/clearInFlight 同不变式),
     * 生产链路的 confirm 必然有 pending 前奏,测试构造也必须走同一序列。
     */
    private fun seedConfirmed(objType: Int, id: Long, op: MyAgreeOp) {
        OpRecordStore.setPending(st, objType, id, op)
        OpRecordStore.confirm(st, objType, id, op)
    }

    // ---------- 状态机基础语义 ----------

    @Test
    fun setPendingKeepsServerMarkerUntouched() {
        OpRecordStore.setPending(st, 1, 100, MyAgreeOp.AGREE)
        // 乐观更新:意图=赞,基准标记仍是 NONE → displayDelta = +1;
        // E2:同时置在途标志(请求未返回前刷新跳过对齐)
        assertEquals(OpRecord(MyAgreeOp.AGREE, MyAgreeOp.NONE, inFlight = true), recordOf(1, 100))
        // 落盘键格式与 SharedPreferences 时代一致
        assertEquals("AGREE", st.map["my_1_100"])
        assertEquals("NONE", st.map["srv_1_100"])
        assertEquals("1", st.map["inf_1_100"])
    }

    @Test
    fun confirmAlignsIntentAndMarkerTogether() {
        OpRecordStore.setPending(st, 1, 100, MyAgreeOp.AGREE)
        OpRecordStore.confirm(st, 1, 100, MyAgreeOp.DISAGREE)
        // 服务端确认后 delta 归零:my 与 server 必须同时对齐(权威陈述可改写在途意图)
        assertEquals(OpRecord(MyAgreeOp.DISAGREE, MyAgreeOp.DISAGREE), recordOf(1, 100))
    }

    @Test
    fun confirmOnRecordlessObjectDoesNotCreateGhostRecord() {
        // ★ 回归(2026-09-13 审查):confirm 是最后一个无守卫的终态写入——账号切换
        // 窗口内旧账号的权威陈述("你已赞过"等)经 storage() 重解析落到新账号文件,
        // 凭空造出的记录会永久屏蔽该对象在新账号下的服务端回显
        // (与 revertPending/clearInFlight 的守卫同一不变式)
        OpRecordStore.confirm(st, 3, 557, MyAgreeOp.AGREE)
        assertNull(recordOf(3, 557))
        assertTrue(st.map.keys.none { it.endsWith("_3_557") })
    }

    @Test
    fun revertPendingRestoresIntentToAlignedMarker() {
        seedConfirmed(1, 100, MyAgreeOp.NONE)
        OpRecordStore.setPending(st, 1, 100, MyAgreeOp.AGREE)
        OpRecordStore.revertPending(st, 1, 100)
        // 请求失败:意图回退到对齐标记,乐观偏移消失
        assertEquals(OpRecord(MyAgreeOp.NONE, MyAgreeOp.NONE), recordOf(1, 100))
    }

    @Test
    fun revertPendingWithoutPendingIsNoOp() {
        seedConfirmed(1, 100, MyAgreeOp.AGREE)
        OpRecordStore.revertPending(st, 1, 100)
        // my==server 时回退是空操作,不能把已确认状态弄丢
        assertEquals(OpRecord(MyAgreeOp.AGREE, MyAgreeOp.AGREE), recordOf(1, 100))
    }

    @Test
    fun revertPendingOnRecordlessObjectDoesNotCreateGhostRecord() {
        // ★ 回归(2026-09-12 审查):收尾动作可能在"发起→返回"窗口内遭遇账号切换,
        // storage() 重解析落到新账号文件——对无记录对象凭空写 my=server=NONE 的
        // 压制记录会永久屏蔽该对象的服务端回显(与 runner onFailureAgree 的限流防护同理)。
        OpRecordStore.revertPending(st, 3, 555)
        assertNull(recordOf(3, 555))
        assertTrue(st.map.keys.none { it.endsWith("_3_555") })
    }

    @Test
    fun clearInFlightOnRecordlessObjectDoesNotCreateGhostRecord() {
        // 同 revertPending:Ok 收尾清标志同样不得凭空造记录
        OpRecordStore.clearInFlight(st, 3, 556)
        assertNull(recordOf(3, 556))
        assertTrue(st.map.keys.none { it.endsWith("_3_556") })
    }

    @Test
    fun onAccountSwitched_normalizesCrashLeftoversInTargetAccountFile() {
        // ★ 回归(2026-09-12 审查):切到的账号文件里若有上次会话崩溃/收尾缺失留下的
        // my!=server / inf=1 残留,必须按冷启动口径收口(对齐+清标志)。少了这步,
        // 该对象之后每次 rebase 都被当在途跳过,漂移连"杀进程"这个最后愈合点都没有。
        val target = MemoryStorage()
        target.map["my_1_50"] = MyAgreeOp.AGREE.name
        target.map["srv_1_50"] = MyAgreeOp.NONE.name
        target.map["inf_1_50"] = "1"

        OpRecordStore.onAccountSwitched(target)

        assertEquals(OpRecord(MyAgreeOp.AGREE, MyAgreeOp.AGREE), recordOf(1, 50))
        // 收口必须同步写回该账号文件(否则下次冷启动还要重复收口)
        assertEquals("0", target.map["inf_1_50"])
        assertEquals(MyAgreeOp.AGREE.name, target.map["srv_1_50"])
    }

    @Test
    fun loadAndMerge_skipsStaleNormalizationForCurrentSessionInFlight() {
        // ★ 回归(2026-09-13 晚):冷启动异步加载窗口内,setPending 已把当前会话的在途
        // 请求写盘(my!=server/inf=1)。加载线程迟到读到它,若按"崩溃残留"收口把 srv 抬到
        // my,随后的失败回滚 revertPending 读盘得到 my=server=已赞——服务端拒绝被本地
        // "确认",且 rebase 因 my==server 永不自愈。当前会话在途对象必须跳过收口。
        OpRecordStore.setPending(st, 1, 100, MyAgreeOp.AGREE) // 写盘+写内存(模拟窗口内点赞)
        OpRecordStore.loadAndMerge(st) // 加载线程迟到收口

        // 盘不被污染:srv 仍是 NONE,inf 仍是 1
        assertEquals("NONE", st.map["srv_1_100"])
        assertEquals("1", st.map["inf_1_100"])
        assertEquals(OpRecord(MyAgreeOp.AGREE, MyAgreeOp.NONE, inFlight = true), recordOf(1, 100))
        // 失败回滚语义完整:回滚后精确回到 NONE/NONE
        OpRecordStore.revertPending(st, 1, 100)
        assertEquals(OpRecord(MyAgreeOp.NONE, MyAgreeOp.NONE), recordOf(1, 100))
    }

    @Test
    fun updateMergesIntoRecordsInsteadOfReplacing() {
        OpRecordStore.setPending(st, 1, 100, MyAgreeOp.AGREE)
        OpRecordStore.setPending(st, 1, 101, MyAgreeOp.AGREE)
        assertEquals(2, OpRecordStore.records.value.size)

        OpRecordStore.setPending(st, 1, 101, MyAgreeOp.DISAGREE)
        // 更新单条记录不能把其他记录挤掉(map 合并,而非整体替换)
        assertEquals(2, OpRecordStore.records.value.size)
        assertEquals(MyAgreeOp.AGREE, recordOf(1, 100)?.my)
        assertEquals(MyAgreeOp.DISAGREE, recordOf(1, 101)?.my)
    }

    // ---------- §3.3 回归:rebase(keys) 只对齐本次重载的对象 ----------

    @Test
    fun rebaseScopesAlignmentToReloadedObjectsOnly() {
        // A 已确认赞(服务端基准含此操作);B 乐观切踩、请求在途
        // (E2:刷新不能确认在途操作,rebase 必须跳过 inFlight 对象)
        seedConfirmed(1, 100, MyAgreeOp.AGREE)
        OpRecordStore.setPending(st, 1, 101, MyAgreeOp.DISAGREE)

        // 只有 A 所在的对象发生了重载(刷新/翻页)
        OpRecordStore.rebase(st, setOf(OpRecordStore.key(1, 100)))

        // A:已确认,对齐保持(rebase 防御性重写 srv=my,不产生漂移)
        assertEquals(OpRecord(MyAgreeOp.AGREE, MyAgreeOp.AGREE), recordOf(1, 100))

        // B:在途(inFlight=true),rebase 不碰基准——srv 对齐到在途意图后,B 请求一旦
        // 被拒 revertPending 会回退到被污染的标记,回滚失效
        assertEquals(OpRecord(MyAgreeOp.DISAGREE, MyAgreeOp.NONE, inFlight = true), recordOf(1, 101))

        // B 请求失败回退 → 回到 NONE/NONE,不残留乐观偏移
        OpRecordStore.revertPending(st, 1, 101)
        assertEquals(OpRecord(MyAgreeOp.NONE, MyAgreeOp.NONE), recordOf(1, 101))
    }

    @Test
    fun rebaseKeepsConfirmedMarkerAlignedInsteadOfClearingIt() {
        // 文档不变量:rebase 对已确认对象是"标记=意图",不是"清除标记"。
        // 清成 NONE 会让重载后的显示计数重复叠加 delta。
        seedConfirmed(1, 100, MyAgreeOp.AGREE)
        OpRecordStore.rebase(st, setOf(OpRecordStore.key(1, 100)))

        val rec = recordOf(1, 100)
        assertEquals(MyAgreeOp.AGREE, rec?.my)
        assertEquals("标记必须对齐到意图,而不是清成 NONE", MyAgreeOp.AGREE, rec?.server)
        // 落盘一致:srv_ 保持 AGREE
        assertEquals("AGREE", st.map["srv_1_100"])
    }

    @Test
    fun rebaseSkipsKeysMissingFromStorage() {
        seedConfirmed(1, 100, MyAgreeOp.AGREE)
        OpRecordStore.rebase(st, setOf(OpRecordStore.key(1, 100), "9_999"))

        // 存储里没有的 key 直接跳过,不产生空记录
        assertNull(OpRecordStore.records.value["9_999"])
        assertEquals(1, OpRecordStore.records.value.size)
    }

    @Test
    fun rebaseWithEmptyKeysIsNoOp() {
        seedConfirmed(1, 100, MyAgreeOp.AGREE)
        OpRecordStore.rebase(st, emptySet())
        assertEquals(1, OpRecordStore.records.value.size)
    }

    @Test
    fun seedMissing_writesOnlyAbsentKeysInSingleBatch() {
        // 批量播种(替代逐条 confirm):为无记录对象按回显写入 my=server,
        // 已有记录的对象一律跳过——本地此后为准。整批只写一次 prefs。
        seedConfirmed(1, 100, MyAgreeOp.DISAGREE)
        st.putAllCount = 0

        OpRecordStore.seedMissing(
            st,
            mapOf(
                OpRecordStore.key(1, 100) to MyAgreeOp.AGREE,  // 已有记录,必须被跳过
                OpRecordStore.key(1, 200) to MyAgreeOp.AGREE,  // 新播种
                OpRecordStore.key(1, 300) to MyAgreeOp.NONE,   // 无态度不写
            )
        )

        // 已有记录不被回显覆盖
        assertEquals(OpRecord(MyAgreeOp.DISAGREE, MyAgreeOp.DISAGREE), recordOf(1, 100))
        // 新对象按回显播种,my=server(回显无乐观偏移)
        assertEquals(OpRecord(MyAgreeOp.AGREE, MyAgreeOp.AGREE), recordOf(1, 200))
        assertEquals("NONE 不写记录", null, recordOf(1, 300))
        // 落盘成对写入
        assertEquals("AGREE", st.map["my_1_200"])
        assertEquals("AGREE", st.map["srv_1_200"])
        // 整批只 putAll 一次(逐条 confirm 会写两次)
        assertEquals(1, st.putAllCount)
    }

    @Test
    fun seedMissing_nothingToSeedSkipsWrite() {
        // 全部命中已有记录/全 NONE → 不产生任何 prefs 写入与状态发射
        seedConfirmed(1, 100, MyAgreeOp.AGREE)
        st.putAllCount = 0
        OpRecordStore.seedMissing(
            st,
            mapOf(OpRecordStore.key(1, 100) to MyAgreeOp.DISAGREE)
        )
        assertEquals(0, st.putAllCount)
        assertEquals(OpRecord(MyAgreeOp.AGREE, MyAgreeOp.AGREE), recordOf(1, 100))
    }

    // ---------- 持久化往返与容错 ----------

    @Test
    fun loadAllRoundTripsWrittenRecords() {
        seedConfirmed(1, 100, MyAgreeOp.AGREE)
        OpRecordStore.setPending(st, 2, 200, MyAgreeOp.DISAGREE)

        // 从存储重新加载 = 进程重启后的状态(inFlight 标志一并往返)
        val reloaded = OpRecordStore.loadAll(st)
        assertEquals(2, reloaded.size)
        assertEquals(OpRecord(MyAgreeOp.AGREE, MyAgreeOp.AGREE), reloaded["1_100"])
        assertEquals(OpRecord(MyAgreeOp.DISAGREE, MyAgreeOp.NONE, inFlight = true), reloaded["2_200"])
    }

    @Test
    fun loadAllToleratesGarbageAndStrayKeys() {
        st.map["my_1_5"] = "GARBAGE"
        st.map["srv_1_5"] = "AGREE"
        st.map["my_2_6"] = "DISAGREE"
        st.map["stray_key"] = "AGREE"   // 不带 my_ 前缀 → 忽略

        val all = OpRecordStore.loadAll(st)
        assertEquals(2, all.size)
        // 非法枚举值容错为 NONE,不抛异常
        assertEquals(OpRecord(MyAgreeOp.NONE, MyAgreeOp.AGREE), all["1_5"])
        assertEquals(OpRecord(MyAgreeOp.DISAGREE, MyAgreeOp.NONE), all["2_6"])
        assertNull(all["stray_key"])
    }

    @Test
    fun getToleratesGarbageValues() {
        st.map["my_1_7"] = "GARBAGE"
        st.map["srv_1_7"] = "ALSO_GARBAGE"
        assertEquals(OpRecord(MyAgreeOp.NONE, MyAgreeOp.NONE), OpRecordStore.get(st, 1, 7))
    }

    // ---------- §3.4 回归:并发 read-modify-write ----------

    @Test
    fun concurrentUpdatesDoNotLoseRecords() {
        // 32 线程各写自己的 key 200 次。丢 key = _records 的 map 合并 RMW 竞态,
        // 正是 §3.4 加锁要防的"并发丢记录"。
        val threadCount = 32
        val iterations = 200
        val executor = Executors.newFixedThreadPool(threadCount)
        val startGate = CountDownLatch(1)
        val done = CountDownLatch(threadCount)

        repeat(threadCount) { t ->
            executor.submit {
                startGate.await()
                repeat(iterations) { i ->
                    OpRecordStore.setPending(
                        st, 1, t.toLong(),
                        if (i % 2 == 0) MyAgreeOp.AGREE else MyAgreeOp.DISAGREE
                    )
                }
                done.countDown()
            }
        }
        startGate.countDown()
        done.await()
        executor.shutdown()

        // 一个 key 都不能丢
        assertEquals("并发 update 不得丢 key", threadCount, OpRecordStore.records.value.size)
        // 每线程最后一次写入是 i=199(奇) → DISAGREE,确定性终态(setPending 置在途标志)
        repeat(threadCount) { t ->
            assertEquals(
                "key 1_$t 的终态",
                OpRecord(MyAgreeOp.DISAGREE, MyAgreeOp.NONE, inFlight = true),
                recordOf(1, t.toLong())
            )
        }
    }

    @Test
    fun concurrentSameKeyConfirmsStayConsistent() {
        // 同一 key 上竞争 confirm(AGREE)/confirm(DISAGREE):
        // 终态必须是某一次完整的 confirm(不变量 my==server),
        // 不允许出现 my 与 srv 来自不同请求的"撕裂"组合。
        // (confirm 带无记录守卫,先按生产序列播种——并发竞争的都是对既有记录的终态写)
        seedConfirmed(1, 42, MyAgreeOp.NONE)
        val threadCount = 32
        val iterations = 500
        val executor = Executors.newFixedThreadPool(threadCount)
        val startGate = CountDownLatch(1)
        val done = CountDownLatch(threadCount)

        repeat(threadCount) { t ->
            executor.submit {
                startGate.await()
                repeat(iterations) { i ->
                    OpRecordStore.confirm(
                        st, 1, 42L,
                        if ((t + i) % 2 == 0) MyAgreeOp.AGREE else MyAgreeOp.DISAGREE
                    )
                }
                done.countDown()
            }
        }
        startGate.countDown()
        done.await()
        executor.shutdown()

        val rec = recordOf(1, 42)
        assertEquals("confirm 的不变量:my 与 server 必须来自同一次写入", rec?.my, rec?.server)
        assertTrue(
            "终态必须是合法的确认值",
            rec?.my == MyAgreeOp.AGREE || rec?.my == MyAgreeOp.DISAGREE
        )
        // 落盘与内存一致
        assertEquals(rec?.my?.name, st.map["my_1_42"])
        assertEquals(rec?.server?.name, st.map["srv_1_42"])
    }

    // ---------- 异步 init 的加载收口(R4-F3) ----------

    @Test
    fun applyLoadedRecords_mergesAndPrefersInMemoryWrites() {
        // init 改为后台线程全量加载后,加载窗口内可能已有内存写入(用户手速快):
        // 收口必须合并而非整表替换,同一 key 以内存值优先,否则窗口内的操作记录被覆盖丢失
        OpRecordStore.setPending(st, 1, 100, MyAgreeOp.AGREE)
        OpRecordStore.applyLoadedRecords(
            mapOf(
                // prefs 里也有 1_100 的旧值(比如进程上次的记录),内存写必须赢
                "1_100" to OpRecord(MyAgreeOp.NONE, MyAgreeOp.NONE),
                "2_200" to OpRecord(MyAgreeOp.AGREE, MyAgreeOp.AGREE),
            )
        )
        assertEquals(OpRecord(MyAgreeOp.AGREE, MyAgreeOp.NONE, inFlight = true), recordOf(1, 100))
        assertEquals(OpRecord(MyAgreeOp.AGREE, MyAgreeOp.AGREE), recordOf(2, 200))
        // 合并不得挤掉其他内存记录
        assertEquals(2, OpRecordStore.records.value.size)
    }

    @Test
    fun loadAndMerge_swallowsStorageFailureAndKeepsMemory() {
        // R5-F1:后台加载线程裸抛会崩进程;loadAndMerge 必须兜底——
        // prefs 读异常时静默降级(保持无记录/内存已有记录),不向外抛
        OpRecordStore.setPending(st, 1, 100, MyAgreeOp.AGREE)
        val boom = object : OpRecordStorage {
            override fun get(key: String): String? = throw IllegalStateException("prefs corrupt")
            override fun all(): Map<String, String> = throw IllegalStateException("prefs corrupt")
            override fun putAll(entries: Map<String, String>) = Unit
        }
        OpRecordStore.loadAndMerge(boom)
        // 异常被吞,内存记录完好,records 不被清空
        assertEquals(OpRecord(MyAgreeOp.AGREE, MyAgreeOp.NONE, inFlight = true), recordOf(1, 100))
    }

    @Test
    fun applyLoadedRecords_withCurrentGenerationMerges() {
        // 显式传当前世代 = 正常的 init 收口路径,必须照常合并
        val gen = OpRecordStore.currentGenerationForTest()
        OpRecordStore.applyLoadedRecords(
            mapOf("1_100" to OpRecord(MyAgreeOp.AGREE, MyAgreeOp.AGREE)),
            gen,
        )
        assertEquals(OpRecord(MyAgreeOp.AGREE, MyAgreeOp.AGREE), recordOf(1, 100))
    }

    @Test
    fun applyLoadedRecords_staleGenerationIsDiscardedEntirely() {
        // init 的后台加载线程以捕获的旧世代回写;若期间账号切换
        // (onAccountSwitched 递增世代并整体重载内存表),迟到的旧账号结果
        // 必须整批丢弃——否则 A 的记录串进 B 的界面判定(跨账号污染)。
        val genBeforeSwitch = OpRecordStore.currentGenerationForTest()

        // 模拟账号切换:世代递增 + 内存表重载为当前(新账号)存储
        OpRecordStore.onAccountSwitched(st)

        val lateFromOldAccount = mapOf("1_200" to OpRecord(MyAgreeOp.DISAGREE, MyAgreeOp.DISAGREE))
        OpRecordStore.applyLoadedRecords(lateFromOldAccount, genBeforeSwitch)
        assertNull("旧世代的迟到加载结果必须被丢弃", recordOf(1, 200))
        assertTrue(OpRecordStore.records.value.isEmpty())
    }

    @Test
    fun loadAndMerge_staleGenerationStillAlignsPersistedButDiscardsMemory() {
        // 世代丢弃只针对内存合并;持久层的启动对齐(stale 写入)照常——
        // 旧账号 prefs 里的崩溃残留对齐是无害且必要的(该数据本就属于旧账号文件)
        val genBeforeSwitch = OpRecordStore.currentGenerationForTest()
        val persisted = MemoryStorage()
        persisted.map["my_1_300"] = MyAgreeOp.AGREE.name
        persisted.map["srv_1_300"] = MyAgreeOp.NONE.name

        OpRecordStore.onAccountSwitched(st)

        OpRecordStore.loadAndMerge(persisted, genBeforeSwitch)
        // 内存:被丢弃(旧世代)
        assertNull(recordOf(1, 300))
        // 持久层:已按启动对齐口径写回
        assertEquals(MyAgreeOp.AGREE.name, persisted.map["srv_1_300"])
    }

    // ---------- agreeFlag: 列表页意图旗标的记录优先语义 ----------
    // (Concern/UserPost 等列表页迁移到差分模型的核心不变量:
    //  点击时"当前是否已赞"有本地记录必须以记录为准,回显仅作无记录兜底)

    @Test
    fun agreeFlag_noRecord_fallsBackToServerEcho() {
        // 无记录 → 保持旧行为,原样返回服务端回显
        assertEquals(1, OpRecordStore.agreeFlag(3, 7, 1))
        assertEquals(0, OpRecordStore.agreeFlag(3, 8, 0))
    }

    @Test
    fun agreeFlag_ghostAgreeEchoCannotReviveUnlikedThread() {
        // ★ 本迁移要消灭的缺陷场景:用户踩过之后,回显 hasAgree=1 是
        // "有过操作"的幽灵值——直读回显会把踩过的楼判成"已赞",
        // 再点赞就发成"取消赞"请求。记录必须压过回显。
        OpRecordStore.setPending(st, 3, 9, MyAgreeOp.DISAGREE)
        assertEquals("踩记录存在时,幽灵回显不得判成已赞", 0, OpRecordStore.agreeFlag(3, 9, 1))
    }

    @Test
    fun agreeFlag_confirmedAgreeWinsOverZeroEcho() {
        // 确认过的赞(跨页面互通:帖子页点的赞,列表页回显未必跟得上)
        seedConfirmed(3, 10, MyAgreeOp.AGREE)
        assertEquals(1, OpRecordStore.agreeFlag(3, 10, 0))
    }

    @Test
    fun agreeFlag_keySpaceIsolatedByObjType() {
        // 楼层(1)与主帖(3)同 id 不得互相污染——列表页 confirm/显示共用
        // `objType_id` 键式的前提
        OpRecordStore.setPending(st, 1, 11, MyAgreeOp.DISAGREE)
        assertEquals(1, OpRecordStore.agreeFlag(3, 11, 1))
    }

    @Test
    fun listPageConfirm_agreeFlagAndDisplayDeltaStayConsistent() {
        // 列表页 confirm(Ok 分支)之后:意图旗标=已赞、显示偏移归零,
        // 锁定 confirm 是记录表对该手势的唯一写入点
        seedConfirmed(3, 12, MyAgreeOp.AGREE)
        val rec = recordOf(3, 12)
        assertEquals(OpRecord(MyAgreeOp.AGREE, MyAgreeOp.AGREE), rec)
        assertEquals(0L, rec!!.displayDelta())
        assertEquals(1, OpRecordStore.agreeFlag(3, 12, 0))
    }

    @Test
    fun revertedUnconfirmedAgreeStillSuppressesGhostEcho() {
        // 乐观赞被 Business 拒绝 → revertPending 回 server=NONE 后,
        // 记录仍存在,幽灵回显 1 不得复活那个从未确认的赞
        OpRecordStore.setPending(st, 3, 13, MyAgreeOp.AGREE)
        OpRecordStore.revertPending(st, 3, 13)
        assertEquals(0, OpRecordStore.agreeFlag(3, 13, 1))
    }

    @Test
    fun keyMatchesRateLimiterKeyFormat() {
        // 键式与 AgreeRateLimiter.keyFor 同构:同一服务端对象在限流与差分记录里指到同一条目。
        // 若两处格式漂移,此断言即失败(避免回到手拼前缀的时代)。
        // (3b-move 自 core:network 的 AgreeRateLimiterTest 移入——core 测试看不到 app 的类)
        assertEquals(com.huanchengfly.tieba.post.api.AgreeRateLimiter.keyFor(2, 42L), OpRecordStore.key(2, 42L))
    }
}
