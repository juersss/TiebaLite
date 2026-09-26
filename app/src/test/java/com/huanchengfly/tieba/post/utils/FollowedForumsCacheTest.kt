package com.huanchengfly.tieba.post.utils

import com.huanchengfly.tieba.post.api.models.ForumGuideBean.LikeForum
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * 关注吧缓存的状态语义回归。
 *
 * 该缓存是首页快/慢两条加载路径的汇聚点:快路径 [FollowedForumsCache.mergeAll]
 * 增量并入前几页,慢路径 [FollowedForumsCache.updateAll] 全量替换,两者并发时
 * 必须共用同一把锁且语义不能互相破坏。此前无任何单测(审查报告 B8 指出)。
 *
 * 同时锁定本轮改动:锁从 `synchronized(this)`(公开单例对象当锁)改为私有锁对象,
 * 对外行为必须完全不变。
 */
class FollowedForumsCacheTest {

    private fun forum(id: Long, name: String = "forum$id") = LikeForum(
        forumName = name,
        forumId = id,
        levelId = 1,
    )

    @Before
    fun setUp() {
        FollowedForumsCache.resetForTest()
    }

    @After
    fun tearDown() {
        FollowedForumsCache.resetForTest()
    }

    @Test
    fun mergeAllKeepsOtherForums() {
        FollowedForumsCache.mergeAll(listOf(forum(1), forum(2)))
        FollowedForumsCache.mergeAll(listOf(forum(3)))

        assertTrue(FollowedForumsCache.isFollowed(1))
        assertTrue(FollowedForumsCache.isFollowed(2))
        assertTrue(FollowedForumsCache.isFollowed(3))
        assertEquals(3, FollowedForumsCache.getAllFollowedForums().size)
    }

    @Test
    fun mergeAllOverwritesSameId() {
        FollowedForumsCache.mergeAll(listOf(forum(1, "oldName")))
        FollowedForumsCache.mergeAll(listOf(forum(1, "newName")))

        assertEquals("newName", FollowedForumsCache.getFollowedForum(1)?.forumName)
    }

    @Test
    fun mergeAllIgnoresEmptyInput() {
        FollowedForumsCache.mergeAll(listOf(forum(1)))
        FollowedForumsCache.mergeAll(null)
        FollowedForumsCache.mergeAll(emptyList())

        assertTrue(FollowedForumsCache.isFollowed(1))
    }

    @Test
    fun updateAllReplacesEverything() {
        FollowedForumsCache.mergeAll(listOf(forum(1), forum(2)))
        FollowedForumsCache.updateAll(listOf(forum(3)))

        assertFalse(FollowedForumsCache.isFollowed(1))
        assertFalse(FollowedForumsCache.isFollowed(2))
        assertTrue(FollowedForumsCache.isFollowed(3))
    }

    @Test
    fun updateAllNullClears() {
        FollowedForumsCache.mergeAll(listOf(forum(1)))
        FollowedForumsCache.updateAll(null)

        assertFalse(FollowedForumsCache.isFollowed(1))
        assertEquals(0, FollowedForumsCache.getAllFollowedForums().size)
    }

    @Test
    fun onAccountSwitchedClearsAccountPrivateData() {
        // ★ 回归(第三轮审查):关注吧缓存是账号私有数据。切号/退出账号若不重载,
        // 新账号的 isFollowed 判定("只看关注吧"过滤/关注状态显示)全部按上一个
        // 账号的吧单来。AccountUtil.switchAccount/exit 两处钩子点都调本函数。
        FollowedForumsCache.updateAll(listOf(forum(1), forum(2)))
        assertTrue(FollowedForumsCache.isFollowed(1))

        // 传 null = 退出最后一个账号（无归属账号）。**不能传真实 uid**：那会触发
        // preload → DatabaseUtil → App.INSTANCE，而本类是纯 JVM 测试（无 Robolectric）。
        // 带 uid 的预载路径由实机自检「关注吧落盘副本」覆盖。
        FollowedForumsCache.onAccountSwitched(null)

        assertFalse(FollowedForumsCache.isFollowed(1))
        assertFalse(FollowedForumsCache.isFollowed(2))
        assertEquals(0, FollowedForumsCache.getAllFollowedForums().size)
    }

