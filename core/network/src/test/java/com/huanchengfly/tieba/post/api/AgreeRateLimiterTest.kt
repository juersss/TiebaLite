package com.huanchengfly.tieba.post.api

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * 赞踩限流器的 JVM 单测。
 *
 * 依赖 [AgreeRateLimiter.clock] 可替换,把时间源换成可控的假时钟,
 * 从而在不接触 SystemClock(仅 Android 可用)的前提下验证窗口与间隔逻辑。
 *
 * 限流器是进程级单例,每个用例结束后必须 [AgreeRateLimiter.resetForTest]。
 */
class AgreeRateLimiterTest {

    /** 假时钟的当前值。取一个较大的起点,避免与"从未操作"的 null 语义混淆。 */
    private var now = 1_000_000L

    @Before
    fun setUp() {
        AgreeRateLimiter.resetForTest()
        now = 1_000_000L
        AgreeRateLimiter.clock = { now }
    }

    @After
    fun tearDown() {
        AgreeRateLimiter.resetForTest()
    }

    // ---------- 同对象最小间隔 ----------

    @Test
    fun firstCallAlwaysSucceeds() {
        assertTrue(AgreeRateLimiter.tryAcquire("post_1"))
    }

    @Test
    fun sameObjectWithinMinIntervalIsRejected() {
        // ★ 对应修复 §3.1 的前置:限流命中时不能产生 Start,否则留下永久"幽灵赞"。
        // 这里验证限流器确实会在 3 秒内拦住同一对象。
        assertTrue(AgreeRateLimiter.tryAcquire("post_1"))
        now += AgreeRateLimiter.MIN_INTERVAL_MS - 1
        assertFalse(AgreeRateLimiter.tryAcquire("post_1"))
    }

    @Test
    fun sameObjectAfterMinIntervalIsAllowed() {
        assertTrue(AgreeRateLimiter.tryAcquire("post_1"))
        now += AgreeRateLimiter.MIN_INTERVAL_MS
        assertTrue(AgreeRateLimiter.tryAcquire("post_1"))
    }

    @Test
    fun differentObjectsAreIndependent() {
        assertTrue(AgreeRateLimiter.tryAcquire("post_1"))
        // 换一个 key 不受 post_1 的间隔约束
        assertTrue(AgreeRateLimiter.tryAcquire("post_2"))
    }

    // ---------- 全局每分钟配额 ----------

    @Test
    fun globalQuotaBlocksEleventhCall() {
        repeat(AgreeRateLimiter.MAX_PER_MINUTE) { i ->
            assertTrue("第 ${i + 1} 次应当放行", AgreeRateLimiter.tryAcquire("post_$i"))
        }
        assertFalse("第 11 次应被全局配额拦住", AgreeRateLimiter.tryAcquire("post_999"))
    }

    @Test
    fun globalQuotaRecoversAfterWindow() {
        repeat(AgreeRateLimiter.MAX_PER_MINUTE) { i -> AgreeRateLimiter.tryAcquire("post_$i") }
        assertFalse(AgreeRateLimiter.tryAcquire("post_999"))

        // 滑出 1 分钟窗口后配额恢复
        now += 60_001L
        assertTrue(AgreeRateLimiter.tryAcquire("post_999"))
    }

    @Test
    fun windowSlidesRatherThanResets() {
        // 先消耗 5 次
        repeat(5) { i -> AgreeRateLimiter.tryAcquire("post_$i") }
        // 前进 30 秒后再消耗 5 次 —— 此时已满 10 次
        now += 30_000L
        repeat(5) { i -> AgreeRateLimiter.tryAcquire("post_${i + 100}") }
        assertFalse(AgreeRateLimiter.tryAcquire("post_999"))

        // 再前进 31 秒:前 5 次已滑出窗口,但后 5 次仍在窗口内 → 可再放行 5 次
        now += 31_000L
        repeat(5) { i -> assertTrue(AgreeRateLimiter.tryAcquire("post_${i + 200}")) }
        assertFalse("后 5 次仍在窗口内,应再次触顶", AgreeRateLimiter.tryAcquire("post_999"))
    }

    // ---------- checkPerObject=false（赞踩互斥的撤销请求） ----------

    @Test
    fun skipPerObjectCheckStillCountsGlobalQuota() {
        // 同一次手势里"撤销旧态"紧跟着"置新态",必须跳过同对象间隔,
        // 但仍占用全局配额,否则连续切换可以绕过每分钟上限。
        assertTrue(AgreeRateLimiter.tryAcquire("post_1"))
        assertTrue("撤销请求应跳过同对象间隔", AgreeRateLimiter.tryAcquire("post_1", checkPerObject = false))

        // 全局窗口已消耗 2 次
        repeat(AgreeRateLimiter.MAX_PER_MINUTE - 2) { i ->
            assertTrue(AgreeRateLimiter.tryAcquire("post_${i + 10}"))
        }
        assertFalse("撤销请求同样计入全局配额", AgreeRateLimiter.tryAcquire("post_999", checkPerObject = false))
    }

