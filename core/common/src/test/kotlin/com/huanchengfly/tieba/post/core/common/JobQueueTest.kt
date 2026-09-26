package com.huanchengfly.tieba.post.core.common

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * JobQueue 串行化语义测试（Phase 5，2026-09-17）。
 *
 * 0ranko0P 同款队列：任务按提交顺序逐个执行。DataStoreSettingsRepository 的全部写入
 * 依赖这个"不并发"保证——save 的读-改-写原子链、以及 62 项写入互不覆盖都建立在其上。
 */
class JobQueueTest {

    @Test
    fun jobsRunInSubmissionOrder() = runBlocking {
        val queue = JobQueue()
        val order = Collections.synchronizedList(mutableListOf<Int>())
        repeat(20) { i ->
            queue.submit(Dispatchers.Default) { order.add(i) }
        }
        withTimeout(5_000) {
            while (order.size < 20) delay(10)
        }
        assertEquals((0 until 20).toList(), order.toList())
        queue.cancel()
    }

    @Test
    fun jobsNeverOverlap() = runBlocking {
        val queue = JobQueue()
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val done = AtomicInteger(0)
        repeat(10) {
            queue.submit(Dispatchers.Default) {
                val now = active.incrementAndGet()
                maxActive.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                delay(30)
                active.decrementAndGet()
                done.incrementAndGet()
            }
        }
        withTimeout(5_000) {
            while (done.get() < 10) delay(10)
        }
        assertEquals("任务并发执行了（活跃峰值 > 1）——串行化失效", 1, maxActive.get())
        queue.cancel()
    }
}
