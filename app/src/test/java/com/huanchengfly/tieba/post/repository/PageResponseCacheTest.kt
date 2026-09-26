package com.huanchengfly.tieba.post.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * 列表响应会话级缓存的语义回归。
 *
 * 被替代的旧实现是 `lastHash` / `lastResponse` 两个全局字段,存在三个可复现的缺陷:
 * 1. 单槽 —— 并发请求互相覆盖,读方可能拿到另一个吧的响应;
 * 2. 撕裂读 —— 两字段非 volatile,hash 与 response 可能来自不同次请求;
 * 3. 失败污染 —— 旧代码在发起请求前就写入 `lastHash`,请求失败后 hash 已更新而
 *    response 仍是上一次的旧对象,下一次相同请求会命中并返回错误的页。
 *
 * 本用例锁定新实现的正确语义:按 hash 索引、命中返回原对象、未命中返回 null、
 * 超容量按最久未使用淘汰、重新写入可刷新使用顺序。
 */
class PageResponseCacheTest {

    @Test
    fun getReturnsNullWhenAbsent() {
        val cache = PageResponseCache<String>()
        assertNull(cache.get("missing"))
    }

    @Test
    fun putThenGetReturnsSameInstance() {
        val cache = PageResponseCache<String>()
        cache.put("a", "page-a")
        assertEquals("page-a", cache.get("a"))
    }

    /** 不同吧/不同分页的 key 必须互不串味(旧实现的单槽正是栽在这里) */
    @Test
    fun differentHashesAreIsolated() {
        val cache = PageResponseCache<String>()
        cache.put("forumA_1_1_0_null", "respA")
        cache.put("forumB_1_1_0_null", "respB")
        assertEquals("respA", cache.get("forumA_1_1_0_null"))
        assertEquals("respB", cache.get("forumB_1_1_0_null"))
    }

    @Test
    fun evictsLeastRecentlyUsedWhenOverCapacity() {
        val cache = PageResponseCache<String>(maxSize = 2)
        cache.put("a", "A")
        cache.put("b", "B")
        cache.put("c", "C") // 超限,淘汰最久未使用的 a

        assertNull(cache.get("a"))
        assertEquals("B", cache.get("b"))
        assertEquals("C", cache.get("c"))
    }

    @Test
    fun rePutRefreshesRecency() {
        val cache = PageResponseCache<String>(maxSize = 2)
        cache.put("a", "A")
        cache.put("b", "B")
        cache.put("a", "A2") // a 变为最近使用 → 下一个被淘汰的应是 b
        cache.put("c", "C")

        assertEquals("A2", cache.get("a"))
        assertNull(cache.get("b"))
        assertEquals("C", cache.get("c"))
    }

    @Test
    fun clearForTestEmpties() {
        val cache = PageResponseCache<String>()
        cache.put("a", "A")
        cache.clearForTest()
        assertNull(cache.get("a"))
    }

    /**
     * 并发读写不产生脏值。容量给足以免淘汰干扰断言;每个线程读写自己独占的 key,
     * 断言"写进去的能原样读回"。子线程断言失败不会让 JUnit 失败,必须显式收集后抛出。
     */
    @Test
    fun concurrentPutsAndGetsStayConsistent() {
        val cache = PageResponseCache<String>(maxSize = 4096)
        val failures = AtomicReference<Throwable?>(null)
        val threads = (0 until 8).map { t ->
            Thread {
                try {
                    repeat(200) { i ->
                        val hash = "f${t}_p$i"
                        cache.put(hash, "r${t}_$i")
                        assertEquals("r${t}_$i", cache.get(hash))
                    }
                } catch (e: Throwable) {
                    failures.compareAndSet(null, e)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        failures.get()?.let { throw it }
    }
}
