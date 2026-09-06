package com.huanchengfly.tieba.post.utils

import com.huanchengfly.tieba.post.api.models.protos.MyAgreeOp
import com.huanchengfly.tieba.post.api.models.protos.OpRecord
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 外部审查修复轮的回归测试:
 * - 第 5 项:刷新 rebase 与在途点赞的竞争(回滚失效)
 * - 第 4 项:记录按账号分文件的迁移与命名
 */
class OpRecordRaceAndMigrationTest {

    private class MemoryStorage : OpRecordStorage {
        val map = LinkedHashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun all(): Map<String, String> = map.toMap()
        override fun putAll(entries: Map<String, String>) = map.putAll(entries)
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

    // ---------- 外部审查-5:rebase 与在途操作竞争 ----------

    @Test
    fun race_refreshRebasesInFlightAgree_thenRevertStillWorks() {
        // 复现时序(修复前终态错误地停在 AGREE/AGREE):
        // 1. 起始未赞 → 2. 发起点赞(在途) → 3. 同对象刷新先返回,执行 rebase
        // → 4. 点赞被服务端拒绝,执行 revertPending
        OpRecordStore.setPending(st, 3, 42L, MyAgreeOp.AGREE)
        OpRecordStore.rebase(st, setOf(OpRecordStore.key(3, 42L)))
        OpRecordStore.revertPending(st, 3, 42L)

        assertEquals(
            "刷新不得确认在途操作:回滚后必须回到 NONE/NONE",
            OpRecord(MyAgreeOp.NONE, MyAgreeOp.NONE),
            recordOf(3, 42L)
        )
    }

    @Test
    fun rebaseDoesNotTouchInFlightRecordEvenForDisagree() {
        OpRecordStore.setPending(st, 1, 7L, MyAgreeOp.DISAGREE)
        OpRecordStore.rebase(st, setOf(OpRecordStore.key(1, 7L)))

        // 在途记录原样保留:意图=踩,基准未动
        assertEquals(OpRecord(MyAgreeOp.DISAGREE, MyAgreeOp.NONE), recordOf(1, 7L))
        assertEquals("在途对象的 srv_ 不得被改写", "NONE", st.map["srv_1_7"])
    }

    @Test
    fun rebaseAfterConfirmStillAlignsMarker() {
        // 在途请求成功收尾后,下一次刷新的对齐路径不受"跳过在途"影响
        OpRecordStore.setPending(st, 1, 7L, MyAgreeOp.AGREE)
        OpRecordStore.confirm(st, 1, 7L, MyAgreeOp.AGREE)
        OpRecordStore.rebase(st, setOf(OpRecordStore.key(1, 7L)))

        assertEquals(OpRecord(MyAgreeOp.AGREE, MyAgreeOp.AGREE), recordOf(1, 7L))
    }

    @Test
    fun loadAndMerge_alignsStaleInFlightRecordsFromCrashedProcess() {
        // 进程崩溃遗留的未决记录:持久化 my=AGREE/srv=NONE,但已无在途请求。
        // 启动加载按"视为已确认"对齐(与旧 rebase 的崩溃恢复口径一致)。
        st.map["my_1_9"] = "AGREE"
        st.map["srv_1_9"] = "NONE"

        OpRecordStore.loadAndMerge(st)

        assertEquals(OpRecord(MyAgreeOp.AGREE, MyAgreeOp.AGREE), recordOf(1, 9L))
        assertEquals("AGREE", st.map["srv_1_9"])
    }

    @Test
    fun loadAndMerge_keepsAlignedRecordsUntouched() {
        st.map["my_1_9"] = "DISAGREE"
        st.map["srv_1_9"] = "DISAGREE"

        OpRecordStore.loadAndMerge(st)

        assertEquals(OpRecord(MyAgreeOp.DISAGREE, MyAgreeOp.DISAGREE), recordOf(1, 9L))
    }

    // ---------- 外部审查-4:按账号分文件的迁移 ----------

    @Test
    fun accountPrefsName_partitionsByUidAndFallsBackToLegacy() {
        assertEquals("agree_op_records_u_123456", accountPrefsName("123456"))
        assertEquals("agree_op_records", accountPrefsName(null))
        assertEquals("agree_op_records", accountPrefsName("   "))
    }

    @Test
    fun migrateLegacy_copiesRecordsToCurrentAccountAndTombstones() {
        val legacy = MemoryStorage()
        legacy.map["my_3_11"] = "AGREE"
        legacy.map["srv_3_11"] = "AGREE"
        val current = MemoryStorage()

        migrateLegacyStorageIfNeeded(current, legacy)

        assertEquals("AGREE", current.map["my_3_11"])
        assertEquals("AGREE", current.map["srv_3_11"])
        assertEquals("墓碑必须写入旧文件", "1", legacy.map["migrated_per_account_v1"])
    }

    @Test
    fun migrateLegacy_isSkippedOnceTombstoned() {
        val legacy = MemoryStorage()
        legacy.map["my_3_11"] = "AGREE"
        legacy.map["migrated_per_account_v1"] = "1"
        val current = MemoryStorage()

        migrateLegacyStorageIfNeeded(current, legacy)

        // 已迁移过:第二个账号不得再次吞下同一批记录
        assertNull(current.map["my_3_11"])
    }

    @Test
    fun migrateLegacy_emptyLegacyIsTombstonedToAvoidRescan() {
        val legacy = MemoryStorage()
        val current = MemoryStorage()

        migrateLegacyStorageIfNeeded(current, legacy)

        assertTrue(current.map.isEmpty())
        assertEquals("1", legacy.map["migrated_per_account_v1"])
    }

    // ---------- 透传:混合迁移后内存表可读 ----------

    @Test
    fun migratedRecordsVisibleThroughStoreLoadPath() {
        val legacy = MemoryStorage()
        legacy.map["my_2_33"] = "DISAGREE"
        legacy.map["srv_2_33"] = "DISAGREE"
        val current = MemoryStorage()

        migrateLegacyStorageIfNeeded(current, legacy)
        OpRecordStore.applyLoadedRecords(OpRecordStore.loadAll(current))

        assertEquals(OpRecord(MyAgreeOp.DISAGREE, MyAgreeOp.DISAGREE), recordOf(2, 33L))
    }
}
