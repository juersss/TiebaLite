package com.huanchengfly.tieba.post.api

import com.huanchengfly.tieba.post.api.models.AgreeBean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * OpResponseLog 的 JVM 单测:分组、上限、有界增长。
 * AgreeBean 是 Gson bean,可直接构造;objId 与 UI 诊断的 (objType, id) 对齐。
 */
class OpResponseLogTest {

    // AgreeBean 字段是 val + Gson 注入,直接构造传不进值,用 Gson 反序列化构造
    private fun beanViaGson(errorCode: String?, score: String?): AgreeBean =
        com.google.gson.Gson().fromJson(
            """{"error_code": ${errorCode?.let { "\"$it\"" } ?: "null"},
                "data": {"agree": {"score": ${score?.let { "\"$it\"" } ?: "null"}}}}""",
            AgreeBean::class.java
        )

    @Before
    fun setUp() = OpResponseLog.resetForTest()

    @After
    fun tearDown() = OpResponseLog.resetForTest()

    @Test
    fun emptyLogReturnsNoEntries() {
        assertTrue(OpResponseLog.recent(1, 99L).isEmpty())
    }

    @Test
    fun recordsGroupedByObjTypeAndId() {
        // OBJ_THREAD 的 key 是 (3, threadId),OBJ_POST 是 (1, postId)——
        // 同一数字 id 在不同 objType 下必须互不可见(与限流器 keyFor 的教训一致)。
        OpResponseLog.record(1, 42L, 2, 0, beanViaGson("0", "1"))
        OpResponseLog.record(3, 42L, 5, 0, beanViaGson("0", "-1"))
        assertEquals(1, OpResponseLog.recent(1, 42L).size)
        assertEquals(1, OpResponseLog.recent(3, 42L).size)
        assertEquals(2, OpResponseLog.recent(1, 42L).first().agreeType)
        assertEquals(5, OpResponseLog.recent(3, 42L).first().agreeType)
    }

    @Test
    fun capturesErrorCodeAndScore() {
        OpResponseLog.record(1, 7L, 5, 0, beanViaGson("ERR_USER_HAS_AGREED", "3"))
        val e = OpResponseLog.recent(1, 7L).single()
        assertEquals("ERR_USER_HAS_AGREED", e.errorCode)
        assertEquals("3", e.score)
        assertEquals(5, e.agreeType)
        assertEquals(0, e.opType)
    }

    @Test
    fun recordFailureCapturesExceptionPath() {
        // 取消被拒(ERR_USER_NOT_DISAGREE)走 FailureResponseInterceptor 抛异常,
        // 不经过 onEach——必须靠 recordFailure 补记,否则诊断里隐形。
        OpResponseLog.recordFailure(3, 42L, 5, 1, "1234002", "您还没有踩过")
        val e = OpResponseLog.recent(3, 42L).single()
        assertEquals("1234002", e.errorCode)
        assertEquals("您还没有踩过", e.errorMsg)
        assertNull(e.score)
        assertEquals(1, e.opType)   // 撤销
    }

    @Test
    fun keepsLatestPerObjectDroppingOldest() {
        repeat(10) { OpResponseLog.record(1, 8L, 2, 0, beanViaGson("0", "$it")) }
        val list = OpResponseLog.recent(1, 8L)
        assertEquals(8, list.size)                       // 每对象上限 8
        assertEquals("2", list.first().score)            // 最旧两条被丢弃
        assertEquals("9", list.last().score)
    }

    @Test
    fun boundsTrackedObjects() {
        repeat(210) { OpResponseLog.record(1, it.toLong(), 2, 0, beanViaGson("0", null)) }
        // 超过 200 个对象后,最早写入的整组丢弃;最后写入的仍在
        assertTrue(OpResponseLog.recent(1, 0L).isEmpty())
        assertEquals(1, OpResponseLog.recent(1, 209L).size)
    }
}
