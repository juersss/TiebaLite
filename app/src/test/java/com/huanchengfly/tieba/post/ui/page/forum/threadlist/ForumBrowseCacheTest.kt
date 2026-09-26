package com.huanchengfly.tieba.post.ui.page.forum.threadlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 吧列表滚动锚点暂存(修复"从帖子页返回时偶发回到顶部")的行为回归:
 *
 * 病根(trace_20260907_030646.log 行 909):saveable 恢复的索引遇上"数据晚一帧"
 * 的空列表测量,被钳回顶部。对策:离开时暂存锚点(锚点帖 id+偏移),数据落地后
 * 按锚点帖重新定位,消费一次即失效。
 *
 * 行为约定(2026-09-07 用户拍板):重进吧 = 新浏览归零(页面在 FirstLoad 时
 * 通过 consume 丢弃锚点);导航栈内返回 = 恢复位置。归零/恢复的页面侧编排
 * 不在本单测范围,这里验证暂存语义本身。
 */
class ForumBrowseCacheTest {

    @Test
    fun markAndConsume_roundTrip() {
        val key = ForumBrowseCache.key("吧A", false, 0)
        ForumBrowseCache.markPendingRestore(key, ForumBrowseCache.Anchor(7L, 123))
        assertEquals(ForumBrowseCache.Anchor(7L, 123), ForumBrowseCache.consumeRestoreAnchor(key))
    }

    @Test
    fun consumeExactlyOnce() {
        val key = ForumBrowseCache.key("吧A", false, 0)
        ForumBrowseCache.markPendingRestore(key, ForumBrowseCache.Anchor(7L, 123))
        ForumBrowseCache.consumeRestoreAnchor(key)
        assertNull("锚点只消费一次,列表后续变更不得重复恢复", ForumBrowseCache.consumeRestoreAnchor(key))
    }

    @Test
    fun nullAnchorMarksNothing() {
        val key = ForumBrowseCache.key("吧A", false, 0)
        // 离开时在顶部(无锚点)不登记:回到顶部本就是空列表钳制的"正确还原"
        ForumBrowseCache.markPendingRestore(key, null)
        assertNull(ForumBrowseCache.consumeRestoreAnchor(key))
    }

    @Test
    fun keysIsolateForumSortAndTab() {
        val latest = ForumBrowseCache.key("吧A", false, 0)
        ForumBrowseCache.markPendingRestore(latest, ForumBrowseCache.Anchor(7L, 0))
        assertNull("不同排序互不串位", ForumBrowseCache.consumeRestoreAnchor(ForumBrowseCache.key("吧A", false, 1)))
        assertNull("不同吧互不串位", ForumBrowseCache.consumeRestoreAnchor(ForumBrowseCache.key("吧B", false, 0)))
        assertNull("精品区与最新互不串位", ForumBrowseCache.consumeRestoreAnchor(ForumBrowseCache.key("吧A", true, 0)))
        assertEquals(ForumBrowseCache.Anchor(7L, 0), ForumBrowseCache.consumeRestoreAnchor(latest))
    }

    @Test
    fun keyFormatDistinct() {
        assertEquals(ForumBrowseCache.key("吧A", false, 0), ForumBrowseCache.key("吧A", false, 0))
        assertNotEquals(ForumBrowseCache.key("吧A", false, 0), ForumBrowseCache.key("吧A", true, 0))
        assertNotEquals(ForumBrowseCache.key("吧A", false, 0), ForumBrowseCache.key("吧A", false, 1))
    }
}
