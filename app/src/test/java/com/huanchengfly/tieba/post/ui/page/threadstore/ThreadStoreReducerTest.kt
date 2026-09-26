package com.huanchengfly.tieba.post.ui.page.threadstore

import com.huanchengfly.tieba.post.api.models.ThreadStoreBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收藏页 ThreadStore 列表的标志生命周期与代际门控语义。
 *
 * 代际门控:Refresh.Start 递增代际,在途 LoadMore 携带发起时刻的代际,旧代结果按代
 * 丢弃(防重复行+游标错位)。关键不变式:**被丢弃的旧代 LoadMore.Success 也必须清
 * isLoadingMore**——该标志只由 LoadMore.Start 置位,Refresh 三个分支都不碰它;若
 * 丢弃分支原样返回旧状态,标志恒 true,LoadMoreLayout 的 !curIsLoading 双门控会
 * 拦住后续所有加载,收藏页从此翻页卡死(第 14 轮修复引入、第 15 轮自纠)。
 *
 * 代际计数器是文件级 AtomicInteger(测试 JVM 内恒为 0,只有
 * ThreadStoreViewModel.dispatchEvent 会递增,测试不触碰):
 * generation = 0 视为"新鲜",负数视为"旧代"。
 */
class ThreadStoreReducerTest {

    private fun info(id: String) = ThreadStoreBean.ThreadStoreInfo(
        threadId = id, title = "t$id", forumName = "f",
        author = ThreadStoreBean.AuthorInfo(), media = emptyList(),
        isDeleted = "0", lastTime = "0", type = "0", status = "0",
        maxPid = "0", minPid = "0", markPid = "0", markStatus = "0",
        postNo = "0", postNoMsg = "0", count = "0"
    )

    private val loadingState = ThreadStoreUiState(
        isLoadingMore = true,
        data = listOf(info("101")),
        currentPage = 1,
        hasMore = true
    )

    @Test
    fun staleLoadMoreSuccessDropsPageAndClearsLoadingFlag() {
        val change = ThreadStorePartialChange.LoadMore.Success(
            data = listOf(info("201")),
            hasMore = false,
            currentPage = 2,
            generation = Int.MIN_VALUE
        )
        val state = change.reduce(loadingState)
        assertFalse("旧代结果被丢弃也必须清 isLoadingMore", state.isLoadingMore)
        assertEquals("旧代页数据不得追加(防重复行+游标错位)", listOf("101"), state.data.map { it.threadId })
        assertEquals(1, state.currentPage)
        assertTrue(state.hasMore)
    }

    @Test
    fun freshLoadMoreSuccessAppendsAndClearsLoadingFlag() {
        val change = ThreadStorePartialChange.LoadMore.Success(
            data = listOf(info("201")),
            hasMore = false,
            currentPage = 2,
            generation = 0
        )
        val state = change.reduce(loadingState)
        assertFalse(state.isLoadingMore)
        assertEquals(listOf("101", "201"), state.data.map { it.threadId })
        assertEquals(2, state.currentPage)
        assertFalse(state.hasMore)
    }

    @Test
    fun refreshBranchesNeverTouchLoadingFlag() {
        // 锁定"Refresh 不清 isLoadingMore"的现状:这正是丢弃分支必须自己清标志的原因
        val started = ThreadStorePartialChange.Refresh.Start.reduce(loadingState)
        assertTrue(started.isLoadingMore)
        val succeeded = ThreadStorePartialChange.Refresh.Success(
            data = listOf(info("301")), hasMore = true
        ).reduce(loadingState)
        assertTrue(succeeded.isLoadingMore)
        val failed = ThreadStorePartialChange.Refresh.Failure(RuntimeException("x"))
            .reduce(loadingState)
        assertTrue(failed.isLoadingMore)
    }

    @Test
    fun loadMoreFailureClearsLoadingFlag() {
        val state = ThreadStorePartialChange.LoadMore.Failure(RuntimeException("x"))
            .reduce(loadingState)
        assertFalse(state.isLoadingMore)
    }
}
