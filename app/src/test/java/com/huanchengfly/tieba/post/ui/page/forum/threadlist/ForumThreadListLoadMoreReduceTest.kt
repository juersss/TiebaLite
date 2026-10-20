package com.huanchengfly.tieba.post.ui.page.forum.threadlist

import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 吧内列表 LoadMore 游标守卫（2026-10-20，审查 P3「Refresh 与 LoadMore 无 epoch/互斥」）。
 *
 * 场景：下拉刷新/切换排序把游标重置成 1 时，之前那一页请求还在路上；它晚到后若照旧
 * `currentPage = 传入页`，游标就被推回旧值——中间某些页从此不会再被请求（漏页），
 * 同时旧页内容被并进刚刷出来的列表（跳页/混排）。本用例锁住三条判定：
 * ① 正常翻页（当前页 + 1）必须被接受；
 * ② 同页批量续拉（thread_id_list 换窗口，页号不变）必须被接受；
 * ③ 其它页号 = 过期结果，丢弃且不动游标。
 */
class ForumThreadListLoadMoreReduceTest {

    private val refreshing = ForumThreadListUiState(currentPage = 1, hasMore = true, isRefreshing = true)

    @Test
    fun nextPageAdvancesCursor() {
        val state = ForumThreadListUiState(currentPage = 2, hasMore = true)
        val reduced = ForumThreadListPartialChange.LoadMore.Success(
            threadList = emptyList(),
            threadListIds = emptyList(),
            currentPage = 3,
            hasMore = true,
        ).reduce(state)

        assertEquals(3, reduced.currentPage)
    }

    @Test
    fun samePagedBatchLoadIsAccepted() {
        val state = ForumThreadListUiState(currentPage = 2, hasMore = true)
        val reduced = ForumThreadListPartialChange.LoadMore.Success(
            threadList = emptyList(),
            threadListIds = listOf(1L, 2L),
            currentPage = 2,
            hasMore = true,
        ).reduce(state)

        assertEquals("批量续拉不该被当成过期结果", 2, reduced.currentPage)
        assertEquals(listOf(1L, 2L), reduced.threadListIds.toList())
    }

    /** 刷新把游标重置成 1 之后，在途的"页 3"结果到达：丢弃，游标与列表都不动。 */
    @Test
    fun stalePageAfterRefreshIsDropped() {
        val reduced = ForumThreadListPartialChange.LoadMore.Success(
            threadList = emptyList(),
            threadListIds = persistentListOf(),
            currentPage = 3,
            hasMore = true,
        ).reduce(refreshing)

        assertEquals("过期结果不能改写游标", 1, reduced.currentPage)
        assertEquals(emptyList<Any>(), reduced.threadList.toList())
        assertEquals(false, reduced.isLoadingMore)
    }

    /** 只允许"当前页"与"当前页 + 1"，更远的页号一律视为过期（包括重复推进）。 */
    @Test
    fun pagesBeyondNextAreDropped() {
        val state = ForumThreadListUiState(currentPage = 5, hasMore = true)
        val reduced = ForumThreadListPartialChange.LoadMore.Success(
            threadList = emptyList(),
            threadListIds = persistentListOf(),
            currentPage = 8,
            hasMore = true,
        ).reduce(state)

        assertEquals(5, reduced.currentPage)
    }
}
