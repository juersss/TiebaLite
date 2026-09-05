package com.huanchengfly.tieba.post.ui.page.forum.threadlist

import com.huanchengfly.tieba.post.api.models.protos.ThreadInfo
import com.huanchengfly.tieba.post.arch.wrapImmutable
import com.huanchengfly.tieba.post.ui.models.ThreadItemData
import kotlinx.collections.immutable.toPersistentList
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 吧列表 Refresh reducer 的"保位刷新"语义(用户报告:下拉刷新后回到最顶上的帖子、
 * 已加载的历史页整页丢失,浏览进度归零)。
 *
 * preserveList = true(下拉/FAB 手动刷新):新页在前、旧列表在后去重合并——
 * 列表项 key 为 thread id(与位置无关),滚动锚点仍在列表中,浏览位置得以保留;
 * preserveList = false(排序/分区切换):整表替换,回到顶部是预期行为,不受影响。
 */
class ForumThreadListRefreshReducerTest {

    private fun item(id: Long) =
        ThreadItemData(ThreadInfo(id = id).wrapImmutable(), blocked = false)

    private val oldState = ForumThreadListUiState(
        threadList = listOf(101L, 102L, 103L).map(::item).toPersistentList()
    )

    @Test
    fun refreshWithPreserveListKeepsLoadedPages() {
        // 旧列表 [101,102,103];刷新返回新页 [201,101](101 与旧表重复,重复项取新数据)
        val change = ForumThreadListPartialChange.Refresh.Success(
            threadList = listOf(201L, 101L).map(::item),
            threadListIds = listOf(201L, 101L),
            goodClassifies = emptyList(),
            goodClassifyId = null,
            hasMore = true,
            preserveList = true,
        )
        val state = change.reduce(oldState)
        // 合并结果:新页在前 + 旧列表去重 → [201,101,102,103]
        assertEquals(
            listOf(201L, 101L, 102L, 103L),
            state.threadList.map { it.thread.get { id } }
        )
        assertEquals(false, state.isRefreshing)
        assertEquals(1, state.currentPage)
    }

    @Test
    fun refreshWithoutPreserveListReplacesList() {
        // 排序/分区切换:整表替换,不保留旧页(此时回到顶部是预期行为)
        val change = ForumThreadListPartialChange.Refresh.Success(
            threadList = listOf(201L, 101L).map(::item),
            threadListIds = listOf(201L, 101L),
            goodClassifies = emptyList(),
            goodClassifyId = null,
            hasMore = true,
        )
        val state = change.reduce(oldState)
        assertEquals(
            listOf(201L, 101L),
            state.threadList.map { it.thread.get { id } }
        )
    }

    @Test
    fun refreshWithPreserveListKeepsPaginationCursor() {
        // 保位刷新(R4-F1):分页游标沿用旧值、id 队列清空——LoadMore 从旧流第 N+1 页续拉,
        // 不重放旧表已加载的第 2..N 页(否则加载条空转 N-1 次)
        val old = ForumThreadListUiState(
            threadList = listOf(101L, 102L, 103L).map(::item).toPersistentList(),
            threadListIds = listOf(901L, 902L).toPersistentList(),
            currentPage = 3,
        )
        val change = ForumThreadListPartialChange.Refresh.Success(
            threadList = listOf(201L).map(::item),
            threadListIds = listOf(201L, 101L),
            goodClassifies = emptyList(),
            goodClassifyId = null,
            hasMore = true,
            preserveList = true,
        )
        val state = change.reduce(old)
        assertEquals(listOf(201L, 101L, 102L, 103L), state.threadList.map { it.thread.get { id } })
        assertEquals(3, state.currentPage)
        assertEquals(true, state.threadListIds.isEmpty())
    }
}