    // ---------- 清理与并发 ----------

    @Test
    fun onAccountSwitchedClearsQuotaWithoutTouchingTestClock() {
        // ★ 回归(第三轮审查):服务端风控按账号计数,切号后新账号不该继承
        // 旧账号的 3s 同对象间隔与 60s 全局配额(最坏 60 秒完全点不动赞踩)。
        // 先占用一个对象的 3 秒间隔,同时把全局配额消耗到只剩 obj_1 这 1 次:
        repeat(AgreeRateLimiter.MAX_PER_MINUTE - 1) { i -> AgreeRateLimiter.tryAcquire("post_$i") }
        assertTrue(AgreeRateLimiter.tryAcquire("obj_1"))
        assertFalse("同对象 3 秒内应拦截", AgreeRateLimiter.tryAcquire("obj_1"))
        assertFalse("全局配额已顶满", AgreeRateLimiter.tryAcquire("post_999"))

        AgreeRateLimiter.onAccountSwitched()

        // 同对象间隔与全局配额同时归零,新账号立即恢复可用
        assertTrue("切号后同对象应放行", AgreeRateLimiter.tryAcquire("obj_1"))
        assertTrue("切号后全局配额应清空", AgreeRateLimiter.tryAcquire("post_999"))
        // 时钟字段不得被重置(resetForTest 才重置它;本用例断言仍依赖注入时钟)
        now += 1L
        assertTrue(AgreeRateLimiter.tryAcquire("post_998"))
    }

    @Test
    fun longRunUsageKeepsBehavingCorrectly() {
        // 注意:全局配额是 10 次/分,因此不可能在短时间内灌满 MAX_TRACKED_KEYS(256) 个 key
        // ——批内第 11 次会被配额拦住。要让 lastOpAt 累积到触发清理,必须分批滑出窗口。
        // 这也说明 lastOpAt 的规模是有界的:每满 256 条就会被清理一次,不会无限膨胀。
        repeat(30) { batch ->
            repeat(AgreeRateLimiter.MAX_PER_MINUTE) { i ->
                assertTrue("第 $batch 批第 $i 次应放行", AgreeRateLimiter.tryAcquire("post_${batch}_$i"))
            }
            assertFalse("每批第 11 次应被全局配额拦住", AgreeRateLimiter.tryAcquire("post_${batch}_overflow"))
            now += 60_001L   // 滑出 1 分钟窗口,恢复配额
        }

        // 累积了 300 个历史 key 之后,新 key 的行为仍应正确
        assertTrue(AgreeRateLimiter.tryAcquire("post_new"))
        assertFalse("刚用过的新 key 应受同对象间隔约束", AgreeRateLimiter.tryAcquire("post_new"))
    }

    @Test
    fun concurrentCallsOnSameObjectAllowExactlyOne() {
        // ★ 验证 synchronized 修复(§3.16):check-then-act 若非原子,
        // 并发下多个线程会同时读到"从未操作"而全部放行。
        val threadCount = 32
        val executor = Executors.newFixedThreadPool(threadCount)
        val startGate = CountDownLatch(1)
        val successes = ConcurrentLinkedQueue<Boolean>()
        val done = CountDownLatch(threadCount)

        repeat(threadCount) {
            executor.submit {
                startGate.await()
                successes.add(AgreeRateLimiter.tryAcquire("post_same"))
                done.countDown()
            }
        }
        startGate.countDown()   // 同时放行,制造最大竞争
        done.await()
        executor.shutdown()

        assertEquals(
            "并发下同对象应只有 1 次放行(其余被 3 秒间隔拦住)",
            1, successes.count { it }
        )
    }

    // ---------- keyFor 键式统一(接手轮 §7.2) ----------

    @Test
    fun keyForSameIdAcrossObjTypesIsIndependent() {
        // ★ §3.16 key 冲突回归:楼中楼(2_x)与楼层(1_x)、首楼(1_x)与主帖(3_x)
        // 曾因手拼前缀互相受限或共享窗口;objType_id 键式下同类共享、异类互不影响。
        val id = 42L
        assertTrue(AgreeRateLimiter.tryAcquire(AgreeRateLimiter.keyFor(1, id)))
        now += AgreeRateLimiter.MIN_INTERVAL_MS - 1
        // 同类同 id:仍在 3 秒间隔内,必须拒绝
        assertFalse(AgreeRateLimiter.tryAcquire(AgreeRateLimiter.keyFor(1, id)))
        // 异类同 id:独立条目,必须放行(若 keyFor 丢失 objType,此断言会失败)
        assertTrue(AgreeRateLimiter.tryAcquire(AgreeRateLimiter.keyFor(2, id)))
        assertTrue(AgreeRateLimiter.tryAcquire(AgreeRateLimiter.keyFor(3, id)))
    }

    // keyForMatchesRecordKeyFormat（与 app 侧 OpRecordStore.key 的同构断言）随 3b-move 移入
    // app/src/test/.../utils/OpRecordStoreTest.kt——core:network 测试看不到 app 的类。
}