    /**
     * 落盘守卫：**没有归属账号（ownerUid 为空）时绝不碰 DB**。
     *
     * 这条同时是"本类能保持纯 JVM 测试"的前提——一旦落库被触发，
     * `DatabaseUtil` 会走 `App.INSTANCE` 抛 NPE。resetForTest 后 ownerUid 为空，
     * 所以下面这些写操作只动内存。
     */
    @Test
    fun noPersistWithoutOwnerUid() {
        // 顺序有意：updateAll 是"整体替换"，放最后并把前面的清掉——所以断言要按替换后的状态写
        FollowedForumsCache.mergeAll(listOf(forum(1)))
        FollowedForumsCache.updateOrAddFollowedForum(forum(3))
        FollowedForumsCache.updateAll(listOf(forum(4)))
        FollowedForumsCache.updateOrAddFollowedForum(forum(5))
        FollowedForumsCache.removeFollowedForum(4)

        // 走到这里没抛异常即证明落库被守卫拦住（否则会 App.INSTANCE NPE）；
        // 同时四条写路径的内存语义不变：updateAll 清掉了 1/3，remove 掉了 4，只剩 5
        assertFalse(FollowedForumsCache.isFollowed(1))
        assertFalse(FollowedForumsCache.isFollowed(3))
        assertFalse(FollowedForumsCache.isFollowed(4))
        assertTrue(FollowedForumsCache.isFollowed(5))
    }

    @Test
    fun updateOrAddInsertsAndUpdates() {
        FollowedForumsCache.updateOrAddFollowedForum(forum(9, "nine"))
        assertEquals("nine", FollowedForumsCache.getFollowedForum(9)?.forumName)

        FollowedForumsCache.updateOrAddFollowedForum(forum(9, "nineEdited"))
        assertEquals("nineEdited", FollowedForumsCache.getFollowedForum(9)?.forumName)
    }

    @Test
    fun updateOrAddIgnoresInvalidInput() {
        FollowedForumsCache.updateOrAddFollowedForum(null)
        FollowedForumsCache.updateOrAddFollowedForum(forum(0))

        assertFalse(FollowedForumsCache.isFollowed(0))
        assertEquals(0, FollowedForumsCache.getAllFollowedForums().size)
    }

    @Test
    fun removeFollowedForumRemoves() {
        FollowedForumsCache.mergeAll(listOf(forum(1), forum(2)))
        FollowedForumsCache.removeFollowedForum(1)

        assertFalse(FollowedForumsCache.isFollowed(1))
        assertTrue(FollowedForumsCache.isFollowed(2))
    }

    @Test
    fun removeFollowedForumIgnoresAbsent() {
        FollowedForumsCache.mergeAll(listOf(forum(1)))
        FollowedForumsCache.removeFollowedForum(999)
        FollowedForumsCache.removeFollowedForum(null)
        FollowedForumsCache.removeFollowedForum(0)

        assertEquals(1, FollowedForumsCache.getAllFollowedForums().size)
    }

    @Test
    fun isFollowedFalseForNullOrZero() {
        FollowedForumsCache.mergeAll(listOf(forum(1)))
        assertFalse(FollowedForumsCache.isFollowed(null))
        assertFalse(FollowedForumsCache.isFollowed(0L))
    }

    @Test
    fun getFollowedForumNullWhenAbsent() {
        assertNull(FollowedForumsCache.getFollowedForum(123))
        assertNull(FollowedForumsCache.getFollowedForum(null))
    }

    /**
     * 快慢路径并发是设计内场景:结果可以是任意一次写入的完整快照,
     * 但绝不能出现"部分写入"或读到半构造的值。
     */
    @Test
    fun concurrentMergeAndUpdateAllStayConsistent() {
        val failures = AtomicReference<Throwable?>(null)
        val slow = Thread {
            try {
                repeat(200) {
                    FollowedForumsCache.updateAll(listOf(forum(1), forum(2), forum(3)))
                }
            } catch (e: Throwable) {
                failures.compareAndSet(null, e)
            }
        }
        val fast = Thread {
            try {
                repeat(200) {
                    FollowedForumsCache.mergeAll(listOf(forum(4), forum(5)))
                    // 任一时刻读到的都必须是合法值:要么 null(未关注),要么是完整对象
                    FollowedForumsCache.getFollowedForum(1)?.let {
                        assertEquals(1L, it.forumId)
                    }
                }
            } catch (e: Throwable) {
                failures.compareAndSet(null, e)
            }
        }
        slow.start()
        fast.start()
        slow.join()
        fast.join()
        failures.get()?.let { throw it }
    }
}
